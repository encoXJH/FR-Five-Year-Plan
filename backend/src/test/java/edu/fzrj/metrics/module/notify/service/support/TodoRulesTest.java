package edu.fzrj.metrics.module.notify.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TodoRules 纯逻辑单测（待填报判定：进行中 / 待补做 / 惰性激活）
 */
class TodoRulesTest {

    @Test
    void 进行中与待补做直接可填() {
        assertTrue(TodoRules.isFillableTaskYear("IN_PROGRESS", false));
        assertTrue(TodoRules.isFillableTaskYear("IN_PROGRESS", true));
        assertTrue(TodoRules.isFillableTaskYear("MAKEUP_PENDING", false));
        assertTrue(TodoRules.isFillableTaskYear("MAKEUP_PENDING", true));
    }

    @Test
    void 未激活仅当年度已激活时可填() {
        assertTrue(TodoRules.isFillableTaskYear("NOT_ACTIVATED", true));
        assertFalse(TodoRules.isFillableTaskYear("NOT_ACTIVATED", false));
    }

    @Test
    void 其他状态不算待填报() {
        assertFalse(TodoRules.isFillableTaskYear("PENDING_REVIEW", true));
        assertFalse(TodoRules.isFillableTaskYear("ADJUSTING", true));
        assertFalse(TodoRules.isFillableTaskYear("ACHIEVED", true));
        assertFalse(TodoRules.isFillableTaskYear("NOT_ACHIEVED", true));
        assertFalse(TodoRules.isFillableTaskYear("MADE_UP", true));
    }
}
