package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.service.NotificationService;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskStaff;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskStaffServiceTest {

    @Mock
    private TaskStaffMapper taskStaffMapper;
    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private TaskStaffService taskStaffService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TaskStaff.class);
    }

    @Test
    void 同学院非任务负责人可跨创建人整体保存且仅写入差异() {
        Task task = task();
        TaskStaff first = relation(1L, 901L);
        TaskStaff second = relation(2L, 902L);
        when(taskStaffMapper.selectList(any())).thenReturn(List.of(first, second));
        when(taskStaffMapper.selectAssignableStaff(31L))
                .thenReturn(List.of(user(902L), user(903L)));

        mockInsertIds();

        TaskStaffService.MergeResult result = taskStaffService.merge(
                task, List.of(902L, 903L, 903L), 88L);

        assertTrue(result.changed());
        assertEquals(List.of(901L, 902L), result.beforeStaffIds());
        assertEquals(List.of(902L, 903L), result.afterStaffIds());
        verify(taskStaffMapper, times(1)).delete(any());
        ArgumentCaptor<TaskStaff> inserted = ArgumentCaptor.forClass(TaskStaff.class);
        verify(taskStaffMapper, times(1)).insert(inserted.capture());
        assertEquals(903L, inserted.getValue().getStaffId());
        assertEquals(88L, inserted.getValue().getAssignedBy());
        verify(taskStaffMapper, never()).updateById(any(TaskStaff.class));
        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).insertOnce(notification.capture());
        assertEquals(903L, notification.getValue().getUserId());
        assertEquals("TASK_DISTRIBUTED", notification.getValue().getType());
        assertEquals("干事任务分配：A01-C01", notification.getValue().getTitle());
        assertEquals("task", notification.getValue().getBizType());
        assertEquals(10L, notification.getValue().getBizId());
        assertEquals("/tasks/10/submit", notification.getValue().getLink());
        assertEquals("TASK_STAFF_ASSIGNED:10:903:1903", notification.getValue().getDedupKey());
    }

    @Test
    void 空数组移除全部干事且无需查询候选人() {
        Task task = task();
        when(taskStaffMapper.selectList(any())).thenReturn(List.of(relation(1L, 901L)));

        TaskStaffService.MergeResult result = taskStaffService.merge(task, List.of(), 99L);

        assertTrue(result.changed());
        assertEquals(List.of(), result.afterStaffIds());
        verify(taskStaffMapper).delete(any());
        verify(taskStaffMapper, never()).selectAssignableStaff(any());
        verify(taskStaffMapper, never()).insert(any(TaskStaff.class));
        verify(notificationService, never()).insertOnce(any());
    }

    @Test
    void 任一干事不合格时不执行任何差异写入() {
        Task task = task();
        when(taskStaffMapper.selectList(any())).thenReturn(List.of(relation(1L, 901L)));
        when(taskStaffMapper.selectAssignableStaff(31L)).thenReturn(List.of());

        BizException error = assertThrows(BizException.class,
                () -> taskStaffService.merge(task, List.of(901L, 999L), 99L));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(taskStaffMapper, never()).delete(any());
        verify(taskStaffMapper, never()).insert(any(TaskStaff.class));
        verify(notificationService, never()).insertOnce(any());
    }

    @Test
    void 分配名单未变化时不写关系也不通知() {
        Task task = task();
        when(taskStaffMapper.selectList(any())).thenReturn(List.of(relation(1L, 901L)));
        when(taskStaffMapper.selectAssignableStaff(31L)).thenReturn(List.of(user(901L)));

        TaskStaffService.MergeResult result = taskStaffService.merge(task, List.of(901L, 901L), 99L);

        assertFalse(result.changed());
        assertEquals(List.of(901L), result.afterStaffIds());
        verify(taskStaffMapper, never()).delete(any());
        verify(taskStaffMapper, never()).insert(any(TaskStaff.class));
        verify(notificationService, never()).insertOnce(any());
    }

    @Test
    void 移除后重新分配按新关系再次通知() {
        Task task = task();
        when(taskStaffMapper.selectList(any()))
                .thenReturn(List.of(relation(1L, 901L)))
                .thenReturn(List.of(relation(2L, 902L)));
        when(taskStaffMapper.selectAssignableStaff(31L))
                .thenReturn(List.of(user(901L), user(902L)));
        mockInsertIds();

        taskStaffService.merge(task, List.of(902L), 88L);
        taskStaffService.merge(task, List.of(901L), 88L);

        ArgumentCaptor<Notification> notifications = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService, times(2)).insertOnce(notifications.capture());
        assertEquals("TASK_STAFF_ASSIGNED:10:902:1902", notifications.getAllValues().get(0).getDedupKey());
        assertEquals("TASK_STAFF_ASSIGNED:10:901:1901", notifications.getAllValues().get(1).getDedupKey());
    }

    @Test
    void 管理员创建的纯干事进入任务候选且不按创建人过滤() throws Exception {
        SysUser staff = user(903L);
        staff.setOwnerUserId(88L);
        staff.setCreatedBy(99L);
        when(taskStaffMapper.selectAssignableStaff(31L)).thenReturn(List.of(staff));

        var candidates = taskStaffService.assignableStaffOf(task());

        assertEquals(List.of(903L), candidates.stream().map(candidate -> candidate.id()).toList());
        String sql = TaskStaffMapper.class.getMethod("selectAssignableStaff", Long.class)
                .getAnnotation(Select.class).value()[0];
        assertTrue(sql.contains("ur.role = 'STAFF'"));
        assertTrue(sql.contains("other.role <> 'STAFF'"));
        assertFalse(sql.contains("created_by"));
        assertFalse(sql.contains("owner_user_id"));
    }

    private void mockInsertIds() {
        when(taskStaffMapper.insert(any(TaskStaff.class))).thenAnswer(invocation -> {
            TaskStaff row = invocation.getArgument(0);
            row.setId(1000L + row.getStaffId());
            return 1;
        });
    }

    private Task task() {
        Task task = new Task();
        task.setId(10L);
        task.setUnitId(31L);
        task.setOwnerId(99L);
        task.setCode("A01-C01");
        return task;
    }

    private TaskStaff relation(Long id, Long staffId) {
        TaskStaff row = new TaskStaff();
        row.setId(id);
        row.setTaskId(10L);
        row.setStaffId(staffId);
        row.setAssignedBy(88L);
        row.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return row;
    }

    private SysUser user(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setName("干事" + id);
        return user;
    }
}
