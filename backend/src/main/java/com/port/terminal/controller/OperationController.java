package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.domain.GateTransaction;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.InstructionRequest;
import com.port.terminal.dto.StageRequest;
import com.port.terminal.service.OperationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationController {
    private final OperationService operationService;

    public OperationController(OperationService operationService) { this.operationService = operationService; }

    @GetMapping("/gates")
    public ApiResponse<List<GateTransaction>> gates() { return ApiResponse.success(operationService.gates()); }

    @GetMapping("/instructions")
    public ApiResponse<List<WorkInstruction>> instructions() { return ApiResponse.success(operationService.instructions()); }

    @PostMapping("/instructions")
    public ApiResponse<WorkInstruction> create(@Valid @RequestBody InstructionRequest request) {
        return ApiResponse.success("作业指令已创建", operationService.create(request));
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
