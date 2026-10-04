package com.port.terminal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DriverShiftRequest(
        @NotBlank(message = "司机姓名不能为空") String driverName,
        @NotNull(message = "班次开始时间不能为空") LocalDateTime shiftStart,
        @NotNull(message = "班次结束时间不能为空") LocalDateTime shiftEnd,
        @NotNull(message = "作业量不能为空") @Min(value = 0, message = "作业量不能为负数") Integer handledBoxes,
        @NotNull(message = "工时不能为空") @DecimalMin(value = "0.1", message = "工时必须大于 0") BigDecimal workHours) {
}
