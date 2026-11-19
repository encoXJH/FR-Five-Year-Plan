package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskStaff;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
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
    @InjectMocks
    private TaskStaffService taskStaffService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TaskStaff.class);
    }

    @Test
    void 多干事整体保存仅差异新增删除并保留未变化关系() {
        Task task = task();
        TaskStaff first = relation(1L, 901L);
        TaskStaff second = relation(2L, 902L);
        when(taskStaffMapper.selectList(any())).thenReturn(List.of(first, second));
        when(taskStaffMapper.selectAssignableStaff(99L, 31L))
                .thenReturn(List.of(user(902L), user(903L)));

        TaskStaffService.MergeResult result = taskStaffService.merge(
                task, List.of(902L, 903L, 903L), 99L);

        assertTrue(result.changed());
        assertEquals(List.of(901L, 902L), result.beforeStaffIds());
        assertEquals(List.of(902L, 903L), result.afterStaffIds());
        verify(taskStaffMapper, times(1)).delete(any());
        ArgumentCaptor<TaskStaff> inserted = ArgumentCaptor.forClass(TaskStaff.class);
        verify(taskStaffMapper, times(1)).insert(inserted.capture());
        assertEquals(903L, inserted.getValue().getStaffId());
        assertEquals(99L, inserted.getValue().getAssignedBy());
        verify(taskStaffMapper, never()).updateById(any(TaskStaff.class));
    }

    @Test
    void 空数组移除全部干事且无需查询候选人() {
        Task task = task();
        when(taskStaffMapper.selectList(any())).thenReturn(List.of(relation(1L, 901L)));

        TaskStaffService.MergeResult result = taskStaffService.merge(task, List.of(), 99L);

        assertTrue(result.changed());
        assertEquals(List.of(), result.afterStaffIds());
        verify(taskStaffMapper).delete(any());
        verify(taskStaffMapper, never()).selectAssignableStaff(any(), any());
        verify(taskStaffMapper, never()).insert(any(TaskStaff.class));
    }

    @Test
    void 任一干事不合格时不执行任何差异写入() {
        Task task = task();
        when(taskStaffMapper.selectList(any())).thenReturn(List.of(relation(1L, 901L)));
        when(taskStaffMapper.selectAssignableStaff(99L, 31L)).thenReturn(List.of());

        BizException error = assertThrows(BizException.class,
                () -> taskStaffService.merge(task, List.of(901L, 999L), 99L));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(taskStaffMapper, never()).delete(any());
        verify(taskStaffMapper, never()).insert(any(TaskStaff.class));
    }

    private Task task() {
        Task task = new Task();
        task.setId(10L);
        task.setUnitId(31L);
        task.setOwnerId(99L);
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
