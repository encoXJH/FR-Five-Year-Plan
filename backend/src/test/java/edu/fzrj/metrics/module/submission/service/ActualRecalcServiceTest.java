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
import edu.fzrj.metrics.module.task.entity.TaskCompletionLink;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskCompletionLinkMapper;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @Mock
    private TaskCompletionLinkMapper completionLinkMapper;

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
        lenient().when(completionLinkMapper.selectActiveByTaskId(any())).thenReturn(List.of());
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

    @Test
    void 增量部分审核通过后同步进度但不伪造达标() {
        LinkedFixture fixture = linkedFixture("INCREMENT");
        TaskYear sourceYear = fixture.sourceYear();
        TaskYear targetYear = fixture.targetYear();
        stubRecalcSource(sourceYear);
        stubSubmissions(Map.of(101L, List.of(approvedAmount("4")), 102L, List.of()));

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("4", sourceYear.getOwnActualValue());
        assertDecimalEquals("4", sourceYear.getActualValue());
        assertNull(targetYear.getOwnActualValue());
        assertDecimalEquals("4", targetYear.getActualValue());
        assertDecimalEquals("0.4", sourceYear.getCompletionRate());
        assertDecimalEquals("0.8", targetYear.getCompletionRate());
        assertEquals(false, sourceYear.getIsAchieved());
        assertEquals(false, targetYear.getIsAchieved());
        assertEquals(false, targetYear.getLinkedAchieved());
        assertEquals(101L, targetYear.getLinkedSourceTaskYearId());
        assertEquals("IN_PROGRESS", sourceYear.getYearStatus());
        assertEquals("IN_PROGRESS", targetYear.getYearStatus());
        verify(taskYearMapper).updateById(sourceYear);
        verify(taskYearMapper).updateById(targetYear);
        verify(submissionMapper, never()).insert(any(Submission.class));
    }

    @Test
    void 待审核填报不增加关联进度且来源缺失时回退自身() {
        LinkedFixture fixture = linkedFixture("INCREMENT");
        TaskYear sourceYear = fixture.sourceYear();
        TaskYear targetYear = fixture.targetYear();
        sourceYear.setLinkedAchieved(true);
        sourceYear.setLinkedSourceTaskYearId(999L);
        Submission pending = approvedAmount("6");
        pending.setStatus("PENDING_REVIEW");
        stubRecalcSource(sourceYear);
        stubSubmissions(Map.of(101L, List.of(approvedAmount("4"), pending), 102L, List.of()));

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("4", sourceYear.getOwnActualValue());
        assertDecimalEquals("4", sourceYear.getActualValue());
        assertDecimalEquals("4", targetYear.getActualValue());
        assertNull(sourceYear.getLinkedSourceTaskYearId());
        assertEquals(false, sourceYear.getLinkedAchieved());
        assertEquals("PENDING_REVIEW", sourceYear.getYearStatus());
        assertEquals("IN_PROGRESS", targetYear.getYearStatus());
        assertDecimalEquals("0.4", sourceYear.getCompletionRate());
        assertDecimalEquals("0.8", targetYear.getCompletionRate());
    }

    @Test
    void 双方均已填报时各自保留自身值且有效值不相加() {
        LinkedFixture fixture = linkedFixture("INCREMENT");
        TaskYear sourceYear = fixture.sourceYear();
        TaskYear targetYear = fixture.targetYear();
        stubRecalcSource(sourceYear);
        stubSubmissions(Map.of(101L, List.of(approvedAmount("4")), 102L, List.of(approvedAmount("3"))));

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("4", sourceYear.getOwnActualValue());
        assertDecimalEquals("3", targetYear.getOwnActualValue());
        assertDecimalEquals("4", sourceYear.getActualValue());
        assertDecimalEquals("4", targetYear.getActualValue());
        assertDecimalEquals("0.4", sourceYear.getCompletionRate());
        assertDecimalEquals("0.8", targetYear.getCompletionRate());
        assertNull(sourceYear.getLinkedSourceTaskYearId());
        assertEquals(false, sourceYear.getLinkedAchieved());
        assertEquals(101L, targetYear.getLinkedSourceTaskYearId());
        assertEquals(false, targetYear.getLinkedAchieved());
    }

    @Test
    void 关联任务没有同学年记录时不造年度或完成值() {
        LinkedFixture fixture = linkedFixture("INCREMENT");
        TaskYear sourceYear = fixture.sourceYear();
        TaskYear targetYear = fixture.targetYear();
        stubRecalcSource(sourceYear);
        when(taskYearMapper.selectByTaskIdAndYearForUpdate(202L, 2026)).thenReturn(null);
        stubSubmissions(Map.of(101L, List.of(approvedAmount("4"))));

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("4", sourceYear.getOwnActualValue());
        assertDecimalEquals("4", sourceYear.getActualValue());
        assertNull(targetYear.getActualValue());
        verify(taskYearMapper, times(1)).updateById(sourceYear);
        verify(taskYearMapper, never()).updateById(targetYear);
        verify(yearTargetMapper, never()).updateById(any(IndicatorYearTarget.class));
        verify(submissionMapper, never()).insert(any(Submission.class));
    }

    @ParameterizedTest
    @MethodSource("linkedProgressCases")
    void 各口径选择较优自身值且按本任务目标计算完成率(
            String caliber, Submission sourceSubmission, Submission targetSubmission,
            ExpectedProgress sourceOwn, ExpectedProgress targetOwn,
            String sourceTarget, String targetTarget, String sourceRate, String targetRate) {
        LinkedFixture fixture = linkedFixture(caliber);
        TaskYear sourceYear = fixture.sourceYear();
        TaskYear targetYear = fixture.targetYear();
        sourceYear.setTarget(new BigDecimal(sourceTarget));
        targetYear.setTarget(new BigDecimal(targetTarget));
        stubRecalcSource(sourceYear);
        if ("STOCK".equals(caliber) || "GROWTH".equals(caliber)) {
            when(taskYearMapper.selectList(any())).thenReturn(List.of(sourceYear));
        }
        stubSubmissions(Map.of(101L, List.of(sourceSubmission), 102L, List.of(targetSubmission)));

        actualRecalcService.recalcTaskYear(101L);

        assertOwnProgress(sourceOwn, sourceYear);
        assertOwnProgress(targetOwn, targetYear);
        assertEffectiveProgress(sourceOwn, sourceYear);
        assertEffectiveProgress(sourceOwn, targetYear);
        assertDecimalEquals(sourceRate, sourceYear.getCompletionRate());
        assertDecimalEquals(targetRate, targetYear.getCompletionRate());
        assertNull(sourceYear.getLinkedSourceTaskYearId());
        assertEquals(false, sourceYear.getLinkedAchieved());
        assertEquals(101L, targetYear.getLinkedSourceTaskYearId());
        assertEquals(true, targetYear.getIsAchieved());
        assertEquals(true, targetYear.getLinkedAchieved());
        verify(taskYearMapper).updateById(sourceYear);
        verify(taskYearMapper).updateById(targetYear);
    }

    @Test
    void 关联链不递归且未重算邻居只回退读取自身缓存() {
        LinkedFixture fixture = linkedFixture("INCREMENT");
        TaskYear sourceYear = fixture.sourceYear();
        TaskYear targetYear = fixture.targetYear();
        TaskYear thirdYear = taskYear(105L, 2026);
        thirdYear.setTaskId(203L);
        thirdYear.setIndicatorId(303L);
        thirdYear.setOwnActualValue(new BigDecimal("2"));
        thirdYear.setActualValue(new BigDecimal("99"));
        when(completionLinkMapper.selectActiveByTaskId(202L))
                .thenReturn(List.of(link(201L, 202L), link(202L, 203L)));
        stubTaskYears(List.of(sourceYear, targetYear, thirdYear));
        stubRecalcSource(sourceYear);
        stubSubmissions(Map.of(101L, List.of(approvedAmount("4")), 102L, List.of()));

        actualRecalcService.recalcTaskYear(101L);

        assertDecimalEquals("4", sourceYear.getActualValue());
        assertNull(targetYear.getOwnActualValue());
        assertDecimalEquals("4", targetYear.getActualValue());
        assertEquals(101L, targetYear.getLinkedSourceTaskYearId());
        assertDecimalEquals("2", thirdYear.getOwnActualValue());
        assertDecimalEquals("99", thirdYear.getActualValue());
        verify(submissionMapper, times(2)).selectList(any());
        verify(taskYearMapper).updateById(sourceYear);
        verify(taskYearMapper).updateById(targetYear);
        verify(taskYearMapper, never()).updateById(thirdYear);
    }

    @Test
    void 新建关联时双侧重算全部年度既有进度() {
        LinkedFixture fixture = linkedFixture("INCREMENT");
        TaskYear source2026 = fixture.sourceYear();
        TaskYear target2026 = fixture.targetYear();
        TaskYear source2027 = taskYear(103L, 2027);
        source2027.setTarget(new BigDecimal("10"));
        TaskYear target2027 = taskYear(104L, 2027);
        target2027.setTaskId(202L);
        target2027.setIndicatorId(302L);
        target2027.setTarget(new BigDecimal("5"));
        List<TaskYear> allYears = List.of(source2026, target2026, source2027, target2027);
        when(taskYearMapper.selectByTaskIdsForUpdate(List.of(201L, 202L), null)).thenReturn(allYears);
        stubTaskYears(allYears);
        stubSubmissions(Map.of(
                101L, List.of(approvedAmount("4")),
                102L, List.of(approvedAmount("3")),
                103L, List.of(),
                104L, List.of(approvedAmount("6"))));

        actualRecalcService.syncExistingProgress(201L, 202L);

        assertDecimalEquals("4", source2026.getOwnActualValue());
        assertDecimalEquals("3", target2026.getOwnActualValue());
        assertDecimalEquals("4", source2026.getActualValue());
        assertDecimalEquals("4", target2026.getActualValue());
        assertEquals(101L, target2026.getLinkedSourceTaskYearId());
        assertNull(source2027.getOwnActualValue());
        assertDecimalEquals("6", source2027.getActualValue());
        assertDecimalEquals("6", target2027.getOwnActualValue());
        assertDecimalEquals("6", target2027.getActualValue());
        assertEquals(104L, source2027.getLinkedSourceTaskYearId());
        assertEquals(false, source2027.getLinkedAchieved());
        assertNull(target2027.getLinkedSourceTaskYearId());
        assertEquals(false, target2027.getLinkedAchieved());
        assertEquals(true, target2027.getIsAchieved());
        verify(taskYearMapper, times(4)).updateById(any(TaskYear.class));
    }

    @Test
    void 关联目标年度并发修改时返回10005() {
        LinkedFixture fixture = linkedFixture("INCREMENT");
        TaskYear sourceYear = fixture.sourceYear();
        TaskYear targetYear = fixture.targetYear();
        stubRecalcSource(sourceYear);
        stubSubmissions(Map.of(101L, List.of(approvedAmount("10")), 102L, List.of()));
        when(taskYearMapper.updateById(targetYear)).thenReturn(0);

        BizException error = assertThrows(BizException.class, () -> actualRecalcService.recalcTaskYear(101L));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(taskYearMapper).updateById(targetYear);
    }

    private static Stream<Arguments> linkedProgressCases() {
        return Stream.of(
                Arguments.of("STOCK", approvedTotal("120"), approvedTotal("110"),
                        progress("120", null, null, "120"), progress("110", null, null, "110"),
                        "200", "100", "0.6", "1.2"),
                Arguments.of("GROWTH", approvedTotal("120"), approvedTotal("110"),
                        progress("0.2", null, null, "120"), progress("0.1", null, null, "110"),
                        "200", "100", "0.6", "1.2"),
                Arguments.of("RATIO", approvedRatio("3", "4"), approvedRatio("1", "2"),
                        progress("0.75", "3", "4", null), progress("0.5", "1", "2", null),
                        "0.8", "0.6", "0.9375", "1.25"),
                Arguments.of("DIRECT_PERCENT", approvedPercent("0.8"), approvedPercent("0.6"),
                        progress("0.8", null, null, null), progress("0.6", null, null, null),
                        "0.5", "0.4", "1.6", "2"),
                Arguments.of("BOOLEAN", approvedBoolean(true), approvedBoolean(false),
                        progress("1", null, null, null), progress("0", null, null, null),
                        "1", "1", "1", "1"));
    }

    private static ExpectedProgress progress(String actualValue, String numerator,
                                             String denominator, String total) {
        return new ExpectedProgress(actualValue, numerator, denominator, total);
    }

    private static Submission approvedTotal(String total) {
        Submission submission = new Submission();
        submission.setStatus("APPROVED");
        submission.setApprovedTotal(new BigDecimal(total));
        return submission;
    }

    private static Submission approvedRatio(String numerator, String denominator) {
        Submission submission = new Submission();
        submission.setStatus("APPROVED");
        submission.setApprovedNumerator(new BigDecimal(numerator));
        submission.setApprovedDenominator(new BigDecimal(denominator));
        return submission;
    }

    private static Submission approvedPercent(String percent) {
        Submission submission = new Submission();
        submission.setStatus("APPROVED");
        submission.setApprovedPercent(new BigDecimal(percent));
        return submission;
    }

    private static Submission approvedBoolean(boolean achieved) {
        Submission submission = new Submission();
        submission.setStatus("APPROVED");
        submission.setApprovedAchieved(achieved);
        return submission;
    }

    private LinkedFixture linkedFixture(String caliber) {
        Task sourceTask = task(201L, 301L, 401L);
        Task targetTask = task(202L, 302L, 402L);
        Indicator sourceIndicator = indicator(301L, null);
        Indicator targetIndicator = indicator(302L, null);
        for (Indicator indicator : List.of(sourceIndicator, targetIndicator)) {
            indicator.setStatCaliber(caliber);
            indicator.setRollupMode("MANUAL");
            indicator.setBaselineValue(new BigDecimal("100"));
        }
        TaskYear sourceYear = taskYear(101L, 2026);
        sourceYear.setTarget(new BigDecimal("10"));
        TaskYear targetYear = taskYear(102L, 2026);
        targetYear.setTaskId(202L);
        targetYear.setIndicatorId(302L);
        targetYear.setTarget(new BigDecimal("5"));
        stubLinkedData(List.of(sourceTask, targetTask), List.of(sourceIndicator, targetIndicator),
                List.of(plan(401L), plan(402L)));
        lenient().when(completionLinkMapper.selectActiveByTaskId(201L)).thenReturn(List.of(link(201L, 202L)));
        lenient().when(completionLinkMapper.selectActiveByTaskId(202L)).thenReturn(List.of(link(201L, 202L)));
        stubTaskYears(List.of(sourceYear, targetYear));
        return new LinkedFixture(sourceYear, targetYear);
    }

    private void stubRecalcSource(TaskYear sourceYear) {
        when(taskYearMapper.selectById(sourceYear.getId())).thenReturn(sourceYear);
        when(taskYearMapper.selectByIdForUpdate(sourceYear.getId())).thenReturn(sourceYear);
    }

    private void stubTaskYears(List<TaskYear> taskYears) {
        lenient().when(taskYearMapper.selectByTaskIdAndYearForUpdate(any(), any())).thenAnswer(invocation -> {
            Long taskId = invocation.getArgument(0);
            Integer year = invocation.getArgument(1);
            return taskYears.stream()
                    .filter(taskYear -> taskYear.getTaskId().equals(taskId) && taskYear.getYear().equals(year))
                    .findFirst().orElse(null);
        });
    }

    private void stubSubmissions(Map<Long, List<Submission>> submissions) {
        List<List<Submission>> ordered = submissions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        int[] index = {0};
        when(submissionMapper.selectList(any())).thenAnswer(invocation ->
                ordered.get(index[0]++ % ordered.size()));
    }

    private void stubLinkedData(List<Task> tasks, List<Indicator> indicators, List<Plan> plans) {
        int[] taskIndex = {0};
        int[] indicatorIndex = {0};
        int[] planIndex = {0};
        lenient().when(taskMapper.selectOne(any())).thenAnswer(invocation ->
                tasks.get(taskIndex[0]++ % tasks.size()));
        lenient().when(indicatorMapper.selectOne(any())).thenAnswer(invocation ->
                indicators.get(indicatorIndex[0]++ % indicators.size()));
        lenient().when(planMapper.selectOne(any())).thenAnswer(invocation ->
                plans.get(planIndex[0]++ % plans.size()));
    }

    private void assertOwnProgress(ExpectedProgress expected, TaskYear taskYear) {
        assertNullableDecimal(expected.actualValue(), taskYear.getOwnActualValue());
        assertNullableDecimal(expected.numerator(), taskYear.getOwnActualNumerator());
        assertNullableDecimal(expected.denominator(), taskYear.getOwnActualDenominator());
        assertNullableDecimal(expected.total(), taskYear.getOwnActualTotal());
    }

    private void assertEffectiveProgress(ExpectedProgress expected, TaskYear taskYear) {
        assertNullableDecimal(expected.actualValue(), taskYear.getActualValue());
        assertNullableDecimal(expected.numerator(), taskYear.getActualNumerator());
        assertNullableDecimal(expected.denominator(), taskYear.getActualDenominator());
        assertNullableDecimal(expected.total(), taskYear.getActualTotal());
    }

    private void assertNullableDecimal(String expected, BigDecimal actual) {
        if (expected == null) {
            assertNull(actual);
        } else {
            assertDecimalEquals(expected, actual);
        }
    }

    private record LinkedFixture(TaskYear sourceYear, TaskYear targetYear) {
    }

    private record ExpectedProgress(String actualValue, String numerator, String denominator, String total) {
    }

    private Task task(Long id, Long indicatorId, Long planId) {
        Task task = new Task();
        task.setId(id);
        task.setIndicatorId(indicatorId);
        task.setPlanId(planId);
        task.setDeleted(0L);
        return task;
    }

    private Plan plan(Long id) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        plan.setDeleted(0L);
        return plan;
    }

    private TaskCompletionLink link(Long taskAId, Long taskBId) {
        TaskCompletionLink link = new TaskCompletionLink();
        link.setTaskAId(taskAId);
        link.setTaskBId(taskBId);
        link.setDeleted(0L);
        return link;
    }

    private Submission approvedAmount(String amount) {
        Submission submission = new Submission();
        submission.setStatus("APPROVED");
        submission.setApprovedAmount(new BigDecimal(amount));
        return submission;
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
