package edu.fzrj.metrics.module.adjustment.service;

import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.adjustment.dto.AdjustmentDtos;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.entity.AdjustmentItem;
import edu.fzrj.metrics.module.adjustment.entity.ApprovalStep;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentItemMapper;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentMapper;
import edu.fzrj.metrics.module.adjustment.mapper.ApprovalStepMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdjustmentDecisionServiceTest {

    @Mock
    private AdjustmentMapper adjustmentMapper;
    @Mock
    private AdjustmentItemMapper itemMapper;
    @Mock
    private ApprovalStepMapper stepMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private IndicatorYearTargetMapper yearTargetMapper;
    @Mock
    private PlanYearMapper planYearMapper;
    @Mock
    private OrgUnitAdminService orgUnitAdminService;
    @Mock
    private AdjustmentService adjustmentService;
    @Mock
    private TaskService taskService;
    @Mock
    private IndicatorService indicatorService;
    @Mock
    private PlanService planService;

    @InjectMocks
    private AdjustmentDecisionService decisionService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 历史关联任务在审批写入前被统一守卫拒绝() {
        login(new LoginUser(201L, "owner", "校级负责人", null,
                List.of("SCHOOL_OWNER"), List.of()));
        Adjustment adjustment = adjustment("PENDING_OWNER_REVIEW", "OWNER_REVIEW");
        ApprovalStep step = step("OWNER_REVIEW");
        step.setExpectedApproverId(201L);
        List<AdjustmentItem> items = List.of();
        when(adjustmentMapper.selectOne(any())).thenReturn(adjustment);
        when(adjustmentService.stepsOf(1L)).thenReturn(List.of(step));
        when(adjustmentService.itemsOf(1L)).thenReturn(items);
        BizException expected = new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY);
        doThrow(expected).when(adjustmentService).requireCurrentTasks(adjustment, items);

        BizException actual = assertThrows(BizException.class,
                () -> decisionService.decide(1L, request("APPROVE")));

        assertSame(expected, actual);
        verify(stepMapper, never()).updateById(any(ApprovalStep.class));
        verify(adjustmentMapper, never()).updateById(any(Adjustment.class));
    }

    @Test
    void 最终生效前再次校验转移接收单元资格() {
        login(new LoginUser(202L, "leader", "校领导", null,
                List.of("LEADER"), List.of()));
        Adjustment adjustment = adjustment("PENDING_LEADER_APPROVAL", "LEADER_APPROVAL");
        adjustment.setType("TRANSFER");
        adjustment.setToUnitId(20L);
        when(adjustmentMapper.selectOne(any())).thenReturn(adjustment);
        when(adjustmentService.stepsOf(1L)).thenReturn(List.of(step("LEADER_APPROVAL")));
        when(adjustmentService.itemsOf(1L)).thenReturn(List.of());
        Plan plan = new Plan();
        plan.setId(2L);
        plan.setIsReceiverConfirmRequired(true);
        when(planService.requirePlan(2L)).thenReturn(plan);
        when(adjustmentService.bizCode(adjustment)).thenReturn("A01-1-C01");
        Task fromTask = new Task();
        fromTask.setId(101L);
        when(taskService.requireCurrentTask(101L)).thenReturn(fromTask);
        Indicator indicator = new Indicator();
        indicator.setId(3L);
        indicator.setStatCaliber("INCREMENT");
        when(indicatorService.requireIndicator(3L)).thenReturn(indicator);
        BizException expected = new BizException(ErrorCode.PARAM_INVALID, "执行单元不可承接新 B");
        when(orgUnitAdminService.requireBAssignable(20L)).thenThrow(expected);

        BizException actual = assertThrows(BizException.class,
                () -> decisionService.decide(1L, request("APPROVE")));

        assertSame(expected, actual);
        verify(orgUnitAdminService).requireBAssignable(20L);
        verify(taskMapper, never()).insert(any(Task.class));
        verify(taskYearMapper, never()).updateById(any(TaskYear.class));
    }

    private static Adjustment adjustment(String status, String node) {
        Adjustment adjustment = new Adjustment();
        adjustment.setId(1L);
        adjustment.setPlanId(2L);
        adjustment.setIndicatorId(3L);
        adjustment.setTaskId(101L);
        adjustment.setType("POSTPONE");
        adjustment.setStatus(status);
        adjustment.setCurrentNode(node);
        adjustment.setVersion(1);
        return adjustment;
    }

    private static ApprovalStep step(String node) {
        ApprovalStep step = new ApprovalStep();
        step.setAdjustmentId(1L);
        step.setNode(node);
        return step;
    }

    private static AdjustmentDtos.DecisionRequest request(String decision) {
        AdjustmentDtos.DecisionRequest request = new AdjustmentDtos.DecisionRequest();
        request.setDecision(decision);
        request.setVersion(1);
        return request;
    }

    private static void login(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
