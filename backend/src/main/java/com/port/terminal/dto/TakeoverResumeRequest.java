package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 原机械恢复后的恢复确认：只允许作用于暂停中的未完成指令，已转交指令不得抢回。 */
public record TakeoverResumeRequest(
        @NotNull(message = "方案版本不能为空") Long expectedVersion,
        @NotBlank(message = "调度员不能为空") String dispatcher,
        @NotEmpty(message = "恢复清单不能为空") List<Long> itemIds) {
}
