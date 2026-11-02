package edu.fzrj.metrics.module.stats.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.milestone.entity.Milestone;
import edu.fzrj.metrics.module.milestone.mapper.MilestoneMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanReadScopeService;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.stats.dto.GanttDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskDependency;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskDependencyService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GanttServiceTest {

    @Mock
    private PlanService planService;
    @Mock
    private PlanReadScopeService planReadScopeService;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private IndicatorYearTargetMapper yearTargetMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private MilestoneMapper milestoneMapper;
    @Mock
    private TaskDependencyService dependencyService;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private UserRefResolver userRefResolver;
    @InjectMocks
    private GanttService ganttService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, IndicatorYearTarget.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
        TableInfoHelper.initTableInfo(assistant, Milestone.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
    }

    @Test
    void 历史A加与直承B只算一次且占比按合计重算() {
        Plan plan = plan(11L, 2026, 2026);
        Indicator a = indicator(1L, null, "A01", "RATIO");
        a.setRollupMode("AUTO_SUM");
        a.setTotalTarget(new BigDecimal("0.5"));
        Indicator aPlus = indicator(2L, 1L, "A01-1", "RATIO");
        Task direct = task(101L, 1L, 1L, 31L, "A01-C01", "DISTRIBUTED", false);
        Task legacy = task(102L, 2L, 1L, 32L, "A01-1-C02", "IN_PROGRESS", true);
        Task draft = task(103L, 1L, 1L, 33L, "A01-C03", "PENDING_DISTRIBUTION", false);
        List<Task> tasks = List.of(direct, legacy, draft);
        TaskYear directYear = taskYear(101L, 2026, "0.5", "0.5", null, "1", "2");
        TaskYear legacyYear = taskYear(102L, 2026, "0.5", "0.25", null, "1", "4");
        TaskYear draftYear = taskYear(103L, 2026, null, "0", null, "0", "10");
        IndicatorYearTarget target = target(1L, 2026, "0.5");
        stubBasic(plan, List.of(a, aPlus), tasks, Set.of(1L, 2L),
                List.of(directYear, legacyYear, draftYear), List.of(target));

        GanttDtos.View view = ganttService.get(11L, null);

        assertEquals(4, view.nodes().size());
        assertTrue(view.nodes().stream().noneMatch(node -> "A01-1".equals(node.code())));
        GanttDtos.Node aNode = view.nodes().get(0);
        assertDecimal("0.125000", aNode.years().get(0).actualValue());
        assertDecimal("2", aNode.years().get(0).actualNumerator());
        assertDecimal("16", aNode.years().get(0).actualDenominator());
        assertDecimal("0.250000", aNode.years().get(0).completionRate());
        assertEquals(2026, aNode.startYear());
        assertTrue(view.nodes().stream().anyMatch(node -> node.isHistorical()));
        GanttDtos.Node draftNode = view.nodes().stream().filter(node -> node.id().equals(103L)).findFirst().orElseThrow();
        assertEquals("PENDING_DISTRIBUTION", draftNode.status());
        assertDecimal("0", draftNode.years().get(0).actualValue());
        assertNull(draftNode.years().get(0).target());
        verify(taskYearMapper, times(1)).selectList(any());
        verify(yearTargetMapper, times(1)).selectList(any());
        verify(milestoneMapper, times(1)).selectList(any());
        verify(dependencyService, times(1)).findByPlan(11L);
    }

    @ParameterizedTest
    @MethodSource("calibers")
    void 六种口径复用指标计算规则(String caliber, String actual, String total,
                                  String numerator, String denominator, String expectedActual) {
        Plan plan = plan(11L, 2026, 2026);
        Indicator a = indicator(1L, null, "A01", caliber);
        a.setRollupMode("AUTO_SUM");
        a.setBaselineValue(new BigDecimal("100"));
        a.setTotalTarget(BigDecimal.ONE);
        Task task = task(101L, 1L, 1L, 31L, "A01-C01", "DISTRIBUTED", false);
        TaskYear year = taskYear(101L, 2026, "1", actual, total, numerator, denominator);
        stubBasic(plan, List.of(a), List.of(task), Set.of(1L), List.of(year), List.of(target(1L, 2026, "1")));

        GanttDtos.YearCell cell = ganttService.get(11L, null).nodes().get(0).years().get(0);

        assertDecimal(expectedActual, cell.actualValue());
        if (total == null) {
            assertNull(cell.actualTotal());
        } else {
            assertDecimal(total, cell.actualTotal());
        }
        if ("RATIO".equals(caliber)) {
            assertDecimal(numerator, cell.actualNumerator());
            assertDecimal(denominator, cell.actualDenominator());
        }
    }

    static Stream<Arguments> calibers() {
        return Stream.of(
                Arguments.of("INCREMENT", "2", null, null, null, "2"),
                Arguments.of("STOCK", "8", "8", null, null, "8"),
                Arguments.of("GROWTH", "0.1", "110", null, null, "0.100000"),
                Arguments.of("RATIO", "0.5", null, "1", "2", "0.500000"),
                Arguments.of("DIRECT_PERCENT", "0.4", null, null, null, "0.4"),
                Arguments.of("BOOLEAN", "1", null, null, null, "1"));
    }

    @Test
    void 手工认定优先于可见任务汇总() {
        Plan plan = plan(11L, 2026, 2026);
        Indicator a = indicator(1L, null, "A01", "DIRECT_PERCENT");
        a.setRollupMode("MANUAL");
        a.setTotalTarget(BigDecimal.ONE);
        Task task = task(101L, 1L, 1L, 31L, "A01-C01", "DISTRIBUTED", false);
        TaskYear year = taskYear(101L, 2026, "1", "0.9", null, null, null);
        IndicatorYearTarget target = target(1L, 2026, "1");
        target.setActualValue(new BigDecimal("0.1"));
        target.setManualActualValue(new BigDecimal("0.77"));
        stubBasic(plan, List.of(a), List.of(task), Set.of(1L), List.of(year), List.of(target));

        GanttDtos.YearCell cell = ganttService.get(11L, null).nodes().get(0).years().get(0);

        assertDecimal("0.77", cell.actualValue());
        assertDecimal("0.770000", cell.completionRate());
    }

    @Test
    void 单A只返回可见依赖与里程碑且不泄露其他任务() {
        Plan plan = plan(11L, 2026, 2028);
        Indicator a1 = indicator(1L, null, "A01", "INCREMENT");
        Indicator a2 = indicator(2L, null, "A02", "INCREMENT");
        Task first = task(101L, 1L, 1L, 31L, "A01-C01", "DISTRIBUTED", false);
        Task second = task(102L, 1L, 1L, 31L, "A01-C02", "DISTRIBUTED", false);
        Task hidden = task(103L, 2L, 2L, 32L, "A02-C02", "DISTRIBUTED", false);
        List<Task> allTasks = List.of(first, second, hidden);
        TaskDependency visible = dependency(901L, 101L, 102L);
        TaskDependency omitted = dependency(902L, 102L, 103L);
        Milestone hiddenMilestone = new Milestone();
        hiddenMilestone.setId(801L);
        hiddenMilestone.setTaskId(103L);
        hiddenMilestone.setName("隐藏里程碑");
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectOne(any())).thenReturn(a1);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(a1, a2));
        when(planReadScopeService.visibleTasks(11L)).thenReturn(allTasks);
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of(1L, 2L));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(
                taskYear(101L, 2026, "1", null, null, null, null),
                taskYear(102L, 2027, "1", null, null, null, null),
                taskYear(103L, 2028, "1", null, null, null, null)));
        when(yearTargetMapper.selectList(any())).thenReturn(List.of());
        when(dependencyService.findByPlan(11L)).thenReturn(List.of(visible, omitted));
        when(milestoneMapper.selectList(any())).thenReturn(List.of(hiddenMilestone));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit(31L, "C01"), unit(32L, "C02")));
        when(userRefResolver.mapOf(any())).thenReturn(Map.of());

        GanttDtos.View view = ganttService.get(11L, 1L);

        assertEquals(3, view.nodes().size());
        assertTrue(view.nodes().stream().noneMatch(node -> node.id().equals(103L) || node.id().equals(2L)));
        assertEquals(List.of(901L), view.dependencies().stream().map(item -> item.id()).toList());
        assertEquals(1, view.graph().omittedDependencies());
        assertTrue(view.graph().truncated());
        assertEquals("OK", view.criticalPath().status());
        assertEquals(List.of(101L, 102L), view.criticalPath().taskIds());
        assertEquals(List.of(901L), view.criticalPath().dependencyIds());
        assertTrue(view.milestones().isEmpty());
    }

    @Test
    void 历史A加里程碑归集到父A() {
        Plan plan = plan(11L, 2026, 2026);
        Indicator a = indicator(1L, null, "A01", "INCREMENT");
        Indicator aPlus = indicator(2L, 1L, "A01-1", "INCREMENT");
        Task task = task(101L, 2L, 1L, 31L, "A01-1-C01", "DISTRIBUTED", true);
        Milestone milestone = new Milestone();
        milestone.setId(801L);
        milestone.setIndicatorId(2L);
        milestone.setName("历史里程碑");
        milestone.setStatus("NOT_STARTED");
        milestone.setVersion(3);
        stubBasic(plan, List.of(a, aPlus), List.of(task), Set.of(1L, 2L),
                List.of(taskYear(101L, 2026, "1", null, null, null, null)), List.of());
        when(milestoneMapper.selectList(any())).thenReturn(List.of(milestone));

        GanttDtos.View view = ganttService.get(11L, null);

        assertEquals(1, view.milestones().size());
        assertEquals(1L, view.milestones().get(0).indicatorId());
        assertNull(view.milestones().get(0).taskId());
        assertEquals(3, view.milestones().get(0).version());
    }

    @Test
    void 单A越权与跨计划保持现有错误码() {
        Plan plan = plan(11L, 2026, 2026);
        Indicator other = indicator(2L, null, "A02", "INCREMENT");
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectOne(any())).thenReturn(other);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(other));
        when(planReadScopeService.visibleTasks(11L)).thenReturn(List.of());
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of());

        BizException denied = assertThrows(BizException.class, () -> ganttService.get(11L, 2L));
        assertEquals(20005, denied.getErrorCode().getCode());

        other.setPlanId(12L);
        BizException crossPlan = assertThrows(BizException.class, () -> ganttService.get(11L, 2L));
        assertEquals(10001, crossPlan.getErrorCode().getCode());
    }

    private void stubBasic(Plan plan, List<Indicator> indicators, List<Task> tasks,
                           Set<Long> visibleIndicators, List<TaskYear> years, List<IndicatorYearTarget> targets) {
        when(planService.requirePlan(plan.getId())).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(indicators);
        when(planReadScopeService.visibleTasks(plan.getId())).thenReturn(tasks);
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(visibleIndicators);
        when(taskYearMapper.selectList(any())).thenReturn(years);
        when(yearTargetMapper.selectList(any())).thenReturn(targets);
        when(dependencyService.findByPlan(plan.getId())).thenReturn(List.of());
        when(milestoneMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(tasks.stream().map(Task::getUnitId).distinct()
                .map(id -> unit(id, "C" + id)).toList());
        when(userRefResolver.mapOf(any())).thenReturn(Map.of());
    }

    private static Plan plan(Long id, int start, int end) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setName("测试计划");
        plan.setStartYear(start);
        plan.setEndYear(end);
        plan.setStatus("ACTIVE");
        return plan;
    }

    private static Indicator indicator(Long id, Long parentId, String code, String caliber) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setPlanId(11L);
        indicator.setParentId(parentId);
        indicator.setLevel(parentId == null ? "A" : "A_PLUS");
        indicator.setCode(code);
        indicator.setName(code + " 指标");
        indicator.setStatCaliber(caliber);
        indicator.setTargetDirection("HIGHER_BETTER");
        indicator.setSort(0);
        indicator.setStatus("DISTRIBUTED");
        return indicator;
    }

    private static Task task(Long id, Long indicatorId, Long topId, Long unitId,
                             String code, String status, boolean historical) {
        Task task = new Task();
        task.setId(id);
        task.setPlanId(11L);
        task.setIndicatorId(indicatorId);
        task.setTopIndicatorId(topId);
        task.setUnitId(unitId);
        task.setCode(code);
        task.setStatus(status);
        task.setIsHistorical(historical);
        return task;
    }

    private static TaskYear taskYear(Long taskId, Integer year, String target, String actual,
                                     String total, String numerator, String denominator) {
        TaskYear row = new TaskYear();
        row.setId(taskId * 100 + year);
        row.setTaskId(taskId);
        row.setYear(year);
        row.setTarget(target == null ? null : new BigDecimal(target));
        row.setActualValue(actual == null ? null : new BigDecimal(actual));
        row.setActualTotal(total == null ? null : new BigDecimal(total));
        row.setActualNumerator(numerator == null ? null : new BigDecimal(numerator));
        row.setActualDenominator(denominator == null ? null : new BigDecimal(denominator));
        row.setTargetVersion(1);
        row.setFrozenAmount(BigDecimal.ZERO);
        row.setYearStatus("IN_PROGRESS");
        return row;
    }

    private static IndicatorYearTarget target(Long indicatorId, Integer year, String value) {
        IndicatorYearTarget target = new IndicatorYearTarget();
        target.setId(indicatorId * 100 + year);
        target.setIndicatorId(indicatorId);
        target.setYear(year);
        target.setTarget(new BigDecimal(value));
        return target;
    }

    private static OrgUnit unit(Long id, String code) {
        OrgUnit unit = new OrgUnit();
        unit.setId(id);
        unit.setCode(code);
        unit.setName(code + " 学院");
        return unit;
    }

    private static TaskDependency dependency(Long id, Long predecessor, Long successor) {
        TaskDependency dependency = new TaskDependency();
        dependency.setId(id);
        dependency.setPlanId(11L);
        dependency.setPredecessorTaskId(predecessor);
        dependency.setSuccessorTaskId(successor);
        dependency.setDependencyType("FS");
        dependency.setVersion(0);
        return dependency;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but got " + actual);
    }
}
