package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.dto.DispatchRequest;
import com.port.terminal.service.DispatchService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 岸桥作业·动态优先级队列。
 * 队列接口在服务端对全部指令排序后分页（跨页指令/故障机械不遗漏）；
 * 派发接口在锁内复核指令阶段、机械状态与已接负荷，并发冲突返回 409 + 最新版本。
 */
@RestController
@RequestMapping("/api/operations/dispatch")
public class DispatchController {
    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @GetMapping("/queue")
    public ApiResponse<Map<String, Object>> queue(
            @RequestParam(defaultValue = "DEPARTURE_FIRST") String strategy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String anchorAt,
            @RequestParam(required = false) String expectedVersion) {
        return ApiResponse.success(dispatchService.queue(strategy, page, size, anchorAt, expectedVersion));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> dispatch(@Valid @RequestBody DispatchRequest request) {
        return ApiResponse.success("派发成功，指令已进入对位阶段", dispatchService.dispatch(request));
    }
}
