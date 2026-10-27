package edu.fzrj.metrics.module.task.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskScopeRulesTest {

    private static final Long USER_ID = 201L;
    private static final Long UNIT_C01 = 301L;
    private static final Long UNIT_C02 = 302L;

    @Test
    void 全校范围_管理员与校领导可访问任意任务() {
        assertTrue(TaskScopeRules.canAccess(true, false, false, USER_ID, null, UNIT_C02, null, null));
    }

    @Test
    void 学院负责人_本单元可访问跨单元拒绝() {
        // c01 用户访问本单元（c01）任务
        assertTrue(TaskScopeRules.canAccess(false, true, false, USER_ID, UNIT_C01, UNIT_C01, 999L, 999L));
        // c01 用户访问 c02 单元任务 → 拒绝（服务层据此抛 20005）
        assertFalse(TaskScopeRules.canAccess(false, true, false, USER_ID, UNIT_C01, UNIT_C02, 999L, 999L));
    }

    @Test
    void 学院负责人_未绑定单元时不可按单元访问() {
        assertFalse(TaskScopeRules.canAccess(false, true, false, USER_ID, null, UNIT_C01, null, null));
    }

    @Test
    void 校级负责人_本人负责的指标可访问() {
        // A+ 直接指定了负责人
        assertTrue(TaskScopeRules.canAccess(false, false, true, USER_ID, null, UNIT_C02, USER_ID, 999L));
        // A+ 未设负责人，沿用所属 A 的负责人（topOwner）
        assertTrue(TaskScopeRules.canAccess(false, false, true, USER_ID, null, UNIT_C02, null, USER_ID));
    }

    @Test
    void 校级负责人_非本人负责且非本单元拒绝() {
        assertFalse(TaskScopeRules.canAccess(false, false, true, USER_ID, null, UNIT_C02, 999L, 998L));
    }

    @Test
    void 多角色_单元或负责人任一匹配即可访问() {
        // 单元不匹配但负责人匹配
        assertTrue(TaskScopeRules.canAccess(false, true, true, USER_ID, UNIT_C01, UNIT_C02, USER_ID, null));
        // 负责人不匹配但单元匹配
        assertTrue(TaskScopeRules.canAccess(false, true, true, USER_ID, UNIT_C01, UNIT_C01, 999L, null));
        // 都不匹配
        assertFalse(TaskScopeRules.canAccess(false, true, true, USER_ID, UNIT_C01, UNIT_C02, 999L, 998L));
    }

    @Test
    void 可见范围_全校或有单元或有负责人角色即可见() {
        assertFalse(TaskScopeRules.noVisibleScope(true, false, false, null));
        assertFalse(TaskScopeRules.noVisibleScope(false, true, false, UNIT_C01));
        assertFalse(TaskScopeRules.noVisibleScope(false, false, true, null));
        // 学院角色但未绑定单元，且无负责人角色 → 无可见数据
        assertTrue(TaskScopeRules.noVisibleScope(false, true, false, null));
        assertTrue(TaskScopeRules.noVisibleScope(false, false, false, UNIT_C01));
    }
}
