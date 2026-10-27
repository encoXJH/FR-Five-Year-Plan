package edu.fzrj.metrics.module.submission.service.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubmissionValueRules 纯逻辑单测（60002 / 60005 / 60006）
 */
class SubmissionValueRulesTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 增量型只填amount合法() {
        assertNull(SubmissionValueRules.caliberError("INCREMENT", bd("1"), null, null, null, null, null));
    }

    @Test
    void 增量型缺amount不合法() {
        assertNotNull(SubmissionValueRules.caliberError("INCREMENT", null, null, null, null, null, null));
    }

    @Test
    void 增量型多填total不合法() {
        assertNotNull(SubmissionValueRules.caliberError("INCREMENT", bd("1"), bd("1"), null, null, null, null));
    }

    @Test
    void 存量与增长率只填total合法() {
        assertNull(SubmissionValueRules.caliberError("STOCK", null, bd("100"), null, null, null, null));
        assertNull(SubmissionValueRules.caliberError("GROWTH", null, bd("100"), null, null, null, null));
        assertNotNull(SubmissionValueRules.caliberError("STOCK", bd("1"), bd("100"), null, null, null, null));
    }

    @Test
    void 占比型分子分母必填且分母大于零() {
        assertNull(SubmissionValueRules.caliberError("RATIO", null, null, bd("1"), bd("2"), null, null));
        assertNotNull(SubmissionValueRules.caliberError("RATIO", null, null, bd("1"), null, null, null));
        assertNotNull(SubmissionValueRules.caliberError("RATIO", null, null, bd("1"), BigDecimal.ZERO, null, null));
        assertNotNull(SubmissionValueRules.caliberError("RATIO", null, null, bd("1"), bd("2"), bd("0.5"), null));
    }

    @Test
    void 直填百分比与是否型字段校验() {
        assertNull(SubmissionValueRules.caliberError("DIRECT_PERCENT", null, null, null, null, bd("0.6"), null));
        assertNotNull(SubmissionValueRules.caliberError("DIRECT_PERCENT", null, null, null, null, null, null));
        assertNull(SubmissionValueRules.caliberError("BOOLEAN", null, null, null, null, null, true));
        assertNotNull(SubmissionValueRules.caliberError("BOOLEAN", null, null, null, null, null, null));
    }

    @Test
    void 认定值大于填报值判定() {
        assertTrue(SubmissionValueRules.exceedsSubmitted("INCREMENT",
                bd("2"), null, null, null, null, null,
                bd("3"), null, null, null, null, null));
        assertFalse(SubmissionValueRules.exceedsSubmitted("INCREMENT",
                bd("3"), null, null, null, null, null,
                bd("2"), null, null, null, null, null));
        assertTrue(SubmissionValueRules.exceedsSubmitted("RATIO",
                null, null, bd("10"), bd("20"), null, null,
                null, null, bd("11"), bd("20"), null, null));
        assertTrue(SubmissionValueRules.exceedsSubmitted("BOOLEAN",
                null, null, null, null, null, false,
                null, null, null, null, null, true));
        assertFalse(SubmissionValueRules.exceedsSubmitted("BOOLEAN",
                null, null, null, null, null, true,
                null, null, null, null, null, true));
    }

    @Test
    void 材料数量与直填来源说明校验() {
        assertNull(SubmissionValueRules.attachmentError("INCREMENT", 1, 1, null));
        assertNotNull(SubmissionValueRules.attachmentError("INCREMENT", 2, 1, null));
        assertNotNull(SubmissionValueRules.attachmentError("DIRECT_PERCENT", 0, 0, null));
        assertNull(SubmissionValueRules.attachmentError("DIRECT_PERCENT", 0, 0, "来源：省教育厅公报"));
    }
}
