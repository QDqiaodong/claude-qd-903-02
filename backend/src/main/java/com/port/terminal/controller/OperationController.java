package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.domain.GateTransaction;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.DispatchRequest;
import com.port.terminal.dto.InstructionRequest;
import com.port.terminal.dto.StageRequest;
import com.port.terminal.service.DispatchQueueService;
import com.port.terminal.service.OperationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationController {
    private final OperationService operationService;
    private final DispatchQueueService dispatchQueueService;

    public OperationController(OperationService operationService, DispatchQueueService dispatchQueueService) {
        this.operationService = operationService;
        this.dispatchQueueService = dispatchQueueService;
    }

    @GetMapping("/gates")
    public ApiResponse<List<GateTransaction>> gates() { return ApiResponse.success(operationService.gates()); }

    @GetMapping("/instructions")
    public ApiResponse<List<WorkInstruction>> instructions() { return ApiResponse.success(operationService.instructions()); }

    @PostMapping("/instructions")
    public ApiResponse<WorkInstruction> create(@Valid @RequestBody InstructionRequest request) {
        return ApiResponse.success("作业指令已创建", operationService.create(request));
    }

    /**
     * 动态优先级派发队列：服务端统一计算 + 服务端分页。
     * asOf 为前端翻页/切换策略时回传的计算时点，保证同一计算时点内顺序稳定可重现。
     */
    @GetMapping("/dispatch-queue")
    public ApiResponse<Map<String, Object>> dispatchQueue(@RequestParam(required = false) String strategy,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "8") int size,
                                                          @RequestParam(required = false) String asOf) {
        return ApiResponse.success(dispatchQueueService.queue(strategy, page, size, asOf));
    }

    /** 数据指纹探针：船期/机械/指令任一变化指纹即变，前端据此标识推荐已过期并重算 */
    @GetMapping("/dispatch-queue/fingerprint")
    public ApiResponse<Map<String, Object>> dispatchFingerprint() {
        return ApiResponse.success(dispatchQueueService.fingerprint());
    }

    /** 真正派发：携带队列里看到的指令版本，服务端重新核对阶段/机械状态/已接负荷 */
    @PostMapping("/instructions/{id}/dispatch")
    public ApiResponse<WorkInstruction> dispatch(@PathVariable Long id, @Valid @RequestBody DispatchRequest request) {
        return ApiResponse.success("指令已派发", operationService.dispatch(id, request));
    }

    @PostMapping("/instructions/{id}/start")
    public ApiResponse<WorkInstruction> start(@PathVariable Long id, @Valid @RequestBody StageRequest request) {
        return ApiResponse.success("指令已开工", operationService.start(id, request));
    }

    @PostMapping("/instructions/{id}/stage")
    public ApiResponse<WorkInstruction> stage(@PathVariable Long id, @Valid @RequestBody StageRequest request) {
        return ApiResponse.success("指令阶段已推进", operationService.advanceStage(id, request));
    }

    @PostMapping("/instructions/{id}/complete")
    public ApiResponse<WorkInstruction> complete(@PathVariable Long id) {
        return ApiResponse.success("作业指令已完成", operationService.complete(id));
    }
}
