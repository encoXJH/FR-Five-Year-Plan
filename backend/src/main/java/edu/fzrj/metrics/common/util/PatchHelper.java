package edu.fzrj.metrics.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;

import java.math.BigDecimal;

/**
 * PATCH 请求体辅助（API 约定 3.2）：不传 = 不修改；传 null = 清空（仅限可空字段）。
 * 基于 JsonNode 区分字段缺席与显式 null。
 */
public final class PatchHelper {

    private final JsonNode node;

    public PatchHelper(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new BizException(ErrorCode.BODY_INVALID);
        }
        this.node = node;
    }

    /** 字段是否出现（包括显式 null） */
    public boolean has(String field) {
        return node.has(field);
    }

    /** 原始 JsonNode（嵌套对象 / 数组字段用），未出现返回 null */
    public JsonNode raw(String field) {
        return node.get(field);
    }

    /** 字段出现且为 null */
    public boolean isNull(String field) {
        return node.has(field) && node.get(field).isNull();
    }

    public String text(String field) {
        JsonNode v = requireNonNullNode(field);
        if (!v.isTextual()) {
            throw invalid(field, "应为字符串");
        }
        return v.asText();
    }

    public Integer integer(String field) {
        JsonNode v = requireNonNullNode(field);
        if (!v.isIntegralNumber()) {
            throw invalid(field, "应为整数");
        }
        return v.asInt();
    }

    /** ID：接受字符串或数字 */
    public Long longId(String field) {
        JsonNode v = requireNonNullNode(field);
        try {
            if (v.isTextual()) {
                return Long.valueOf(v.asText());
            }
            if (v.isIntegralNumber()) {
                return v.asLong();
            }
        } catch (NumberFormatException ignored) {
        }
        throw invalid(field, "应为 ID 字符串");
    }

    public BigDecimal decimal(String field) {
        JsonNode v = requireNonNullNode(field);
        if (!v.isNumber()) {
            throw invalid(field, "应为数字");
        }
        return v.decimalValue();
    }

    public Boolean bool(String field) {
        JsonNode v = requireNonNullNode(field);
        if (!v.isBoolean()) {
            throw invalid(field, "应为布尔值");
        }
        return v.asBoolean();
    }

    /** 乐观锁版本号（5.7 必带），缺失抛 10001 */
    public int versionRequired() {
        if (!node.has("version") || !node.get("version").isIntegralNumber()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 version（乐观锁版本号）");
        }
        return node.get("version").asInt();
    }

    private JsonNode requireNonNullNode(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw invalid(field, "不能为 null");
        }
        return v;
    }

    private BizException invalid(String field, String hint) {
        return new BizException(ErrorCode.PARAM_INVALID, "字段 " + field + " " + hint);
    }
}
