package edu.fzrj.metrics.module.system.service.support;

/**
 * 系统参数值校验纯逻辑（sys_setting.value_type）：
 * NUMBER 必须可解析为数字；BOOLEAN 只接受 true/false；JSON 必须是合法 JSON 对象或数组；STRING 任意。
 */
public final class SettingRules {

    private SettingRules() {
    }

    /** 校验通过返回 null；不合法返回错误说明 */
    public static String validateValue(String valueType, String value) {
        if (valueType == null) {
            return "值类型缺失";
        }
        if (value == null) {
            return "参数值不能为 null";
        }
        return switch (valueType) {
            case "STRING" -> null;
            case "NUMBER" -> isNumber(value) ? null : "NUMBER 类型参数值必须是数字";
            case "BOOLEAN" -> ("true".equals(value) || "false".equals(value)) ? null
                    : "BOOLEAN 类型参数值只能是 true / false";
            case "JSON" -> isJson(value) ? null : "JSON 类型参数值必须是合法 JSON";
            default -> "未知值类型：" + valueType;
        };
    }

    private static boolean isNumber(String value) {
        try {
            new java.math.BigDecimal(value.trim());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isJson(String value) {
        String trimmed = value.trim();
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))
                && !(trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return false;
        }
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
