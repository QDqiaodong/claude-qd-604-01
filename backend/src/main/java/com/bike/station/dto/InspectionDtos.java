package com.bike.station.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 桩位巡检模块入参。
 */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    /**
     * 创建巡检批次：选一个站点（可带备注与建单人）。
     * 桩位清单与占车情况由后端在【建单那一刻】快照，不接受前端传桩位，
     * 避免之后新增的桩位悄悄混进旧批次。
     */
    public record BatchCreate(
            @NotNull(message = "巡检站点不能为空")
            Long stationId,

            String remark,

            @NotBlank(message = "建单人（运营人员）不能为空")
            String createdBy) {
    }

    /**
     * 逐桩登记检查结果。
     *  - 三项检查 lock/power/comm 取值 NORMAL 正常 / FAULT 故障；
     *  - 任一项 FAULT 即为该桩故障，必须给故障等级；三项全 NORMAL 为正常。
     *  - rev 是打开页面时该明细的版本号：提交时必须与库内一致，否则 409 冲突。
     */
    public record CheckRequest(
            @NotBlank(message = "检查人不能为空")
            String checkerName,

            @NotBlank(message = "桩锁检查结果不能为空")
            String lockStatus,

            @NotBlank(message = "供电检查结果不能为空")
            String powerStatus,

            @NotBlank(message = "通信检查结果不能为空")
            String commStatus,

            /** 故障等级 LOW / MID / HIGH：任一检查项为故障时必填 */
            String faultLevel,

            String faultDesc,

            @NotNull(message = "检查记录版本号不能为空（请刷新页面获取最新版本）")
            Integer rev) {
    }

    /** 故障处置：记录处置人与处置说明，提交后进入「待复验」 */
    public record HandleRequest(
            @NotBlank(message = "处置人不能为空")
            String handlerName,

            String handleNote,

            @NotNull(message = "处置单版本号不能为空（请刷新页面获取最新版本）")
            Integer rev) {
    }

    /**
     * 复验：
     *  - pass=false 必须填 recheckNote（复验不通过要保留原因，继续隔离）；
     *  - pass=true 时不接受页面指定恢复成空闲还是占用 —— 后端按真实占车恢复。
     */
    public record RecheckRequest(
            @NotBlank(message = "复验人不能为空")
            String recheckerName,

            @NotNull(message = "复验结论不能为空")
            Boolean pass,

            String recheckNote,

            @NotNull(message = "处置单版本号不能为空（请刷新页面获取最新版本）")
            Integer rev) {
    }
}
