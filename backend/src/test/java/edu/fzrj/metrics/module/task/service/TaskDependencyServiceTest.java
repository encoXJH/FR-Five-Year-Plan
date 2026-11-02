package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.task.dto.TaskDependencyDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskDependency;
import edu.fzrj.metrics.module.task.mapper.TaskDependencyMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskDependencyServiceTest {

    @Mock
    private TaskDependencyMapper dependencyMapper;
    @Mock
    private PlanMapper planMapper;
    @Mock
    private PlanService planService;
    @Mock
    private TaskService taskService;
    @InjectMocks
    private TaskDependencyService dependencyService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TaskDependency.class);
    }

    @BeforeEach
    void login() {
        LoginUser user = new LoginUser(9L, "admin", "管理员", null,
                List.of("ADMIN"), List.of("allocation:edit"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 创建依赖成功并复用计划行锁() {
        Plan plan = plan(10L);
        Task first = task(1L, 10L);
        Task second = task(2L, 10L);
        TaskDependency created = dependency(99L, 1L, 2L);
        when(planMapper.selectByIdForUpdate(10L)).thenReturn(plan);
        when(taskService.findByIds(any())).thenReturn(List.of(first, second));
        when(dependencyMapper.selectCount(any())).thenReturn(0L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of());
        when(dependencyMapper.selectOne(any())).thenReturn(created);

        TaskDependencyDtos.View result = dependencyService.create(10L, request(1L, 2L));

        assertEquals(99L, result.id());
        assertEquals("FS", result.type());
        verify(planMapper).selectByIdForUpdate(10L);
        verify(dependencyMapper).insert(any(TaskDependency.class));
    }

    @Test
    void 跨计划与越权任务不能创建依赖() {
        when(planMapper.selectByIdForUpdate(10L)).thenReturn(plan(10L));
        when(taskService.findByIds(any())).thenReturn(List.of(task(1L, 10L), task(2L, 11L)));
        BizException crossPlan = assertThrows(BizException.class,
                () -> dependencyService.create(10L, request(1L, 2L)));
        assertEquals(10001, crossPlan.getErrorCode().getCode());

        Task first = task(1L, 10L);
        Task second = task(2L, 10L);
        when(taskService.findByIds(any())).thenReturn(List.of(first, second));
        org.mockito.Mockito.doNothing().when(taskService).requireAccess(first);
        doThrow(new BizException(ErrorCode.DATA_SCOPE_DENIED)).when(taskService).requireAccess(second);
        BizException denied = assertThrows(BizException.class,
                () -> dependencyService.create(10L, request(1L, 2L)));
        assertEquals(20005, denied.getErrorCode().getCode());
        verify(dependencyMapper, never()).insert(any(TaskDependency.class));
    }

    @Test
    void 历史任务和归档计划只读() {
        Task first = task(1L, 10L);
        Task second = task(2L, 10L);
        when(planMapper.selectByIdForUpdate(10L)).thenReturn(plan(10L));
        when(taskService.findByIds(any())).thenReturn(List.of(first, second));
        doThrow(new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY)).when(taskService).requireCurrentTask(first);

        BizException historical = assertThrows(BizException.class,
                () -> dependencyService.create(10L, request(1L, 2L)));
        assertEquals(50006, historical.getErrorCode().getCode());

        Plan archived = plan(10L);
        archived.setStatus("ARCHIVED");
        when(planMapper.selectByIdForUpdate(10L)).thenReturn(archived);
        doThrow(new BizException(ErrorCode.PLAN_ARCHIVED)).when(planService).requireNotArchived(archived);
        BizException readOnly = assertThrows(BizException.class,
                () -> dependencyService.create(10L, request(1L, 2L)));
        assertEquals(30002, readOnly.getErrorCode().getCode());
    }

    @Test
    void 自依赖重复依赖和反向成环均被拒绝() {
        when(planMapper.selectByIdForUpdate(10L)).thenReturn(plan(10L));
        BizException self = assertThrows(BizException.class,
                () -> dependencyService.create(10L, request(1L, 1L)));
        assertEquals(10001, self.getErrorCode().getCode());

        when(taskService.findByIds(any())).thenReturn(List.of(task(1L, 10L), task(2L, 10L)));
        when(dependencyMapper.selectCount(any())).thenReturn(1L);
        BizException duplicate = assertThrows(BizException.class,
                () -> dependencyService.create(10L, request(1L, 2L)));
        assertEquals(10001, duplicate.getErrorCode().getCode());

        when(dependencyMapper.selectCount(any())).thenReturn(0L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of(dependency(8L, 1L, 2L)));
        BizException cycle = assertThrows(BizException.class,
                () -> dependencyService.create(10L, request(2L, 1L)));
        assertEquals(10001, cycle.getErrorCode().getCode());
        verify(planMapper, org.mockito.Mockito.atLeast(3)).selectByIdForUpdate(10L);
        verify(dependencyMapper, never()).insert(any(TaskDependency.class));
    }

    @Test
    void 删除依赖执行乐观锁和逻辑删除() {
        TaskDependency existing = dependency(99L, 1L, 2L);
        when(dependencyMapper.selectOne(any())).thenReturn(existing);
        when(planMapper.selectByIdForUpdate(10L)).thenReturn(plan(10L));
        when(taskService.requireTask(1L)).thenReturn(task(1L, 10L));
        when(taskService.requireTask(2L)).thenReturn(task(2L, 10L));
        when(dependencyMapper.update(any(), any())).thenReturn(1);

        BizException conflict = assertThrows(BizException.class, () -> dependencyService.delete(99L, 3));
        assertEquals(10005, conflict.getErrorCode().getCode());

        dependencyService.delete(99L, 0);
        verify(dependencyMapper).update(any(), any());
    }

    private static TaskDependencyDtos.CreateRequest request(Long predecessor, Long successor) {
        TaskDependencyDtos.CreateRequest request = new TaskDependencyDtos.CreateRequest();
        request.setPredecessorTaskId(predecessor);
        request.setSuccessorTaskId(successor);
        request.setType("FS");
        return request;
    }

    private static Plan plan(Long id) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setStatus("ACTIVE");
        return plan;
    }

    private static Task task(Long id, Long planId) {
        Task task = new Task();
        task.setId(id);
        task.setPlanId(planId);
        task.setIsHistorical(false);
        return task;
    }

    private static TaskDependency dependency(Long id, Long predecessor, Long successor) {
        TaskDependency dependency = new TaskDependency();
        dependency.setId(id);
        dependency.setPlanId(10L);
        dependency.setPredecessorTaskId(predecessor);
        dependency.setSuccessorTaskId(successor);
        dependency.setDependencyType("FS");
        dependency.setVersion(0);
        dependency.setDeleted(0L);
        return dependency;
    }
}
