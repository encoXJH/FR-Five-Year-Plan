package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Review;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.entity.SubmissionHistory;
import edu.fzrj.metrics.module.submission.mapper.ReviewMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionHistoryMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.AnnualTargetGuard;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

    @Mock private SubmissionMapper submissionMapper;
    @Mock private SubmissionHistoryMapper historyMapper;
    @Mock private ReviewMapper reviewMapper;
    @Mock private TaskYearMapper taskYearMapper;
    @Mock private PlanYearMapper planYearMapper;
    @Mock private FileRefMapper fileRefMapper;
    @Mock private FileInfoMapper fileInfoMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private TaskService taskService;
    @Mock private IndicatorService indicatorService;
    @Mock private PlanService planService;
    @Mock private DataScopeService dataScopeService;
    @Mock private UserRefResolver userRefResolver;
    @Mock private ObjectMapper objectMapper;
    @Mock private AnnualTargetGuard annualTargetGuard;
    @InjectMocks private SubmissionService submissionService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Submission.class);
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
        TableInfoHelper.initTableInfo(assistant, FileRef.class);
        TableInfoHelper.initTableInfo(assistant, Review.class);
        TableInfoHelper.initTableInfo(assistant, SubmissionHistory.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
    }

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(20L, "c01", "学院负责人", 10L,
                        List.of("UNIT_OWNER"), List.of()), null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 提交前年度上限守卫失败时不写填报和历史() {
        Submission submission = new Submission();
        submission.setId(1L);
        submission.setTaskId(2L);
        submission.setTaskYearId(3L);
        submission.setPlanId(4L);
        submission.setIndicatorId(5L);
        submission.setUnitId(10L);
        submission.setYear(2026);
        submission.setStatCaliber("INCREMENT");
        submission.setAmount(new BigDecimal("5"));
        submission.setStatus("DRAFT");
        submission.setRevisionNo(0);
        submission.setVersion(1);
        when(submissionMapper.selectOne(any())).thenReturn(submission);
        when(dataScopeService.isUnitScope(any())).thenReturn(true);

        Task task = new Task();
        task.setId(2L);
        task.setStatus("IN_PROGRESS");
        when(taskService.requireTask(2L)).thenReturn(task);

        PlanYear planYear = new PlanYear();
        planYear.setStatus("ACTIVE");
        when(planYearMapper.selectOne(any())).thenReturn(planYear);

        Indicator indicator = new Indicator();
        indicator.setStatCaliber("INCREMENT");
        indicator.setMinFileCount(0);
        indicator.setTargetDirection("HIGHER_BETTER");
        when(indicatorService.requireIndicator(5L)).thenReturn(indicator);
        when(fileRefMapper.selectList(any())).thenReturn(List.of());

        TaskYear taskYear = new TaskYear();
        taskYear.setId(3L);
        taskYear.setTarget(BigDecimal.TEN);
        taskYear.setYearStatus("IN_PROGRESS");
        when(taskYearMapper.selectOne(any())).thenReturn(taskYear);
        doThrow(new BizException(ErrorCode.ANNUAL_TARGET_EXCEEDED))
                .when(annualTargetGuard).checkSubmit(submission, indicator, taskYear);

        BizException error = assertThrows(BizException.class, () -> submissionService.submit(1L, 1));

        assertEquals(60007, error.getErrorCode().getCode());
        verify(submissionMapper, never()).updateById(any(Submission.class));
        verify(historyMapper, never()).insert(any(SubmissionHistory.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void 管理员代填原因缺失或全为空白时拒绝创建(String proxyReason) {
        loginAdmin();
        Task task = new Task();
        task.setId(2L);
        task.setUnitId(10L);
        when(taskService.requireTask(2L)).thenReturn(task);
        SubmissionDtos.CreateRequest request = new SubmissionDtos.CreateRequest();
        request.setProxyReason(proxyReason);

        BizException error = assertThrows(BizException.class, () -> submissionService.create(2L, request));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(submissionMapper, never()).insert(any(Submission.class));
    }

    @Test
    void 管理员代填原因去除首尾空白后保存() {
        loginAdmin();
        Task task = new Task();
        task.setId(2L);
        task.setUnitId(10L);
        task.setIndicatorId(5L);
        task.setPlanId(4L);
        task.setStatus("IN_PROGRESS");
        when(taskService.requireTask(2L)).thenReturn(task);
        when(taskService.findByIds(any())).thenReturn(List.of(task));
        Indicator indicator = new Indicator();
        indicator.setId(5L);
        indicator.setStatCaliber("INCREMENT");
        when(indicatorService.requireIndicator(5L)).thenReturn(indicator);
        when(indicatorService.findByIds(any())).thenReturn(List.of(indicator));
        Plan plan = new Plan();
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        when(planService.requirePlan(4L)).thenReturn(plan);
        PlanYear planYear = new PlanYear();
        planYear.setStatus("ACTIVE");
        when(planYearMapper.selectOne(any())).thenReturn(planYear);
        TaskYear taskYear = new TaskYear();
        taskYear.setId(3L);
        when(taskYearMapper.selectOne(any())).thenReturn(taskYear);
        when(orgUnitMapper.selectList(any())).thenReturn(List.of());
        when(fileRefMapper.selectList(any())).thenReturn(List.of());
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        when(historyMapper.selectList(any())).thenReturn(List.of());
        when(userRefResolver.mapOf(any())).thenReturn(Map.of());
        AtomicReference<Submission> stored = new AtomicReference<>();
        when(submissionMapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            Submission submission = invocation.getArgument(0);
            submission.setId(1L);
            submission.setVersion(1);
            stored.set(submission);
            return 1;
        }).when(submissionMapper).insert(any(Submission.class));
        SubmissionDtos.CreateRequest request = new SubmissionDtos.CreateRequest();
        request.setYear(2026);
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(BigDecimal.ONE);
        request.setValue(value);
        request.setProxyReason("  补录历史数据  ");

        submissionService.create(2L, request);

        assertEquals("补录历史数据", stored.get().getProxyReason());
    }

    @Test
    void 非当前自然年份不能填报() {
        BizException error = assertThrows(BizException.class,
                () -> submissionService.requireFillableYear(4L, 2027));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(planYearMapper, never()).selectOne(any());
    }

    @Test
    void 当前自然年份默认激活后即可填报() {
        PlanYear planYear = new PlanYear();
        planYear.setStatus("ACTIVE");
        when(planYearMapper.selectOne(any())).thenReturn(planYear);

        submissionService.requireFillableYear(4L, 2026);
    }

    @Test
    void 已归档计划在年度校验前拒绝填报提交() {
        Plan plan = new Plan();
        plan.setStatus("ARCHIVED");
        when(planService.requirePlan(4L)).thenReturn(plan);
        doThrow(new BizException(ErrorCode.PLAN_ARCHIVED)).when(planService).requireNotArchived(plan);

        BizException error = assertThrows(BizException.class,
                () -> submissionService.requireFillableYear(4L, 2026));

        assertEquals(30002, error.getErrorCode().getCode());
        verify(planYearMapper, never()).selectOne(any());
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    private void loginAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(99L, "admin", "管理员", null,
                        List.of("ADMIN"), List.of()), null, List.of()));
    }
}
