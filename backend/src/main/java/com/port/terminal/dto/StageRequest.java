package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;

/** 指令开工/阶段推进必须携带操作机械编号，用于归属校验，防止两台机械同时继续同一指令。 */
public record StageRequest(
        @NotBlank(message = "操作机械编号不能为空") String craneCode,
        String stage) {
}
