package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.SettingService;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
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
import static org.mockito.Mockito.never;
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
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private FileRefMapper fileRefMapper;

    @InjectMocks
    private TaskService taskService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
        TableInfoHelper.initTableInfo(assistant, Plan.class);
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
        TableInfoHelper.initTableInfo(assistant, Submission.class);
        TableInfoHelper.initTableInfo(assistant, FileRef.class);
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
    void 多行任务列表批量装载截止参数与材料摘要() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(201L, "admin", "管理员", null, List.of("ADMIN"), List.of()), null, List.of()));
        when(dataScopeService.isAllData(any())).thenReturn(true);
        Plan plan = new Plan();
        plan.setId(10L);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        Task first = task(1L, "A01-1-C01");
        Task second = task(2L, "A02-01");
        second.setIndicatorId(101L);
        second.setTopIndicatorId(101L);
        Page<Task> taskPage = new Page<>();
        taskPage.setRecords(List.of(first, second));
        taskPage.setTotal(2);
        when(taskMapper.selectPage(any(), any())).thenReturn(taskPage);

        TaskYear firstYear = taskYear(11L, 1L);
        firstYear.setActualValue(new BigDecimal("0.25"));
        firstYear.setFrozenAmount(new BigDecimal("0.15"));
        TaskYear firstNextYear = taskYear(13L, 1L);
        firstNextYear.setYear(2027);
        firstNextYear.setTarget(BigDecimal.valueOf(2));
        TaskYear secondYear = taskYear(12L, 2L);
        secondYear.setIndicatorId(101L);
        when(taskYearMapper.selectList(any())).thenReturn(List.of(firstNextYear, firstYear, secondYear));

        Indicator indicator = new Indicator();
        indicator.setId(100L);
        indicator.setCode("A01-1");
        indicator.setName("测试指标");
        indicator.setStatCaliber("INCREMENT");
        indicator.setMinFileCount(2);
        Indicator directPercent = new Indicator();
        directPercent.setId(101L);
        directPercent.setCode("A02");
        directPercent.setName("直填百分比指标");
        directPercent.setStatCaliber("DIRECT_PERCENT");
        directPercent.setMinFileCount(0);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator, directPercent));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(new OrgUnit()));
        when(userRefResolver.mapOf(any())).thenReturn(Map.of());

        PlanYear active = new PlanYear();
        active.setPlanId(10L);
        active.setYear(2026);
        active.setStatus("ACTIVE");
        when(planYearMapper.selectList(any())).thenReturn(List.of(active));
        when(settingService.getValue("plan.fillDeadline", "12-31")).thenReturn("12-31");

        Submission firstSubmission = submission(1001L, 1L, "PENDING_REVIEW");
        Submission secondSubmission = submission(1002L, 2L, "REJECTED");
        when(submissionMapper.selectList(any())).thenReturn(List.of(firstSubmission, secondSubmission));
        when(fileRefMapper.selectList(any())).thenReturn(List.of(
                fileRef(1001L), fileRef(1001L), fileRef(1002L)));

        TaskDtos.TaskQuery query = new TaskDtos.TaskQuery();
        query.setYear(2026);
        PageResult<TaskDtos.TaskListItem> result = taskService.page(query);

        assertEquals(2, result.getItems().size());
        assertEquals("2026-12-31", result.getItems().get(0).deadline());
        assertEquals("2026-12-31", result.getItems().get(1).deadline());
        assertEquals(List.of(2026, 2027), result.getItems().get(0).yearTargets().stream()
                .map(TaskDtos.YearTargetView::year).toList());
        assertEquals(BigDecimal.valueOf(2), result.getItems().get(0).yearTargets().get(1).target());
        TaskDtos.TaskListItem firstItem = result.getItems().get(0);
        TaskDtos.TaskListItem secondItem = result.getItems().get(1);
        assertEquals(2026, firstItem.displayYear());
        assertEquals(new BigDecimal("0.60"), firstItem.adjustableAmount());
        assertEquals(null, secondItem.adjustableAmount());
        assertEquals(1001L, firstItem.latestSubmissionId());
        assertEquals("PENDING_REVIEW", firstItem.latestSubmissionStatus());
        assertEquals(2, firstItem.fileCount());
        assertEquals(2, firstItem.requiredFileCount());
        assertEquals(1002L, secondItem.latestSubmissionId());
        assertEquals("REJECTED", secondItem.latestSubmissionStatus());
        assertEquals(1, secondItem.fileCount());
        assertEquals(1, secondItem.requiredFileCount());
        verify(settingService, times(1)).getValue("plan.fillDeadline", "12-31");
        verify(submissionMapper, times(1)).selectList(any());
        verify(fileRefMapper, times(1)).selectList(any());
    }

    @Test
    void 已删除计划下的任务不进入列表() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(201L, "admin", "管理员", null, List.of("ADMIN"), List.of()), null, List.of()));
        when(dataScopeService.isAllData(any())).thenReturn(true);
        when(planMapper.selectList(any())).thenReturn(List.of());

        PageResult<TaskDtos.TaskListItem> result = taskService.page(new TaskDtos.TaskQuery());

        assertEquals(0, result.getTotal());
        assertEquals(0, result.getItems().size());
        verify(taskMapper, never()).selectPage(any(), any());
    }

    private Submission submission(Long id, Long taskId, String status) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setTaskId(taskId);
        submission.setYear(2026);
        submission.setStatus(status);
        return submission;
    }

    private FileRef fileRef(Long submissionId) {
        FileRef fileRef = new FileRef();
        fileRef.setBizType("SUBMISSION");
        fileRef.setBizId(submissionId);
        return fileRef;
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
