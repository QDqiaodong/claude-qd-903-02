package com.port.terminal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 批量处置提交：expectedVersion 是调度员看到的方案版本，
 * 与库中版本不一致即说明他人已先提交，返回 409 与最新方案。
 */
public record TakeoverDecisionRequest(
        @NotNull(message = "方案版本不能为空") Long expectedVersion,
        @NotBlank(message = "调度员不能为空") String dispatcher,
        @NotEmpty(message = "处置清单不能为空") List<@Valid Decision> decisions) {

    public record Decision(
            @NotNull(message = "处置项不能为空") Long itemId,
            @NotBlank(message = "处置动作不能为空") String action,
            String takeoverCraneCode) {
    }
}
