package edu.fzrj.metrics.module.system.service.support;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SettingRules 纯逻辑单测（系统参数值按 valueType 校验）
 */
class SettingRulesTest {

    @Nested
    class Number {
        @Test
        void 接受整数与小数() {
            assertNull(SettingRules.validateValue("NUMBER", "7"));
            assertNull(SettingRules.validateValue("NUMBER", "0.5"));
            assertNull(SettingRules.validateValue("NUMBER", "52428800"));
        }

        @Test
        void 拒绝非数字() {
            assertNotNull(SettingRules.validateValue("NUMBER", "abc"));
            assertNotNull(SettingRules.validateValue("NUMBER", ""));
            assertNotNull(SettingRules.validateValue("NUMBER", "七天"));
        }
    }

    @Nested
    class BooleanType {
        @Test
        void 只接受true或false() {
            assertNull(SettingRules.validateValue("BOOLEAN", "true"));
            assertNull(SettingRules.validateValue("BOOLEAN", "false"));
            assertNotNull(SettingRules.validateValue("BOOLEAN", "yes"));
            assertNotNull(SettingRules.validateValue("BOOLEAN", "1"));
            assertNotNull(SettingRules.validateValue("BOOLEAN", "TRUE"));
        }
    }

    @Nested
    class JsonType {
        @Test
        void 接受合法JSON对象与数组() {
            assertNull(SettingRules.validateValue("JSON", "[\"pdf\",\"docx\"]"));
            assertNull(SettingRules.validateValue("JSON", "{\"a\":1}"));
        }

        @Test
        void 拒绝非JSON与残缺JSON() {
            assertNotNull(SettingRules.validateValue("JSON", "pdf"));
            assertNotNull(SettingRules.validateValue("JSON", "[\"pdf\""));
            assertNotNull(SettingRules.validateValue("JSON", "{\"a\":}"));
        }
    }

    @Nested
    class StringAndGuard {
        @Test
        void STRING任意值通过() {
            assertNull(SettingRules.validateValue("STRING", "01-15"));
            assertNull(SettingRules.validateValue("STRING", "任意文本"));
        }

        @Test
        void 未知类型与空值拒绝() {
            assertNotNull(SettingRules.validateValue("UNKNOWN", "x"));
            assertNotNull(SettingRules.validateValue(null, "x"));
            assertNotNull(SettingRules.validateValue("STRING", null));
        }
    }
}
