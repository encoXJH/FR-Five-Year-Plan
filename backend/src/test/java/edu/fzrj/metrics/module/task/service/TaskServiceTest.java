package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.SettingService;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private PlanMapper planMapper;
    @Mock
    private PlanYearMapper planYearMapper;
    @Mock
    private UserRefResolver userRefResolver;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private SettingService settingService;

    @InjectMocks
    private TaskService taskService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
    }

    @Test
    void 历史任务保持可读但执行守卫返回50006() {
        Task task = new Task();
        task.setId(7L);
        task.setDeleted(0L);
        task.setIsHistorical(true);
        when(taskMapper.selectOne(any())).thenReturn(task);

        assertSame(task, taskService.requireTask(7L));
        BizException error = assertThrows(BizException.class, () -> taskService.requireCurrentTask(7L));
        assertEquals(50006, error.getErrorCode().getCode());
    }

    @Test
    void 当前任务及未标记的旧数据可继续执行() {
        Task current = new Task();
        current.setIsHistorical(false);
        Task legacy = new Task();
        legacy.setIsHistorical(null);

        assertSame(current, taskService.requireCurrentTask(current));
        assertSame(legacy, taskService.requireCurrentTask(legacy));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 多行任务列表只读取一次填报截止参数() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(201L, "admin", "管理员", null, List.of("ADMIN"), List.of()), null, List.of()));
        when(dataScopeService.isAllData(any())).thenReturn(true);

        Task first = task(1L, "A01-1-C01");
        Task second = task(2L, "A01-1-C02");
        Page<Task> taskPage = new Page<>();
        taskPage.setRecords(List.of(first, second));
        taskPage.setTotal(2);
        when(taskMapper.selectPage(any(), any())).thenReturn(taskPage);

        TaskYear firstYear = taskYear(11L, 1L);
        TaskYear secondYear = taskYear(12L, 2L);
        when(taskYearMapper.selectList(any())).thenReturn(List.of(firstYear, secondYear));

        Indicator indicator = new Indicator();
        indicator.setId(100L);
        indicator.setCode("A01-1");
        indicator.setName("测试指标");
        indicator.setStatCaliber("INCREMENT");
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(new OrgUnit()));
        when(userRefResolver.mapOf(any())).thenReturn(Map.of());

        PlanYear active = new PlanYear();
        active.setPlanId(10L);
        active.setYear(2026);
        active.setStatus("ACTIVE");
        when(planYearMapper.selectList(any())).thenReturn(List.of(active));
        when(settingService.getValue("plan.fillDeadline", "12-31")).thenReturn("12-31");

        TaskDtos.TaskQuery query = new TaskDtos.TaskQuery();
        query.setYear(2026);
        PageResult<TaskDtos.TaskListItem> result = taskService.page(query);

        assertEquals(2, result.getItems().size());
        assertEquals("2026-12-31", result.getItems().get(0).deadline());
        assertEquals("2026-12-31", result.getItems().get(1).deadline());
        verify(settingService, times(1)).getValue("plan.fillDeadline", "12-31");
    }

    private Task task(Long id, String code) {
        Task task = new Task();
        task.setId(id);
        task.setPlanId(10L);
        task.setIndicatorId(100L);
        task.setTopIndicatorId(100L);
        task.setUnitId(101L);
        task.setOwnerId(211L);
        task.setCode(code);
        task.setStatus("DISTRIBUTED");
        task.setIsHistorical(false);
        return task;
    }

    private TaskYear taskYear(Long id, Long taskId) {
        TaskYear year = new TaskYear();
        year.setId(id);
        year.setTaskId(taskId);
        year.setPlanId(10L);
        year.setIndicatorId(100L);
        year.setUnitId(101L);
        year.setYear(2026);
        year.setTarget(BigDecimal.ONE);
        year.setYearStatus("IN_PROGRESS");
        return year;
    }
}
