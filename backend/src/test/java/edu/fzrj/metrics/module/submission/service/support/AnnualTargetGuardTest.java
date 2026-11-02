package edu.fzrj.metrics.module.submission.service.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator.ApprovedValue;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnualTargetGuardTest {

    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @InjectMocks
    private AnnualTargetGuard guard;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Submission.class);
    }

    @Test
    void 增量累计已有完成值且等于目标允许() {
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("INCREMENT", "HIGHER_BETTER",
                bd("10"), List.of(ApprovedValue.increment(bd("6"))), ApprovedValue.increment(bd("4"))));
    }

    @Test
    void 增量累计已有完成值后超过目标拦截() {
        BizException error = assertThrows(BizException.class,
                () -> AnnualTargetGuard.assertWithinTarget("INCREMENT", "HIGHER_BETTER", bd("10"),
                        List.of(ApprovedValue.increment(bd("6"))), ApprovedValue.increment(bd("4.1"))));
        assertEquals(60007, error.getErrorCode().getCode());
    }

    @Test
    void 存量只取候选最新总量不累加历史值() {
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("STOCK", "HIGHER_BETTER", bd("100"),
                List.of(ApprovedValue.total(bd("120"))), ApprovedValue.total(bd("100"))));
        assertThrows(BizException.class, () -> AnnualTargetGuard.assertWithinTarget("STOCK", "HIGHER_BETTER",
                bd("100"), List.of(ApprovedValue.total(bd("80"))), ApprovedValue.total(bd("101"))));
    }

    @Test
    void 增长率口径按候选最新总量而不是增长率判断() {
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("GROWTH", "HIGHER_BETTER", bd("100"),
                List.of(ApprovedValue.total(bd("120"))), ApprovedValue.total(bd("100"))));
        assertThrows(BizException.class, () -> AnnualTargetGuard.assertWithinTarget("GROWTH", "HIGHER_BETTER",
                bd("100"), List.of(ApprovedValue.total(bd("80"))), ApprovedValue.total(bd("101"))));
    }

    @Test
    void 占比按累计分子除以累计分母() {
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("RATIO", "HIGHER_BETTER", bd("0.4"),
                List.of(ApprovedValue.ratio(bd("40"), bd("100"))), ApprovedValue.ratio(bd("20"), bd("50"))));
        assertThrows(BizException.class, () -> AnnualTargetGuard.assertWithinTarget("RATIO", "HIGHER_BETTER",
                bd("0.4"), List.of(ApprovedValue.ratio(bd("40"), bd("100"))),
                ApprovedValue.ratio(bd("20"), bd("40"))));
    }

    @Test
    void 直填百分比只取候选最新值() {
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("DIRECT_PERCENT", "HIGHER_BETTER",
                bd("0.8"), List.of(ApprovedValue.percent(bd("0.9"))), ApprovedValue.percent(bd("0.8"))));
        assertThrows(BizException.class, () -> AnnualTargetGuard.assertWithinTarget("DIRECT_PERCENT",
                "HIGHER_BETTER", bd("0.8"), List.of(ApprovedValue.percent(bd("0.7"))),
                ApprovedValue.percent(bd("0.81"))));
    }

    @Test
    void 是否型不应用年度上限() {
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("BOOLEAN", "HIGHER_BETTER",
                BigDecimal.ONE, List.of(ApprovedValue.bool(true)), ApprovedValue.bool(true)));
    }

    @Test
    void 越小越好不套用上限且空目标不拦截() {
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("INCREMENT", "LOWER_BETTER", bd("1"),
                List.of(), ApprovedValue.increment(bd("2"))));
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("INCREMENT", "HIGHER_BETTER", null,
                List.of(), ApprovedValue.increment(bd("2"))));
        assertDoesNotThrow(() -> AnnualTargetGuard.assertWithinTarget("INCREMENT", "HIGHER_BETTER",
                BigDecimal.ZERO, List.of(), ApprovedValue.increment(bd("2"))));
    }

    @Test
    void 提交校验读取已通过累计值但不加行锁() {
        Submission candidate = submission(5);
        Submission approved = submission(6);
        approved.setId(2L);
        TaskYear taskYear = taskYear("10");
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));

        BizException error = assertThrows(BizException.class,
                () -> guard.checkSubmit(candidate, indicator(), taskYear));

        assertEquals(60007, error.getErrorCode().getCode());
        verify(taskYearMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void 审核先锁定任务年度且核减到目标内允许() {
        Submission candidate = submission(8);
        Submission approved = submission(6);
        approved.setId(2L);
        when(taskYearMapper.selectByIdForUpdate(3L)).thenReturn(taskYear("10"));
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));
        SubmissionDtos.MetricValue reduced = new SubmissionDtos.MetricValue();
        reduced.setAmount(bd("4"));

        assertDoesNotThrow(() -> guard.checkReview(candidate, indicator(), reduced));

        InOrder calls = inOrder(taskYearMapper, submissionMapper);
        calls.verify(taskYearMapper).selectByIdForUpdate(3L);
        calls.verify(submissionMapper).selectList(any());
    }

    @Test
    void 审核未核减时填报值导致超限则拦截() {
        Submission candidate = submission(5);
        Submission approved = submission(6);
        approved.setId(2L);
        when(taskYearMapper.selectByIdForUpdate(3L)).thenReturn(taskYear("10"));
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(bd("5"));

        BizException error = assertThrows(BizException.class,
                () -> guard.checkReview(candidate, indicator(), value));

        assertEquals(60007, error.getErrorCode().getCode());
    }

    private static Submission submission(int amount) {
        Submission submission = new Submission();
        submission.setId(1L);
        submission.setTaskYearId(3L);
        submission.setStatCaliber("INCREMENT");
        submission.setAmount(bd(Integer.toString(amount)));
        return submission;
    }

    private static TaskYear taskYear(String target) {
        TaskYear taskYear = new TaskYear();
        taskYear.setId(3L);
        taskYear.setTarget(bd(target));
        return taskYear;
    }

    private static Indicator indicator() {
        Indicator indicator = new Indicator();
        indicator.setTargetDirection("HIGHER_BETTER");
        return indicator;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
