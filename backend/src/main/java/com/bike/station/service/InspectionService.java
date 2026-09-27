package com.bike.station.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bike.station.common.BizException;
import com.bike.station.common.ConflictException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.FaultHandling;
import com.bike.station.domain.InspectionBatch;
import com.bike.station.domain.InspectionDict;
import com.bike.station.domain.InspectionItem;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Station;
import com.bike.station.dto.InspectionDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.FaultHandlingRepository;
import com.bike.station.repository.InspectionBatchRepository;
import com.bike.station.repository.InspectionItemRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.StationRepository;

/**
 * 桩位巡检（模块六）。
 *
 * 一条不可动摇的取舍：在「尽快释放可用桩位」和「故障桩绝不接收新车」之间，
 * 永远以安全隔离优先 ——
 *  1. 空桩故障立即 ISOLATED，借车 / 还车 / 调拨的可选桩位里立刻消失（后端硬拦，
 *     不是前端隐藏）；
 *  2. 桩上有车不清除车辆落点，进入 PENDING_ISOLATED：车仍可被借走或调出，
 *     但任何新车不能进入；车一离开（SlotService.releasePile）桩位自动转 ISOLATED；
 *  3. 隔离只能由复验解除：复验不通过保留原因继续隔离；复验通过时按【真实占车】
 *     恢复 IDLE / OCCUPIED，页面不能手工指定。
 *
 * 批次口径：建单时把桩位清单与当时占车快照进 inspection_item，之后加桩不混入旧批次；
 * 同一站点允许任意历史批次，但数据库生成列唯一索引硬保证「一桩至多一条未完成处置」。
 *
 * 并发口径：登记检查、处置、复验都带乐观版本 rev，与车辆离桩/复验相撞时后一方收到
 * 409 + 最新视图；桩位、车辆、批次行锁保证「待隔离还是已隔离」按提交时最新事实判定。
 */
