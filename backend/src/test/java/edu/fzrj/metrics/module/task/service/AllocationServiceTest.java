package edu.fzrj.metrics.module.task.service;

import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {

    private static final Long PLAN_ID = 11L;
    private static final Long INDICATOR_ID = 21L;
    private static final Long TASK_ID = 31L;
    private static final Long UNIT_ID = 41L;
    private static final Long OWNER_ID = 51L;

    @Mock
    private IndicatorService indicatorService;
    @Mock
    private PlanService planService;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private OrgUnitAdminService orgUnitAdminService;
    @Mock
    private UserRefResolver userRefResolver;
    @Mock
    private DataScopeService dataScopeService;

    @InjectMocks
    private AllocationService allocationService;

    private Indicator indicator;
    private Plan plan;
    private Task historicalTask;
    private OrgUnit unit;
    private List<TaskYear> historicalYears;

    @BeforeEach
    void setUp() {
        indicator = new Indicator();
        indicator.setId(INDICATOR_ID);
        indicator.setPlanId(PLAN_ID);
        indicator.setParentId(20L);
        indicator.setLevel("A_PLUS");
        indicator.setCode("A01-1");
        indicator.setStatCaliber("INCREMENT");
        indicator.setStatus("FINALIZED");
        indicator.setVersion(3);

        plan = new Plan();
        plan.setId(PLAN_ID);
        plan.setStartYear(2026);
        plan.setEndYear(2027);
        plan.setStatus("ACTIVE");

        historicalTask = new Task();
        historicalTask.setId(TASK_ID);
        historicalTask.setPlanId(PLAN_ID);
        historicalTask.setIndicatorId(INDICATOR_ID);
        historicalTask.setUnitId(UNIT_ID);
        historicalTask.setOwnerId(OWNER_ID);
        historicalTask.setCode("A01-1-C01");
        historicalTask.setStatus("IN_PROGRESS");
        historicalTask.setIsHistorical(true);

        unit = new OrgUnit();
        unit.setId(UNIT_ID);
        unit.setCode("C01");
        unit.setName("智能产业学院");
        unit.setIsEnabled(false);
        unit.setIsBAssignable(false);

        historicalYears = List.of(
                taskYear(1001L, 2026, "10"),
                taskYear(1002L, 2027, "20"));

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
    void 未变化历史行可随整体提交且继续参与守恒() {
        stubExistingAllocation();
        when(orgUnitAdminService.requireUnit(UNIT_ID)).thenReturn(unit);
        when(taskYearMapper.selectList(any())).thenReturn(historicalYears);
        when(indicatorService.updateIndicator(indicator)).thenReturn(1);
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));
        SysUser owner = new SysUser();
        owner.setId(OWNER_ID);
        owner.setName("学院负责人");
        when(userRefResolver.mapOf(any())).thenReturn(Map.of(OWNER_ID, owner));
        when(userRefResolver.refOf(eq(OWNER_ID), anyMap()))
                .thenReturn(new UserRef(OWNER_ID, owner.getName()));
        when(indicatorService.loadTargets(anyList())).thenReturn(Map.of(INDICATOR_ID,
                Map.of(2026, new BigDecimal("10"), 2027, new BigDecimal("20"))));

        TaskDtos.AllocationView view = allocationService.putAllocation(INDICATOR_ID,
                request(List.of(row(OWNER_ID, "10", "20"))));

        assertTrue(view.isBalanced());
        assertEquals(1, view.rows().size());
        assertTrue(view.rows().get(0).isHistorical());
        assertEquals(0, view.summary().get(0).allocatedTarget().compareTo(new BigDecimal("10")));
        assertEquals(0, view.summary().get(1).allocatedTarget().compareTo(new BigDecimal("20")));
        verify(orgUnitAdminService, never()).requireBAssignable(any());
        verify(indicatorService).updateIndicator(indicator);
        verifyNoTaskWrites();
    }

    @Test
    void 修改历史任务年度目标返回50006() {
        stubExistingAllocation();
        when(orgUnitAdminService.requireUnit(UNIT_ID)).thenReturn(unit);
        when(taskYearMapper.selectList(any())).thenReturn(historicalYears);

        BizException error = assertThrows(BizException.class,
                () -> allocationService.putAllocation(INDICATOR_ID,
                        request(List.of(row(OWNER_ID, "11", "20")))));

        assertEquals(50006, error.getErrorCode().getCode());
        verifyNoTaskWrites();
    }

    @Test
    void 修改历史任务负责人返回50006() {
        stubExistingAllocation();
        when(orgUnitAdminService.requireUnit(UNIT_ID)).thenReturn(unit);

        BizException error = assertThrows(BizException.class,
                () -> allocationService.putAllocation(INDICATOR_ID,
                        request(List.of(row(52L, "10", "20")))));

        assertEquals(50006, error.getErrorCode().getCode());
        verifyNoTaskWrites();
    }

    @Test
    void 从历史矩阵删除任务返回50006() {
        stubExistingAllocation();

        BizException error = assertThrows(BizException.class,
                () -> allocationService.putAllocation(INDICATOR_ID, request(List.of())));

        assertEquals(50006, error.getErrorCode().getCode());
        verifyNoTaskWrites();
    }

    private void stubExistingAllocation() {
        when(indicatorService.requireIndicator(INDICATOR_ID)).thenReturn(indicator);
        when(planService.requirePlan(PLAN_ID)).thenReturn(plan);
        when(taskMapper.selectList(any())).thenReturn(List.of(historicalTask));
    }

    private TaskYear taskYear(Long id, Integer year, String target) {
        TaskYear taskYear = new TaskYear();
        taskYear.setId(id);
        taskYear.setTaskId(TASK_ID);
        taskYear.setYear(year);
        taskYear.setTarget(new BigDecimal(target));
        return taskYear;
    }

    private TaskDtos.AllocationPutRequest request(List<TaskDtos.AllocationRow> rows) {
        TaskDtos.AllocationPutRequest request = new TaskDtos.AllocationPutRequest();
        request.setVersion(indicator.getVersion());
        request.setRows(rows);
        return request;
    }

    private TaskDtos.AllocationRow row(Long ownerId, String firstTarget, String secondTarget) {
        TaskDtos.AllocationRow row = new TaskDtos.AllocationRow();
        row.setUnitId(UNIT_ID);
        row.setOwnerId(ownerId);
        row.setYearTargets(List.of(
                yearTarget(2026, firstTarget),
                yearTarget(2027, secondTarget)));
        return row;
    }

    private TaskDtos.AllocationYearTargetItem yearTarget(Integer year, String target) {
        TaskDtos.AllocationYearTargetItem item = new TaskDtos.AllocationYearTargetItem();
        item.setYear(year);
        item.setTarget(new BigDecimal(target));
        return item;
    }

    private void verifyNoTaskWrites() {
        verify(taskMapper, never()).insert(any(Task.class));
        verify(taskMapper, never()).updateById(any(Task.class));
        verify(taskYearMapper, never()).insert(any(TaskYear.class));
        verify(taskYearMapper, never()).updateById(any(TaskYear.class));
    }
}
