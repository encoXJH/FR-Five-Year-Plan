package edu.fzrj.metrics.module.stats.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.stats.dto.StatsDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysSettingMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private PlanService planService;
    @Mock
    private PlanYearMapper planYearMapper;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private IndicatorYearTargetMapper yearTargetMapper;
    @Mock
    private DirectionMapper directionMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private SysSettingMapper sysSettingMapper;
    @Mock
    private DataScopeService dataScopeService;

    @InjectMocks
    private StatsService statsService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, IndicatorYearTarget.class);
        TableInfoHelper.initTableInfo(assistant, Direction.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 默认统计年度取当前自然年而不是规划末年() {
        Plan plan = new Plan();
        plan.setStartYear(2026);
        plan.setEndYear(2030);

        Integer resolved = statsService.resolveYear(plan, null);

        assertEquals(Math.max(2026, Math.min(2030, java.time.LocalDate.now().getYear())), resolved);
    }

    @Test
    void 手工存量指标使用认定总量计算年度完成率() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        Indicator a = indicator(1L, null, "A01", "STOCK");
        a.setRollupMode("MANUAL");
        a.setTotalTarget(new BigDecimal("100"));
        IndicatorYearTarget row = target(501L, 1L, "STOCK", "80", "0", "1");
        row.setTarget(new BigDecimal("100"));
        row.setManualActualValue(new BigDecimal("80"));
        row.setActualTotal(new BigDecimal("80"));
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(a));
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(row));

        StatsDtos.TreeNode result = statsService.tree(11L, 2026, StatsService.ScopeFilter.allData()).get(0);

        assertEquals(0, new BigDecimal("80").compareTo(result.actualTotal()));
        assertEquals(0, new BigDecimal("0.8").compareTo(result.completionRate()));
        assertEquals(0, new BigDecimal("0.8").compareTo(result.cumulativeRate()));
    }

    @Test
    void 范围统计累计值仅归集可见任务() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        Indicator a = indicator(1L, null, "A01", "INCREMENT");
        a.setRollupMode("AUTO_SUM");
        a.setTotalTarget(BigDecimal.TEN);
        Task visible = task(101L, 1L, 31L, "A01-C01");
        TaskYear visibleYear = taskYear(1001L, 101L, 1L, 31L, "INCREMENT", "2", "0", "1");
        IndicatorYearTarget allSchoolCache = target(501L, 1L, "INCREMENT", "9", "0", "1");
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(a));
        when(taskMapper.selectList(any())).thenReturn(List.of(visible));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(visibleYear), List.of(visibleYear));
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(allSchoolCache));

        StatsDtos.TreeNode result = statsService.tree(11L, 2026,
                new StatsService.ScopeFilter(false, 31L, java.util.Set.of())).get(0);

        assertEquals(0, new BigDecimal("2").compareTo(result.actualValue()));
        assertEquals(0, new BigDecimal("2").compareTo(result.cumulativeActual()));
        assertEquals(0, new BigDecimal("0.2").compareTo(result.cumulativeRate()));
    }

    @Test
    void 趋势返回占比分子分母() {
        LoginUser user = new LoginUser(201L, "admin", "管理员", null, List.of("ADMIN"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        Indicator indicator = indicator(1L, null, "A01", "RATIO");
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        IndicatorYearTarget row = target(501L, 1L, "RATIO", "0.586667", "88", "150");
        when(indicatorMapper.selectOne(any())).thenReturn(indicator);
        when(dataScopeService.isAllData(any())).thenReturn(true);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(row));

        StatsDtos.TrendYear year = statsService.trend(1L).years().get(0);

        assertEquals(0, new BigDecimal("88").compareTo(year.actualNumerator()));
        assertEquals(0, new BigDecimal("150").compareTo(year.actualDenominator()));
        assertEquals(0, new BigDecimal("0.586667").compareTo(year.actualValue()));
    }

    @Test
    void 混合分工统计树扁平为A到B且完成值不重不漏() {
        StatsDtos.TreeNode a = mixedTree("INCREMENT");

        assertEquals(0, new BigDecimal("8").compareTo(a.actualValue()));
        assertEquals(2, a.children().size());
        assertTrue(a.children().stream().allMatch(node -> "B".equals(node.level())));
        assertEquals(List.of("A01-C01", "A01-1-C02"),
                a.children().stream().map(StatsDtos.TreeNode::code).toList());
    }

    @Test
    void 混合分工占比仍按分子合计除分母合计() {
        StatsDtos.TreeNode a = mixedTree("RATIO");

        assertEquals(0, new BigDecimal("0.333333").compareTo(a.actualValue()));
        assertEquals(2, a.children().size());
        assertTrue(a.children().stream().noneMatch(node -> "A_PLUS".equals(node.level())));
    }

    @Test
    void 增长率统计树返回年度完成总量() {
        StatsDtos.TreeNode a = mixedTree("GROWTH");

        assertEquals(0, new BigDecimal("8").compareTo(a.actualTotal()));
        assertEquals(0, new BigDecimal("3").compareTo(a.children().get(0).actualTotal()));
        assertEquals(0, new BigDecimal("5").compareTo(a.children().get(1).actualTotal()));
    }

    @Test
    void 五年累计不受所选年度零目标影响() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        Indicator a = indicator(1L, null, "A01", "INCREMENT");
        a.setRollupMode("AUTO_SUM");
        a.setTotalTarget(BigDecimal.TEN);
        Task task = task(101L, 1L, 31L, "A01-C01");
        TaskYear y2026 = taskYear(1001L, 101L, 1L, 31L, "INCREMENT", "4", "0", "1");
        TaskYear y2027 = taskYear(1002L, 101L, 1L, 31L, "INCREMENT", "0", "0", "1");
        y2027.setYear(2027);
        y2027.setTarget(BigDecimal.ZERO);
        y2027.setActualValue(null);
        List<TaskYear> allYears = List.of(y2026, y2027);

        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(a));
        when(directionMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit(31L, "C01")));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(y2027), allYears);

        List<StatsDtos.UnitStat> result = statsService.byUnit(11L, 2027, StatsService.ScopeFilter.allData());

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).taskCount());
        assertEquals(0, new BigDecimal("0.4").compareTo(result.get(0).cumulativeRate()));
    }

    @Test
    void 概览与指标统计将无完成值按零计入且结果一致() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        Indicator first = indicator(1L, null, "A01", "INCREMENT");
        first.setRollupMode("AUTO_SUM");
        first.setTotalTarget(BigDecimal.TEN);
        Indicator second = indicator(2L, null, "A02", "INCREMENT");
        second.setRollupMode("AUTO_SUM");
        second.setTotalTarget(BigDecimal.TEN);
        Task firstTask = task(101L, 1L, 31L, "A01-C01");
        Task secondTask = task(102L, 2L, 32L, "A02-C02");
        TaskYear completed = taskYear(1001L, 101L, 1L, 31L, "INCREMENT", "4", "0", "1");
        completed.setYearStatus("IN_PROGRESS");
        TaskYear noData = taskYear(1002L, 102L, 2L, 32L, "INCREMENT", "0", "0", "1");
        noData.setActualValue(null);
        noData.setYearStatus("IN_PROGRESS");
        List<TaskYear> rows = List.of(completed, noData);

        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(first, second));
        when(directionMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit(31L, "C01"), unit(32L, "C02")));
        when(taskMapper.selectList(any())).thenReturn(List.of(firstTask, secondTask));
        when(taskYearMapper.selectList(any())).thenReturn(rows);
        when(yearTargetMapper.selectList(any())).thenReturn(List.of());
        when(planYearMapper.selectList(any())).thenReturn(List.of());

        StatsDtos.Overview overview = statsService.overview(11L, 2026, StatsService.ScopeFilter.allData());
        List<StatsDtos.IndicatorStat> indicators = statsService.byIndicator(
                11L, 2026, StatsService.ScopeFilter.allData());

        assertEquals(0, new BigDecimal("0.2").compareTo(overview.cumulativeCompletionRate()));
        assertEquals(0, new BigDecimal("0.4").compareTo(indicators.get(0).cumulativeRate()));
        assertEquals(0, BigDecimal.ZERO.compareTo(indicators.get(1).cumulativeRate()));
    }

    @Test
    void 指标五年累计占比按分子分母汇总而非任务完成率平均() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        Indicator a = indicator(1L, null, "A01", "RATIO");
        a.setRollupMode("AUTO_SUM");
        a.setTotalTarget(new BigDecimal("0.8"));
        Task firstTask = task(101L, 1L, 31L, "A01-C01");
        Task secondTask = task(102L, 1L, 32L, "A01-C02");
        TaskYear firstYear = taskYear(1001L, 101L, 1L, 31L, "RATIO", "0", "90", "100");
        firstYear.setTarget(new BigDecimal("0.8"));
        TaskYear secondYear = taskYear(1002L, 102L, 1L, 32L, "RATIO", "0", "30", "50");
        secondYear.setTarget(new BigDecimal("0.8"));
        List<TaskYear> rows = List.of(firstYear, secondYear);

        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(a));
        when(directionMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit(31L, "C01"), unit(32L, "C02")));
        when(taskMapper.selectList(any())).thenReturn(List.of(firstTask, secondTask));
        when(taskYearMapper.selectList(any())).thenReturn(rows);

        StatsDtos.IndicatorStat result = statsService.byIndicator(
                11L, 2026, StatsService.ScopeFilter.allData()).get(0);

        assertEquals(0, BigDecimal.ONE.compareTo(result.cumulativeRate()));
    }

    @Test
    void 纯学院未下发也能访问统计报表() {
        login(new LoginUser(201L, "unit", "学院负责人", 31L, List.of("UNIT_OWNER"), List.of()));
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of());
        when(directionMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(planYearMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, statsService.overview(11L, 2026).taskCount());
        assertTrue(statsService.tree(11L, 2026).isEmpty());
        assertTrue(statsService.byUnit(11L, 2026).isEmpty());
        assertTrue(statsService.byIndicator(11L, 2026).isEmpty());
    }

    @Test
    void 学院统计只归集本单元任务() {
        login(new LoginUser(201L, "unit", "学院负责人", 31L, List.of("UNIT_OWNER"), List.of()));
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        Indicator a = indicator(1L, null, "A01", "INCREMENT");
        a.setRollupMode("AUTO_SUM");
        a.setTotalTarget(BigDecimal.TEN);
        Task own = task(101L, 1L, 31L, "A01-01");
        Task other = task(102L, 1L, 32L, "A01-02");
        TaskYear ownYear = taskYear(1001L, 101L, 1L, 31L, "INCREMENT", "4", "0", "1");
        TaskYear otherYear = taskYear(1002L, 102L, 1L, 32L, "INCREMENT", "10", "0", "1");
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(a));
        when(directionMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit(31L, "01"), unit(32L, "02")));
        when(taskMapper.selectList(any())).thenReturn(List.of(own, other));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(ownYear), List.of(ownYear));
        when(yearTargetMapper.selectList(any())).thenReturn(List.of());
        when(planYearMapper.selectList(any())).thenReturn(List.of());

        List<StatsDtos.UnitStat> units = statsService.byUnit(11L, 2026);
        List<StatsDtos.IndicatorStat> indicators = statsService.byIndicator(11L, 2026);

        assertEquals(1, units.size());
        assertEquals(31L, units.get(0).unit().id());
        assertEquals(1, units.get(0).taskCount());
        assertEquals(0, new BigDecimal("0.4").compareTo(units.get(0).cumulativeRate()));
        assertEquals(1, indicators.size());
        assertEquals(1, indicators.get(0).taskCount());
        assertEquals(0, new BigDecimal("0.4").compareTo(indicators.get(0).cumulativeRate()));
    }

    @Test
    void 多角色用户按数据范围并集访问统计() {
        login(new LoginUser(201L, "owner", "负责人", 31L,
                List.of("UNIT_OWNER", "SCHOOL_OWNER"), List.of()));
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        when(dataScopeService.isOwnerScope(any())).thenReturn(true);
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of());

        StatsDtos.Overview result = statsService.overview(11L, 2026);

        assertEquals(0, result.taskCount());
    }

    @Test
    void 学院占比趋势按本单元分子分母归集且不采用全校缓存() {
        login(new LoginUser(201L, "unit", "学院负责人", 31L, List.of("UNIT_OWNER"), List.of()));
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Indicator indicator = indicator(1L, null, "A01", "RATIO");
        indicator.setRollupMode("AUTO_SUM");
        indicator.setTotalTarget(new BigDecimal("0.8"));
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        Task own = task(101L, 1L, 31L, "A01-01");
        Task other = task(102L, 1L, 32L, "A01-02");
        TaskYear ownYear = taskYear(1001L, 101L, 1L, 31L, "RATIO", "0", "90", "100");
        IndicatorYearTarget allSchoolCache = target(501L, 1L, "RATIO", "0.99", "99", "100");
        allSchoolCache.setTarget(new BigDecimal("0.8"));
        when(indicatorMapper.selectOne(any())).thenReturn(indicator);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator));
        when(directionMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit(31L, "01"), unit(32L, "02")));
        when(taskMapper.selectList(any())).thenReturn(List.of(own, other));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(ownYear), List.of(ownYear));
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(allSchoolCache));
        when(planYearMapper.selectList(any())).thenReturn(List.of());

        StatsDtos.TrendView result = statsService.trend(1L);

        StatsDtos.TrendYear year = result.years().get(0);
        assertEquals(0, new BigDecimal("90").compareTo(year.actualNumerator()));
        assertEquals(0, new BigDecimal("100").compareTo(year.actualDenominator()));
        assertEquals(0, new BigDecimal("0.9").compareTo(year.actualValue()));
        assertEquals(0, new BigDecimal("0.9").compareTo(result.cumulativeActual()));
    }

    private void login(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private StatsDtos.TreeNode mixedTree(String caliber) {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);

        Indicator a = indicator(1L, null, "A01", caliber);
        a.setRollupMode("AUTO_SUM");
        a.setTotalTarget(BigDecimal.TEN);
        Indicator aPlus = indicator(2L, 1L, "A01-1", caliber);

        OrgUnit c01 = unit(31L, "C01");
        OrgUnit c02 = unit(32L, "C02");
        Task direct = task(101L, 1L, 31L, "A01-C01");
        Task legacy = task(102L, 2L, 32L, "A01-1-C02");
        TaskYear directYear = taskYear(1001L, 101L, 1L, 31L, caliber, "3", "1", "2");
        TaskYear legacyYear = taskYear(1002L, 102L, 2L, 32L, caliber, "5", "1", "4");

        IndicatorYearTarget aTarget = target(501L, 1L, caliber, "8", "2", "6");
        IndicatorYearTarget apTarget = target(502L, 2L, caliber, "5", "1", "4");

        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(a, aPlus));
        when(directionMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(c01, c02));
        when(taskMapper.selectList(any())).thenReturn(List.of(direct, legacy));
        when(taskYearMapper.selectList(any())).thenReturn(
                List.of(directYear, legacyYear), List.of(directYear, legacyYear));
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(aTarget, apTarget));
        when(planYearMapper.selectList(any())).thenReturn(List.of());

        return statsService.tree(11L, 2026, StatsService.ScopeFilter.allData()).get(0);
    }

    private Indicator indicator(Long id, Long parentId, String code, String caliber) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setPlanId(11L);
        indicator.setParentId(parentId);
        indicator.setLevel(parentId == null ? "A" : "A_PLUS");
        indicator.setCode(code);
        indicator.setName(code + " 指标");
        indicator.setNature("CONSTRAINT");
        indicator.setIsQuantifiable(true);
        indicator.setStatCaliber(caliber);
        indicator.setTargetDirection("HIGHER_BETTER");
        indicator.setSort(0);
        return indicator;
    }

    private OrgUnit unit(Long id, String code) {
        OrgUnit unit = new OrgUnit();
        unit.setId(id);
        unit.setCode(code);
        unit.setName(code);
        return unit;
    }

    private Task task(Long id, Long indicatorId, Long unitId, String code) {
        Task task = new Task();
        task.setId(id);
        task.setPlanId(11L);
        task.setIndicatorId(indicatorId);
        task.setTopIndicatorId(1L);
        task.setUnitId(unitId);
        task.setCode(code);
        task.setStatus("DISTRIBUTED");
        task.setIsHistorical(false);
        return task;
    }

    private TaskYear taskYear(Long id, Long taskId, Long indicatorId, Long unitId,
                              String caliber, String actual, String numerator, String denominator) {
        TaskYear year = new TaskYear();
        year.setId(id);
        year.setTaskId(taskId);
        year.setPlanId(11L);
        year.setIndicatorId(indicatorId);
        year.setUnitId(unitId);
        year.setYear(2026);
        year.setTarget(BigDecimal.TEN);
        if ("RATIO".equals(caliber)) {
            year.setActualNumerator(new BigDecimal(numerator));
            year.setActualDenominator(new BigDecimal(denominator));
            year.setActualValue(year.getActualNumerator().divide(year.getActualDenominator(), 6,
                    java.math.RoundingMode.HALF_UP));
        } else {
            year.setActualValue(new BigDecimal(actual));
            if ("GROWTH".equals(caliber)) {
                year.setActualTotal(new BigDecimal(actual));
            }
        }
        return year;
    }

    private IndicatorYearTarget target(Long id, Long indicatorId, String caliber,
                                       String actual, String numerator, String denominator) {
        IndicatorYearTarget target = new IndicatorYearTarget();
        target.setId(id);
        target.setIndicatorId(indicatorId);
        target.setYear(2026);
        target.setTarget(BigDecimal.TEN);
        if ("RATIO".equals(caliber)) {
            target.setActualNumerator(new BigDecimal(numerator));
            target.setActualDenominator(new BigDecimal(denominator));
            target.setActualValue(target.getActualNumerator().divide(target.getActualDenominator(), 6,
                    java.math.RoundingMode.HALF_UP));
        } else {
            target.setActualValue(new BigDecimal(actual));
        }
        return target;
    }
}
