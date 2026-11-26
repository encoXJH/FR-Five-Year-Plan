package edu.fzrj.metrics.module.submission.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.file.service.FileService;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.mapper.ReviewMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionHistoryMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.AnnualTargetGuard;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskReadScopeService;
import edu.fzrj.metrics.module.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 预算超支校验与支出归属 A 的归集规则 */
@ExtendWith(MockitoExtension.class)
class SubmissionBudgetTest {

    @Mock private SubmissionMapper submissionMapper;
    @Mock private SubmissionHistoryMapper historyMapper;
    @Mock private ReviewMapper reviewMapper;
    @Mock private TaskYearMapper taskYearMapper;
    @Mock private PlanYearMapper planYearMapper;
    @Mock private FileRefMapper fileRefMapper;
    @Mock private FileInfoMapper fileInfoMapper;
    @Mock private FileService fileService;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private TaskService taskService;
    @Mock private IndicatorService indicatorService;
    @Mock private PlanService planService;
    @Mock private DataScopeService dataScopeService;
    @Mock private UserRefResolver userRefResolver;
    @Mock private ObjectMapper objectMapper;
    @Mock private AnnualTargetGuard annualTargetGuard;
    @Mock private TaskReadScopeService taskReadScopeService;
    @Spy @InjectMocks private SubmissionService submissionService;

    @Test
    void 支出为空或零不校验预算() {
        submissionService.checkBudgetLimit(new Task(), 5L, null);
        submissionService.checkBudgetLimit(new Task(), 5L, BigDecimal.ZERO);
        verifyNoInteractions(indicatorService);
    }

    @Test
    void 未设预算不限制支出() {
        Task task = new Task();
        task.setTopIndicatorId(9L);
        Indicator a = new Indicator();
        a.setId(9L);
        when(indicatorService.findByIds(List.of(9L))).thenReturn(List.of(a));

        submissionService.checkBudgetLimit(task, 5L, new BigDecimal("9999"));

        verify(indicatorService, never()).spentOfIndicator(any());
    }

    @Test
    void 累计已花加本次超过预算拒绝() {
        Task task = new Task();
        task.setTopIndicatorId(9L);
        Indicator a = new Indicator();
        a.setId(9L);
        a.setTotalBudget(new BigDecimal("100"));
        when(indicatorService.findByIds(List.of(9L))).thenReturn(List.of(a));
        when(indicatorService.spentOfIndicator(a)).thenReturn(new BigDecimal("80"));

        BizException ex = assertThrows(BizException.class,
                () -> submissionService.checkBudgetLimit(task, 5L, new BigDecimal("20.01")));
        assertEquals(ErrorCode.BUDGET_EXCEEDED, ex.getErrorCode());
    }

    @Test
    void 累计恰好等于预算允许() {
        Task task = new Task();
        task.setTopIndicatorId(9L);
        Indicator a = new Indicator();
        a.setId(9L);
        a.setTotalBudget(new BigDecimal("100"));
        when(indicatorService.findByIds(List.of(9L))).thenReturn(List.of(a));
        when(indicatorService.spentOfIndicator(a)).thenReturn(new BigDecimal("80"));

        submissionService.checkBudgetLimit(task, 5L, new BigDecimal("20"));

        verify(indicatorService).spentOfIndicator(a);
    }

    @Test
    void 旧任务无top引用时按Aplus父级归集() {
        Task task = new Task();
        task.setIndicatorId(6L);
        Indicator aplus = new Indicator();
        aplus.setId(6L);
        aplus.setParentId(9L);
        when(indicatorService.requireIndicator(6L)).thenReturn(aplus);
        Indicator a = new Indicator();
        a.setId(9L);
        a.setTotalBudget(new BigDecimal("100"));
        when(indicatorService.findByIds(List.of(9L))).thenReturn(List.of(a));
        when(indicatorService.spentOfIndicator(a)).thenReturn(new BigDecimal("90"));

        BizException ex = assertThrows(BizException.class,
                () -> submissionService.checkBudgetLimit(task, 6L, new BigDecimal("11")));
        assertEquals(ErrorCode.BUDGET_EXCEEDED, ex.getErrorCode());
    }

    @Test
    void 支出归属A的三种挂载方式() {
        Task newB = new Task();
        newB.setTopIndicatorId(9L);
        newB.setIndicatorId(9L);
        assertEquals(9L, IndicatorService.topIndicatorIdOf(newB, null));

        Task legacyB = new Task();
        legacyB.setIndicatorId(6L);
        Indicator aplus = new Indicator();
        aplus.setParentId(9L);
        assertEquals(9L, IndicatorService.topIndicatorIdOf(legacyB, aplus));

        Task directB = new Task();
        directB.setIndicatorId(9L);
        Indicator a = new Indicator();
        a.setId(9L);
        assertEquals(9L, IndicatorService.topIndicatorIdOf(directB, a));
    }
}
