package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PauseAllRequest(
        @NotNull(message = "方案版本不能为空") Long expectedVersion,
        @NotBlank(message = "调度员不能为空") String dispatcher) {
}
