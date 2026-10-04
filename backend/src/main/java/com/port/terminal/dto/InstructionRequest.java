package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;

public record InstructionRequest(
        @NotBlank(message = "箱号不能为空") String containerNo,
        String toYardZone,
        Integer toBay,
        Integer toRow,
        Integer toTier,
        String craneCode,
        @NotBlank(message = "指令类型不能为空") String instructionType,
        String priority,
        Long vesselScheduleId) {
}
