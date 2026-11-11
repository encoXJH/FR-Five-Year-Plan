package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanReadScopeService;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributeServiceTest {

    @Mock
    private PlanService planService;
    @Mock
    private IndicatorService indicatorService;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private TaskYearActivationService taskYearActivationService;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private OrgUnitAdminService orgUnitAdminService;
    @Mock
    private NotificationMapper notificationMapper;
    @Spy
    private DataScopeService dataScopeService;
    @Mock
    private PlanReadScopeService planReadScopeService;

    @InjectMocks
    private DistributeService distributeService;

    private Plan plan;
    private Indicator parent;
    private Indicator candidate;
    private Task task;
    private OrgUnit unit;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
        TableInfoHelper.initTableInfo(assistant, edu.fzrj.metrics.module.plan.entity.PlanYear.class);
    }

    @BeforeEach
    void setUp() {
        plan = new Plan();
        plan.setId(11L);
        plan.setName("直接下发测试计划");
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        plan.setVersion(1);

        parent = new Indicator();
        parent.setId(1L);
        parent.setPlanId(11L);
        parent.setLevel("A");
        parent.setCode("A01");
        parent.setStatus("DRAFT");

        candidate = new Indicator();
        candidate.setId(2L);
        candidate.setPlanId(11L);
        candidate.setParentId(1L);
        candidate.setLevel("A_PLUS");
        candidate.setCode("A01-1");
        candidate.setStatCaliber("INCREMENT");

        task = new Task();
        task.setId(21L);
        task.setPlanId(11L);
        task.setIndicatorId(2L);
        task.setUnitId(31L);
        task.setOwnerId(41L);
        task.setCode("A01-1-C01");
        task.setStatus("PENDING_DISTRIBUTION");
        task.setIsHistorical(false);

        unit = new OrgUnit();
        unit.setId(31L);
        unit.setCode("C01");
        unit.setName("智能产业学院");

        LoginUser user = new LoginUser(99L, "admin", "管理员", null,
                List.of("ADMIN"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "FINALIZED"})
    void 草稿及历史定稿状态均可直接下发(String status) {
        candidate.setStatus(status);
        List<Indicator> all = List.of(parent, candidate);
        TaskYear year = new TaskYear();
        year.setTaskId(21L);
        year.setYear(2026);
        year.setTarget(BigDecimal.TEN);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(all);
        when(indicatorService.loadTargets(anyList())).thenReturn(Map.of(2L, Map.of(2026, BigDecimal.TEN)));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(year));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));

        TaskDtos.DistributeResponse response = distributeService.distribute(11L, request());

        assertEquals(1, response.indicatorCount());
        assertEquals(1, response.taskCount());
        assertEquals("DISTRIBUTED", candidate.getStatus());
        assertEquals("DISTRIBUTED", parent.getStatus());
        assertEquals("DISTRIBUTED", task.getStatus());
        verify(indicatorService).validateForDistribution(plan, all);
        verify(notificationMapper).insert(any(Notification.class));
    }

    @Test
    void 下发后同步激活计划内已下发任务的年度状态() {
        candidate.setStatus("DRAFT");
        List<Indicator> all = List.of(parent, candidate);
        TaskYear year = new TaskYear();
        year.setTaskId(21L);
        year.setYear(2026);
        year.setTarget(BigDecimal.TEN);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(all);
        when(indicatorService.loadTargets(anyList())).thenReturn(Map.of(2L, Map.of(2026, BigDecimal.TEN)));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(year));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));

        distributeService.distribute(11L, request());

        verify(taskYearActivationService).syncPlan(11L, 99L);
    }

    @Test
    void 重复下发没有候选时保持幂等且不触发全量校验() {
        candidate.setStatus("DISTRIBUTED");
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent, candidate));

        TaskDtos.DistributeResponse response = distributeService.distribute(11L, request());

        assertEquals(0, response.indicatorCount());
        assertEquals(0, response.taskCount());
        verify(indicatorService, never()).validateForDistribution(any(), anyList());
        verify(taskMapper, never()).updateById(any(Task.class));
        verify(notificationMapper, never()).insert(any(Notification.class));
        verify(taskYearActivationService).syncPlan(11L, 99L);
    }

    @Test
    void 已下发指标仅追加下发新学院任务() {
        candidate.setStatus("DISTRIBUTED");
        task.setStatus("DISTRIBUTED");
        Task pending = new Task();
        pending.setId(22L);
        pending.setPlanId(11L);
        pending.setIndicatorId(2L);
        pending.setUnitId(32L);
        pending.setOwnerId(42L);
        pending.setCode("A01-1-C02");
        pending.setStatus("PENDING_DISTRIBUTION");
        pending.setIsHistorical(false);
        OrgUnit newUnit = new OrgUnit();
        newUnit.setId(32L);
        newUnit.setCode("C02");
        TaskYear oldYear = new TaskYear();
        oldYear.setTaskId(21L);
        oldYear.setYear(2026);
        oldYear.setTarget(BigDecimal.TEN);
        TaskYear newYear = new TaskYear();
        newYear.setTaskId(22L);
        newYear.setYear(2026);
        newYear.setTarget(BigDecimal.ONE);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent, candidate));
        when(taskMapper.selectList(any())).thenReturn(List.of(task, pending));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(oldYear, newYear));
        when(indicatorService.loadTargets(anyList())).thenReturn(Map.of(2L, Map.of(2026, BigDecimal.TEN)));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit, newUnit));

        TaskDtos.DistributeResponse response = distributeService.distribute(11L, request());

        assertEquals(1, response.indicatorCount());
        assertEquals(1, response.taskCount());
        assertEquals("DISTRIBUTED", pending.getStatus());
        verify(taskMapper).updateById(pending);
        verify(taskMapper, never()).updateById(task);
        verify(notificationMapper).insert(any(Notification.class));
    }

    @Test
    void 下发前校验失败时不修改任务() {
        candidate.setStatus("DRAFT");
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent, candidate));
        doThrow(new BizException(ErrorCode.YEAR_TARGETS_SUM_INVALID))
                .when(indicatorService).validateForDistribution(any(), anyList());

        BizException error = assertThrows(BizException.class,
                () -> distributeService.distribute(11L, request()));

        assertEquals(40002, error.getErrorCode().getCode());
        verify(taskMapper, never()).updateById(any(Task.class));
    }

    @Test
    void 纯A直接下发自身B且不展开隐藏A加() {
        parent.setIsQuantifiable(true);
        parent.setStatCaliber("INCREMENT");
        task.setIndicatorId(1L);
        task.setTopIndicatorId(1L);
        task.setCode("A01-C01");
        TaskYear year = new TaskYear();
        year.setTaskId(21L);
        year.setYear(2026);
        year.setTarget(BigDecimal.TEN);
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent));
        when(indicatorService.loadTargets(anyList())).thenReturn(Map.of(1L, Map.of(2026, BigDecimal.TEN)));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(year));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));

        TaskDtos.DistributeResponse response = distributeService.distribute(11L, request());

        assertEquals(1, response.indicatorCount());
        assertEquals(1, response.taskCount());
        assertEquals("DISTRIBUTED", parent.getStatus());
        assertEquals("DISTRIBUTED", task.getStatus());
        verify(indicatorService).updateIndicator(parent);
        verify(indicatorService, never()).updateIndicator(candidate);
    }

    @Test
    void 纯A下发强制校验B年度合计返回50001() {
        parent.setIsQuantifiable(true);
        parent.setStatCaliber("INCREMENT");
        task.setIndicatorId(1L);
        task.setTopIndicatorId(1L);
        task.setCode("A01-C01");
        TaskYear year = new TaskYear();
        year.setTaskId(21L);
        year.setYear(2026);
        year.setTarget(new BigDecimal("9"));
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent));
        when(indicatorService.loadTargets(anyList())).thenReturn(Map.of(1L, Map.of(2026, BigDecimal.TEN)));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(year));

        BizException error = assertThrows(BizException.class,
                () -> distributeService.distribute(11L, request()));

        assertEquals(50001, error.getErrorCode().getCode());
        verify(taskMapper, never()).updateById(any(Task.class));
    }

    @Test
    void 建设方向负责人显式下发他人指标时返回20005() {
        candidate.setStatus("DRAFT");
        candidate.setOwnerId(88L);
        TaskDtos.DistributeRequest request = request();
        request.setIndicatorIds(List.of(2L));
        LoginUser user = new LoginUser(99L, "owner", "建设方向负责人", null,
                List.of("SCHOOL_OWNER"), List.of("task:distribute"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent, candidate));
        when(indicatorService.effectiveOwnerOf(candidate)).thenReturn(88L);

        BizException error = assertThrows(BizException.class,
                () -> distributeService.distribute(11L, request));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(indicatorService, never()).validateForDistribution(any(), anyList());
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    void 建设方向负责人不能通过所属A下发他人指标() {
        parent.setOwnerId(88L);
        candidate.setOwnerId(99L);
        TaskDtos.DistributeRequest request = request();
        request.setIndicatorIds(List.of(1L));
        LoginUser user = new LoginUser(99L, "owner", "建设方向负责人", null,
                List.of("SCHOOL_OWNER"), List.of("task:distribute"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent, candidate));
        when(indicatorService.effectiveOwnerOf(parent)).thenReturn(88L);

        BizException error = assertThrows(BizException.class,
                () -> distributeService.distribute(11L, request));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    void 计划越权时不读取或下发指标() {
        when(planService.requirePlan(11L)).thenReturn(plan);
        doThrow(new BizException(ErrorCode.DATA_SCOPE_DENIED))
                .when(planReadScopeService).requireVisible(plan);

        BizException error = assertThrows(BizException.class,
                () -> distributeService.distribute(11L, request()));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(indicatorMapper, never()).selectList(any());
    }

    @Test
    void 建设方向负责人批量下发时仅处理本人负责指标() {
        candidate.setStatus("DRAFT");
        candidate.setOwnerId(99L);
        Indicator other = new Indicator();
        other.setId(3L);
        other.setPlanId(11L);
        other.setParentId(1L);
        other.setLevel("A_PLUS");
        other.setCode("A01-2");
        other.setStatus("DRAFT");
        other.setOwnerId(88L);
        TaskYear year = new TaskYear();
        year.setTaskId(21L);
        year.setYear(2026);
        year.setTarget(BigDecimal.TEN);
        LoginUser user = new LoginUser(99L, "owner", "建设方向负责人", null,
                List.of("SCHOOL_OWNER"), List.of("task:distribute"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(planService.requirePlan(11L)).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(parent, candidate, other));
        when(indicatorService.effectiveOwnerOf(any())).thenAnswer(invocation ->
                ((Indicator) invocation.getArgument(0)).getOwnerId());
        when(indicatorService.loadTargets(anyList())).thenReturn(Map.of(2L, Map.of(2026, BigDecimal.TEN)));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(year));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));

        TaskDtos.DistributeResponse response = distributeService.distribute(11L, request());

        assertEquals(1, response.indicatorCount());
        assertEquals("DISTRIBUTED", candidate.getStatus());
        assertEquals("DRAFT", other.getStatus());
        verify(indicatorService).loadTargets(List.of(2L));
    }

    private static TaskDtos.DistributeRequest request() {
        TaskDtos.DistributeRequest request = new TaskDtos.DistributeRequest();
        request.setVersion(1);
        return request;
    }
}
