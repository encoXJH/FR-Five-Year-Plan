package edu.fzrj.metrics.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 字段级错误（API 约定 6.1 errors 数组元素）
 */
@Data
@AllArgsConstructor
public class FieldError {

    /** 字段路径 */
    private String field;

    /** 说明 */
    private String message;
}
