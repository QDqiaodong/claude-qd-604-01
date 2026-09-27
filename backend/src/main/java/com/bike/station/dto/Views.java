package com.bike.station.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。统一把「外键 id」翻译成人能看懂的编号与名称，
 * 前端拿到的就是可以直接铺在卡片上的数据，不用再自己拼。
 */
public final class Views {

    private Views() {
    }

    /** 车辆台账（带站点/桩位编号，便于卡片直接显示） */
    public record BikeView(
            Long id,
            String code,
            String model,
            String status,
            String statusName,
            Long stationId,
            String stationCode,
            String stationName,
            Long pileId,
            String pileNo,
            Integer totalRides,
            String scrapReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 桩位格子里的一格：空着就只有状态，有车就带上车的编号与车型 */
    public record PileView(
            Long id,
            String pileNo,
            String status,
            String statusName,
            Long bikeId,
            String bikeCode,
            String bikeModel) {
    }

    /** 站点卡片：给「站点卡片 + 拖拽调拨」用 */
    public record StationCard(
            Long id,
            String code,
            String name,
            String district,
            Integer capacity,
            String status,
            String statusName,
            long bikeCount,
            long freePileCount,
            /** 模块六：站内待隔离桩位数（桩上有车，车可离开但新车进不来） */
            long pendingQuarantineCount,
            /** 模块六：站内已隔离桩位数（空桩，不参与借还/调拨） */
            long quarantinedCount,
            /** 模块六：站内待复验的故障处置数（处置完成，等待复验恢复） */
            long pendingRecheckCount,
            List<PileView> piles,
            /** 正在清站（待执行/执行中/部分受阻）时非空，站点看板显示「正在清站 · 受阻 N」 */
            ClearanceBadge activeClearance,
            /** 最近一次清站完成结果（已停用站点展示「清站完成」） */
            ClearanceBadge lastClearance) {
    }

    /** 站点卡片上的清站角标 */
    public record ClearanceBadge(
            Long clearanceId,
            String orderNo,
            String status,
            String statusName,
            Integer migratedCount,
            Integer totalCount,
            Integer blockedCount,
            LocalDateTime completedTime) {
    }

    /** 租借订单 */
    public record RentalView(
            Long id,
            String orderNo,
            Long userId,
            String userNickname,
            Long bikeId,
            String bikeCode,
            Long startStationId,
            String startStationName,
            Long startPileId,
            String startPileNo,
            Long endStationId,
            String endStationName,
            Long endPileId,
            String endPileNo,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Integer durationMinutes,
            BigDecimal amount,
            String status,
            String statusName) {
    }

    /** 借车结果 */
    public record BorrowResult(
            String message,
            RentalView rental,
            String bikeCode,
            String stationName,
            String pileNo,
            BigDecimal balance) {
    }

    /** 还车结果（含计费明细，前端用它弹结算单） */
    public record ReturnResult(
            String message,
            RentalView rental,
            String bikeCode,
            String stationName,
            String pileNo,
            Integer durationMinutes,
            BigDecimal amount,
            BigDecimal balanceAfter) {
    }

    /** 运维工单 */
    public record WorkOrderView(
            Long id,
            String orderNo,
            Long bikeId,
            String bikeCode,
            String faultType,
            String level,
            String levelName,
            String status,
            String statusName,
            Long workerId,
            String workerName,
            LocalDateTime planStart,
            LocalDateTime planEnd,
            LocalDateTime reportTime,
            LocalDateTime assignTime,
            LocalDateTime fixTime,
            String fixNote) {
    }

    /** 调度结果（拖拽调拨返回这个，前端用来弹提示） */
    public record TransferResult(
            String message,
            Long bikeId,
            String bikeCode,
            String fromStationName,
            String fromPileNo,
            String toStationName,
            String toPileNo) {
    }

    /** 顶部概览数字 */
    public record Overview(
            long bikeTotal,
            long bikePile,
            long bikeRiding,
            long bikeRepair,
            long bikeScrapped,
            long stationTotal,
            long stationOpen,
            long pileTotal,
            long pileIdle,
            long pileOccupied,
            long rentalRiding,
            long rentalSettled,
            long orderReported,
            long orderAssigned,
            /** 正在清站（待执行/执行中/部分受阻）的清站单数 */
            long clearanceActive,
            /** 其中「部分受阻」的清站单数 */
            long clearanceBlocked,
            /** 模块六：待隔离桩位数 */
            long pilePendingQuarantine,
            /** 模块六：已隔离桩位数 */
            long pileQuarantined,
            /** 模块六：巡检中批次数 */
            long inspectionOpen,
            /** 模块六：待复验的故障处置数 */
            long inspectionPendingRecheck) {
    }

    // ------------------------------------------------------------------
    // 模块五：应急清站
    // ------------------------------------------------------------------

    /** 清站方案里的接收站实时信息（容量/空桩/维修工接收能力） */
    public record ClearanceStationInfo(
            Long stationId,
            String stationCode,
            String stationName,
            String stationStatus,
            Integer capacity,
            long bikeCount,
            long freePileCount,
            /** 是否具备故障车接收能力：有在岗维修工、且这些维修工名下都没有未完成工单 */
            boolean faultCapable,
            /** 具备接收能力的在岗维修工姓名（故障车优先分配时展示依据） */
            List<String> freeWorkerNames,
            /** 不具备能力的原因（在岗维修工都有未完成工单 / 没有在岗维修工） */
            String faultBlockReason,
            /** 距源站直线距离（公里），无法定位时为 null */
            Double distanceKm,
            Integer seq) {
    }

    /** 清站方案里的一辆待迁车（含计划去向与无处安置原因） */
    public record ClearanceBikeView(
            Long itemId,
            Long bikeId,
            String bikeCode,
            String bikeModel,
            String bikeStatus,
            String bikeStatusName,
            String bikeKind,
            String bikeKindName,
            String itemStatus,
            String itemStatusName,
            Long planStationId,
            String planStationName,
            Long planPileId,
            String planPileNo,
            Long actualStationId,
            String actualStationName,
            Long actualPileId,
            String actualPileNo,
            String failReason,
            LocalDateTime migratedAt) {
    }

    /** 清站单列表项 */
    public record ClearanceListItem(
            Long id,
            String orderNo,
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            String sourceStationStatus,
            String status,
            String statusName,
            int totalCount,
            int plannedCount,
            int migratedCount,
            int blockedCount,
            String planRemark,
            String lastExecuteResult,
            LocalDateTime lastExecuteTime,
            LocalDateTime completedTime,
            LocalDateTime cancelledTime,
            LocalDateTime createdAt,
            List<String> receiverStationNames) {
    }

    /** 清站单详情（含全部车辆明细与接收站实时容量） */
    public record ClearanceOrderView(
            Long id,
            String orderNo,
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            String sourceStationStatus,
            String status,
            String statusName,
            int totalCount,
            int plannedCount,
            int migratedCount,
            int blockedCount,
            String planRemark,
            String lastExecuteResult,
            LocalDateTime executingAt,
            LocalDateTime lastExecuteTime,
            LocalDateTime completedTime,
            LocalDateTime cancelledTime,
            LocalDateTime createdAt,
            List<ClearanceStationInfo> receivers,
            List<ClearanceBikeView> items,
            /** 完成闸门的实时尾巴：在站车辆数 / 在途租借数 / 未关闭工单数 / 占用桩位数 */
            ClearanceGate gate) {
    }

    /** 建单前的方案预览（不落库） */
    public record ClearancePreviewView(
            Long sourceStationId,
            String sourceStationCode,
            String sourceStationName,
            int waitingCount,
            int plannedCount,
            int unplacedCount,
            String planRemark,
            List<ClearanceStationInfo> receivers,
            List<ClearanceBikeView> items) {
    }

    /** 清站完成闸门的四类尾巴，任一不为 0 都不能完成、不能停用源站 */
    public record ClearanceGate(
            long bikesAtStation,
            long ridingRentals,
            long openWorkOrders,
            long occupiedPiles) {
    }

    // ------------------------------------------------------------------
    // 模块六：桩位巡检
    // ------------------------------------------------------------------

    /** 运营人员（检查人 / 处置人 / 复验人） */
    public record OperatorView(
            Long id,
            String name,
            String phone,
            String status,
            String statusName) {
    }

    /** 复验记录（复验动作留痕，复验不通过原因逐条保留） */
    public record VerifyLogView(
            Long id,
            String result,
            String resultName,
            String note,
            Long operatorId,
            String operatorName,
            String pileRestored,
            String pileRestoredName,
            String restoredBikeCode,
            LocalDateTime createdAt) {
    }

    /** 巡检批次中的一个桩位（带检查/处置/复验全部信息与实时占车情况） */
    public record InspectionItemView(
            Long id,
            Long pileId,
            String pileNo,
            /** 建单时桩上车辆编号快照 */
            String snapshotBikeCode,
            String itemResult,
            String itemResultName,
            String lockResult,
            String lockResultName,
            String powerResult,
            String powerResultName,
            String comResult,
            String comResultName,
            String faultLevel,
            String faultLevelName,
            String faultDesc,
            String disposeStatus,
            String disposeStatusName,
            Long inspectorId,
            String inspectorName,
            LocalDateTime inspectedAt,
            /** 检查时登记占用与最新事实不一致的说明 */
            String inspectOccupyNote,
            Long disposerId,
            String disposerName,
            LocalDateTime disposeStartedAt,
            String disposeNote,
            LocalDateTime disposedAt,
            Long recheckerId,
            String recheckerName,
            LocalDateTime recheckedAt,
            String recheckResult,
            String recheckResultName,
            String recheckNote,
            int recheckPassCount,
            int recheckFailCount,
            /** 桩位实时状态（IDLE/OCCUPIED/PENDING_QUARANTINE/QUARANTINED/DISABLED） */
            String pileStatus,
            String pileStatusName,
            /** 桩位实时占用车辆（复验按真实占车恢复，页面不能手工指定） */
            Long currentBikeId,
            String currentBikeCode,
            /** 历次复验记录（复验不通过原因逐条保留） */
            List<VerifyLogView> verifyLogs) {
    }

    /** 巡检批次列表项 */
    public record InspectionBatchListItem(
            Long id,
            String batchNo,
            Long stationId,
            String stationCode,
            String stationName,
            String status,
            String statusName,
            Long creatorId,
            String creatorName,
            String remark,
            int pileCount,
            int checkedCount,
            int normalCount,
            int faultCount,
            int pendingQuarantineCount,
            int quarantinedCount,
            int pendingRecheckCount,
            int snapshotOccupiedCount,
            int snapshotIdleCount,
            int snapshotDisabledCount,
            LocalDateTime finishedAt,
            LocalDateTime createdAt) {
    }

    /** 巡检批次详情（逐桩明细 + 复验记录） */
    public record InspectionBatchView(
            Long id,
            String batchNo,
            Long stationId,
            String stationCode,
            String stationName,
            String stationStatus,
            String status,
            String statusName,
            Long creatorId,
            String creatorName,
            String remark,
            int pileCount,
            int checkedCount,
            int normalCount,
            int faultCount,
            int pendingQuarantineCount,
            int quarantinedCount,
            int pendingRecheckCount,
            int snapshotOccupiedCount,
            int snapshotIdleCount,
            int snapshotDisabledCount,
            LocalDateTime finishedAt,
            LocalDateTime createdAt,
            List<InspectionItemView> items) {
    }

    /** 复验动作结果：带明确结论、恢复后的桩位状态与真实占用车辆 */
    public record RecheckOutcome(
            boolean passed,
            String message,
            String pileStatus,
            String pileStatusName,
            String currentBikeCode,
            InspectionBatchView batch) {
    }
}
