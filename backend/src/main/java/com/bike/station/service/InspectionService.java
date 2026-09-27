package com.bike.station.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.InspectionBatch;
import com.bike.station.domain.InspectionDict;
import com.bike.station.domain.InspectionItem;
import com.bike.station.domain.InspectionVerifyLog;
import com.bike.station.domain.Operator;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Station;
import com.bike.station.dto.InspectionDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.InspectionBatchRepository;
import com.bike.station.repository.InspectionItemRepository;
import com.bike.station.repository.InspectionVerifyLogRepository;
import com.bike.station.repository.OperatorRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.StationRepository;

/**
 * 桩位巡检（模块六）。
 *
 * 一条不可动摇的取舍：在「尽快释放可用桩位」和「故障桩绝不接收新车」之间，永远以安全隔离优先。
 *
 * 批次口径：
 *  - 建单时在站点行锁内把该站点【当时】的桩位清单逐桩快照进批次明细，
 *    并记录每桩当时的车辆编号与占用计数；之后新增桩位不会进入旧批次；
 *  - 同一站点允许有任意多历史批次，但同一桩位同时只能有一条尚未复验通过的故障处置
 *    （服务层锁内预查 + active_pile_id 唯一索引双保险）。
 *
 * 隔离口径（全部基于桩位行锁内的最新占用事实）：
 *  - 空桩故障 → 桩位立即 QUARANTINED，不出现在借车/还车/调拨的可选桩位中；
 *  - 桩上有车故障 → PENDING_QUARANTINE：不清车辆落点，该车仍可被借走/调出，
 *    但任何新车不得进入（SlotService 落桩闸门统一拦截）；车辆离开由 releasePile 自动转 QUARANTINED；
 *  - 检查期间车辆被借走/还走/调拨：以 bike.pile_id 的最新事实决定待隔离还是已隔离，
 *    绝不留下「空闲却挂车 / 占用却无车」的矛盾状态。
 *
 * 复验口径：
 *  - 故障处置完成只进入「待复验」，隔离不解除；
 *  - 复验不通过：保留原因、写复验记录、继续隔离，处置回到「处置中」；
 *  - 复验通过：按真实占车恢复为 IDLE/OCCUPIED，恢复状态不接受页面手工指定。
 *
 * 并发口径：所有写动作统一按「站点 → 桩位 → 车辆 → 批次 → 明细」加锁，
 * 双人同时登记检查、车辆离桩与复验相撞时，后一方拿到锁后看到的是最新事实，
 * 已生效的车辆落点与巡检记录不会被旧页面覆盖。
 */
@Service
public class InspectionService {

    private static final DateTimeFormatter BATCH_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final InspectionBatchRepository batchRepository;
    private final InspectionItemRepository itemRepository;
    private final InspectionVerifyLogRepository verifyLogRepository;
    private final OperatorRepository operatorRepository;
    private final StationRepository stationRepository;
    private final PileRepository pileRepository;
    private final BikeRepository bikeRepository;
    private final SlotService slotService;

    /**
     * 加锁后清持久化上下文用：并发事务在等待行锁期间，
     * 它在加锁【之前】无锁预读过的实体会被前一个事务的提交「越过」，
     * JPA 一级缓存里仍是旧快照 —— FOR UPDATE 拿到锁后必须 clear() 强制重读最新值。
     */
    @PersistenceContext
    private EntityManager entityManager;

    public InspectionService(InspectionBatchRepository batchRepository,
                             InspectionItemRepository itemRepository,
                             InspectionVerifyLogRepository verifyLogRepository,
                             OperatorRepository operatorRepository,
                             StationRepository stationRepository,
                             PileRepository pileRepository,
                             BikeRepository bikeRepository,
                             SlotService slotService) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.verifyLogRepository = verifyLogRepository;
        this.operatorRepository = operatorRepository;
        this.stationRepository = stationRepository;
        this.pileRepository = pileRepository;
        this.bikeRepository = bikeRepository;
        this.slotService = slotService;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public List<Views.OperatorView> operators() {
        return operatorRepository.findAllByOrderByIdAsc().stream()
                .map(o -> new Views.OperatorView(
                        o.getId(), o.getName(), o.getPhone(), o.getStatus(),
                        InspectionDict.operatorStatusName(o.getStatus())))
                .toList();
    }

