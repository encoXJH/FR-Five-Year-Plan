package edu.fzrj.metrics.module.notify.service.support;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReminderRules 纯逻辑单测（截止日推算 / 提醒窗口 / 剩余天数）
 */
class ReminderRulesTest {

    @Nested
    class DeadlineOf {
        @Test
        void 截止日为次年核算截止月日() {
            assertEquals(LocalDate.of(2027, 1, 15), ReminderRules.deadlineOf(2026, "01-15"));
        }

        @Test
        void 非法格式返回空() {
            assertNull(ReminderRules.deadlineOf(2026, null));
            assertNull(ReminderRules.deadlineOf(2026, "1-15"));
            assertNull(ReminderRules.deadlineOf(2026, "13-01"));
            assertNull(ReminderRules.deadlineOf(2026, "02-30"));
            assertNull(ReminderRules.deadlineOf(2026, "abcd"));
        }
    }

    @Nested
    class InRemindWindow {
        private final LocalDate deadline = LocalDate.of(2027, 1, 15);

        @Test
        void 恰好在截止前N天进入窗口() {
            assertTrue(ReminderRules.inRemindWindow(LocalDate.of(2027, 1, 8), deadline, 7));
        }

        @Test
        void 窗口内与截止当天都在窗口() {
            assertTrue(ReminderRules.inRemindWindow(LocalDate.of(2027, 1, 10), deadline, 7));
            assertTrue(ReminderRules.inRemindWindow(deadline, deadline, 7));
        }

        @Test
        void 窗口外不提醒() {
            assertFalse(ReminderRules.inRemindWindow(LocalDate.of(2027, 1, 7), deadline, 7));
        }

        @Test
        void 逾期不补提醒() {
            assertFalse(ReminderRules.inRemindWindow(LocalDate.of(2027, 1, 16), deadline, 7));
        }

        @Test
        void 参数非法不提醒() {
            assertFalse(ReminderRules.inRemindWindow(null, deadline, 7));
            assertFalse(ReminderRules.inRemindWindow(LocalDate.of(2027, 1, 10), null, 7));
            assertFalse(ReminderRules.inRemindWindow(LocalDate.of(2027, 1, 10), deadline, -1));
        }

        @Test
        void N为0时仅截止当天提醒() {
            assertTrue(ReminderRules.inRemindWindow(deadline, deadline, 0));
            assertFalse(ReminderRules.inRemindWindow(deadline.minusDays(1), deadline, 0));
        }
    }

    @Nested
    class DaysRemaining {
        @Test
        void 剩余天数() {
            assertEquals(7, ReminderRules.daysRemaining(LocalDate.of(2027, 1, 8), LocalDate.of(2027, 1, 15)));
            assertEquals(0, ReminderRules.daysRemaining(LocalDate.of(2027, 1, 15), LocalDate.of(2027, 1, 15)));
            assertEquals(-1, ReminderRules.daysRemaining(LocalDate.of(2027, 1, 16), LocalDate.of(2027, 1, 15)));
        }
    }
}
