package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.DriverShift;
import com.port.terminal.dto.CraneEfficiencyDTO;
import com.port.terminal.dto.DriverShiftRequest;
import com.port.terminal.service.CraneService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cranes")
public class CraneController {
    private final CraneService craneService;

    public CraneController(CraneService craneService) { this.craneService = craneService; }

    @GetMapping("/efficiency")
    public ApiResponse<List<CraneEfficiencyDTO>> efficiency() {
        return ApiResponse.success(craneService.efficiencyRanking());
    }

    @GetMapping("/{craneId}/shifts")
    public ApiResponse<List<DriverShift>> shifts(@PathVariable Long craneId) {
        return ApiResponse.success(craneService.shifts(craneId));
    }

    @PostMapping("/{craneId}/shifts")
    public ApiResponse<DriverShift> register(@PathVariable Long craneId,
                                             @Valid @RequestBody DriverShiftRequest request) {
        return ApiResponse.success("司机班次与作业量登记成功", craneService.registerShift(craneId, request));
    }

    @PutMapping("/{craneId}/status")
    public ApiResponse<Crane> status(@PathVariable Long craneId, @RequestParam String status,
                                     @RequestParam(required = false) String operator) {
        return ApiResponse.success("机械状态已更新", craneService.changeStatus(craneId, status, operator));
    }
}