    public List<Views.InspectionBatchListItem> list(Long stationId, String status) {
        List<InspectionBatch> batches;
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase();
            if (!InspectionDict.isValidBatchStatus(s)) {
                throw new BizException("巡检批次状态取值非法：" + status);
            }
            batches = batchRepository.findByStatusOrderByIdDesc(s);
        } else {
            batches = batchRepository.findAllByOrderByIdDesc();
        }
        if (stationId != null) {
            batches = batches.stream().filter(b -> stationId.equals(b.getStationId())).toList();
        }
        Map<Long, Station> stations = stationMap();
        Map<Long, Operator> operators = operatorMap();
        List<Views.InspectionBatchListItem> list = new ArrayList<>(batches.size());
        for (InspectionBatch b : batches) {
            Station st = stations.get(b.getStationId());
            Operator creator = operators.get(b.getCreatorId());
            list.add(new Views.InspectionBatchListItem(
                    b.getId(),
                    b.getBatchNo(),
                    b.getStationId(),
                    st == null ? null : st.getCode(),
                    st == null ? null : st.getName(),
                    b.getStatus(),
                    InspectionDict.batchStatusName(b.getStatus()),
                    b.getCreatorId(),
                    creator == null ? null : creator.getName(),
                    b.getRemark(),
                    nz(b.getPileCount()),
                    nz(b.getCheckedCount()),
                    nz(b.getNormalCount()),
                    nz(b.getFaultCount()),
                    nz(b.getPendingQuarantineCount()),
                    nz(b.getQuarantinedCount()),
                    nz(b.getPendingRecheckCount()),
                    nz(b.getSnapshotOccupiedCount()),
                    nz(b.getSnapshotIdleCount()),
                    nz(b.getSnapshotDisabledCount()),
                    b.getFinishedAt(),
                    b.getCreatedAt()));
        }
        return list;
    }

    public Views.InspectionBatchView detail(Long id) {
        return toBatchView(requireBatch(id));
    }

    // ==================================================================
    // 创建批次：锁内固化桩位清单与占车快照
    // ==================================================================

    @Transactional
    public Views.InspectionBatchView create(InspectionDtos.BatchCreate req) {
        if (req == null || req.stationId() == null) {
            throw new BizException("创建巡检批次失败：巡检站点不能为空");
        }
        requireOnDutyOperator(req.creatorId(), "建单");
        // 站点行锁：建单期间新增桩位/车辆借还都在同一串行区间，清单与占车快照一定一致
        Station station = slotService.lockStation(req.stationId(), "创建巡检批次");
        List<Pile> piles = pileRepository.findByStationIdForUpdate(station.getId());
        if (piles.isEmpty()) {
            throw new BizException("创建巡检批次失败：站点「" + station.getName() + "」当前没有桩位，无法巡检");
        }

        LocalDateTime now = LocalDateTime.now();
        int occupied = 0;
        int idle = 0;
        int disabled = 0;
        List<InspectionItem> items = new ArrayList<>(piles.size());
        for (Pile p : piles) {
            if (BikeDict.PILE_OCCUPIED.equals(p.getStatus())) {
                occupied++;
            } else if (BikeDict.PILE_IDLE.equals(p.getStatus())) {
                idle++;
            } else if (BikeDict.PILE_DISABLED.equals(p.getStatus())) {
                disabled++;
            }
            Bike occupant = bikeRepository.findByPileId(p.getId()).orElse(null);

            InspectionItem item = new InspectionItem();
            item.setPileId(p.getId());
            item.setPileNo(p.getPileNo());
            item.setSnapshotBikeCode(occupant == null ? null : occupant.getCode());
            item.setItemResult(InspectionDict.RESULT_WAITING);
            item.setDisposeStatus(InspectionDict.DISPOSE_NONE);
            item.setRecheckPassCount(0);
            item.setRecheckFailCount(0);
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            items.add(item);
        }

        InspectionBatch batch = new InspectionBatch();
        batch.setBatchNo("IC" + now.format(BATCH_NO_FORMAT));
        batch.setStationId(station.getId());
        batch.setStatus(InspectionDict.BATCH_OPEN);
        batch.setCreatorId(req.creatorId());
        batch.setRemark(trim(req.remark(), 255));
        batch.setPileCount(piles.size());
        batch.setSnapshotOccupiedCount(occupied);
        batch.setSnapshotIdleCount(idle);
        batch.setSnapshotDisabledCount(disabled);
        batch.setCheckedCount(0);
        batch.setNormalCount(0);
        batch.setFaultCount(0);
        batch.setPendingQuarantineCount(0);
        batch.setQuarantinedCount(0);
        batch.setPendingRecheckCount(0);
        batch.setCreatedAt(now);
        batchRepository.save(batch);

        for (InspectionItem item : items) {
            item.setBatchId(batch.getId());
            itemRepository.save(item);
        }

        return toBatchView(batch);
    }

    // ==================================================================
    // 逐桩检查登记
    // ==================================================================

    @Transactional
    public Views.InspectionBatchView check(Long batchId, Long itemId, InspectionDtos.CheckRequest req) {
        if (req == null) {
            throw new BizException("登记检查结果失败：请求内容不能为空");
        }
        Operator inspector = requireOnDutyOperator(req.operatorId(), "巡检登记");

        String lock = normalizeCheck(req.lockResult(), "桩锁");
        String power = normalizeCheck(req.powerResult(), "供电");
        String com = normalizeCheck(req.comResult(), "通信");
        boolean fault = InspectionDict.CHECK_FAIL.equals(lock)
                || InspectionDict.CHECK_FAIL.equals(power)
                || InspectionDict.CHECK_FAIL.equals(com);
        String faultLevel = null;
        String faultDesc = null;
        if (fault) {
            faultLevel = req.faultLevel() == null ? "" : req.faultLevel().trim().toUpperCase();
            if (!BikeDict.isValidFaultLevel(faultLevel)) {
                throw new BizException("登记检查结果失败：发现故障时故障等级必须是 LOW / MID / HIGH");
            }
            faultDesc = req.faultDesc() == null ? "" : req.faultDesc().trim();
            if (faultDesc.isEmpty()) {
                throw new BizException("登记检查结果失败：发现故障时必须填写故障描述");
            }
            faultDesc = truncate(faultDesc, 255);
        }

        // 无锁预读只为拿关联 id；真正的临界区由 lockFullChain 一次性按
        // 「站点 → 站内全部桩位（按 id 升序）→ 批次 → 批内全部明细」加锁，
        // 两名运营并发登记不同桩位时也按同一顺序拿桩位锁，杜绝互相等待成环。
        InspectionItem probe = itemRepository.findById(itemId)
                .orElseThrow(() -> new BizException("登记检查结果失败：巡检桩位明细不存在（id=" + itemId + "）"));
        InspectionBatch batchProbe = requireBatch(probe.getBatchId());
        if (!batchProbe.getId().equals(batchId)) {
            throw new BizException("登记检查结果失败：该桩位不属于巡检批次 " + batchId);
        }
        Pile pileProbe = pileRepository.findById(probe.getPileId())
                .orElseThrow(() -> new BizException("登记检查结果失败：桩位不存在（id=" + probe.getPileId() + "）"));

        Chain chain = lockFullChain(batchId, pileProbe.getStationId(), "巡检登记");
        InspectionBatch batch = chain.batch();
        InspectionItem item = chain.item(itemId);
        Pile pile = chain.pile(probe.getPileId());
        Bike occupant = pile == null ? null : bikeRepository.findByPileIdForUpdate(pile.getId()).orElse(null);
        if (item == null || !item.getBatchId().equals(batch.getId())) {
            throw new BizException("登记检查结果失败：该桩位不属于当前巡检批次");
        }
        if (InspectionDict.BATCH_COMPLETED.equals(batch.getStatus())) {
            throw new BizException("登记检查结果失败：批次 " + batch.getBatchNo() + " 已完成，不能再登记");
        }

        // 乐观并发锚点：页面提交时必须带上它打开时看到的检查结论。
        // 两名运营几乎同时登记（或旧页面重提）时，后一方锁内看到的结论已经变了 → 明确报冲突，
        // 已生效的检查记录与车辆落点绝不允许被旧页面覆盖。
        // 「重新登记」入口会带上最新结论，所以看过最新状态后的改判可以正常通过。
        String previous = req.previousResult() == null || req.previousResult().isBlank()
                ? InspectionDict.RESULT_WAITING
                : req.previousResult().trim().toUpperCase();
        if (!InspectionDict.RESULT_WAITING.equals(previous)
                && !InspectionDict.RESULT_NORMAL.equals(previous)
                && !InspectionDict.RESULT_FAULT.equals(previous)) {
            throw new BizException("登记检查结果失败：页面携带的原检查结论取值非法：" + req.previousResult());
        }
        if (!previous.equals(item.getItemResult())) {
            throw new BizException("登记冲突：桩位「" + pile.getPileNo() + "」的检查记录"
                    + (InspectionDict.RESULT_WAITING.equals(item.getItemResult())
                            ? "状态已变化"
                            : "已被「" + operatorName(item.getInspectorId()) + "」于 "
                                    + (item.getInspectedAt() == null ? "" : item.getInspectedAt())
                                    + " 登记为「" + InspectionDict.resultName(item.getItemResult()) + "」")
                    + "，车辆占用与隔离状态也可能已更新。请刷新查看最新状态后再决定是否重新登记，"
                    + "不能用旧页面覆盖已生效的记录");
        }

        // 同桩不允许两条尚未完成的故障处置。
        // 当前明细若已经复验通过（旧故障关闭后再次查出故障），允许重新打开；
        // 但其它批次/明细上仍挂着未完成处置时必须拒绝（数据库 active_pile_id 唯一索引还会兜底并发）。
        boolean currentAlreadyClosed = InspectionDict.DISPOSE_RECHECK_PASS.equals(item.getDisposeStatus());
        InspectionItem openDispose = findOpenDisposeOfPile(pile.getId(),
                currentAlreadyClosed ? null : item.getId());
        if (fault && openDispose != null) {
            throw new BizException("登记检查结果失败：桩位「" + pile.getPileNo()
                    + "」已有一条尚未完成的故障处置（批次 " + describeBatchOf(openDispose)
                    + "），同一桩位必须先完成处置并复验通过，不能重复登记故障");
        }

        LocalDateTime now = LocalDateTime.now();

        // —— 以锁内最新占用事实决定隔离落点 ——
        String occupyNote = buildOccupyNote(pile, occupant, req.expectedBikeCode(), item.getSnapshotBikeCode());
        if (fault) {
            applyFaultQuarantine(pile, occupant);
            item.setItemResult(InspectionDict.RESULT_FAULT);
            item.setFaultLevel(faultLevel);
            item.setFaultDesc(faultDesc);
            // 故障即进入处置中：首次发现、复验关闭后再次查出故障都走这里
            item.setDisposeStatus(InspectionDict.DISPOSE_PROCESSING);
            // 重新查出故障：上一轮复验结论归档保留，但处置/复验人重新走流程
            item.setDisposerId(null);
            item.setDisposeStartedAt(null);
            item.setDisposeNote(null);
            item.setDisposedAt(null);
        } else {
            // 正常结论绝不解除隔离：只在普通占用/空闲口径内对账
            applyNormalReconcile(pile, occupant);
            item.setItemResult(InspectionDict.RESULT_NORMAL);
            item.setFaultLevel(null);
            item.setFaultDesc(null);
        }
        item.setLockResult(lock);
        item.setPowerResult(power);
        item.setComResult(com);
        item.setInspectorId(inspector.getId());
        item.setInspectedAt(now);
        item.setInspectOccupyNote(occupyNote);
        item.setUpdatedAt(now);

        // checked/normal/fault 等计数全部在 refreshBatchCounts 里按明细重算，
        // 同一桩位重复登记（旧页面重提）也不会把计数加花 —— 计数更新天然幂等。
        refreshBatchCounts(batch, chain);
        return toBatchView(batch);
    }

    // ==================================================================
    // 故障处置：开始 / 完成
    // ==================================================================

    @Transactional
    public Views.InspectionBatchView startDispose(Long batchId, Long itemId, InspectionDtos.DisposeStartRequest req) {
        if (req == null) {
            throw new BizException("开始处置失败：请求内容不能为空");
        }
        Operator disposer = requireOnDutyOperator(req.operatorId(), "故障处置");

        Chain chain = lockChainByItem(batchId, itemId, "开始处置");
        InspectionItem item = chain.item(itemId);
        Pile pile = chain.pileOfItem(itemId);
        if (!InspectionDict.RESULT_FAULT.equals(item.getItemResult())) {
            throw new BizException("开始处置失败：桩位「" + pile.getPileNo() + "」检查结论不是故障，无需处置");
        }
        if (InspectionDict.DISPOSE_FIXED.equals(item.getDisposeStatus())) {
            throw new BizException("开始处置失败：该故障已处置完成，正在等待复验，请直接复验");
        }
        if (InspectionDict.DISPOSE_RECHECK_PASS.equals(item.getDisposeStatus())) {
            throw new BizException("开始处置失败：该故障已复验通过并关闭，不能重复处置");
        }
        LocalDateTime now = LocalDateTime.now();
        item.setDisposerId(disposer.getId());
        if (item.getDisposeStartedAt() == null) {
            item.setDisposeStartedAt(now);
        }
        if (req.disposeNote() != null && !req.disposeNote().isBlank()) {
            item.setDisposeNote(truncate(req.disposeNote().trim(), 255));
        }
        item.setDisposeStatus(InspectionDict.DISPOSE_PROCESSING);
        item.setUpdatedAt(now);
        refreshBatchCounts(chain.batch(), chain);
        return toBatchView(chain.batch());
    }

    @Transactional
    public Views.InspectionBatchView finishDispose(Long batchId, Long itemId, InspectionDtos.DisposeFinishRequest req) {
        if (req == null) {
            throw new BizException("完成处置失败：请求内容不能为空");
        }
        Operator disposer = requireOnDutyOperator(req.operatorId(), "完成处置");

        Chain chain = lockChainByItem(batchId, itemId, "完成处置");
        InspectionItem item = chain.item(itemId);
        Pile pile = chain.pileOfItem(itemId);
        if (!InspectionDict.RESULT_FAULT.equals(item.getItemResult())) {
            throw new BizException("完成处置失败：桩位「" + pile.getPileNo() + "」没有故障记录");
        }
        if (InspectionDict.DISPOSE_RECHECK_PASS.equals(item.getDisposeStatus())) {
            throw new BizException("完成处置失败：该故障已复验通过并关闭");
        }
        String note = req.disposeNote() == null ? item.getDisposeNote() : req.disposeNote().trim();
        if (note == null || note.isBlank()) {
            throw new BizException("完成处置失败：请填写处置措施说明");
        }
        LocalDateTime now = LocalDateTime.now();
        item.setDisposerId(disposer.getId());
        if (item.getDisposeStartedAt() == null) {
            item.setDisposeStartedAt(now);
        }
        item.setDisposeNote(truncate(note, 255));
        item.setDisposedAt(now);
        // 处置完成只进入待复验，隔离绝不提前解除
        item.setDisposeStatus(InspectionDict.DISPOSE_FIXED);
        item.setUpdatedAt(now);
        refreshBatchCounts(chain.batch(), chain);
        return toBatchView(chain.batch());
    }

    // ==================================================================
    // 复验：通过按真实占车恢复；不通过保留原因继续隔离
    // ==================================================================

    @Transactional
    public Views.RecheckOutcome recheck(Long batchId, Long itemId, InspectionDtos.RecheckRequest req) {
        if (req == null) {
            throw new BizException("复验失败：请求内容不能为空");
        }
        Operator rechecker = requireOnDutyOperator(req.operatorId(), "复验");
        String result = req.result() == null ? "" : req.result().trim().toUpperCase();
        if (!InspectionDict.isValidRecheckResult(result)) {
            throw new BizException("复验失败：复验结论取值非法：" + req.result());
        }

        Chain chain = lockChainByItem(batchId, itemId, "复验");
        InspectionBatch batch = chain.batch();
        InspectionItem item = chain.item(itemId);
        Pile pile = chain.pileOfItem(itemId);
        // 占用车辆在桩位锁之后再锁（顺序：站点 → 桩位 → 车辆），读到的是最新已提交事实
        Bike occupant = bikeRepository.findByPileIdForUpdate(pile.getId()).orElse(null);

        if (InspectionDict.DISPOSE_RECHECK_PASS.equals(item.getDisposeStatus())) {
            throw new BizException("复验失败：该故障已复验通过并关闭，不能重复复验");
        }
        if (!InspectionDict.DISPOSE_FIXED.equals(item.getDisposeStatus())) {
            throw new BizException("复验失败：桩位「" + pile.getPileNo() + "」的故障处置尚未完成，"
                    + "请先完成处置再复验");
        }

        // 复验依据的真实占车事实必须与桩位快照一致（有车=占用/待隔离，无车=空闲/已隔离），
        // 不能留下「空闲却挂车 / 占用却无车」的矛盾状态
        String statusBefore = pile.getStatus();

        LocalDateTime now = LocalDateTime.now();
        InspectionVerifyLog log = new InspectionVerifyLog();
        log.setItemId(item.getId());
        log.setBatchId(batch.getId());
        log.setPileId(pile.getId());
        log.setResult(result);
        log.setOperatorId(rechecker.getId());
        log.setCreatedAt(now);

        String message;
        if (InspectionDict.RECHECK_FAIL.equals(result)) {
            String note = req.note() == null ? "" : req.note().trim();
            if (note.isEmpty()) {
                throw new BizException("复验失败：复验不通过时必须填写不通过原因");
            }
            log.setNote(truncate(note, 255));
            verifyLogRepository.save(log);

            item.setRecheckResult(InspectionDict.RECHECK_FAIL);
            item.setRecheckNote(truncate(note, 255));
            item.setRecheckerId(rechecker.getId());
            item.setRecheckedAt(now);
            item.setRecheckFailCount(nz(item.getRecheckFailCount()) + 1);
            // 复验不通过：继续隔离。按最新占车事实维持隔离口径，但绝不动停用桩的停用属性
            applyNormalReconcile(pile, occupant);
            item.setDisposeStatus(InspectionDict.DISPOSE_PROCESSING);
            item.setUpdatedAt(now);
            refreshBatchCounts(batch, chain);
            message = "复验不通过：原因已保留，桩位「" + pile.getPileNo() + "」继续隔离，请重新处置";
            return new Views.RecheckOutcome(false, message, pile.getStatus(),
                    BikeDict.pileStatusName(pile.getStatus()),
                    occupant == null ? null : occupant.getCode(), toBatchView(batch));
        }

        // —— 复验通过：恢复动作必须与真实占车一致，不能由页面指定 ——
        String note = req.note() == null ? null : req.note().trim();
        if (note != null && note.isBlank()) {
            note = null;
        }
        String restoreStatus;
        if (BikeDict.PILE_DISABLED.equals(statusBefore)) {
            // 停用桩的隔离解除后仍保持停用，不放开既有的停用闸门
            restoreStatus = BikeDict.PILE_DISABLED;
        } else if (occupant != null) {
            // 桩上确实有车：恢复为占用（车的落点完全不动）
            pile.setStatus(BikeDict.PILE_OCCUPIED);
            restoreStatus = BikeDict.PILE_OCCUPIED;
        } else {
            pile.setStatus(BikeDict.PILE_IDLE);
            restoreStatus = BikeDict.PILE_IDLE;
        }
        log.setPileRestored(restoreStatus);
        log.setRestoredBikeCode(restoreStatus.equals(BikeDict.PILE_OCCUPIED) ? occupant.getCode() : null);
        log.setNote(truncate(note, 255));
        verifyLogRepository.save(log);

        item.setRecheckResult(InspectionDict.RECHECK_PASS);
        item.setRecheckNote(truncate(note, 255));
        item.setRecheckerId(rechecker.getId());
        item.setRecheckedAt(now);
        item.setRecheckPassCount(nz(item.getRecheckPassCount()) + 1);
        item.setDisposeStatus(InspectionDict.DISPOSE_RECHECK_PASS);
        item.setUpdatedAt(now);
        refreshBatchCounts(batch, chain);

        String restoredName = BikeDict.pileStatusName(pile.getStatus());
        if (BikeDict.PILE_DISABLED.equals(restoreStatus)) {
            message = "复验通过：桩位「" + pile.getPileNo() + "」隔离已解除，但该桩本身处于停用状态，仍不参与借还/调拨";
        } else {
            message = occupant == null
                    ? "复验通过：桩位「" + pile.getPileNo() + "」当前无车，已恢复为空闲并重新开放借还/调拨"
                    : "复验通过：桩位「" + pile.getPileNo() + "」上有车辆 " + occupant.getCode()
                            + "，已按真实占车恢复为占用并重新开放";
        }
        return new Views.RecheckOutcome(true, message, pile.getStatus(), restoredName,
                occupant == null ? null : occupant.getCode(), toBatchView(batch));
    }

    // ==================================================================
    // 内部：加锁链与隔离动作
    // ==================================================================

    /**
     * 巡检写动作的统一加锁链：站点 → 站内全部桩位（按 id 升序）→ 批次 → 批内全部明细（按 id 升序）。
     *
     * 为什么锁全量而不是只锁一个桩：两个并发请求若分别「锁桩 A→等批次锁」和
     * 「持批次锁→等桩 B 锁」会互相等待成环；统一把站点内桩位按 id 升序锁齐后再锁批次/明细，
     * 任何两个事务的等待图都是同方向的，从根上消除死锁。
     * 占用车辆的行锁只在需要时在桩位锁之后单独获取（顺序仍是 桩位 → 车辆）。
     */
    private Chain lockFullChain(Long batchId, Long stationId, String action) {
        slotService.lockStation(stationId, action);
        List<Pile> piles = pileRepository.findByStationIdForUpdate(stationId);
        InspectionBatch lockedBatch = lockBatch(batchId);
        List<InspectionItem> lockedItems = itemRepository.findByBatchIdForUpdate(batchId);
        // 等到全部行锁后清空一级缓存：本事务加锁前的无锁预读可能已被并发提交越过，
        // 不 clear 的话 JPA 仍返回旧快照（看到 WAITING 而不是对方刚提交的 FAULT）。
        // clear 后把锁内实体重新查一遍，下面所有读写都基于数据库最新已提交版本。
        entityManager.clear();
        piles = pileRepository.findByStationIdForUpdate(stationId);
        lockedBatch = lockBatch(batchId);
        lockedItems = itemRepository.findByBatchIdForUpdate(batchId);
        return new Chain(lockedBatch, piles, lockedItems);
    }

    /** 按明细 id 反查站点后加全锁链（复验/处置入口用） */
    private Chain lockChainByItem(Long batchId, Long itemId, String action) {
        InspectionItem probe = itemRepository.findById(itemId)
                .orElseThrow(() -> new BizException(action + "失败：巡检桩位明细不存在（id=" + itemId + "）"));
        Long actualBatchId = batchId != null ? batchId : probe.getBatchId();
        if (batchId != null && !probe.getBatchId().equals(batchId)) {
            throw new BizException(action + "失败：该桩位不属于巡检批次 " + batchId);
        }
        Pile pileProbe = pileRepository.findById(probe.getPileId())
                .orElseThrow(() -> new BizException(action + "失败：桩位不存在（id=" + probe.getPileId() + "）"));
        Chain chain = lockFullChain(actualBatchId, pileProbe.getStationId(), action);
        if (chain.item(itemId) == null) {
            throw new BizException(action + "失败：该桩位不属于巡检批次");
        }
        return chain;
    }

    /**
     * 故障隔离：以锁内最新事实决定「待隔离」还是「已隔离」。
     *  - 桩上有车（含检查期间刚被还进来的车）：待隔离，绝不清掉车辆落点；
     *  - 桩上无车（含检查期间车刚被借走/调出）：已隔离。
     * 停用桩保持停用语义不放开（停用 + 已隔离的安全属性取并集），但处置/复验照常记录。
     */
    private void applyFaultQuarantine(Pile pile, Bike occupant) {
        if (BikeDict.PILE_DISABLED.equals(pile.getStatus())) {
            return;
        }
        if (occupant != null) {
            pile.setStatus(BikeDict.PILE_PENDING_QUARANTINE);
        } else {
            pile.setStatus(BikeDict.PILE_QUARANTINED);
        }
    }

    /**
     * 正常检查：只在「空闲 / 占用」普通口径内对账；
     * 隔离态（待隔离/已隔离）与停用态绝不能因为一次正常登记被放开。
     */
    private void applyNormalReconcile(Pile pile, Bike occupant) {
        String status = pile.getStatus();
        if (BikeDict.isQuarantineStatus(status) || BikeDict.PILE_DISABLED.equals(status)) {
            // 待隔离桩上的车若已离开，借车/调拨时已自动转已隔离；这里再兜底对一次账，
            // 但只会在隔离口径内推进（待隔离→已隔离），绝不恢复为空闲。
            if (occupant == null && BikeDict.PILE_PENDING_QUARANTINE.equals(status)) {
                pile.setStatus(BikeDict.PILE_QUARANTINED);
            }
            return;
        }
        if (occupant != null) {
            pile.setStatus(BikeDict.PILE_OCCUPIED);
        } else {
            pile.setStatus(BikeDict.PILE_IDLE);
        }
    }

    /**
     * 组装「登记时的占用事实」说明：
     * 页面依据的车辆编号与锁内最新事实不一致时，明确写出变化，告诉运营人员是按最新事实隔离的。
     */
    private String buildOccupyNote(Pile pile, Bike occupant, String expectedBikeCode, String snapshotBikeCode) {
        String latest = occupant == null ? null : occupant.getCode();
        String expected = expectedBikeCode == null || expectedBikeCode.isBlank() ? snapshotBikeCode
                : expectedBikeCode.trim().toUpperCase();
        boolean mismatch = expected == null ? latest != null : !expected.equals(latest);
        if (!mismatch) {
            return null;
        }
        String note = "登记时占用发生变化：页面依据为「"
                + (expected == null ? "空桩" : expected)
                + "」，提交时锁内最新事实为「"
                + (latest == null ? "空桩" : latest)
                + "」，已按最新事实决定" + (latest == null ? "已隔离" : "待隔离");
        return truncate(note, 255);
    }

    /** 锁内预查同桩另一条未完成处置；excludeItemId 为 null 时表示当前明细也算冲突 */
    private InspectionItem findOpenDisposeOfPile(Long pileId, Long excludeItemId) {
        return itemRepository.findOpenDisposeByPileId(pileId).stream()
                .filter(i -> excludeItemId == null || !i.getId().equals(excludeItemId))
                .findFirst()
                .orElse(null);
    }

    private String describeBatchOf(InspectionItem item) {
        return batchRepository.findById(item.getBatchId())
                .map(InspectionBatch::getBatchNo)
                .orElse("#" + item.getBatchId());
    }

    private String operatorName(Long operatorId) {
        if (operatorId == null) {
            return "其他运营人员";
        }
        return operatorRepository.findById(operatorId)
                .map(Operator::getName)
                .orElse("其他运营人员");
    }

    /**
     * 重算批次计数（不依赖自增，天然幂等）：
     * 隔离两个数按桩位实时状态统计；待复验数按 FIXED 明细统计；
     * 全部桩位检查完且没有未完成处置时，批次自动完成。
     * 计数全部来自锁链内已持有的明细/桩位行，不再二次发加锁查询。
     */
    private void refreshBatchCounts(InspectionBatch batch, Chain chain) {
        int checked = 0;
        int normal = 0;
        int fault = 0;
        int pendingRecheck = 0;
        int openDispose = 0;
        for (InspectionItem i : chain.items()) {
            if (!InspectionDict.RESULT_WAITING.equals(i.getItemResult())) {
                checked++;
            }
            if (InspectionDict.RESULT_NORMAL.equals(i.getItemResult())) {
                normal++;
            }
            if (InspectionDict.RESULT_FAULT.equals(i.getItemResult())) {
                fault++;
            }
            if (InspectionDict.DISPOSE_FIXED.equals(i.getDisposeStatus())) {
                pendingRecheck++;
            }
            if (InspectionDict.isDisposeOpen(i.getDisposeStatus())) {
                openDispose++;
            }
        }
        int pendingQuarantine = 0;
        int quarantined = 0;
        for (Pile p : chain.piles()) {
            if (BikeDict.PILE_PENDING_QUARANTINE.equals(p.getStatus())) {
                pendingQuarantine++;
            } else if (BikeDict.PILE_QUARANTINED.equals(p.getStatus())) {
                quarantined++;
            }
        }
        batch.setCheckedCount(checked);
        batch.setNormalCount(normal);
        batch.setFaultCount(fault);
        batch.setPendingRecheckCount(pendingRecheck);
        batch.setPendingQuarantineCount(pendingQuarantine);
        batch.setQuarantinedCount(quarantined);

        if (InspectionDict.BATCH_OPEN.equals(batch.getStatus())
                && checked >= nz(batch.getPileCount())
                && openDispose == 0) {
            batch.setStatus(InspectionDict.BATCH_COMPLETED);
            batch.setFinishedAt(LocalDateTime.now());
        }
    }

    // ==================================================================
    // 内部：视图装配
    // ==================================================================

    private Views.InspectionBatchView toBatchView(InspectionBatch batch) {
        Station station = stationRepository.findById(batch.getStationId()).orElse(null);
        Operator creator = operatorRepository.findById(batch.getCreatorId()).orElse(null);
        List<InspectionItem> items = itemRepository.findByBatchIdOrderByIdAsc(batch.getId());

        Map<Long, Pile> pileMap = pileRepository.findAll().stream()
                .collect(Collectors.toMap(Pile::getId, p -> p, (a, b) -> a));
        Map<Long, Bike> bikeByPile = new HashMap<>();
        for (Bike b : bikeRepository.findAll()) {
            if (b.getPileId() != null) {
                bikeByPile.put(b.getPileId(), b);
            }
        }
        Map<Long, Operator> operators = operatorMap();

        List<Views.InspectionItemView> itemViews = new ArrayList<>(items.size());
        for (InspectionItem i : items) {
            Pile p = pileMap.get(i.getPileId());
            Bike b = p == null ? null : bikeByPile.get(p.getId());
            List<Views.VerifyLogView> logs = verifyLogRepository.findByItemIdOrderByIdAsc(i.getId()).stream()
                    .map(l -> {
                        Operator op = operators.get(l.getOperatorId());
                        return new Views.VerifyLogView(
                                l.getId(),
                                l.getResult(),
                                InspectionDict.recheckName(l.getResult()),
                                l.getNote(),
                                l.getOperatorId(),
                                op == null ? null : op.getName(),
                                l.getPileRestored(),
                                l.getPileRestored() == null ? null : BikeDict.pileStatusName(l.getPileRestored()),
                                l.getRestoredBikeCode(),
                                l.getCreatedAt());
                    })
                    .toList();
            itemViews.add(new Views.InspectionItemView(
                    i.getId(),
                    i.getPileId(),
                    i.getPileNo(),
                    i.getSnapshotBikeCode(),
                    i.getItemResult(),
                    InspectionDict.resultName(i.getItemResult()),
                    i.getLockResult(),
                    i.getLockResult() == null ? null : InspectionDict.checkName(i.getLockResult()),
                    i.getPowerResult(),
                    i.getPowerResult() == null ? null : InspectionDict.checkName(i.getPowerResult()),
                    i.getComResult(),
                    i.getComResult() == null ? null : InspectionDict.checkName(i.getComResult()),
                    i.getFaultLevel(),
                    i.getFaultLevel() == null ? null : BikeDict.faultLevelName(i.getFaultLevel()),
                    i.getFaultDesc(),
                    i.getDisposeStatus(),
                    InspectionDict.disposeName(i.getDisposeStatus()),
                    i.getInspectorId(),
                    nameOf(operators, i.getInspectorId()),
                    i.getInspectedAt(),
                    i.getInspectOccupyNote(),
                    i.getDisposerId(),
                    nameOf(operators, i.getDisposerId()),
                    i.getDisposeStartedAt(),
                    i.getDisposeNote(),
                    i.getDisposedAt(),
                    i.getRecheckerId(),
                    nameOf(operators, i.getRecheckerId()),
                    i.getRecheckedAt(),
                    i.getRecheckResult(),
                    i.getRecheckResult() == null ? null : InspectionDict.recheckName(i.getRecheckResult()),
                    i.getRecheckNote(),
                    nz(i.getRecheckPassCount()),
                    nz(i.getRecheckFailCount()),
                    p == null ? null : p.getStatus(),
                    p == null ? null : BikeDict.pileStatusName(p.getStatus()),
                    b == null ? null : b.getId(),
                    b == null ? null : b.getCode(),
                    logs));
        }

        return new Views.InspectionBatchView(
                batch.getId(),
                batch.getBatchNo(),
                batch.getStationId(),
                station == null ? null : station.getCode(),
                station == null ? null : station.getName(),
                station == null ? null : station.getStatus(),
                batch.getStatus(),
                InspectionDict.batchStatusName(batch.getStatus()),
                batch.getCreatorId(),
                creator == null ? null : creator.getName(),
                batch.getRemark(),
                nz(batch.getPileCount()),
                nz(batch.getCheckedCount()),
                nz(batch.getNormalCount()),
                nz(batch.getFaultCount()),
                nz(batch.getPendingQuarantineCount()),
                nz(batch.getQuarantinedCount()),
                nz(batch.getPendingRecheckCount()),
                nz(batch.getSnapshotOccupiedCount()),
                nz(batch.getSnapshotIdleCount()),
                nz(batch.getSnapshotDisabledCount()),
                batch.getFinishedAt(),
                batch.getCreatedAt(),
                itemViews);
    }

    /** 给站点看板用：某站点的待复验故障数 */
    public long stationPendingRecheckCount(Long stationId) {
        return itemRepository.countByStationIdAndDisposeStatus(stationId, InspectionDict.DISPOSE_FIXED);
    }

    /** 顶部概览：全部批次待复验故障数 */
    public long totalPendingRecheckCount() {
        return itemRepository.countByDisposeStatus(InspectionDict.DISPOSE_FIXED);
    }

    /** 顶部概览：巡检中批次数 */
    public long openBatchCount() {
        return batchRepository.countByStatus(InspectionDict.BATCH_OPEN);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private String normalizeCheck(String value, String label) {
        String v = value == null ? "" : value.trim().toUpperCase();
        if (!InspectionDict.isValidCheckValue(v)) {
            throw new BizException("登记检查结果失败：" + label + "检查结果取值非法（应为 NORMAL_OK / FAULT_FAIL）");
        }
        return v;
    }

    private Operator requireOnDutyOperator(Long operatorId, String action) {
        if (operatorId == null) {
            throw new BizException(action + "失败：运营人员不能为空");
        }
        Operator op = operatorRepository.findById(operatorId)
                .orElseThrow(() -> new BizException(action + "失败：运营人员不存在（id=" + operatorId + "）"));
        if (!InspectionDict.OPERATOR_ON_DUTY.equals(op.getStatus())) {
            throw new BizException(action + "失败：运营人员「" + op.getName() + "」当前休息中，不能执行该操作");
        }
        return op;
    }

    private InspectionBatch requireBatch(Long id) {
        if (id == null) {
            throw new BizException("巡检批次不能为空");
        }
        return batchRepository.findById(id)
                .orElseThrow(() -> new BizException("巡检批次不存在（id=" + id + "）"));
    }

    private InspectionBatch lockBatch(Long id) {
        return batchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("巡检批次不存在（id=" + id + "）"));
    }

    private Map<Long, Station> stationMap() {
        return stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, s -> s, (a, b) -> a, HashMap::new));
    }

    private Map<Long, Operator> operatorMap() {
        return operatorRepository.findAll().stream()
                .collect(Collectors.toMap(Operator::getId, o -> o, (a, b) -> a, HashMap::new));
    }

    private String nameOf(Map<Long, Operator> operators, Long id) {
        if (id == null) {
            return null;
        }
        Operator op = operators.get(id);
        return op == null ? null : op.getName();
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 加锁链上的全部对象：批次 + 站内桩位（有序加锁）+ 批内明细（有序加锁）。
     */
    private record Chain(InspectionBatch batch, List<Pile> piles, List<InspectionItem> items) {

        InspectionItem item(Long itemId) {
            return items.stream().filter(i -> i.getId().equals(itemId)).findFirst().orElse(null);
        }

        Pile pile(Long pileId) {
            return piles.stream().filter(p -> p.getId().equals(pileId)).findFirst().orElse(null);
        }

        Pile pileOfItem(Long itemId) {
            InspectionItem i = item(itemId);
            return i == null ? null : pile(i.getPileId());
        }
    }
}
