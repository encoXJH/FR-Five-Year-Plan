package edu.fzrj.metrics.module.indicator.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanReadScopeService;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorServiceTest {

    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private IndicatorYearTargetMapper yearTargetMapper;
    @Mock
    private DirectionMapper directionMapper;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private PlanService planService;
    @Mock
    private UserRefResolver userRefResolver;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private PlanReadScopeService planReadScopeService;

    @InjectMocks
    private IndicatorService indicatorService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, IndicatorYearTarget.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
    }

    @Test
    void 历史FINALIZED且未下发仍可编辑() {
        Indicator indicator = indicator(2L, 1L, "A_PLUS", "A01-1", "FINALIZED", "10", null);
        when(indicatorMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectCount(any())).thenReturn(0L);

        assertDoesNotThrow(() -> indicatorService.requireSubtreeEditable(indicator));
    }

    @Test
    void 父A下属部分下发时禁止绕过生效保护() {
        Indicator parent = indicator(1L, null, "A", "A01", "DRAFT", "10", "AUTO_SUM");
        when(indicatorMapper.selectCount(any())).thenReturn(1L);

        BizException error = assertThrows(BizException.class,
                () -> indicatorService.requireSubtreeEditable(parent));

        assertEquals(50003, error.getErrorCode().getCode());
        verify(taskMapper, never()).selectCount(any());
    }

    @Test
    void 下发前完整性校验返回具体缺项() {
        Plan plan = plan();
        Indicator indicator = indicator(1L, null, "A", "A01", "DRAFT", "10", "MANUAL");
        when(yearTargetMapper.selectList(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class,
                () -> indicatorService.validateForDistribution(plan, List.of(indicator)));

        assertEquals(10001, error.getErrorCode().getCode());
        assertFalse(error.getErrors().isEmpty());
        assertEquals("A01.yearTargets[2026]", error.getErrors().get(0).getField());
    }

    @Test
    void 下发前复核年度目标五年守恒40002() {
        Plan plan = plan();
        Indicator indicator = indicator(1L, null, "A", "A01", "DRAFT", "10", "MANUAL");
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(target(1L, 2026, "9")));

        BizException error = assertThrows(BizException.class,
                () -> indicatorService.validateForDistribution(plan, List.of(indicator)));

        assertEquals(40002, error.getErrorCode().getCode());
        assertEquals("A01.yearTargets", error.getErrors().get(0).getField());
    }

    @Test
    void 纯A直分下发不再触发A加守恒40003() {
        Plan plan = plan();
        Indicator a = indicator(1L, null, "A", "A01", "DRAFT", "10", "AUTO_SUM");
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(target(1L, 2026, "10")));

        assertDoesNotThrow(() -> indicatorService.validateForDistribution(plan, List.of(a)));
    }

    @Test
    void 普通创建入口禁止新增A加() {
        IndicatorDtos.CreateRequest request = new IndicatorDtos.CreateRequest();
        request.setLevel("A_PLUS");

        BizException error = assertThrows(BizException.class, () -> indicatorService.create(11L, request));

        assertEquals(40006, error.getErrorCode().getCode());
        verifyNoInteractions(planService, indicatorMapper);
    }

    @Test
    void 下发前复核A与A加目标守恒40003() {
        Plan plan = plan();
        Indicator a = indicator(1L, null, "A", "A01", "DRAFT", "10", "AUTO_SUM");
        Indicator aPlus = indicator(2L, 1L, "A_PLUS", "A01-1", "FINALIZED", "9", null);
        aPlus.setOwnerId(null);
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(
                target(1L, 2026, "10"), target(2L, 2026, "9")));

        BizException error = assertThrows(BizException.class,
                () -> indicatorService.validateForDistribution(plan, List.of(a, aPlus)));

        assertEquals(40003, error.getErrorCode().getCode());
        assertEquals("A01.totalTarget", error.getErrors().get(0).getField());
    }

    @Test
    void 学院指标树显示关联A加与父A并排除无关兄弟() {
        Plan plan = plan();
        Indicator parent = indicator(1L, null, "A", "A01", "DISTRIBUTED", "10", "AUTO_SUM");
        Indicator child = indicator(2L, 1L, "A_PLUS", "A01-1", "DISTRIBUTED", "10", null);
        Indicator sibling = indicator(3L, null, "A", "A02", "DISTRIBUTED", "10", "MANUAL");
        Task visibleTask = new Task();
        visibleTask.setIndicatorId(2L);
        visibleTask.setTopIndicatorId(1L);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(planReadScopeService.visibleTasks(11L)).thenReturn(List.of(visibleTask));
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent, child, sibling));
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of(1L, 2L));
        when(userRefResolver.mapOf(any())).thenReturn(Map.of());
        when(yearTargetMapper.selectList(any())).thenReturn(List.of());

        List<IndicatorDtos.TreeNode> roots = indicatorService.tree(11L, null, null, null, null, "tree");

        assertEquals(1, roots.size());
        assertEquals(1L, roots.get(0).id());
        assertEquals(1, roots.get(0).children().size());
        assertEquals(2L, roots.get(0).children().get(0).id());
        verify(planReadScopeService).requireVisible(plan);
    }

    private static Plan plan() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        return plan;
    }

    private static Indicator indicator(Long id, Long parentId, String level, String code, String status,
                                       String totalTarget, String rollupMode) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setPlanId(11L);
        indicator.setParentId(parentId);
        indicator.setLevel(level);
        indicator.setCode(code);
        indicator.setStatus(status);
        indicator.setOwnerId(99L);
        indicator.setIsQuantifiable(true);
        indicator.setMeasureType("NUMBER");
        indicator.setStatCaliber("INCREMENT");
        indicator.setUnitLabel("项");
        indicator.setTargetDirection("HIGHER_BETTER");
        indicator.setRollupMode(rollupMode);
        indicator.setTotalTarget(new BigDecimal(totalTarget));
        return indicator;
    }

    private static IndicatorYearTarget target(Long indicatorId, Integer year, String value) {
        IndicatorYearTarget target = new IndicatorYearTarget();
        target.setIndicatorId(indicatorId);
        target.setYear(year);
        target.setTarget(new BigDecimal(value));
        return target;
    }
}
