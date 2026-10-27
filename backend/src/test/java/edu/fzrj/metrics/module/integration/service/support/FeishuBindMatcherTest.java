package edu.fzrj.metrics.module.integration.service.support;

import edu.fzrj.metrics.module.integration.service.support.FeishuBindMatcher.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书用户绑定匹配纯逻辑单测（open_id 优先，其次手机号；未匹配 NOT_FOUND）
 */
class FeishuBindMatcherTest {

    private final List<Candidate> users = List.of(
            new Candidate(1L, "13800000001", "ou_bound_already"),
            new Candidate(2L, "13800000002", null),
            new Candidate(3L, null, null));

    @Test
    void 按openId直接命中无需再绑定() {
        FeishuBindMatcher.Decision decision =
                FeishuBindMatcher.match("ou_bound_already", null, users);
        assertEquals(FeishuBindMatcher.MatchResult.BY_OPEN_ID, decision.result());
        assertEquals(1L, decision.user().id());
        assertFalse(decision.needBind());
    }

    @Test
    void 按手机号命中需要回写openId() {
        FeishuBindMatcher.Decision decision =
                FeishuBindMatcher.match("ou_new", "13800000002", users);
        assertEquals(FeishuBindMatcher.MatchResult.BY_PHONE, decision.result());
        assertEquals(2L, decision.user().id());
        assertTrue(decision.needBind());
    }

    @Test
    void openId优先于手机号() {
        // openId 属于用户 1，手机号属于用户 2 → 以 openId 为准
        FeishuBindMatcher.Decision decision =
                FeishuBindMatcher.match("ou_bound_already", "13800000002", users);
        assertEquals(FeishuBindMatcher.MatchResult.BY_OPEN_ID, decision.result());
        assertEquals(1L, decision.user().id());
    }

    @Test
    void 手机号为空且openId未命中则NOT_FOUND() {
        FeishuBindMatcher.Decision decision = FeishuBindMatcher.match("ou_new", null, users);
        assertEquals(FeishuBindMatcher.MatchResult.NOT_FOUND, decision.result());
        assertNull(decision.user());
        assertFalse(decision.needBind());
    }

    @Test
    void 两者都未命中NOT_FOUND() {
        FeishuBindMatcher.Decision decision = FeishuBindMatcher.match("ou_new", "13900000000", users);
        assertEquals(FeishuBindMatcher.MatchResult.NOT_FOUND, decision.result());
    }

    @Test
    void openId为空白时跳过openId匹配() {
        FeishuBindMatcher.Decision decision = FeishuBindMatcher.match("  ", "13800000002", users);
        assertEquals(FeishuBindMatcher.MatchResult.BY_PHONE, decision.result());
    }

    @Test
    void 手机号空白时跳过手机号匹配() {
        FeishuBindMatcher.Decision decision = FeishuBindMatcher.match("ou_new", "", users);
        assertEquals(FeishuBindMatcher.MatchResult.NOT_FOUND, decision.result());
    }
}
