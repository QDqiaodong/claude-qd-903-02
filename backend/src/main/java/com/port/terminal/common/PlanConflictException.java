package com.port.terminal.common;

/**
 * 接管方案并发冲突：另一名调度员已先提交，方案版本发生变化。
 * 异常携带最新方案快照，统一异常处理返回 409 + 最新方案，后提交的人据此看到方案变化与剩余指令。
 */
public class PlanConflictException extends RuntimeException {

    private final transient Object detail;

    public PlanConflictException(String message, Object detail) {
        super(message);
        this.detail = detail;
    }

    public Object getDetail() {
        return detail;
    }
}
