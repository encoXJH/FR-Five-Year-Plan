package edu.fzrj.metrics.module.plan.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanReadScopeServiceTest {

    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private TaskMapper taskMapper;

    @InjectMocks
    private PlanReadScopeService planReadScopeService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 管理员保留全部计划和指标() {
        login("ADMIN", null);
        when(dataScopeService.isAllData(any())).thenReturn(true);
        List<Plan> plans = List.of(plan(1L), plan(2L));
        List<Indicator> indicators = List.of(indicator(10L, null, "A", 1L, null));

        assertEquals(plans, planReadScopeService.filterVisiblePlans(plans));
        assertEquals(Set.of(10L), planReadScopeService.visibleIndicatorIds(indicators, List.of()));
        verifyNoInteractions(indicatorMapper, taskMapper);
    }

    @Test
    void 纯学院仅看到本单元任务计划且直访他院计划被拒绝() {
        login("UNIT_OWNER", 31L);
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        when(taskMapper.selectList(any())).thenReturn(List.of(task(2L, 31L, 20L, 10L)));
        List<Plan> plans = List.of(plan(1L), plan(2L));

        assertEquals(List.of(2L), planReadScopeService.filterVisiblePlans(plans).stream()
                .map(Plan::getId).toList());
        BizException error = assertThrows(BizException.class,
                () -> planReadScopeService.requireVisible(plan(1L)));
        assertEquals(20005, error.getErrorCode().getCode());
    }

    @Test
    void 校级负责人和学院角色按计划取并集() {
        login("UNIT_OWNER", 31L, "SCHOOL_OWNER");
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        when(dataScopeService.isOwnerScope(any())).thenReturn(true);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator(10L, null, "A", 1L, 99L)));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(2L, 31L, 20L, 11L)));

        assertEquals(Set.of(1L, 2L), planReadScopeService.filterVisiblePlans(List.of(plan(1L), plan(2L), plan(3L)))
                .stream().map(Plan::getId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void 未绑定单元且没有负责人范围时不可见() {
        login("UNIT_OWNER", null);
        when(dataScopeService.isUnitScope(any())).thenReturn(true);

        assertTrue(planReadScopeService.filterVisiblePlans(List.of(plan(1L))).isEmpty());
        assertTrue(planReadScopeService.visibleTasks(1L).isEmpty());
        verifyNoInteractions(indicatorMapper, taskMapper);
    }

    @Test
    void 学院指标集合保留父级并排除无关兄弟() {
        login("UNIT_OWNER", 31L);
        Indicator parent = indicator(10L, null, "A", 1L, null);
        Indicator child = indicator(11L, 10L, "A_PLUS", 1L, null);
        Indicator sibling = indicator(12L, null, "A", 1L, null);

        Set<Long> visible = planReadScopeService.visibleIndicatorIds(
                List.of(parent, child, sibling), List.of(task(1L, 31L, 11L, 10L)));

        assertEquals(Set.of(10L, 11L), visible);
    }

    @Test
    void 负责人和本单元任务关联指标取并集() {
        login("UNIT_OWNER", 31L, "SCHOOL_OWNER");
        when(dataScopeService.isOwnerScope(any())).thenReturn(true);
        Indicator ownedA = indicator(10L, null, "A", 1L, 99L);
        Indicator inheritedAPlus = indicator(11L, 10L, "A_PLUS", 1L, null);
        Indicator unitA = indicator(20L, null, "A", 1L, 88L);
        Indicator unitAPlus = indicator(21L, 20L, "A_PLUS", 1L, 88L);
        Indicator unrelated = indicator(30L, null, "A", 1L, 88L);

        Set<Long> visible = planReadScopeService.visibleIndicatorIds(
                List.of(ownedA, inheritedAPlus, unitA, unitAPlus, unrelated),
                List.of(task(1L, 31L, 21L, 20L)));

        assertEquals(Set.of(10L, 11L, 20L, 21L), visible);
    }

    private static void login(String role, Long unitId, String... additionalRoles) {
        List<String> roles = new java.util.ArrayList<>();
        roles.add(role);
        roles.addAll(List.of(additionalRoles));
        LoginUser user = new LoginUser(99L, "tester", "测试人", unitId, roles, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static Plan plan(Long id) {
        Plan plan = new Plan();
        plan.setId(id);
        return plan;
    }

    private static Indicator indicator(Long id, Long parentId, String level, Long planId, Long ownerId) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setParentId(parentId);
        indicator.setLevel(level);
        indicator.setPlanId(planId);
        indicator.setOwnerId(ownerId);
        return indicator;
    }

    private static Task task(Long planId, Long unitId, Long indicatorId, Long topIndicatorId) {
        Task task = new Task();
        task.setPlanId(planId);
        task.setUnitId(unitId);
        task.setIndicatorId(indicatorId);
        task.setTopIndicatorId(topIndicatorId);
        return task;
    }
}
