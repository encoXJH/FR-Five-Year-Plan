package edu.fzrj.metrics.module.integration.service.support;

import java.util.List;

/**
 * 飞书用户与平台用户的绑定匹配（纯逻辑，便于单测）。
 * 规则：先按 feishu_open_id 精确匹配（已绑定用户直接登录）；未命中再按手机号匹配并补绑定；
 * 手机号为空或未命中则视为未绑定。账号是否停用在服务层另行判定（20007）。
 */
public final class FeishuBindMatcher {

    private FeishuBindMatcher() {
    }

    public enum MatchResult {
        /** 按 feishu_open_id 命中（已绑定） */
        BY_OPEN_ID,
        /** 按手机号命中（需要回写 open_id 完成绑定） */
        BY_PHONE,
        /** 未匹配到平台用户 */
        NOT_FOUND
    }

    /** 平台用户候选（绑定判断所需的最小字段） */
    public record Candidate(Long id, String phone, String feishuOpenId) {
    }

    /** 匹配结论：user 仅在命中时非空；needBind=true 表示需要把 openId 回写到该用户 */
    public record Decision(MatchResult result, Candidate user, boolean needBind) {

        public static Decision notFound() {
            return new Decision(MatchResult.NOT_FOUND, null, false);
        }
    }

    /**
     * @param openId     飞书用户 open_id（必传）
     * @param mobile     飞书用户手机号（可能为空，取决于授权 scope）
     * @param candidates 平台全部候选用户（未删除）
     */
    public static Decision match(String openId, String mobile, List<Candidate> candidates) {
        if (openId != null && !openId.isBlank()) {
            for (Candidate c : candidates) {
                if (openId.equals(c.feishuOpenId())) {
                    return new Decision(MatchResult.BY_OPEN_ID, c, false);
                }
            }
        }
        if (mobile != null && !mobile.isBlank()) {
            for (Candidate c : candidates) {
                if (mobile.equals(c.phone())) {
                    return new Decision(MatchResult.BY_PHONE, c, true);
                }
            }
        }
        return Decision.notFound();
    }
}
