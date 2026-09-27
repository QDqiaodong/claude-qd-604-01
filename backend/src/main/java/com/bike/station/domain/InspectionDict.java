package com.bike.station.domain;

import java.util.List;
import java.util.Map;

/**
 * 桩位巡检模块（模块六）的状态词典。
 *
 * 只放常量、状态名与状态机判定，不放任何实体默认值 ——
 * 默认值一律在 InspectionService 里显式赋值（与全仓口径一致）。
 *
 * 核心取舍：在「尽快释放可用桩位」和「故障桩绝不接收新车」之间，永远以安全隔离优先。
 */
public final class InspectionDict {

    private InspectionDict() {
    }

    // ------------------------------------------------------------------
    // 巡检批次状态
    // ------------------------------------------------------------------
    public static final String BATCH_OPEN = "OPEN";
    public static final String BATCH_COMPLETED = "COMPLETED";

    private static final Map<String, String> BATCH_STATUS_NAME = Map.of(
            BATCH_OPEN, "巡检中",
            BATCH_COMPLETED, "已完成");

    // ------------------------------------------------------------------
    // 桩位检查结论
    // ------------------------------------------------------------------
    public static final String RESULT_WAITING = "WAITING";
    public static final String RESULT_NORMAL = "NORMAL";
    public static final String RESULT_FAULT = "FAULT";

    private static final Map<String, String> RESULT_NAME = Map.of(
            RESULT_WAITING, "待检查",
            RESULT_NORMAL, "正常",
            RESULT_FAULT, "故障");

    // ------------------------------------------------------------------
    // 三项检查（桩锁 / 供电 / 通信）的逐桩结果
    // ------------------------------------------------------------------
    public static final String CHECK_OK = "NORMAL_OK";
    public static final String CHECK_FAIL = "FAULT_FAIL";

    private static final Map<String, String> CHECK_NAME = Map.of(
            CHECK_OK, "正常",
            CHECK_FAIL, "故障");

    // ------------------------------------------------------------------
    // 故障处置状态机
    //   NONE          无故障，无需处置
    //   PROCESSING    处置中（检查发现故障进入；复验不通过也回到这里）
    //   FIXED         处置完成，待复验（桩位仍处于隔离，绝不提前释放）
    //   RECHECK_PASS  复验通过（终态：按真实占车恢复为空闲/占用）
    //   RECHECK_FAIL  复验不通过（保留原因、继续隔离；记录此值后随即回到 PROCESSING，
    //                 这里仅作为历史动作名，明细的当前状态不会停在 RECHECK_FAIL）
    // ------------------------------------------------------------------
    public static final String DISPOSE_NONE = "NONE";
    public static final String DISPOSE_PROCESSING = "PROCESSING";
    public static final String DISPOSE_FIXED = "FIXED";
    public static final String DISPOSE_RECHECK_PASS = "RECHECK_PASS";
    public static final String DISPOSE_RECHECK_FAIL = "RECHECK_FAIL";

    private static final Map<String, String> DISPOSE_NAME = Map.of(
            DISPOSE_NONE, "无需处置",
            DISPOSE_PROCESSING, "处置中",
            DISPOSE_FIXED, "待复验",
            DISPOSE_RECHECK_PASS, "复验通过",
            DISPOSE_RECHECK_FAIL, "复验不通过");

    /** 故障处置「尚未完成」（还没复验通过）的状态 —— 同一桩位同时只允许一条 */
    public static final List<String> DISPOSE_OPEN = List.of(
            DISPOSE_PROCESSING, DISPOSE_FIXED, DISPOSE_RECHECK_FAIL);

    // ------------------------------------------------------------------
    // 复验结论
    // ------------------------------------------------------------------
    public static final String RECHECK_PASS = "PASS";
    public static final String RECHECK_FAIL = "FAIL";

    private static final Map<String, String> RECHECK_NAME = Map.of(
            RECHECK_PASS, "复验通过",
            RECHECK_FAIL, "复验不通过");

    // ------------------------------------------------------------------
    // 运营人员状态
    // ------------------------------------------------------------------
    public static final String OPERATOR_ON_DUTY = "ON_DUTY";
    public static final String OPERATOR_OFF_DUTY = "OFF_DUTY";

    private static final Map<String, String> OPERATOR_STATUS_NAME = Map.of(
            OPERATOR_ON_DUTY, "在岗",
            OPERATOR_OFF_DUTY, "休息");

    // ------------------------------------------------------------------
    // 取值方法
    // ------------------------------------------------------------------

    public static boolean isValidBatchStatus(String status) {
        return status != null && BATCH_STATUS_NAME.containsKey(status);
    }

    public static String batchStatusName(String status) {
        return status == null ? "" : BATCH_STATUS_NAME.getOrDefault(status, status);
    }

    public static String resultName(String status) {
        return status == null ? "" : RESULT_NAME.getOrDefault(status, status);
    }

    public static boolean isValidCheckValue(String value) {
        return value != null && CHECK_NAME.containsKey(value);
    }

    public static String checkName(String value) {
        return value == null ? "" : CHECK_NAME.getOrDefault(value, value);
    }

    public static String disposeName(String status) {
        return status == null ? "" : DISPOSE_NAME.getOrDefault(status, status);
    }

    /** 该处置状态是否仍占着「同桩唯一未完成处置」的名额 */
    public static boolean isDisposeOpen(String status) {
        return status != null && DISPOSE_OPEN.contains(status);
    }

    public static boolean isValidRecheckResult(String result) {
        return result != null && RECHECK_NAME.containsKey(result);
    }

    public static String recheckName(String result) {
        return result == null ? "" : RECHECK_NAME.getOrDefault(result, result);
    }

    public static boolean isValidOperatorStatus(String status) {
        return status != null && OPERATOR_STATUS_NAME.containsKey(status);
    }

    public static String operatorStatusName(String status) {
        return status == null ? "" : OPERATOR_STATUS_NAME.getOrDefault(status, status);
    }
}
