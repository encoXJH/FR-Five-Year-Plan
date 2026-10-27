package edu.fzrj.metrics.common.api;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 引用对象（API 约定 3.4）：{ "id": "...", "name": "..." }
 */
public record UserRef(@JsonSerialize(using = ToStringSerializer.class) Long id, String name) {
}
