package com.bike.station.domain;

import java.util.List;
import java.util.Map;

/**
 * 桩位巡检（模块六）业务词典。
 *
 * 隔离取舍（不可动摇）：在「尽快释放可用桩位」和「故障桩绝不接收新车」之间，
 * 永远以安全隔离优先 —— 空桩故障立即 ISOLATED；有车故障 PENDING_ISOLATED，
 * 只等车离开自动转 ISOLATED，期间任何业务都不能把新车放进去；
 * 桩位只能由复验动作恢复，且恢复成空闲还是占用由【真实占车】决定。
 */
public final class InspectionDict {

    private InspectionDict() {
    }

    // ------------------------------------------------------------------
    // 巡检批次状态
    // ------------------------------------------------------------------
    public static final String BATCH_IN_PROGRESS = "IN_PROGRESS";
    public static final String BATCH_COMPLETED = "COMPLETED";

    private static final Map<String, String> BATCH_STATUS_NAME = Map.of(
            "IN_PROGRESS", "巡检中",
            "COMPLETED", "已完成");

    // ------------------------------------------------------------------
    // 逐桩检查结果
    // ------------------------------------------------------------------
    public static final String RESULT_PENDING = "PENDING";
    public static final String RESULT_OK = "OK";
    public static final String RESULT_FAULT = "FAULT";

    private static final Map<String, String> RESULT_NAME = Map.of(
            "PENDING", "待检查",
            "OK", "正常",
            "FAULT", "故障");

    // ------------------------------------------------------------------
    // 桩锁 / 供电 / 通信 三项检查的取值
    // ------------------------------------------------------------------
    public static final String CHECK_UNKNOWN = "UNKNOWN";
    public static final String CHECK_NORMAL = "NORMAL";
    public static final String CHECK_FAULT = "FAULT";

    private static final Map<String, String> CHECK_NAME = Map.of(
            "UNKNOWN", "未查",
            "NORMAL", "正常",
            "FAULT", "故障");

    public static final List<String> CHECK_VALUES = List.of(CHECK_NORMAL, CHECK_FAULT);

    // ------------------------------------------------------------------
    // 故障桩的隔离形态（inspection_item.isolated_mode）
    // ------------------------------------------------------------------
    public static final String MODE_ISOLATED = "ISOLATED";
    public static final String MODE_PENDING_ISOLATED = "PENDING_ISOLATED";

    private static final Map<String, String> MODE_NAME = Map.of(
            "ISOLATED", "已隔离",
            "PENDING_ISOLATED", "待隔离");

    // ------------------------------------------------------------------
    // 故障处置单状态机
    //   FH_PENDING 待处置 → FH_HANDLED 已处置待复验
    //     → FH_RECHECK_PASS 复验通过（终态，桩位恢复）
    //     → FH_RECHECK_REJECTED 复验不通过（保留原因、继续隔离，可再处置/复验）
    // ------------------------------------------------------------------
    public static final String FH_PENDING = "FH_PENDING";
    public static final String FH_HANDLED = "FH_HANDLED";
    public static final String FH_RECHECK_PASS = "FH_RECHECK_PASS";
    public static final String FH_RECHECK_REJECTED = "FH_RECHECK_REJECTED";

    private static final Map<String, String> FH_STATUS_NAME = Map.of(
            "FH_PENDING", "待处置",
            "FH_HANDLED", "待复验",
            "FH_RECHECK_PASS", "复验通过",
            "FH_RECHECK_REJECTED", "复验不通过");

    /** 仍未完成的处置状态：用于「同一桩位不能同时有两条未完成处置」的判定 */
    public static final List<String> FH_OPEN = List.of(
            FH_PENDING, FH_HANDLED, FH_RECHECK_REJECTED);

    /** 站点看板「待复验」计数口径 */
    public static final List<String> FH_PENDING_RECHECK = List.of(FH_HANDLED, FH_RECHECK_REJECTED);

    // ------------------------------------------------------------------
    // 取值方法
    // ------------------------------------------------------------------

    public static boolean isValidBatchStatus(String status) {
        return status != null && BATCH_STATUS_NAME.containsKey(status);
    }

    public static String batchStatusName(String status) {
        return status == null ? "" : BATCH_STATUS_NAME.getOrDefault(status, status);
    }

    public static String resultName(String result) {
        return result == null ? "" : RESULT_NAME.getOrDefault(result, result);
    }

    public static boolean isValidCheckValue(String value) {
        return value != null && CHECK_NAME.containsKey(value) && !CHECK_UNKNOWN.equals(value);
    }

    public static String checkName(String value) {
        return value == null ? "" : CHECK_NAME.getOrDefault(value, value);
    }

    public static boolean isValidFaultLevel(String level) {
        return BikeDict.isValidFaultLevel(level);
    }

    public static String faultLevelName(String level) {
        return BikeDict.faultLevelName(level);
    }

    public static String modeName(String mode) {
        return mode == null ? "" : MODE_NAME.getOrDefault(mode, mode);
    }

    public static boolean isValidHandlingStatus(String status) {
        return status != null && FH_STATUS_NAME.containsKey(status);
    }

    public static String handlingStatusName(String status) {
        return status == null ? "" : FH_STATUS_NAME.getOrDefault(status, status);
    }

    public static boolean isHandlingOpen(String status) {
        return FH_OPEN.contains(status);
    }
}
