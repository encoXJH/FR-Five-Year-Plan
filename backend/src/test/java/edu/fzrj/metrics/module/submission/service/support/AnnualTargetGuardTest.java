package edu.fzrj.metrics.module.submission.service.support;

import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnualTargetGuardTest {

    @Mock
    private TaskYearMapper taskYearMapper;
    @InjectMocks
    private AnnualTargetGuard guard;

    @Test
    void 提交允许增量累计超过年度目标() {
        Submission candidate = submission(12);

        assertDoesNotThrow(() -> guard.checkSubmit(candidate, indicator(), taskYear("10")));

        verify(taskYearMapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void 审核允许超过年度目标且先锁定任务年度() {
        Submission candidate = submission(12);
        when(taskYearMapper.selectByIdForUpdate(3L)).thenReturn(taskYear("10"));
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(bd("12"));

        assertDoesNotThrow(() -> guard.checkReview(candidate, indicator(), value));

        verify(taskYearMapper).selectByIdForUpdate(3L);
    }

    @Test
    void 越小越好与是否型审核也先锁定任务年度() {
        when(taskYearMapper.selectByIdForUpdate(3L)).thenReturn(taskYear("10"));
        Submission lower = submission(1);
        Indicator lowerIndicator = indicator();
        lowerIndicator.setTargetDirection("LOWER_BETTER");
        SubmissionDtos.MetricValue amount = new SubmissionDtos.MetricValue();
        amount.setAmount(BigDecimal.ONE);
        Submission bool = submission(1);
        bool.setStatCaliber("BOOLEAN");
        SubmissionDtos.MetricValue achieved = new SubmissionDtos.MetricValue();
        achieved.setAchieved(true);

        guard.checkReview(lower, lowerIndicator, amount);
        guard.checkReview(bool, indicator(), achieved);

        verify(taskYearMapper, times(2)).selectByIdForUpdate(3L);
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
