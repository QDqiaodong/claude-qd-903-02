package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 队列派发请求：调度员在队列里看到的指令版本必须一并带回，
 * 服务端据此识别「他人已先派发/指令已被改动」，失败方不留重复指派。
 */
public record DispatchRequest(
        @NotBlank(message = "派发机械编号不能为空") String craneCode,
        @NotNull(message = "指令版本号不能为空") Integer expectedVersion) {
}
