package com.bike.station.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 桩位巡检模块（模块六）的入参。
 */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    /** 建单：选一个站点 + 建单运营人；桩位清单与占车情况由后端在锁内固化 */
    public record BatchCreate(
            @NotNull(message = "巡检站点不能为空")
            Long stationId,

            @NotNull(message = "建单人不能为空")
            Long creatorId,

            String remark) {
    }

    /**
     * 逐桩检查登记：
     *  - operatorId 检查人；
     *  - lockResult / powerResult / comResult 三项检查结果，取值 NORMAL_OK / FAULT_FAIL；
     *  - faultLevel / faultDesc：任一检查项为 FAULT_FAIL 时必填；
     *  - previousResult：页面打开时该桩的检查结论（WAITING/NORMAL/FAULT），是乐观并发锚点。
     *    提交时若锁内最新结论与此不一致（另一个人已经登记过），后端拒绝并返回冲突，
     *    要求先看最新状态再决定是否改判，旧页面不能覆盖已生效的检查记录与车辆落点；
     *  - expectedBikeCode：页面打开时该桩的真实车辆编号（快照或实时），
     *    与提交时的最新事实不一致时后端按最新事实决定待隔离/已隔离并在记录中留痕。
     */
    public record CheckRequest(
            @NotNull(message = "检查人不能为空")
            Long operatorId,

            @NotNull(message = "桩锁检查结果不能为空")
            String lockResult,

            @NotNull(message = "供电检查结果不能为空")
            String powerResult,

            @NotNull(message = "通信检查结果不能为空")
            String comResult,

            String faultLevel,

            String faultDesc,

            /** 页面登记时所依据的车辆编号（并发占用变化时用于明确冲突），可空 */
            String expectedBikeCode,

            /** 页面打开时该桩的检查结论（乐观锁）；为空时按 WAITING 处理（首次登记） */
            String previousResult) {
    }

    /** 开始处置：登记处置人与处置措施（措施可在完成时补充） */
    public record DisposeStartRequest(
            @NotNull(message = "处置人不能为空")
            Long operatorId,

            String disposeNote) {
    }

    /** 完成处置：桩位继续保持隔离，进入待复验 */
    public record DisposeFinishRequest(
            @NotNull(message = "处置人不能为空")
            Long operatorId,

            String disposeNote) {
    }

    /**
     * 复验：通过/不通过。
     *  - PASS：后端按桩位真实占车恢复为 IDLE/OCCUPIED，不接受页面指定恢复状态；
     *  - FAIL：必须填写不通过原因，桩位继续隔离，处置回到处置中。
     */
    public record RecheckRequest(
            @NotNull(message = "复验人不能为空")
            Long operatorId,

            @NotNull(message = "复验结论不能为空")
            String result,

            String note) {
    }
}