@Service
public class InspectionService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final InspectionBatchRepository batchRepository;
    private final InspectionItemRepository itemRepository;
    private final FaultHandlingRepository handlingRepository;
    private final StationRepository stationRepository;
    private final PileRepository pileRepository;
    private final BikeRepository bikeRepository;
    private final SlotService slotService;

    public InspectionService(InspectionBatchRepository batchRepository,
                             InspectionItemRepository itemRepository,
                             FaultHandlingRepository handlingRepository,
                             StationRepository stationRepository,
                             PileRepository pileRepository,
                             BikeRepository bikeRepository,
                             SlotService slotService) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.handlingRepository = handlingRepository;
        this.stationRepository = stationRepository;
        this.pileRepository = pileRepository;
        this.bikeRepository = bikeRepository;
        this.slotService = slotService;
    }

    // ------------------------------------------------------------------
    // 站点看板计数（StationService 组装卡片时复用，保证全站口径唯一）
    // ------------------------------------------------------------------

    /** 一个站点的三类隔离计数：待隔离 / 已隔离 / 待复验 */
    public record StationCounters(long pendingIsolated, long isolated, long pendingRecheck) {
    }

    public Map<Long, StationCounters> stationCountersMap() {
        Map<Long, long[]> raw = new HashMap<>();
        for (Pile p : pileRepository.findAll()) {
            long[] arr = raw.computeIfAbsent(p.getStationId(), k -> new long[3]);
            if (BikeDict.PILE_PENDING_ISOLATED.equals(p.getStatus())) {
                arr[0]++;
            } else if (BikeDict.PILE_ISOLATED.equals(p.getStatus())) {
                arr[1]++;
            }
        }
        // 待复验按【未完成且等待复验的处置单】计数；一桩至多一条未完成处置
        // （fault_handling 生成列唯一索引硬保证），所以行数即桩位数
        for (FaultHandling h : handlingRepository.findByStatusIn(InspectionDict.FH_PENDING_RECHECK)) {
            raw.computeIfAbsent(h.getStationId(), k -> new long[3])[2]++;
        }
        Map<Long, StationCounters> out = new HashMap<>();
        raw.forEach((sid, arr) -> out.put(sid, new StationCounters(arr[0], arr[1], arr[2])));
        return out;
    }

    // ------------------------------------------------------------------
    // 查询：列表 / 详情
    // ------------------------------------------------------------------

    public List<Views.InspectionListItem> list(Long stationId, String status) {
        if (status != null && !status.isBlank()
                && !InspectionDict.isValidBatchStatus(status.trim().toUpperCase())) {
            throw new BizException("巡检批次状态取值非法：" + status);
        }
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();

        List<InspectionBatch> batches;
        if (stationId != null && st != null) {
            batches = batchRepository.findByStationIdOrderByIdDesc(stationId).stream()
                    .filter(b -> st.equals(b.getStatus())).toList();
        } else if (stationId != null) {
            batches = batchRepository.findByStationIdOrderByIdDesc(stationId);
        } else if (st != null) {
            batches = batchRepository.findByStatusOrderByIdDesc(st);
        } else {
            batches = batchRepository.findAllByOrderByIdDesc();
        }

        Map<Long, Station> stations = stationMap();
        Map<Long, StationCounters> counters = stationCountersMap();
        List<Views.InspectionListItem> list = new ArrayList<>(batches.size());
        for (InspectionBatch b : batches) {
            Station s = stations.get(b.getStationId());
            StationCounters c = counters.getOrDefault(b.getStationId(), new StationCounters(0, 0, 0));
            list.add(new Views.InspectionListItem(
                    b.getId(),
                    b.getBatchNo(),
                    b.getStationId(),
                    s == null ? null : s.getCode(),
                    s == null ? null : s.getName(),
                    b.getStatus(),
                    InspectionDict.batchStatusName(b.getStatus()),
                    nz(b.getTotalCount()),
                    nz(b.getCheckedCount()),
                    nz(b.getFaultCount()),
                    b.getRemark(),
                    b.getCreatedBy(),
                    b.getCreatedAt(),
                    b.getCompletedAt(),
                    c.pendingIsolated(),
                    c.isolated(),
                    c.pendingRecheck()));
        }
        return list;
    }

    public Views.InspectionBatchView detail(Long id) {
        return toBatchView(requireBatch(id));
    }

    // ------------------------------------------------------------------
    // 创建批次：固定桩位清单 + 当时占车快照
    // ------------------------------------------------------------------

    @Transactional
    public Views.InspectionBatchView create(InspectionDtos.BatchCreate req) {
        if (req == null || req.stationId() == null) {
            throw new BizException("创建巡检批次失败：巡检站点不能为空");
        }
        String createdBy = req.createdBy() == null ? "" : req.createdBy().trim();
        if (createdBy.isEmpty()) {
            throw new BizException("创建巡检批次失败：建单人（运营人员）不能为空");
        }
        if (createdBy.length() > 32) {
            throw new BizException("创建巡检批次失败：建单人长度不能超过 32");
        }
        Station station = slotService.lockStation(req.stationId(), "创建巡检批次");

        // 快照在桩位行锁 + 站点车辆行锁内一次读齐：建单瞬间的「桩位清单」与「占车情况」
        List<Pile> piles = pileRepository.findByStationIdForUpdate(station.getId());
        if (piles.isEmpty()) {
            throw new BizException("创建巡检批次失败：站点「" + station.getName() + "」还没有桩位，无法巡检");
        }
        Map<Long, Bike> bikeByPile = new HashMap<>();
        for (Bike b : bikeRepository.findByStationIdForUpdate(station.getId())) {
            if (b.getPileId() != null) {
                bikeByPile.put(b.getPileId(), b);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        InspectionBatch batch = new InspectionBatch();
        batch.setBatchNo("JC" + now.format(NO_FORMAT));
        batch.setStationId(station.getId());
        batch.setStatus(InspectionDict.BATCH_IN_PROGRESS);
        batch.setTotalCount(piles.size());
        batch.setCheckedCount(0);
        batch.setFaultCount(0);
        batch.setRemark(trim(req.remark(), 255));
        batch.setCreatedBy(createdBy);
        batch.setCreatedAt(now);
        batchRepository.save(batch);

        for (Pile p : piles.stream().sorted(Comparator.comparing(Pile::getId)).toList()) {
            Bike occupant = bikeByPile.get(p.getId());
            InspectionItem item = new InspectionItem();
            item.setBatchId(batch.getId());
            item.setPileId(p.getId());
            item.setPileNo(p.getPileNo());
            item.setSnapshotOccupied(occupant != null);
            item.setSnapshotBikeId(occupant == null ? null : occupant.getId());
            item.setSnapshotBikeCode(occupant == null ? null : occupant.getCode());
            item.setResult(InspectionDict.RESULT_PENDING);
            item.setLockStatus(InspectionDict.CHECK_UNKNOWN);
            item.setPowerStatus(InspectionDict.CHECK_UNKNOWN);
            item.setCommStatus(InspectionDict.CHECK_UNKNOWN);
            item.setRev(0);
            item.setCreatedAt(now);
            itemRepository.save(item);
        }

        return toBatchView(requireBatch(batch.getId()));
    }

    // ------------------------------------------------------------------
    // 逐桩登记检查结果（故障 → 立即/待隔离 + 自动开立处置单）
    // ------------------------------------------------------------------

    @Transactional
    public Views.InspectionBatchView check(Long batchId, Long itemId, InspectionDtos.CheckRequest req) {
        if (req == null) {
            throw new BizException("登记检查结果失败：请求参数不能为空");
        }
        String checker = req.checkerName() == null ? "" : req.checkerName().trim();
        if (checker.isEmpty()) {
            throw new BizException("登记检查结果失败：检查人不能为空");
        }
        if (checker.length() > 32) {
            throw new BizException("登记检查结果失败：检查人长度不能超过 32");
        }
        String lock = normalizeCheck(req.lockStatus(), "桩锁");
        String power = normalizeCheck(req.powerStatus(), "供电");
        String comm = normalizeCheck(req.commStatus(), "通信");
        boolean fault = InspectionDict.CHECK_FAULT.equals(lock)
                || InspectionDict.CHECK_FAULT.equals(power)
                || InspectionDict.CHECK_FAULT.equals(comm);
        String level = null;
        if (fault) {
            level = req.faultLevel() == null ? "" : req.faultLevel().trim().toUpperCase();
            if (!InspectionDict.isValidFaultLevel(level)) {
                throw new BizException("登记检查结果失败：检查发现故障，故障等级必须是 LOW / MID / HIGH");
            }
        }

        // 批次行锁先拿：两名运营人员同时登记时，批次计数在这里串行
        InspectionBatch batch = lockBatch(batchId);
        InspectionItem item = itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new BizException("登记检查结果失败：巡检明细不存在（id=" + itemId + "）"));
        if (!item.getBatchId().equals(batch.getId())) {
            throw new BizException("登记检查结果失败：该桩位明细不属于批次 " + batch.getBatchNo());
        }

        // 乐观版本 + 状态双重判定：旧页面提交 → 409 冲突并带回最新视图
        if (req.rev() == null || !req.rev().equals(item.getRev())
                || !InspectionDict.RESULT_PENDING.equals(item.getResult())) {
            throw new ConflictException(buildCheckConflict(batch, item), toBatchView(batch));
        }

        // 固定加锁顺序：站点 → 桩位 → 车辆，与借还/调拨完全一致
        slotService.lockStation(batch.getStationId(), "登记巡检结果");
        Pile pile = slotService.lockPile(item.getPileId(), "登记巡检结果");
        Bike occupant = bikeRepository.findByPileIdForUpdate(pile.getId()).orElse(null);

        LocalDateTime now = LocalDateTime.now();
        item.setCheckerName(checker);
        item.setCheckedAt(now);
        item.setLockStatus(lock);
        item.setPowerStatus(power);
        item.setCommStatus(comm);
        item.setFaultDesc(trim(req.faultDesc(), 255));

        if (fault) {
            markFaultAndIsolate(batch, item, pile, occupant, level, now);
        } else {
            // 正常：清掉故障字段，并顺手把「桩位状态 vs bike.pile_id」账实对齐，
            // 绝不留下「显示空闲却挂车 / 显示占用却查不到车」的矛盾
            item.setResult(InspectionDict.RESULT_OK);
            item.setFaultLevel(null);
            item.setIsolatedMode(null);
            reconcileHealthyPile(pile, occupant);
        }
        item.setRev(item.getRev() + 1);

        recount(batch);
        return toBatchView(batch);
    }

    /**
     * 故障桩处置：
     *  - 一桩已有未完成处置单 → 拒绝（数据库生成列唯一索引兜底，这里给中文提示）；
     *  - 空桩立即 ISOLATED；有车 PENDING_ISOLATED（不动车辆落点，车可借走/调出）；
     *  - 人工停用桩不恢复：保持 DISABLED，仅按已隔离登记处置单。
     */
    private void markFaultAndIsolate(InspectionBatch batch, InspectionItem item,
                                     Pile pile, Bike occupant, String level, LocalDateTime now) {
        List<FaultHandling> open = handlingRepository.findByPileIdAndStatusIn(
                pile.getId(), InspectionDict.FH_OPEN);
        if (!open.isEmpty()) {
            throw new BizException("登记检查结果失败：桩位「" + pile.getPileNo() + "」已存在未完成的故障处置单 "
                    + open.get(0).getHandlingNo() + "（"
                    + InspectionDict.handlingStatusName(open.get(0).getStatus())
                    + "），同一桩位不能同时有两条未完成处置，请先完成复验");
        }

        String mode;
        if (BikeDict.PILE_DISABLED.equals(pile.getStatus())) {
            // 人工停用桩：本就不可借还/调拨，维持停用状态，处置单按「已隔离」口径跟踪
            mode = InspectionDict.MODE_ISOLATED;
        } else if (occupant == null) {
            pile.setStatus(BikeDict.PILE_ISOLATED);
            mode = InspectionDict.MODE_ISOLATED;
        } else {
            // 桩上仍有车：绝不清车辆落点，进入待隔离，车离开时由 releasePile 自动转已隔离
            pile.setStatus(BikeDict.PILE_PENDING_ISOLATED);
            mode = InspectionDict.MODE_PENDING_ISOLATED;
        }

        item.setResult(InspectionDict.RESULT_FAULT);
        item.setFaultLevel(level);
        item.setIsolatedMode(mode);

        FaultHandling h = new FaultHandling();
        h.setHandlingNo("FH" + now.format(NO_FORMAT));
        h.setPileId(pile.getId());
        h.setStationId(pile.getStationId());
        h.setBatchId(batch.getId());
        h.setItemId(item.getId());
        h.setBikeId(occupant == null ? null : occupant.getId());
        h.setFaultLevel(level);
        h.setFaultDesc(item.getFaultDesc());
        h.setStatus(InspectionDict.FH_PENDING);
        h.setRev(0);
        h.setCreatedAt(now);
        h.setUpdatedAt(now);
        handlingRepository.save(h);
    }

    /** 正常桩的账实对齐：以 bike.pile_id 这一真实占用关系为准修正桩位快照状态 */
    private void reconcileHealthyPile(Pile pile, Bike occupant) {
        if (BikeDict.PILE_DISABLED.equals(pile.getStatus())
                || BikeDict.pileIsolation(pile.getStatus())) {
            // 停用/隔离态不是检查一次「正常」就能解除的（必须走复验），不动
            return;
        }
        if (occupant == null && BikeDict.PILE_OCCUPIED.equals(pile.getStatus())) {
            pile.setStatus(BikeDict.PILE_IDLE);
        } else if (occupant != null && BikeDict.PILE_IDLE.equals(pile.getStatus())) {
            pile.setStatus(BikeDict.PILE_OCCUPIED);
        }
    }

    // ------------------------------------------------------------------
    // 故障处置：FH_PENDING / FH_RECHECK_REJECTED → FH_HANDLED（待复验）
    // ------------------------------------------------------------------

    @Transactional
    public Views.InspectionBatchView handle(Long handlingId, InspectionDtos.HandleRequest req) {
        if (req == null) {
            throw new BizException("故障处置失败：请求参数不能为空");
        }
        String handler = req.handlerName() == null ? "" : req.handlerName().trim();
        if (handler.isEmpty()) {
            throw new BizException("故障处置失败：处置人不能为空");
        }
        if (handler.length() > 32) {
            throw new BizException("故障处置失败：处置人长度不能超过 32");
        }
        FaultHandling h = lockHandling(handlingId);
        if (req.rev() == null || !req.rev().equals(h.getRev())) {
            throw new ConflictException("冲突：处置单 " + h.getHandlingNo()
                    + " 已被其他操作推进到「" + InspectionDict.handlingStatusName(h.getStatus())
                    + "」，请刷新查看最新状态后再操作", batchViewOf(h));
        }
        if (InspectionDict.FH_RECHECK_PASS.equals(h.getStatus())) {
            throw new BizException("故障处置失败：处置单 " + h.getHandlingNo() + " 已复验通过并闭环，不能再处置");
        }
        if (InspectionDict.FH_HANDLED.equals(h.getStatus())) {
            throw new BizException("故障处置失败：处置单 " + h.getHandlingNo() + " 已处置，正在等待复验，不能重复处置");
        }
        LocalDateTime now = LocalDateTime.now();
        h.setStatus(InspectionDict.FH_HANDLED);
        h.setHandlerName(handler);
        h.setHandleNote(trim(req.handleNote(), 255));
        h.setHandledAt(now);
        h.setRev(h.getRev() + 1);
        h.setUpdatedAt(now);
        // 处置期间桩位维持隔离（待隔离/已隔离都不变），只有复验能恢复
        return batchViewOf(h);
    }

    // ------------------------------------------------------------------
    // 复验：唯一的隔离出口
    // ------------------------------------------------------------------

    @Transactional
    public Views.InspectionBatchView recheck(Long handlingId, InspectionDtos.RecheckRequest req) {
        if (req == null || req.pass() == null) {
            throw new BizException("复验失败：复验结论不能为空");
        }
        String rechecker = req.recheckerName() == null ? "" : req.recheckerName().trim();
        if (rechecker.isEmpty()) {
            throw new BizException("复验失败：复验人不能为空");
        }
        if (rechecker.length() > 32) {
            throw new BizException("复验失败：复验人长度不能超过 32");
        }
        FaultHandling h = lockHandling(handlingId);
        if (req.rev() == null || !req.rev().equals(h.getRev())) {
            throw new ConflictException("冲突：处置单 " + h.getHandlingNo()
                    + " 已被其他操作推进到「" + InspectionDict.handlingStatusName(h.getStatus())
                    + "」，请刷新查看最新状态后再复验", batchViewOf(h));
        }
        if (InspectionDict.FH_RECHECK_PASS.equals(h.getStatus())) {
            throw new BizException("复验失败：处置单 " + h.getHandlingNo() + " 已复验通过并闭环，不能重复复验");
        }
        if (InspectionDict.FH_PENDING.equals(h.getStatus())) {
            throw new BizException("复验失败：处置单 " + h.getHandlingNo() + " 尚未完成处置，不能复验");
        }

        String note = trim(req.recheckNote(), 255);
        LocalDateTime now = LocalDateTime.now();
        h.setRecheckerName(rechecker);
        h.setRecheckNote(note);
        h.setRecheckedAt(now);
        h.setRev(h.getRev() + 1);
        h.setUpdatedAt(now);

        if (!req.pass()) {
            if (note == null || note.isBlank()) {
                throw new BizException("复验失败：复验不通过必须填写原因，桩位将继续隔离");
            }
            h.setStatus(InspectionDict.FH_RECHECK_REJECTED);
            h.setRecheckPass(false);
            // 继续隔离：桩位状态一个都不动（待隔离的车仍可离开，离开后自动转已隔离）
            return batchViewOf(h);
        }

        // 复验通过：按【真实占车】恢复，空闲/占用由 bike.pile_id 决定，不接受页面指定
        slotService.lockStation(h.getStationId(), "复验");
        Pile pile = slotService.lockPile(h.getPileId(), "复验");
        Bike occupant = bikeRepository.findByPileIdForUpdate(pile.getId()).orElse(null);
        if (BikeDict.PILE_DISABLED.equals(pile.getStatus())) {
            // 人工停用桩：巡检闭环不改变人工停用决定
        } else if (occupant == null) {
            pile.setStatus(BikeDict.PILE_IDLE);
        } else {
            pile.setStatus(BikeDict.PILE_OCCUPIED);
        }
        h.setStatus(InspectionDict.FH_RECHECK_PASS);
        h.setRecheckPass(true);
        return batchViewOf(h);
    }

    // ------------------------------------------------------------------
    // 视图装配
    // ------------------------------------------------------------------

    private Views.InspectionBatchView batchViewOf(FaultHandling h) {
        return toBatchView(requireBatch(h.getBatchId()));
    }

    private Views.InspectionBatchView toBatchView(InspectionBatch batch) {
        Station station = stationRepository.findById(batch.getStationId()).orElse(null);
        List<InspectionItem> items = itemRepository.findByBatchIdOrderByIdAsc(batch.getId());

        Map<Long, Pile> pileMap = pileRepository.findAll().stream()
                .collect(Collectors.toMap(Pile::getId, p -> p, (a, b) -> a));
        Map<Long, Bike> bikeMap = bikeRepository.findAll().stream()
                .collect(Collectors.toMap(Bike::getId, b -> b, (a, b) -> a));
        Map<Long, Bike> bikeByPile = bikeMap.values().stream()
                .filter(b -> b.getPileId() != null)
                .collect(Collectors.toMap(Bike::getPileId, b -> b, (a, b) -> a));

        // 每个桩位选「当前生效」的处置单：未完成的优先（一桩至多一条，由唯一索引保证），
        // 否则取最近一条闭环单，用于展示历史复验结论
        Map<Long, FaultHandling> handlingByPile = new HashMap<>();
        for (FaultHandling h : handlingRepository.findAll()) {
            FaultHandling oldOne = handlingByPile.get(h.getPileId());
            if (oldOne == null) {
                handlingByPile.put(h.getPileId(), h);
            } else if (InspectionDict.isHandlingOpen(h.getStatus())
                    && !InspectionDict.isHandlingOpen(oldOne.getStatus())) {
                handlingByPile.put(h.getPileId(), h);
            } else if (!InspectionDict.isHandlingOpen(h.getStatus())
                    && !InspectionDict.isHandlingOpen(oldOne.getStatus())
                    && h.getId() > oldOne.getId()) {
                handlingByPile.put(h.getPileId(), h);
            }
        }
        Map<Long, String> batchNoMap = batchRepository.findAll().stream()
                .collect(Collectors.toMap(InspectionBatch::getId, InspectionBatch::getBatchNo, (a, b) -> a));
        Map<Long, String> stationNameMap = stationMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getName()));

        List<Views.InspectionItemView> itemViews = new ArrayList<>(items.size());
        for (InspectionItem it : items) {
            Pile pile = pileMap.get(it.getPileId());
            Bike current = pile == null ? null : bikeByPile.get(pile.getId());
            Bike snapshotBike = it.getSnapshotBikeId() == null ? null : bikeMap.get(it.getSnapshotBikeId());
            FaultHandling h = handlingByPile.get(it.getPileId());
            boolean changed = (it.getSnapshotOccupied() != null && it.getSnapshotOccupied())
                    ? current == null || !current.getId().equals(it.getSnapshotBikeId())
                    : current != null;
            itemViews.add(new Views.InspectionItemView(
                    it.getId(),
                    it.getPileId(),
                    it.getPileNo(),
                    Boolean.TRUE.equals(it.getSnapshotOccupied()),
                    it.getSnapshotBikeId(),
                    snapshotBike == null ? it.getSnapshotBikeCode() : snapshotBike.getCode(),
                    it.getResult(),
                    InspectionDict.resultName(it.getResult()),
                    it.getLockStatus(),
                    InspectionDict.checkName(it.getLockStatus()),
                    it.getPowerStatus(),
                    InspectionDict.checkName(it.getPowerStatus()),
                    it.getCommStatus(),
                    InspectionDict.checkName(it.getCommStatus()),
                    it.getFaultLevel(),
                    it.getFaultLevel() == null ? null : InspectionDict.faultLevelName(it.getFaultLevel()),
                    it.getFaultDesc(),
                    it.getIsolatedMode(),
                    it.getIsolatedMode() == null ? null : InspectionDict.modeName(it.getIsolatedMode()),
                    it.getCheckerName(),
                    it.getCheckedAt(),
                    pile == null ? null : pile.getStatus(),
                    pile == null ? null : BikeDict.pileStatusName(pile.getStatus()),
                    current == null ? null : current.getId(),
                    current == null ? null : current.getCode(),
                    changed,
                    h == null ? null : toHandlingView(h, pile, batchNoMap, stationNameMap, bikeMap),
                    it.getRev()));
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
                nz(batch.getTotalCount()),
                nz(batch.getCheckedCount()),
                nz(batch.getFaultCount()),
                batch.getRemark(),
                batch.getCreatedBy(),
                batch.getCreatedAt(),
                batch.getCompletedAt(),
                itemViews);
    }

    private Views.HandlingView toHandlingView(FaultHandling h,
                                              Pile pile,
                                              Map<Long, String> batchNoMap,
                                              Map<Long, String> stationNameMap,
                                              Map<Long, Bike> bikeMap) {
        Bike bike = h.getBikeId() == null ? null : bikeMap.get(h.getBikeId());
        return new Views.HandlingView(
                h.getId(),
                h.getHandlingNo(),
                h.getPileId(),
                pile == null ? null : pile.getPileNo(),
                h.getStationId(),
                stationNameMap.get(h.getStationId()),
                h.getBatchId(),
                batchNoMap.get(h.getBatchId()),
                h.getItemId(),
                h.getBikeId(),
                bike == null ? null : bike.getCode(),
                h.getFaultLevel(),
                InspectionDict.faultLevelName(h.getFaultLevel()),
                h.getFaultDesc(),
                h.getStatus(),
                InspectionDict.handlingStatusName(h.getStatus()),
                h.getHandlerName(),
                h.getHandleNote(),
                h.getHandledAt(),
                h.getRecheckerName(),
                h.getRecheckPass(),
                h.getRecheckNote(),
                h.getRecheckedAt(),
                h.getRev(),
                h.getCreatedAt());
    }

    private String buildCheckConflict(InspectionBatch batch, InspectionItem item) {
        if (InspectionDict.RESULT_PENDING.equals(item.getResult())) {
            return "冲突：桩位「" + item.getPileNo() + "」的检查记录版本已过期，请刷新后重新登记";
        }
        return "冲突：桩位「" + item.getPileNo() + "」的检查结果已由「" + item.getCheckerName()
                + "」登记为「" + InspectionDict.resultName(item.getResult())
                + "」，你页面上的状态已过期；已生效的巡检记录不会被覆盖，请刷新查看最新结果";
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private String normalizeCheck(String value, String label) {
        String v = value == null ? "" : value.trim().toUpperCase();
        if (!InspectionDict.isValidCheckValue(v)) {
            throw new BizException("登记检查结果失败：" + label + "检查结果必须是 NORMAL（正常）或 FAULT（故障）");
        }
        return v;
    }

    private void recount(InspectionBatch batch) {
        long checked = itemRepository.countByBatchIdAndResultIn(
                batch.getId(), List.of(InspectionDict.RESULT_OK, InspectionDict.RESULT_FAULT));
        long fault = itemRepository.countByBatchIdAndResult(batch.getId(), InspectionDict.RESULT_FAULT);
        batch.setCheckedCount((int) checked);
        batch.setFaultCount((int) fault);
        if (checked >= nz(batch.getTotalCount())
                && !InspectionDict.BATCH_COMPLETED.equals(batch.getStatus())) {
            batch.setStatus(InspectionDict.BATCH_COMPLETED);
            batch.setCompletedAt(LocalDateTime.now());
        }
    }

    private InspectionBatch requireBatch(Long id) {
        if (id == null) {
            throw new BizException("巡检批次不能为空");
        }
        return batchRepository.findById(id)
                .orElseThrow(() -> new BizException("巡检批次不存在（id=" + id + "）"));
    }

    private InspectionBatch lockBatch(Long id) {
        if (id == null) {
            throw new BizException("巡检批次不能为空");
        }
        return batchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("巡检批次不存在（id=" + id + "）"));
    }

    private FaultHandling lockHandling(Long id) {
        if (id == null) {
            throw new BizException("故障处置单不能为空");
        }
        return handlingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("故障处置单不存在（id=" + id + "）"));
    }

    private Map<Long, Station> stationMap() {
        return stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, s -> s, (a, b) -> a, LinkedHashMap::new));
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
}
