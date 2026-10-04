package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.dto.PauseAllRequest;
import com.port.terminal.dto.TakeoverDecisionRequest;
import com.port.terminal.dto.TakeoverResumeRequest;
import com.port.terminal.service.TakeoverService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/takeover")
public class TakeoverController {
    private final TakeoverService takeoverService;

    public TakeoverController(TakeoverService takeoverService) { this.takeoverService = takeoverService; }

    @GetMapping("/overview")
    public ApiResponse<List<Map<String, Object>>> overview() {
        return ApiResponse.success(takeoverService.overview());
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<Map<String, Object>> plan(@PathVariable Long planId) {
        return ApiResponse.success(takeoverService.planDetail(planId));
    }

    @GetMapping("/items/{itemId}/candidates")
    public ApiResponse<List<Map<String, Object>>> candidates(@PathVariable Long itemId) {
        return ApiResponse.success(takeoverService.candidates(itemId));
    }

    @PostMapping("/plans/{planId}/decisions")
    public ApiResponse<Map<String, Object>> decide(@PathVariable Long planId,
                                                   @Valid @RequestBody TakeoverDecisionRequest request) {
        return ApiResponse.success("处置已提交", takeoverService.submitDecisions(planId, request));
    }

    @PostMapping("/plans/{planId}/pause-all")
    public ApiResponse<Map<String, Object>> pauseAll(@PathVariable Long planId,
                                                     @Valid @RequestBody PauseAllRequest request) {
        return ApiResponse.success("已整批暂停", takeoverService.pauseAll(planId, request));
    }

    @PostMapping("/plans/{planId}/resume")
    public ApiResponse<Map<String, Object>> resume(@PathVariable Long planId,
                                                   @Valid @RequestBody TakeoverResumeRequest request) {
        return ApiResponse.success("恢复确认已提交", takeoverService.resume(planId, request));
    }
}
