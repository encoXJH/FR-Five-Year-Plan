package edu.fzrj.metrics.module.stats.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
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
