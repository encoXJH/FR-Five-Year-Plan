package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskStaff;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReadScopeServiceTest {

    @Mock
    private TaskStaffMapper taskStaffMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private DataScopeService dataScopeService;
    @InjectMocks
    private TaskReadScopeService taskReadScopeService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TaskStaff.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 纯干事无分配时可见任务集合failClosed() {
        LoginUser user = login(List.of("STAFF"));
        when(dataScopeService.isAssignedScope(user)).thenReturn(true);
        when(taskStaffMapper.selectList(any())).thenReturn(List.of());

        assertEquals(Set.of(), taskReadScopeService.visibleTaskIds(user));
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    void 分配后仅返回本人任务且多角色取并集() {
        LoginUser user = login(List.of("STAFF", "UNIT_OWNER", "SCHOOL_OWNER"));
        when(dataScopeService.isAssignedScope(user)).thenReturn(true);
        when(dataScopeService.isUnitScope(user)).thenReturn(true);
        when(dataScopeService.isOwnerScope(user)).thenReturn(true);
        when(taskStaffMapper.selectList(any())).thenReturn(List.of(relation(3L)));
        Indicator owned = new Indicator();
        owned.setId(9L);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(owned));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L), task(2L), task(3L)));

        assertEquals(Set.of(1L, 2L, 3L), taskReadScopeService.visibleTaskIds(user));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Task>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("OR"));
        assertTrue(query.getValue().getSqlSegment().contains("unit_id"));
        assertTrue(query.getValue().getSqlSegment().contains("top_indicator_id"));
    }

    @Test
    void 移除分配后填报权限立即失效() {
        LoginUser user = login(List.of("STAFF"));
        Task task = task(7L);
        task.setUnitId(31L);
        when(dataScopeService.isAssignedScope(user)).thenReturn(true);
        when(taskStaffMapper.selectCount(any())).thenReturn(1L, 0L);

        assertDoesNotThrow(() -> taskReadScopeService.requireSubmitter(task));
        BizException error = assertThrows(BizException.class,
                () -> taskReadScopeService.requireSubmitter(task));
        assertEquals(20005, error.getErrorCode().getCode());
    }

    private LoginUser login(List<String> roles) {
        LoginUser user = new LoginUser(99L, "staff", "干事", 31L, roles, List.of(), List.of(31L));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user;
    }

    private TaskStaff relation(Long taskId) {
        TaskStaff row = new TaskStaff();
        row.setTaskId(taskId);
        row.setStaffId(99L);
        return row;
    }

    private Task task(Long id) {
        Task task = new Task();
        task.setId(id);
        return task;
    }
}
