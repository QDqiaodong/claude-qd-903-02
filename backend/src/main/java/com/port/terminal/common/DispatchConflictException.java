package com.port.terminal.common;

/**
 * 实际派发瞬间的并发冲突：两名调度员同时派发同一指令，或候选机械在确认时已转故障/满载。
 * 统一返回 409 并携带失败原因与最新队列版本——失败方不会产生重复指派，
 * 前端刷新后看到的是指令与机械的最新去向。
 */
public class DispatchConflictException extends RuntimeException {

    private final transient Object detail;

    public DispatchConflictException(String message, Object detail) {
        super(message);
        this.detail = detail;
    }

    public Object getDetail() {
        return detail;
    }
}
