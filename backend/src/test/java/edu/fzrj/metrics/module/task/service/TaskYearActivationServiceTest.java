package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskYearActivationServiceTest {

    @Mock
    private PlanYearMapper planYearMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;

    @InjectMocks
    private TaskYearActivationService taskYearActivationService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
    }

    @Test
    void 计划年度已激活时把已下发任务的未激活年度置为进行中() {
        PlanYear active = new PlanYear();
        active.setPlanId(11L);
        active.setYear(2026);
        active.setStatus("ACTIVE");
        Task task = new Task();
        task.setId(21L);
        when(planYearMapper.selectList(any())).thenReturn(List.of(active));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.update(isNull(), any())).thenReturn(2);

        int updated = taskYearActivationService.syncPlan(11L, 99L);

        assertEquals(2, updated);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TaskYear>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskYearMapper).update(isNull(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("year_status"));
        assertTrue(sql.contains("year IN"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("IN_PROGRESS"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(2026));
    }

    @Test
    void 没有激活年度时不执行更新() {
        when(planYearMapper.selectList(any())).thenReturn(List.of());

        int updated = taskYearActivationService.syncPlan(11L, 99L);

        assertEquals(0, updated);
        verify(taskMapper, never()).selectList(any());
        verify(taskYearMapper, never()).update(any(), any());
    }
}
