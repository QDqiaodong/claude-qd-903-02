package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.domain.Berth;
import com.port.terminal.domain.VesselSchedule;
import com.port.terminal.dto.AssignCranesRequest;
import com.port.terminal.service.BerthService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/berths")
public class BerthController {
    private final BerthService berthService;

    public BerthController(BerthService berthService) { this.berthService = berthService; }

    @GetMapping("/shoreline")
    public ApiResponse<List<Map<String, Object>>> shoreline() { return ApiResponse.success(berthService.shoreline()); }

    @GetMapping("/{berthId}/schedules")
    public ApiResponse<List<VesselSchedule>> schedules(@PathVariable Long berthId) {
        return ApiResponse.success(berthService.schedules(berthId));
    }

    @PostMapping("/schedules/{scheduleId}/assign-cranes")
    public ApiResponse<VesselSchedule> assign(@PathVariable Long scheduleId,
                                               @Valid @RequestBody AssignCranesRequest request) {
        return ApiResponse.success("岸桥分配成功", berthService.assignCranes(scheduleId, request));
    }

    @PutMapping("/{berthId}/status")
    public ApiResponse<Berth> status(@PathVariable Long berthId, @RequestParam String status) {
        return ApiResponse.success("泊位状态已更新", berthService.changeStatus(berthId, status));
    }

    @PostMapping("/schedules/{scheduleId}/depart")
    public ApiResponse<VesselSchedule> depart(@PathVariable Long scheduleId) {
        return ApiResponse.success("船舶已离港", berthService.depart(scheduleId));
    }
}
