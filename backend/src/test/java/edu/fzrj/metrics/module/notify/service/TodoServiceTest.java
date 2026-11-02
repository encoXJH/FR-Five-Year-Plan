package edu.fzrj.metrics.module.notify.service;

import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.notify.dto.NotifyDtos;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.ReviewService;
import edu.fzrj.metrics.module.system.service.SettingService;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private PlanYearMapper planYearMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private ReviewService reviewService;
    @Mock
    private AdjustmentService adjustmentService;
    @Mock
    private SettingService settingService;

    @InjectMocks
    private TodoService todoService;

    @BeforeEach
    void login() {
        LoginUser user = new LoginUser(99L, "c01", "学院负责人", 41L,
                List.of("UNIT_OWNER"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 按计划与年度过滤后条目和汇总使用同一结果集() {
        Task first = task(101L, 11L, "A01-1-C01");
        Task second = task(102L, 11L, "A02-1-C01");
        Task otherPlan = task(103L, 22L, "A01-1-C01");
        when(taskMapper.selectList(any()))
                .thenReturn(List.of(first, second, otherPlan))
                .thenReturn(List.of());
        when(taskYearMapper.selectList(any())).thenReturn(List.of(
                taskYear(1001L, 101L, 11L, 2026, "IN_PROGRESS"),
                taskYear(1002L, 102L, 11L, 2027, "IN_PROGRESS"),
                taskYear(1003L, 103L, 22L, 2026, "IN_PROGRESS")));
        when(planYearMapper.selectList(any())).thenReturn(List.of());
        when(adjustmentService.todoAdjustments(any())).thenReturn(List.of());

        NotifyDtos.TodoView result = todoService.todos(11L, 2026);

        assertEquals(1, result.items().size());
        NotifyDtos.TodoItem item = result.items().get(0);
        assertEquals("FILL", item.type());
        assertEquals(101L, item.bizId());
        assertEquals(11L, item.planId());
        assertEquals(2026, item.year());
        assertEquals(Map.of("FILL", 1L), result.summary());
    }

    @Test
    void 未接收任务不生成ACCEPT且保留填报待办() {
        Task task = task(101L, 11L, "A01-1-C01");
        task.setStatus("DISTRIBUTED");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(
                taskYear(1001L, 101L, 11L, 2026, "NOT_ACTIVATED"),
                taskYear(1002L, 101L, 11L, 2027, "IN_PROGRESS")));
        when(adjustmentService.todoAdjustments(any())).thenReturn(List.of());

        NotifyDtos.TodoView result = todoService.todos();

        assertEquals(1, result.items().size());
        NotifyDtos.TodoItem item = result.items().get(0);
        assertEquals("FILL", item.type());
        assertEquals(101L, item.bizId());
        assertEquals(11L, item.planId());
        assertEquals(2027, item.year());
        assertEquals(Map.of("FILL", 1L), result.summary());
    }

    private Task task(Long id, Long planId, String code) {
        Task task = new Task();
        task.setId(id);
        task.setPlanId(planId);
        task.setUnitId(41L);
        task.setCode(code);
        task.setStatus("IN_PROGRESS");
        task.setIsHistorical(false);
        return task;
    }

    private TaskYear taskYear(Long id, Long taskId, Long planId, Integer year, String status) {
        TaskYear taskYear = new TaskYear();
        taskYear.setId(id);
        taskYear.setTaskId(taskId);
        taskYear.setPlanId(planId);
        taskYear.setYear(year);
        taskYear.setYearStatus(status);
        return taskYear;
    }
}
