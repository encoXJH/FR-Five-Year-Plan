package edu.fzrj.metrics.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 统一响应结构（API 约定 6.1）
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {

    /** 业务码，0 表示成功 */
    private Integer code;

    /** 中文提示 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 请求追踪 ID */
    private String requestId;

    /** 只在出错时出现 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<FieldError> errors;

    public static <T> ApiResponse<T> success(T data, String requestId) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        r.requestId = requestId;
        return r;
    }

    public static <T> ApiResponse<T> error(Integer code, String message, String requestId) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        r.data = null;
        r.requestId = requestId;
        return r;
    }

    public static <T> ApiResponse<T> error(Integer code, String message, String requestId, List<FieldError> errors) {
        ApiResponse<T> r = error(code, message, requestId);
        r.errors = errors;
        return r;
    }
}
