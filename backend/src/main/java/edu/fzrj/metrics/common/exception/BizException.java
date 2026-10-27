package edu.fzrj.metrics.common.exception;

import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.error.ErrorCode;
import lombok.Getter;

import java.util.List;

/**
 * 业务异常：携带业务错误码，由 GlobalExceptionHandler 统一翻译为响应
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldError> errors;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.errors = null;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errors = null;
    }

    public BizException(ErrorCode errorCode, String message, List<FieldError> errors) {
        super(message);
        this.errorCode = errorCode;
        this.errors = errors;
    }
}
