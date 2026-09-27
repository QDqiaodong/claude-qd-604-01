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
 *  - POST /                          创建巡检批次（后端固定桩位清单与当时占车快照）
 *  - GET  /                          巡检批次列表（可按站点 / 状态过滤）
 *  - GET  /{id}                      批次详情（逐桩检查记录、故障处置与复验结论）
 *  - POST /{id}/items/{itemId}/check 逐桩登记桩锁/供电/通信检查结果（故障自动隔离+建处置单）
 *  - POST /handlings/{id}/handle     故障处置（进入待复验，桩位继续隔离）
 *  - POST /handlings/{id}/recheck    复验（不通过保留原因继续隔离；通过按真实占车恢复）
 *
 * 并发冲突统一返回 409，data 里带最新批次视图。
 */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping
    public ApiResponse<List<Views.InspectionListItem>> list(
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
        Views.InspectionBatchView view = inspectionService.check(id, itemId, req);
        Views.InspectionItemView item = view.items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElse(null);
        boolean fault = item != null && "FAULT".equals(item.result());
        String message = fault
                ? ("检查结果已登记：桩位「" + item.pileNo() + "」故障，已按最新占车情况进入「"
                        + item.isolatedModeName() + "」并开立故障处置单")
                : "检查结果已登记：该桩位正常";
        return ApiResponse.success(message, view);
    }

    @PostMapping("/handlings/{id}/handle")
    public ApiResponse<Views.InspectionBatchView> handle(@PathVariable Long id,
                                                         @Valid @RequestBody InspectionDtos.HandleRequest req) {
        return ApiResponse.success("处置已完成，桩位继续隔离，等待复验", inspectionService.handle(id, req));
    }

    @PostMapping("/handlings/{id}/recheck")
    public ApiResponse<Views.InspectionBatchView> recheck(@PathVariable Long id,
                                                          @Valid @RequestBody InspectionDtos.RecheckRequest req) {
        Views.InspectionBatchView view = inspectionService.recheck(id, req);
        String message = Boolean.TRUE.equals(req.pass())
                ? "复验通过：桩位已按真实占车情况恢复"
                : "复验不通过：原因已记录，桩位继续隔离";
        return ApiResponse.success(message, view);
    }
}
