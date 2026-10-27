package edu.fzrj.metrics.module.adjustment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.adjustment.dto.AdjustmentDtos;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.entity.AdjustmentItem;
import edu.fzrj.metrics.module.adjustment.entity.ApprovalStep;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentItemMapper;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentMapper;
import edu.fzrj.metrics.module.adjustment.mapper.ApprovalStepMapper;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdjustmentServiceTest {

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
    private IndicatorMapper indicatorMapper;
    @Mock
    private IndicatorYearTargetMapper yearTargetMapper;
    @Mock
    private PlanYearMapper planYearMapper;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private FileRefMapper fileRefMapper;
    @Mock
    private FileInfoMapper fileInfoMapper;
    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private TaskService taskService;
    @Mock
    private IndicatorService indicatorService;
    @Mock
    private PlanService planService;
    @Mock
    private OrgUnitAdminService orgUnitAdminService;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private UserRefResolver userRefResolver;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AdjustmentService adjustmentService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Adjustment.class);
        TableInfoHelper.initTableInfo(assistant, AdjustmentItem.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
    }

    @Test
    void 试算通过当前任务守卫拒绝历史转出任务() {
        AdjustmentDtos.CreateRequest request = new AdjustmentDtos.CreateRequest();
        request.setType("POSTPONE");
        request.setTaskId(10L);
        BizException expected = new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY);
        when(taskService.requireCurrentTask(10L)).thenThrow(expected);

        BizException actual = assertThrows(BizException.class, () -> adjustmentService.preview(request));

        assertSame(expected, actual);
        verify(taskService).requireCurrentTask(10L);
    }

    @Test
    void 转移试算委托执行单元服务校验承接资格() {
        Task source = task(10L, false);
        source.setPlanId(1L);
        source.setIndicatorId(2L);
        source.setUnitId(30L);
        source.setStatus("DISTRIBUTED");
        Plan plan = new Plan();
        plan.setId(1L);
        Indicator indicator = new Indicator();
        indicator.setId(2L);
        indicator.setStatCaliber("INCREMENT");
        when(taskService.requireCurrentTask(10L)).thenReturn(source);
        when(planService.requirePlan(1L)).thenReturn(plan);
        when(indicatorService.requireIndicator(2L)).thenReturn(indicator);
        BizException expected = new BizException(ErrorCode.PARAM_INVALID, "执行单元不可承接新 B");
        when(orgUnitAdminService.requireBAssignable(20L)).thenThrow(expected);

        AdjustmentDtos.CreateRequest request = new AdjustmentDtos.CreateRequest();
        request.setType("TRANSFER");
        request.setTaskId(10L);
        request.setToUnitId(20L);
        request.setItems(List.of());
        BizException actual = assertThrows(BizException.class, () -> adjustmentService.preview(request));

        assertSame(expected, actual);
        verify(orgUnitAdminService).requireBAssignable(20L);
    }

    @Test
    void 当前待办排除关联历史任务() {
        LoginUser user = new LoginUser(900L, "leader", "校领导", null,
                List.of("LEADER"), List.of());
        Adjustment current = adjustment(11L, 101L);
        Adjustment historical = adjustment(12L, 102L);
        when(stepMapper.selectList(any())).thenReturn(List.of(step(1L, 11L), step(2L, 12L)));
        when(adjustmentMapper.selectList(any())).thenReturn(
                List.of(current, historical), List.of(current));
        AdjustmentItem currentItem = item(11L, 101L, null);
        AdjustmentItem historicalItem = item(12L, 102L, null);
        when(itemMapper.selectList(any())).thenReturn(List.of(currentItem, historicalItem));
        when(taskService.findByIds(anyList())).thenReturn(List.of(
                task(101L, false), task(102L, true)));

        List<Adjustment> result = adjustmentService.todoAdjustments(user);

        assertEquals(List.of(11L), result.stream().map(Adjustment::getId).toList());
    }

    @Test
    void 守恒合计保留历史任务冻结目标() {
        when(taskMapper.selectList(any())).thenReturn(List.of(task(101L, false), task(102L, true)));
        TaskYear currentYear = taskYear(101L, 2026, "2");
        TaskYear historicalYear = taskYear(102L, 2026, "3");
        when(taskYearMapper.selectList(any())).thenReturn(List.of(currentYear, historicalYear));

        Map<Integer, BigDecimal> result = adjustmentService.bSumByYear(2L);

        assertEquals(0, new BigDecimal("5").compareTo(result.get(2026)));
    }

    @Test
    void 历史任务调整时间线仍可按原读接口访问() {
        Task historical = task(102L, true);
        when(taskService.requireTask(102L)).thenReturn(historical);
        when(adjustmentMapper.selectList(any())).thenReturn(List.of());
        when(itemMapper.selectList(any())).thenReturn(List.of());

        List<AdjustmentDtos.Detail> result = adjustmentService.timelineOfTask(102L);

        assertTrue(result.isEmpty());
        verify(taskService).requireAccess(historical);
        verify(taskService, never()).requireCurrentTask(anyLong());
    }

    @Test
    void 写操作共用守卫检查转出与接收任务() {
        Adjustment adjustment = adjustment(11L, 101L);
        List<AdjustmentItem> items = List.of(item(11L, 101L, 102L));
        when(taskService.requireCurrentTask(101L)).thenReturn(task(101L, false));
        BizException expected = new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY);
        when(taskService.requireCurrentTask(102L)).thenThrow(expected);

        BizException actual = assertThrows(BizException.class,
                () -> adjustmentService.requireCurrentTasks(adjustment, items));

        assertSame(expected, actual);
        verify(taskService).requireCurrentTask(101L);
        verify(taskService).requireCurrentTask(102L);
    }

    private static Adjustment adjustment(Long id, Long taskId) {
        Adjustment adjustment = new Adjustment();
        adjustment.setId(id);
        adjustment.setTaskId(taskId);
        adjustment.setCurrentNode("LEADER_APPROVAL");
        return adjustment;
    }

    private static ApprovalStep step(Long id, Long adjustmentId) {
        ApprovalStep step = new ApprovalStep();
        step.setId(id);
        step.setAdjustmentId(adjustmentId);
        step.setNode("LEADER_APPROVAL");
        return step;
    }

    private static AdjustmentItem item(Long adjustmentId, Long fromTaskId, Long toTaskId) {
        AdjustmentItem item = new AdjustmentItem();
        item.setAdjustmentId(adjustmentId);
        item.setFromTaskId(fromTaskId);
        item.setToTaskId(toTaskId);
        return item;
    }

    private static Task task(Long id, boolean historical) {
        Task task = new Task();
        task.setId(id);
        task.setIsHistorical(historical);
        return task;
    }

    private static TaskYear taskYear(Long taskId, Integer year, String target) {
        TaskYear taskYear = new TaskYear();
        taskYear.setTaskId(taskId);
        taskYear.setYear(year);
        taskYear.setTarget(new BigDecimal(target));
        return taskYear;
    }
}
