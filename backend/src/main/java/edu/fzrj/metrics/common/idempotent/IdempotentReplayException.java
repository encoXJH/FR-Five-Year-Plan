package edu.fzrj.metrics.common.idempotent;

import edu.fzrj.metrics.module.system.entity.IdempotencyRecord;
import lombok.Getter;

/**
 * 幂等重放：直接返回首次响应
 */
@Getter
public class IdempotentReplayException extends RuntimeException {

    private final IdempotencyRecord record;

    public IdempotentReplayException(IdempotencyRecord record) {
        super("幂等重放");
        this.record = record;
    }
}
