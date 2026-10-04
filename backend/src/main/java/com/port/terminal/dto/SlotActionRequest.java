package com.port.terminal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SlotActionRequest(
        @NotBlank(message = "箱号不能为空") String containerNo,
        @NotBlank(message = "目标箱区不能为空") String yardZone,
        @NotNull(message = "目标贝位不能为空") @Min(value = 1, message = "贝位必须大于 0") Integer bay,
        @NotNull(message = "目标列不能为空") @Min(value = 1, message = "列必须大于 0") Integer row,
        @NotNull(message = "目标层不能为空") @Min(value = 1, message = "层必须大于 0") Integer tier) {
}
