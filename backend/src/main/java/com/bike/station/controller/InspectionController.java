package com.bike.station.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bike.station.common.ApiResponse;
import com.bike.station.dto.InspectionDtos;
import com.bike.station.dto.Views;
import com.bike.station.service.InspectionService;

import jakarta.validation.Valid;

/**
 * 模块六：桩位巡检。
 *
 *  - GET  /operators                          在岗/休息运营人员（检查人/处置人/复验人）
 *  - GET  /                                   巡检批次列表（可按站点、状态过滤）
 *  - POST /                                   创建批次（桩位清单与占车情况锁内固化）
 *  - GET  /{id}                               批次详情（逐桩检查记录、处置状态、复验结论）
 *  - POST /{id}/items/{itemId}/check          逐桩登记检查结果（按最新事实决定待隔离/已隔离）
 *  - POST /{id}/items/{itemId}/dispose/start  开始故障处置
 *  - POST /{id}/items/{itemId}/dispose/finish 完成处置（进入待复验，隔离不解除）
 *  - POST /{id}/items/{itemId}/recheck        复验（通过按真实占车恢复，不通过保留原因继续隔离）
 */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping("/operators")
    public ApiResponse<List<Views.OperatorView>> operators() {
        return ApiResponse.success(inspectionService.operators());
    }

    @GetMapping
    public ApiResponse<List<Views.InspectionBatchListItem>> list(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(inspectionService.list(stationId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.InspectionBatchView> detail(@PathVariable Long id) {
        return ApiResponse.success(inspectionService.detail(id));
    }

    @PostMapping
    public ApiResponse<Views.InspectionBatchView> create(@Valid @RequestBody InspectionDtos.BatchCreate req) {
        return ApiResponse.success("巡检批次已创建（桩位清单与占车情况已固定）", inspectionService.create(req));
    }

    @PostMapping("/{id}/items/{itemId}/check")
    public ApiResponse<Views.InspectionBatchView> check(@PathVariable Long id,
                                                        @PathVariable Long itemId,
                                                        @Valid @RequestBody InspectionDtos.CheckRequest req) {
        return ApiResponse.success("检查结果已登记", inspectionService.check(id, itemId, req));
    }

    @PostMapping("/{id}/items/{itemId}/dispose/start")
    public ApiResponse<Views.InspectionBatchView> disposeStart(@PathVariable Long id,
                                                               @PathVariable Long itemId,
                                                               @Valid @RequestBody InspectionDtos.DisposeStartRequest req) {
        return ApiResponse.success("故障处置已开始", inspectionService.startDispose(id, itemId, req));
    }

    @PostMapping("/{id}/items/{itemId}/dispose/finish")
    public ApiResponse<Views.InspectionBatchView> disposeFinish(@PathVariable Long id,
                                                                @PathVariable Long itemId,
                                                                @Valid @RequestBody InspectionDtos.DisposeFinishRequest req) {
        return ApiResponse.success("故障处置已完成，桩位保持隔离，等待复验",
                inspectionService.finishDispose(id, itemId, req));
    }

    @PostMapping("/{id}/items/{itemId}/recheck")
    public ApiResponse<Views.RecheckOutcome> recheck(@PathVariable Long id,
                                                     @PathVariable Long itemId,
                                                     @Valid @RequestBody InspectionDtos.RecheckRequest req) {
        Views.RecheckOutcome outcome = inspectionService.recheck(id, itemId, req);
        return ApiResponse.success(outcome.message(), outcome);
    }
}
