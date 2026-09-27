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

    /** 桩位不能是停用状态 */
    public void assertPileUsable(Pile pile, String action) {
        if (BikeDict.PILE_DISABLED.equals(pile.getStatus())) {
            throw new BizException(action + "失败：桩位「" + pile.getPileNo() + "」处于停用状态，不能使用");
        }
    }

    /**
     * 接收新车（还车 / 调拨落位 / 人工上桩）时的桩位闸门：
     * 停用、待隔离、已隔离的桩位一律不接收新车。
     *
     * 巡检口径（安全隔离优先）：待隔离桩上的那辆旧车仍可正常借走/调出，
     * 但任何新车辆都不能再放进来，故障桩绝不接收新车。
     */
    public void assertPileAcceptsBike(Pile pile, String action) {
        if (!BikeDict.pileAcceptsBike(pile.getStatus())) {
            throw new BizException(action + "失败：桩位「" + pile.getPileNo() + "」当前为「"
                    + BikeDict.pileStatusName(pile.getStatus())
                    + "」状态，不能接收车辆（停用桩与巡检隔离桩都不得放车）");
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

    /**
     * 把车放到桩位上：bike 的位置与 pile 的状态在同一个事务里一起改，不会只改一半。
     * 调用方必须先用 {@link #assertPileAcceptsBike} 过闸门 —— 这里不重复判断，
     * 落位成功即占用（隔离桩永远走不到这里）。
     */
    public void place(Bike bike, Station station, Pile pile) {
        bike.setStationId(station.getId());
        bike.setPileId(pile.getId());
        bike.setUpdatedAt(LocalDateTime.now());
        pile.setStatus(BikeDict.PILE_OCCUPIED);
    }

    /**
     * 把车从它现在占的桩位上摘下来（车离桩：借车、送修、报废都走这里）。
     * 只释放桩位并把 bike.pileId 置空；要不要连 stationId 一起清掉由调用方决定
     * （骑行中/报废要清，维修中要留）。
     *
     * 巡检隔离口径：
     *  - 待隔离桩上的车离开后，桩位自动转为「已隔离」，绝不释放回空闲；
     *  - 停用桩保持停用；其余桩位释放为空闲。
     */
    public void releasePile(Bike bike) {
        Long pileId = bike.getPileId();
        if (pileId == null) {
            return;
        }
        pileRepository.findById(pileId).ifPresent(p -> {
            if (BikeDict.PILE_PENDING_QUARANTINE.equals(p.getStatus())) {
                p.setStatus(BikeDict.PILE_QUARANTINED);
            } else if (!BikeDict.PILE_DISABLED.equals(p.getStatus())
                    && !BikeDict.PILE_QUARANTINED.equals(p.getStatus())) {
                p.setStatus(BikeDict.PILE_IDLE);
            }
        });
        bike.setPileId(null);
    }

    /**
     * 锁内对账：以 bike.pile_id 的真实占用为准，把桩位快照纠正成一致状态。
     *
     * 巡检登记/复验等动作在拿到桩位行锁后调用：车辆可能刚被借走、还走或调拨，
     * 不能留下「桩位显示空闲却仍挂车」或「显示占用却查不到车」的矛盾。
     *
     * @param pile      已持行锁的桩位
     * @param occupant  该桩位上的真实车辆（无车传 null）
     * @return 对账后桩位是否「有车占用」
     */
    public boolean reconcileOccupancy(Pile pile, Bike occupant) {
        String status = pile.getStatus();
        if (occupant != null) {
            // 桩上有车：待隔离/占用维持「有车」语义；其它一律标记占用（含被错误置空的隔离空桩）
            if (BikeDict.PILE_PENDING_QUARANTINE.equals(status)
                    || BikeDict.PILE_QUARANTINED.equals(status)) {
                if (!BikeDict.PILE_PENDING_QUARANTINE.equals(status)) {
                    // 已隔离空桩却挂着车（异常事实）：安全起见先回到待隔离，车可以离开但新车进不来
                    pile.setStatus(BikeDict.PILE_PENDING_QUARANTINE);
                }
            } else if (!BikeDict.PILE_OCCUPIED.equals(status)) {
                pile.setStatus(BikeDict.PILE_OCCUPIED);
            }
            return true;
        }
        // 桩上无车：待隔离 → 已隔离（车已离开，自动推进）；停用保持停用；其余标记空闲
        if (BikeDict.PILE_PENDING_QUARANTINE.equals(status)
                || BikeDict.PILE_QUARANTINED.equals(status)) {
            pile.setStatus(BikeDict.PILE_QUARANTINED);
        } else if (!BikeDict.PILE_DISABLED.equals(status)) {
            pile.setStatus(BikeDict.PILE_IDLE);
        }
        return false;
    }
}
