package edu.fzrj.metrics.common.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一响应包装：业务 Controller 返回值自动包成 ApiResponse。
 * 跳过：swagger 文档、文件流（ResponseEntity / Resource / byte[]）、已是 ApiResponse 的返回值。
 */
@RestControllerAdvice(basePackages = "edu.fzrj.metrics")
public class ResponseAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    public ResponseAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> type = returnType.getParameterType();
        if (ApiResponse.class.isAssignableFrom(type)
                || ResponseEntity.class.isAssignableFrom(type)
                || Resource.class.isAssignableFrom(type)
                || byte[].class.isAssignableFrom(type)) {
            return false;
        }
        String path = returnType.getContainingClass().getPackageName();
        return path.startsWith("edu.fzrj.metrics");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        ApiResponse<Object> wrapped = ApiResponse.success(body, requestId);
        // String 返回值走 StringHttpMessageConverter，需要手动序列化
        if (body instanceof String) {
            try {
                return objectMapper.writeValueAsString(wrapped);
            } catch (JsonProcessingException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR);
            }
        }
        return wrapped;
    }
}
