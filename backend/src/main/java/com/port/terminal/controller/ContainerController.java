package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.domain.Container;
import com.port.terminal.dto.CompanyContainerSummaryDTO;
import com.port.terminal.service.ContainerService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/containers")
public class ContainerController {
    private final ContainerService containerService;

    public ContainerController(ContainerService containerService) { this.containerService = containerService; }

    @GetMapping
    public ApiResponse<List<Container>> inPort() { return ApiResponse.success(containerService.inPort()); }

    @GetMapping("/company-summary")
    public ApiResponse<List<CompanyContainerSummaryDTO>> companySummary() {
        return ApiResponse.success(containerService.companySummary());
    }

    @GetMapping("/{containerNo}/lifecycle")
    public ApiResponse<Map<String, Object>> lifecycle(@PathVariable String containerNo) {
        return ApiResponse.success(containerService.lifecycle(containerNo));
    }
}
