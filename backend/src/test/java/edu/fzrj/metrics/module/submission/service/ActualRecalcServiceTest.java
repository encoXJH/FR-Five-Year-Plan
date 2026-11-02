package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
    }

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(99L, "admin", "管理员", null,
                                List.of("ADMIN"), List.of()), null, List.of()));
        lenient().when(taskYearMapper.updateById(any(TaskYear.class))).thenReturn(1);
        lenient().when(yearTargetMapper.updateById(any(IndicatorYearTarget.class))).thenReturn(1);
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
        approved.setStatus("APPROVED");
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
        when(taskYearMapper.selectByIdForUpdate(101L)).thenReturn(currentYear);
        when(taskMapper.selectOne(any())).thenReturn(currentTask);
        when(indicatorMapper.selectOne(any())).thenReturn(aPlus, a, a, aPlus, aPlus, a);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));
        when(taskYearMapper.selectList(any()))
                .thenReturn(List.of(currentYear), List.of(directHistoricalYear));
        when(indicatorMapper.selectList(any())).thenReturn(List.of(aPlus));
        when(yearTargetMapper.selectOne(any())).thenReturn(aPlusCache, aPlusCache, aCache);

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("5", currentYear.getActualValue());
        assertDecimalEquals("5", aPlusCache.getActualValue());
        assertDecimalEquals("8", aCache.getActualValue());
    }

    @Test
    void 直承A任务重算时也合并legacyA加缓存() {
        TaskYear currentYear = new TaskYear();
        currentYear.setId(101L);
        currentYear.setTaskId(201L);
        currentYear.setIndicatorId(302L);
        currentYear.setYear(2026);
        currentYear.setTarget(new BigDecimal("10"));
        currentYear.setYearStatus("IN_PROGRESS");
        currentYear.setDeleted(0L);

        Task currentTask = new Task();
        currentTask.setId(201L);
        currentTask.setIndicatorId(302L);
        currentTask.setPlanId(401L);

        Indicator a = indicator(302L, null);
        a.setRollupMode("AUTO_SUM");
        Indicator aPlus = indicator(301L, 302L);
        Plan plan = new Plan();
        plan.setId(401L);
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        Submission approved = new Submission();
        approved.setStatus("APPROVED");
        approved.setApprovedAmount(new BigDecimal("5"));

        IndicatorYearTarget aPlusCache = cache(501L, 301L);
        aPlusCache.setActualValue(new BigDecimal("3"));
        IndicatorYearTarget aCache = cache(502L, 302L);

        when(taskYearMapper.selectById(101L)).thenReturn(currentYear);
        when(taskYearMapper.selectByIdForUpdate(101L)).thenReturn(currentYear);
        when(taskMapper.selectOne(any())).thenReturn(currentTask);
        when(indicatorMapper.selectOne(any())).thenReturn(a);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(currentYear));
        when(indicatorMapper.selectList(any())).thenReturn(List.of(aPlus));
        when(yearTargetMapper.selectOne(any())).thenReturn(aPlusCache, aCache);

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("5", currentYear.getActualValue());
        assertDecimalEquals("8", aCache.getActualValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOCK", "GROWTH"})
    void 补审往年后按规划期连续重算后续年度(String caliber) {
        TaskYear year2026 = taskYear(101L, 2026);
        TaskYear year2027 = taskYear(102L, 2027);
        year2027.setActualValue(new BigDecimal("999"));
        Task task = new Task();
        task.setId(201L);
        task.setIndicatorId(301L);
        task.setPlanId(401L);
        Indicator indicator = indicator(301L, null);
        indicator.setStatCaliber(caliber);
        indicator.setRollupMode("AUTO_SUM");
        indicator.setBaselineValue(new BigDecimal("100"));
        Plan plan = new Plan();
        plan.setId(401L);
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        Submission approved2026 = new Submission();
        approved2026.setStatus("APPROVED");
        approved2026.setApprovedTotal(new BigDecimal("110"));
        Submission approved2027 = new Submission();
        approved2027.setStatus("APPROVED");
        approved2027.setApprovedTotal(new BigDecimal("132"));
        IndicatorYearTarget cache2026 = cache(501L, 301L);
        IndicatorYearTarget cache2027 = cache(502L, 301L);
        cache2027.setYear(2027);

        when(taskYearMapper.selectById(101L)).thenReturn(year2026);
        when(taskYearMapper.selectByIdForUpdate(101L)).thenReturn(year2026);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(indicatorMapper.selectOne(any())).thenReturn(indicator);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved2026), List.of(approved2027));
        when(taskYearMapper.selectList(any())).thenReturn(
                List.of(year2026, year2027), List.of(year2026), List.of(year2027));
        when(taskYearMapper.selectOne(any())).thenReturn(year2026);
        when(indicatorMapper.selectList(any())).thenReturn(List.of());
        when(yearTargetMapper.selectOne(any())).thenReturn(cache2026, cache2026, cache2027);

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("110", year2026.getActualTotal());
        assertDecimalEquals("132", year2027.getActualTotal());
        if ("GROWTH".equals(caliber)) {
            assertDecimalEquals("0.1", year2026.getActualValue());
            assertDecimalEquals("0.2", year2027.getActualValue());
            assertDecimalEquals("0.2", cache2027.getActualValue());
        } else {
            assertDecimalEquals("110", year2026.getActualValue());
            assertDecimalEquals("132", year2027.getActualValue());
            assertDecimalEquals("132", cache2027.getActualValue());
        }
        verify(taskYearMapper, times(2)).updateById(any(TaskYear.class));
    }

    @Test
    void 聚合节点锁定后才读取其他B最新完成值() {
        TaskYear currentYear = taskYear(101L, 2026);
        TaskYear otherYear = taskYear(102L, 2026);
        otherYear.setTaskId(202L);
        otherYear.setActualValue(BigDecimal.ZERO);
        Task task = new Task();
        task.setId(201L);
        task.setIndicatorId(301L);
        task.setPlanId(401L);
        Indicator indicator = indicator(301L, null);
        indicator.setRollupMode("AUTO_SUM");
        Plan plan = new Plan();
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        Submission approved = new Submission();
        approved.setStatus("APPROVED");
        approved.setApprovedAmount(new BigDecimal("5"));
        IndicatorYearTarget cache = cache(501L, 301L);

        when(taskYearMapper.selectById(101L)).thenReturn(currentYear);
        when(taskYearMapper.selectByIdForUpdate(101L)).thenReturn(currentYear);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(indicatorMapper.selectOne(any())).thenAnswer(invocation -> {
            Wrapper<?> wrapper = invocation.getArgument(0);
            if (wrapper.getSqlSegment().contains("FOR UPDATE")) {
                otherYear.setActualValue(new BigDecimal("4"));
            }
            return indicator;
        });
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(currentYear, otherYear));
        when(indicatorMapper.selectList(any())).thenReturn(List.of());
        when(yearTargetMapper.selectOne(any())).thenReturn(cache);

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("9", cache.getActualValue());
    }

    @Test
    void 任务年度缓存并发重算失败时返回10005() {
        TaskYear currentYear = taskYear(101L, 2026);
        Task task = new Task();
        task.setId(201L);
        task.setIndicatorId(301L);
        task.setPlanId(401L);
        Indicator indicator = indicator(301L, null);
        indicator.setRollupMode("MANUAL");
        Plan plan = new Plan();
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        Submission approved = new Submission();
        approved.setStatus("APPROVED");
        approved.setApprovedAmount(new BigDecimal("5"));
        when(taskYearMapper.selectById(101L)).thenReturn(currentYear);
        when(taskYearMapper.selectByIdForUpdate(101L)).thenReturn(currentYear);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(indicatorMapper.selectOne(any())).thenReturn(indicator);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));
        when(taskYearMapper.updateById(any(TaskYear.class))).thenReturn(0);

        BizException error = assertThrows(BizException.class, () -> actualRecalcService.recalcTaskYear(101L));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(yearTargetMapper, never()).updateById(any(IndicatorYearTarget.class));
    }

    @Test
    void 指标年度缓存并发重算失败时返回10005() {
        TaskYear currentYear = taskYear(101L, 2026);
        Task task = new Task();
        task.setId(201L);
        task.setIndicatorId(301L);
        task.setPlanId(401L);
        Indicator indicator = indicator(301L, null);
        indicator.setRollupMode("AUTO_SUM");
        Plan plan = new Plan();
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        Submission approved = new Submission();
        approved.setStatus("APPROVED");
        approved.setApprovedAmount(new BigDecimal("5"));
        IndicatorYearTarget cache = cache(501L, 301L);
        when(taskYearMapper.selectById(101L)).thenReturn(currentYear);
        when(taskYearMapper.selectByIdForUpdate(101L)).thenReturn(currentYear);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(indicatorMapper.selectOne(any())).thenReturn(indicator);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(submissionMapper.selectList(any())).thenReturn(List.of(approved));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(currentYear));
        when(indicatorMapper.selectList(any())).thenReturn(List.of());
        when(yearTargetMapper.selectOne(any())).thenReturn(cache);
        when(yearTargetMapper.updateById(any(IndicatorYearTarget.class))).thenReturn(0);

        BizException error = assertThrows(BizException.class, () -> actualRecalcService.recalcTaskYear(101L));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(yearTargetMapper).updateById(cache);
    }

    private TaskYear taskYear(Long id, Integer year) {
        TaskYear taskYear = new TaskYear();
        taskYear.setId(id);
        taskYear.setTaskId(201L);
        taskYear.setIndicatorId(301L);
        taskYear.setYear(year);
        taskYear.setTarget(new BigDecimal("200"));
        taskYear.setYearStatus("IN_PROGRESS");
        taskYear.setDeleted(0L);
        return taskYear;
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
