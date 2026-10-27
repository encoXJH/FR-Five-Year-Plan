package edu.fzrj.metrics.common.exception;

import edu.fzrj.metrics.common.api.ApiResponse;
import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.web.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理：异常 → 统一响应（错误码见 API 约定 7.2）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message, List<FieldError> errors) {
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(code.getCode(), message, requestId(), errors));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> biz(BizException e) {
        return build(e.getErrorCode(), e.getMessage(), e.getErrors());
    }

    /** 幂等重放：返回首次响应 */
    @ExceptionHandler(edu.fzrj.metrics.common.idempotent.IdempotentReplayException.class)
    public ResponseEntity<ApiResponse<com.fasterxml.jackson.databind.JsonNode>> replay(
            edu.fzrj.metrics.common.idempotent.IdempotentReplayException e) throws Exception {
        com.fasterxml.jackson.databind.JsonNode data =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getRecord().getResponseBody());
        int status = e.getRecord().getResponseStatus() == null ? 200 : e.getRecord().getResponseStatus();
        return ResponseEntity.status(status)
                .body(ApiResponse.success(data, requestId()));
    }

    /** 参数校验失败（@Valid @RequestBody / @ModelAttribute） */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> bind(BindException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), errors);
    }

    /** 参数校验失败（@Validated 方法参数） */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> constraint(ConstraintViolationException e) {
        List<FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .collect(Collectors.toList());
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), errors);
    }

    /** 请求体 JSON 解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> notReadable(HttpMessageNotReadableException e) {
        return build(ErrorCode.BODY_INVALID, ErrorCode.BODY_INVALID.getMessage(), null);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> missingParam(Exception e) {
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), null);
    }

    /** 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException e) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage(), null);
    }

    /** Spring Security 权限不足 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> accessDenied(AccessDeniedException e) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage(), null);
    }

    /** 上传超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException e) {
        return build(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.getMessage(), null);
    }

    /** 兜底 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unknown(Exception e) {
        log.error("未处理异常, requestId={}", requestId(), e);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), null);
    }
}
