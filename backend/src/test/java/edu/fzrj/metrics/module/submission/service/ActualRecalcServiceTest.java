package edu.fzrj.metrics.module.submission.service;

import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActualRecalcServiceTest {

    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private IndicatorYearTargetMapper yearTargetMapper;
    @Mock
    private PlanMapper planMapper;

    @InjectMocks
    private ActualRecalcService actualRecalcService;

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(99L, "admin", "管理员", null,
                                List.of("ADMIN"), List.of()), null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void A完成值同时汇总直承历史任务和A加任务() {
        TaskYear currentYear = new TaskYear();
        currentYear.setId(101L);
        currentYear.setTaskId(201L);
        currentYear.setIndicatorId(301L);
        currentYear.setYear(2026);
        currentYear.setTarget(new BigDecimal("10"));
        currentYear.setYearStatus("IN_PROGRESS");
        currentYear.setDeleted(0L);

        Task currentTask = new Task();
        currentTask.setId(201L);
        currentTask.setIndicatorId(301L);
        currentTask.setPlanId(401L);

        Indicator aPlus = indicator(301L, 302L);
        Indicator a = indicator(302L, null);
        a.setRollupMode("AUTO_SUM");

        Plan plan = new Plan();
        plan.setId(401L);
        plan.setStartYear(2026);
        plan.setEndYear(2030);

        Submission approved = new Submission();
        approved.setApprovedAmount(new BigDecimal("5"));

        TaskYear directHistoricalYear = new TaskYear();
        directHistoricalYear.setId(102L);
        directHistoricalYear.setTaskId(202L);
        directHistoricalYear.setIndicatorId(302L);
        directHistoricalYear.setYear(2026);
        directHistoricalYear.setActualValue(new BigDecimal("3"));

        IndicatorYearTarget aPlusCache = cache(501L, 301L);
        IndicatorYearTarget aCache = cache(502L, 302L);

        when(taskYearMapper.selectById(101L)).thenReturn(currentYear);
        when(taskMapper.selectOne(any())).thenReturn(currentTask);
        when(indicatorMapper.selectOne(any())).thenReturn(aPlus, a);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(taskYearMapper.selectList(any()))
                .thenReturn(List.of(currentYear), List.of(directHistoricalYear));
        when(indicatorMapper.selectList(any())).thenReturn(List.of(aPlus));
        when(yearTargetMapper.selectOne(any())).thenReturn(aPlusCache, aPlusCache, aCache);

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("5", currentYear.getActualValue());
        assertDecimalEquals("5", aPlusCache.getActualValue());
        assertDecimalEquals("8", aCache.getActualValue());
    }

    private Indicator indicator(Long id, Long parentId) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setParentId(parentId);
        indicator.setStatCaliber("INCREMENT");
        indicator.setTargetDirection("HIGHER_BETTER");
        indicator.setBaselineValue(BigDecimal.ZERO);
        return indicator;
    }

    private IndicatorYearTarget cache(Long id, Long indicatorId) {
        IndicatorYearTarget cache = new IndicatorYearTarget();
        cache.setId(id);
        cache.setIndicatorId(indicatorId);
        cache.setYear(2026);
        cache.setTarget(new BigDecimal("20"));
        return cache;
    }

    private void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
