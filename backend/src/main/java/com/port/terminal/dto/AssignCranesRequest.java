package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignCranesRequest(@NotBlank(message = "至少分配一台岸桥") String craneCodes) {
}
