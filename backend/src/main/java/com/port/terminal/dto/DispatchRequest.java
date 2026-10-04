package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 优先级队列“真正派发”请求：推荐只排序、不自动派发。
 * 调度员点击派发时必须再次确认指令与机械，后端在锁内复核指令阶段、机械状态与已接负荷。
 */
public record DispatchRequest(
        @NotNull(message = "指令ID不能为空") Long instructionId,
        @NotBlank(message = "必须选择派发机械") String craneCode,
        String dispatcher,
        // 所见推荐的计算时点与数据版本：变化后前端会先标过期并要求重算，这里仅作提示性校验
        String anchorAt,
        String expectedVersion) {
}
