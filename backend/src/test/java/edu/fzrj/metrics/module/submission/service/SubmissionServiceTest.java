package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.file.service.FileService;
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
import edu.fzrj.metrics.module.task.service.TaskReadScopeService;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    @Mock private FileService fileService;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private TaskService taskService;
    @Mock private IndicatorService indicatorService;
    @Mock private PlanService planService;
    @Mock private DataScopeService dataScopeService;
    @Mock private UserRefResolver userRefResolver;
    @Mock private ObjectMapper objectMapper;
    @Mock private AnnualTargetGuard annualTargetGuard;
    @Mock private TaskReadScopeService taskReadScopeService;
    @Spy @InjectMocks private SubmissionService submissionService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Submission.class);
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
        TableInfoHelper.initTableInfo(assistant, FileRef.class);
        TableInfoHelper.initTableInfo(assistant, FileInfo.class);
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
    void 第二关联学院负责人可正常填报() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(20L, "multi", "吴昉", 10L,
                        List.of("UNIT_OWNER"), List.of(), List.of(10L, 11L)), null, List.of()));
        when(dataScopeService.isUnitScope(any())).thenReturn(true);

        boolean proxy = submissionService.requireSubmitter(11L, null);

        assertEquals(false, proxy);
    }

    @Test
    void 第二关联学院负责人可创建填报草稿() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(20L, "multi", "吴昉", 10L,
                        List.of("UNIT_OWNER"), List.of(), List.of(10L, 11L)), null, List.of()));
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Task task = new Task();
        task.setId(2L);
        task.setUnitId(11L);
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
        taskYear.setYearStatus("IN_PROGRESS");
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
        request.setYear(java.time.Year.now().getValue());
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(BigDecimal.ONE);
        request.setValue(value);

        submissionService.create(2L, request);

        assertEquals(11L, stored.get().getUnitId());
        assertEquals(false, stored.get().getIsProxy());
        verify(submissionMapper).insert(any(Submission.class));
    }

    @Test
    void 已达标年度不能再次创建填报草稿() {
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Task task = new Task();
        task.setId(2L);
        task.setUnitId(10L);
        task.setIndicatorId(5L);
        task.setPlanId(4L);
        task.setStatus("IN_PROGRESS");
        when(taskService.requireTask(2L)).thenReturn(task);
        Indicator indicator = new Indicator();
        indicator.setId(5L);
        indicator.setStatCaliber("INCREMENT");
        when(indicatorService.requireIndicator(5L)).thenReturn(indicator);
        Plan plan = new Plan();
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        when(planService.requirePlan(4L)).thenReturn(plan);
        PlanYear planYear = new PlanYear();
        planYear.setStatus("ACTIVE");
        when(planYearMapper.selectOne(any())).thenReturn(planYear);
        TaskYear taskYear = new TaskYear();
        taskYear.setId(3L);
        taskYear.setYearStatus("ACHIEVED");
        when(taskYearMapper.selectOne(any())).thenReturn(taskYear);
        SubmissionDtos.CreateRequest request = new SubmissionDtos.CreateRequest();
        request.setYear(java.time.Year.now().getValue());
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(BigDecimal.ONE);
        request.setValue(value);

        BizException error = assertThrows(BizException.class, () -> submissionService.create(2L, request));

        assertEquals(60001, error.getErrorCode().getCode());
        verify(submissionMapper, never()).insert(any(Submission.class));
    }

    @Test
    void 已达标后达标前遗留草稿也不能提交() {
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        prepareSuccessfulSubmit();
        TaskYear achievedYear = new TaskYear();
        achievedYear.setId(3L);
        achievedYear.setTarget(BigDecimal.TEN);
        achievedYear.setYearStatus("ACHIEVED");
        when(taskYearMapper.selectByTaskIdAndYearForUpdate(2L, java.time.Year.now().getValue()))
                .thenReturn(achievedYear);

        BizException error = assertThrows(BizException.class, () -> submissionService.submit(1L, 1));

        assertEquals(60001, error.getErrorCode().getCode());
        verify(submissionMapper, never()).updateById(any(Submission.class));
        verify(historyMapper, never()).insert(any(SubmissionHistory.class));
        verify(annualTargetGuard, never()).checkSubmit(any(), any(), any());
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
        taskYear.setYearStatus("IN_PROGRESS");
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

    @Test
    void 填报绑定附件使用集中授权并保存合法引用() {
        FileInfo file = new FileInfo();
        file.setStatus("TEMP");
        when(fileService.requireBindable(7L, "SUBMISSION", 1L)).thenReturn(file);

        submissionService.bindFiles(1L, List.of(7L));

        verify(fileRefMapper).insert(any(FileRef.class));
        verify(fileInfoMapper).update(any(), any());
    }

    @Test
    void 填报绑定授权失败时不创建文件引用() {
        doThrow(new BizException(ErrorCode.DATA_SCOPE_DENIED))
                .when(fileService).requireBindable(7L, "SUBMISSION", 1L);

        BizException error = assertThrows(BizException.class,
                () -> submissionService.bindFiles(1L, List.of(7L)));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(fileRefMapper, never()).insert(any(FileRef.class));
        verify(fileInfoMapper, never()).update(any(), any());
    }

    @Test
    void 已归档计划不能删除填报草稿() {
        Submission submission = new Submission();
        submission.setId(1L);
        submission.setPlanId(4L);
        submission.setTaskId(2L);
        submission.setUnitId(10L);
        submission.setStatus("DRAFT");
        when(submissionMapper.selectOne(any())).thenReturn(submission);
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Plan plan = new Plan();
        plan.setStatus("ARCHIVED");
        when(planService.requirePlan(4L)).thenReturn(plan);
        doThrow(new BizException(ErrorCode.PLAN_ARCHIVED)).when(planService).requireNotArchived(plan);

        BizException error = assertThrows(BizException.class, () -> submissionService.delete(1L));

        assertEquals(30002, error.getErrorCode().getCode());
        verify(fileRefMapper, never()).delete(any());
    }

    @Test
    void 干事重提从院审开始并清理上一轮审核暂存() {
        login(40L, "STAFF");
        Submission submission = prepareSuccessfulSubmit();
        submission.setStatus("REJECTED");
        submission.setRevisionNo(1);
        submission.setApprovedAmount(BigDecimal.ONE);
        submission.setApprovedTotal(BigDecimal.TEN);
        submission.setApprovedNumerator(BigDecimal.ONE);
        submission.setApprovedDenominator(BigDecimal.TEN);
        submission.setApprovedPercent(BigDecimal.ONE);
        submission.setApprovedAchieved(true);
        submission.setReviewedAt(java.time.LocalDateTime.now());
        submission.setReviewerId(20L);

        submissionService.submit(1L, 1);

        assertEquals("PENDING_REVIEW", submission.getStatus());
        assertEquals("STAFF", submission.getSubmitterRole());
        assertEquals("UNIT_REVIEW", submission.getCurrentReviewNode());
        assertEquals(2, submission.getRevisionNo());
        assertEquals(40L, submission.getSubmittedBy());
        assertNull(submission.getApprovedAmount());
        assertNull(submission.getApprovedTotal());
        assertNull(submission.getApprovedNumerator());
        assertNull(submission.getApprovedDenominator());
        assertNull(submission.getApprovedPercent());
        assertNull(submission.getApprovedAchieved());
        assertNull(submission.getReviewedAt());
        assertNull(submission.getReviewerId());
        verify(historyMapper).insert(any(SubmissionHistory.class));
    }

    @Test
    void 学院负责人提交直接送校审() {
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Submission submission = prepareSuccessfulSubmit();

        submissionService.submit(1L, 1);

        assertEquals("UNIT_OWNER", submission.getSubmitterRole());
        assertEquals("LEADER_REVIEW", submission.getCurrentReviewNode());
        assertEquals(20L, submission.getSubmittedBy());
    }

    @Test
    void 干事兼任学院负责人时按负责人直送校审() {
        login(20L, "STAFF", "UNIT_OWNER");
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Submission submission = prepareSuccessfulSubmit();

        submissionService.submit(1L, 1);

        assertEquals("UNIT_OWNER", submission.getSubmitterRole());
        assertEquals("LEADER_REVIEW", submission.getCurrentReviewNode());
    }

    @Test
    void 管理员代填提交直接送校审() {
        loginAdmin();
        Submission submission = prepareSuccessfulSubmit();

        submissionService.submit(1L, 1);

        assertEquals("UNIT_OWNER", submission.getSubmitterRole());
        assertEquals("LEADER_REVIEW", submission.getCurrentReviewNode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNIT_REVIEW", "LEADER_REVIEW"})
    void 两种待审节点均可撤回并清空节点(String node) {
        Submission submission = new Submission();
        submission.setId(1L);
        submission.setTaskId(2L);
        submission.setTaskYearId(3L);
        submission.setStatus("PENDING_REVIEW");
        submission.setSubmitterRole("STAFF");
        submission.setCurrentReviewNode(node);
        submission.setVersion(1);
        when(submissionMapper.selectOne(any())).thenReturn(submission);
        when(submissionMapper.updateById(any(Submission.class))).thenReturn(1);
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(dataScopeService.isUnitScope(any())).thenReturn(true);
        Task task = new Task();
        task.setId(2L);
        task.setUnitId(10L);
        when(taskService.requireCurrentTask(2L)).thenReturn(task);
        TaskYear taskYear = new TaskYear();
        taskYear.setId(3L);
        taskYear.setYearStatus("PENDING_REVIEW");
        when(taskYearMapper.selectById(3L)).thenReturn(taskYear);
        doReturn(null).when(submissionService).detail(1L);

        submissionService.withdraw(1L, 1);

        assertEquals("WITHDRAWN", submission.getStatus());
        assertNull(submission.getCurrentReviewNode());
        assertEquals("IN_PROGRESS", taskYear.getYearStatus());
    }

    private Submission prepareSuccessfulSubmit() {
        Submission submission = new Submission();
        submission.setId(1L);
        submission.setTaskId(2L);
        submission.setTaskYearId(3L);
        submission.setPlanId(4L);
        submission.setIndicatorId(5L);
        submission.setUnitId(10L);
        submission.setYear(java.time.Year.now().getValue());
        submission.setStatCaliber("INCREMENT");
        submission.setAmount(new BigDecimal("5"));
        submission.setStatus("DRAFT");
        submission.setSubmitterRole("UNIT_OWNER");
        submission.setRevisionNo(0);
        submission.setVersion(1);
        when(submissionMapper.selectOne(any())).thenReturn(submission);
        lenient().when(submissionMapper.updateById(any(Submission.class))).thenReturn(1);

        Task task = new Task();
        task.setId(2L);
        task.setUnitId(10L);
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
        when(taskYearMapper.selectByTaskIdAndYearForUpdate(2L, java.time.Year.now().getValue()))
                .thenReturn(taskYear);
        lenient().doReturn(null).when(submissionService).detail(1L);
        return submission;
    }

    private static void login(Long userId, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "user" + userId, "测试用户", 10L,
                        List.of(roles), List.of()), null, List.of()));
    }

    private void loginAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(99L, "admin", "管理员", null,
                        List.of("ADMIN"), List.of()), null, List.of()));
    }
}
