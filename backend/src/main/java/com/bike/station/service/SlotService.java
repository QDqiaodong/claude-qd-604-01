package com.bike.station.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.bike.station.common.BizException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Station;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.StationRepository;

/**
 * 桩位占用的公共逻辑（借车 / 还车 / 调拨 / 车辆改位都走这里）。
 *
 * 加锁约定 —— 全仓统一按这个顺序上锁，避免两条流程互相等对方造成死锁：
 *     站点 → 桩位 → 车辆 → 骑行人 → 订单/工单
 *
 * 用法：先用 requireXxx（无锁）做提前失败与拿到关联 id，
 * 再按上面的顺序调 lockXxx 真正上锁，并在锁内把校验重新做一遍。
 */
@Service
public class SlotService {

    private final StationRepository stationRepository;
    private final PileRepository pileRepository;
    private final BikeRepository bikeRepository;

    public SlotService(StationRepository stationRepository,
                       PileRepository pileRepository,
                       BikeRepository bikeRepository) {
        this.stationRepository = stationRepository;
        this.pileRepository = pileRepository;
        this.bikeRepository = bikeRepository;
    }

    // ------------------------------------------------------------------
    // 无锁读取（提前失败 / 取关联 id）
    // ------------------------------------------------------------------

    public Station requireStation(Long stationId, String action) {
        if (stationId == null) {
            throw new BizException(action + "失败：站点不能为空");
        }
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new BizException(action + "失败：站点不存在（id=" + stationId + "）"));
    }

    public Pile requirePile(Long pileId, String action) {
        if (pileId == null) {
            throw new BizException(action + "失败：桩位不能为空");
        }
        return pileRepository.findById(pileId)
                .orElseThrow(() -> new BizException(action + "失败：桩位不存在（id=" + pileId + "）"));
    }

    // ------------------------------------------------------------------
    // 加锁读取（必须在 @Transactional 里调用）
    // ------------------------------------------------------------------

    /** 锁 station 表的这一行 */
    public Station lockStation(Long stationId, String action) {
        if (stationId == null) {
            throw new BizException(action + "失败：站点不能为空");
        }
        return stationRepository.findByIdForUpdate(stationId)
                .orElseThrow(() -> new BizException(action + "失败：站点不存在（id=" + stationId + "）"));
    }

    /** 锁 pile 表的这一行 */
    public Pile lockPile(Long pileId, String action) {
        if (pileId == null) {
            throw new BizException(action + "失败：桩位不能为空");
        }
        return pileRepository.findByIdForUpdate(pileId)
                .orElseThrow(() -> new BizException(action + "失败：桩位不存在（id=" + pileId + "）"));
    }

    // ------------------------------------------------------------------
    // 锁内校验
    // ------------------------------------------------------------------

    /** 站点必须处于运营中，停用站点一律不许借还、不许调入 */
    public void assertStationOpen(Station station, String action) {
        if (!BikeDict.STATION_OPEN.equals(station.getStatus())) {
            throw new BizException(action + "失败：站点「" + station.getName() + "」已停用，不能在该站点借车或还车");
        }
    }

    /**
     * 桩位必须能接收车辆（还车 / 调拨落点 / 新车上桩）。
     * 停用、已隔离、待隔离的桩位一律不放新车 —— 巡检隔离在这条线上硬生效，
     * 不是只在前端隐藏选项。
     */
    public void assertPileUsable(Pile pile, String action) {
        if (!BikeDict.pileAcceptsBike(pile.getStatus())) {
            throw new BizException(action + "失败：桩位「" + pile.getPileNo() + "」处于「"
                    + BikeDict.pileStatusName(pile.getStatus())
                    + "」状态，不能接收车辆（故障桩在复验通过前一律不放新车）");
        }
    }

    /**
     * 借车校验：桩位不能停用、不能已隔离。
     * 「待隔离」桩上的车仍允许被正常借走（车一离开，桩位由 {@link #releasePile}
     * 自动转为已隔离）—— 安全隔离优先，但不把还挂着的车困在桩上。
     */
    public void assertPileBorrowable(Pile pile, String action) {
        String status = pile.getStatus();
        if (BikeDict.PILE_DISABLED.equals(status) || BikeDict.PILE_ISOLATED.equals(status)) {
            throw new BizException(action + "失败：桩位「" + pile.getPileNo() + "」处于「"
                    + BikeDict.pileStatusName(status) + "」状态，不能借车");
        }
    }

    /** 一桩一车：桩位上已经有车就不能再放 */
    public void assertPileFree(Pile pile, String action) {
        Bike occupant = bikeRepository.findByPileId(pile.getId()).orElse(null);
        if (occupant != null) {
            throw new BizException(action + "失败：桩位「" + pile.getPileNo() + "」已被车辆 "
                    + occupant.getCode() + " 占用，一个桩位同时只能停一辆车");
        }
    }

    /**
     * 站点容量校验：站内车辆数不得超过 capacity。
     * movingBike 表示「即将停进来的那辆车」，如果它本来就停在这个站点，则不重复计数。
     */
    public void assertStationHasRoom(Station station, Bike movingBike, String action) {
        long used = bikeRepository.countByStationId(station.getId());
        if (movingBike != null && station.getId().equals(movingBike.getStationId())) {
            used = used - 1;
        }
        if (used >= station.getCapacity()) {
            throw new BizException(action + "失败：站点「" + station.getName() + "」已满，容量 "
                    + station.getCapacity() + " 辆，当前已停 " + used + " 辆");
        }
    }

    // ------------------------------------------------------------------
    // 占用关系的写操作（与上锁在同一个事务里）
    // ------------------------------------------------------------------

    /** 把车放到桩位上：bike 的位置与 pile 的状态在同一个事务里一起改，不会只改一半 */
    public void place(Bike bike, Station station, Pile pile) {
        bike.setStationId(station.getId());
        bike.setPileId(pile.getId());
        bike.setUpdatedAt(LocalDateTime.now());
        pile.setStatus(BikeDict.PILE_OCCUPIED);
    }

    /**
     * 把车从它现在占的桩位上摘下来（车离桩：借车、调出、送修、报废都走这里）。
     * 只释放桩位并把 bike.pileId 置空；要不要连 stationId 一起清掉由调用方决定
     * （骑行中/报废要清，维修中要留）。
     *
     * 巡检联动：待隔离（PENDING_ISOLATED）桩上的车一离开，桩位【自动】转为已隔离 ——
     * 不依赖页面再点一次，调用方（借车/调拨/送修）都已持桩位行锁，状态推进与车辆
     * 落点在同一事务内完成，不会出现「桩显示空闲却仍挂车 / 显示占用却查不到车」。
     */
    public void releasePile(Bike bike) {
        Long pileId = bike.getPileId();
        if (pileId == null) {
            return;
        }
        pileRepository.findById(pileId).ifPresent(p -> {
            if (BikeDict.PILE_PENDING_ISOLATED.equals(p.getStatus())) {
                p.setStatus(BikeDict.PILE_ISOLATED);
            } else if (!BikeDict.PILE_DISABLED.equals(p.getStatus())
                    && !BikeDict.PILE_ISOLATED.equals(p.getStatus())) {
                p.setStatus(BikeDict.PILE_IDLE);
            }
        });
        bike.setPileId(null);
    }
}
