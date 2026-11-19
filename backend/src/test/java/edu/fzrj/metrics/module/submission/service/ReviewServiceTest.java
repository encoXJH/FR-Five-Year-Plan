package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Review;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.ReviewMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.AnnualTargetGuard;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private SubmissionMapper submissionMapper;
    @Mock private ReviewMapper reviewMapper;
    @Mock private IndicatorMapper indicatorMapper;
    @Mock private PlanYearMapper planYearMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private SubmissionService submissionService;
    @Mock private ActualRecalcService actualRecalcService;
    @Mock private IndicatorService indicatorService;
    @Mock private TaskService taskService;
    @Mock private TaskMapper taskMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private PlanService planService;
    @Mock private AnnualTargetGuard annualTargetGuard;

    private ReviewService reviewService;
    private Task task;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, Submission.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(submissionMapper, reviewMapper, indicatorMapper, planYearMapper,
                notificationMapper, submissionService, actualRecalcService, indicatorService, taskService,
                taskMapper, sysUserMapper, planService, transactionManager(), annualTargetGuard);
        login(30L, "LEADER");

        Indicator indicator = new Indicator();
        indicator.setId(5L);
        indicator.setName("指标A");
        indicator.setTargetDirection("HIGHER_BETTER");
        lenient().when(indicatorService.requireIndicator(5L)).thenReturn(indicator);
        lenient().when(submissionService.requireSubmission(anyLong()))
                .thenAnswer(invocation -> submission(invocation.getArgument(0)));
        lenient().when(submissionService.valueViewOf(any())).thenReturn(value("5"));
        lenient().when(submissionService.approvedValueViewOf(any())).thenAnswer(invocation -> {
            Submission submission = invocation.getArgument(0);
            return submission.getApprovedAmount() == null ? null : value(submission.getApprovedAmount());
        });
        lenient().when(submissionService.writeJson(any())).thenReturn("{}");
        lenient().when(submissionService.toListItems(any())).thenReturn(List.of());
        lenient().when(submissionMapper.updateById(any(Submission.class))).thenReturn(1);

        task = new Task();
        task.setId(2L);
        task.setOwnerId(20L);
        task.setCode("A01-C01");
        lenient().when(taskService.requireTask(2L)).thenReturn(task);
        lenient().when(taskService.requireCurrentTask(2L)).thenReturn(task);
        lenient().when(taskService.requireCurrentTask(task)).thenReturn(task);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 终审通过才完成认定并重算() {
        Submission submission = submission(1L);
        submission.setCreatedBy(40L);
        when(submissionService.requireSubmission(1L)).thenReturn(submission);

        reviewService.review(1L, reviewRequest());

        assertEquals("APPROVED", submission.getStatus());
        assertNull(submission.getCurrentReviewNode());
        assertEquals(new BigDecimal("5"), submission.getApprovedAmount());
        Review review = capturedReview();
        assertEquals("LEADER_REVIEW", review.getReviewNode());
        assertFalse(review.getIsProxy());
        assertEquals(30L, review.getReviewerId());
        verify(actualRecalcService).recalcTaskYear(3L);
        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(notification.capture());
        assertTrue(notification.getValue().getContent().contains("已审核通过"));
    }

    @Test
    void 院审核减后仅推进到校审且不重算并通知等待终审() {
        Submission submission = staffSubmission(1L);
        submission.setCreatedBy(99L);
        when(submissionService.requireSubmission(1L)).thenReturn(submission);
        allowUnitOwner(20L, 40L);
        SubmissionDtos.ReviewRequest request = reviewRequest();
        request.setApprovedValue(value("4"));

        reviewService.review(1L, request);

        assertEquals("PENDING_REVIEW", submission.getStatus());
        assertEquals("LEADER_REVIEW", submission.getCurrentReviewNode());
        assertEquals(new BigDecimal("4"), submission.getApprovedAmount());
        assertEquals("UNIT_REVIEW", capturedReview().getReviewNode());
        verify(actualRecalcService, never()).recalcTaskYear(anyLong());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertTrue(captor.getValue().getContent().contains("院审通过，待校级负责人审核"));
        assertEquals(40L, captor.getValue().getUserId());
        assertEquals("/tasks/2/submit?year=2026", captor.getValue().getLink());
    }

    @Test
    void 终审未传认定值时继承院审核减值() {
        Submission submission = staffSubmission(1L);
        submission.setCurrentReviewNode("LEADER_REVIEW");
        submission.setApprovedAmount(new BigDecimal("3"));
        when(submissionService.requireSubmission(1L)).thenReturn(submission);

        reviewService.review(1L, reviewRequest());

        assertEquals(new BigDecimal("3"), submission.getApprovedAmount());
        assertEquals("APPROVED", submission.getStatus());
        verify(actualRecalcService).recalcTaskYear(3L);
    }

    @Test
    void 终审请求认定值优先并可继续核减() {
        Submission submission = staffSubmission(1L);
        submission.setCurrentReviewNode("LEADER_REVIEW");
        submission.setApprovedAmount(new BigDecimal("3"));
        when(submissionService.requireSubmission(1L)).thenReturn(submission);
        SubmissionDtos.ReviewRequest request = reviewRequest();
        request.setApprovedValue(value("2"));

        reviewService.review(1L, request);

        assertEquals(new BigDecimal("2"), submission.getApprovedAmount());
        assertEquals("2", valueOfCapturedReviewJson());
    }

    @Test
    void 终审不得调高院审认定值() {
        Submission submission = staffSubmission(1L);
        submission.setCurrentReviewNode("LEADER_REVIEW");
        submission.setApprovedAmount(new BigDecimal("3"));
        when(submissionService.requireSubmission(1L)).thenReturn(submission);
        SubmissionDtos.ReviewRequest request = reviewRequest();
        request.setApprovedValue(value("4"));

        BizException error = assertThrows(BizException.class, () -> reviewService.review(1L, request));

        assertEquals(60005, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    void 院审与终审均可驳回并清空节点() {
        Submission unit = staffSubmission(1L);
        when(submissionService.requireSubmission(1L)).thenReturn(unit);
        allowUnitOwner(20L, 40L);
        SubmissionDtos.ReviewRequest reject = reviewRequest();
        reject.setResult("REJECTED");
        reject.setComment("材料不完整");

        reviewService.review(1L, reject);

        assertEquals("REJECTED", unit.getStatus());
        assertNull(unit.getCurrentReviewNode());
        assertEquals("UNIT_REVIEW", capturedReview().getReviewNode());

        Submission leader = staffSubmission(2L);
        leader.setCurrentReviewNode("LEADER_REVIEW");
        when(submissionService.requireSubmission(2L)).thenReturn(leader);
        login(30L, "LEADER");
        reviewService.review(2L, reject);

        assertEquals("REJECTED", leader.getStatus());
        assertNull(leader.getCurrentReviewNode());
        verify(actualRecalcService, times(2)).recalcTaskYear(3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNIT_REVIEW", "LEADER_REVIEW"})
    void 任一级驳回都必须填写意见(String node) {
        Submission submission = submission(1L);
        submission.setCurrentReviewNode(node);
        when(submissionService.requireSubmission(1L)).thenReturn(submission);
        SubmissionDtos.ReviewRequest request = reviewRequest();
        request.setResult("REJECTED");
        request.setComment(" ");

        BizException error = assertThrows(BizException.class, () -> reviewService.review(1L, request));

        assertEquals(60004, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    void 两级核减均不得超过填报值() {
        SubmissionDtos.ReviewRequest request = reviewRequest();
        request.setApprovedValue(value("6"));

        BizException error = assertThrows(BizException.class, () -> reviewService.review(1L, request));

        assertEquals(60005, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    void 校级负责人不能越级院审() {
        Submission submission = staffSubmission(1L);
        when(submissionService.requireSubmission(1L)).thenReturn(submission);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    void 非任务负责人不能跨院初审() {
        Submission submission = staffSubmission(1L);
        when(submissionService.requireSubmission(1L)).thenReturn(submission);
        login(21L, "UNIT_OWNER");

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).selectOne(any());
    }

    @Test
    void 提交干事不归属任务负责人时拒绝初审() {
        Submission submission = staffSubmission(1L);
        when(submissionService.requireSubmission(1L)).thenReturn(submission);
        allowUnitOwner(21L, 40L);
        login(20L, "UNIT_OWNER");

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(20005, error.getErrorCode().getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAFF", "SCHOOL_OWNER"})
    void 纯干事和建设方向负责人不能审核(String role) {
        login(77L, role);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    void 兼任负责人也不能审核本人提交() {
        Submission submission = submission(1L);
        submission.setSubmittedBy(20L);
        when(submissionService.requireSubmission(1L)).thenReturn(submission);
        login(20L, "LEADER", "UNIT_OWNER");

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(20005, error.getErrorCode().getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNIT_REVIEW", "LEADER_REVIEW"})
    void 管理员可按当前节点代审并保留标识(String node) {
        Submission submission = staffSubmission(1L);
        submission.setCurrentReviewNode(node);
        when(submissionService.requireSubmission(1L)).thenReturn(submission);
        login(99L, "ADMIN");
        SubmissionDtos.ReviewRequest request = reviewRequest();
        request.setReason("代为处理");

        reviewService.review(1L, request);

        Review review = capturedReview();
        assertTrue(review.getIsProxy());
        assertEquals("代为处理", review.getProxyReason());
        assertEquals(node, review.getReviewNode());
    }

    @Test
    void 混合节点批量审核分别推进且独立重算() {
        Submission unit = staffSubmission(1L);
        Submission leader = submission(2L);
        Map<Long, Submission> submissions = new HashMap<>();
        submissions.put(1L, unit);
        submissions.put(2L, leader);
        when(submissionService.requireSubmission(anyLong()))
                .thenAnswer(invocation -> submissions.get(invocation.getArgument(0)));
        login(99L, "ADMIN");
        SubmissionDtos.BatchReviewRequest request = new SubmissionDtos.BatchReviewRequest();
        request.setSubmissionIds(List.of(1L, 2L));

        SubmissionDtos.BatchReviewResponse response = reviewService.batchReview(request);

        assertEquals(2, response.results().size());
        assertTrue(response.results().stream().allMatch(SubmissionDtos.BatchItemResult::success));
        assertEquals("LEADER_REVIEW", unit.getCurrentReviewNode());
        assertEquals("PENDING_REVIEW", unit.getStatus());
        assertEquals("APPROVED", leader.getStatus());
        assertNull(leader.getCurrentReviewNode());
        verify(reviewMapper, times(2)).insert(any(Review.class));
        verify(actualRecalcService, times(1)).recalcTaskYear(3L);
    }

    @Test
    void 批量单条失败不影响其他记录() {
        Submission first = submission(1L);
        Submission second = submission(2L);
        Map<Long, Submission> submissions = Map.of(1L, first, 2L, second);
        when(submissionService.requireSubmission(anyLong()))
                .thenAnswer(invocation -> submissions.get(invocation.getArgument(0)));
        doThrow(new BizException(ErrorCode.INTERNAL_ERROR))
                .when(annualTargetGuard).checkReview(any(), any(), any());
        SubmissionDtos.BatchReviewRequest request = new SubmissionDtos.BatchReviewRequest();
        request.setSubmissionIds(List.of(1L, 2L));

        SubmissionDtos.BatchReviewResponse response = reviewService.batchReview(request);

        assertEquals(2, response.results().size());
        assertTrue(response.results().stream().allMatch(item -> !item.success() && item.code() == 10099));
        verify(annualTargetGuard, times(2)).checkReview(any(), any(), any());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    void 版本不一致返回10005且不写数据() {
        SubmissionDtos.ReviewRequest request = reviewRequest();
        request.setVersion(6);

        BizException error = assertThrows(BizException.class, () -> reviewService.review(1L, request));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    void 非待审核状态返回60001() {
        Submission reviewed = submission(1L);
        reviewed.setStatus("APPROVED");
        reviewed.setCurrentReviewNode(null);
        when(submissionService.requireSubmission(1L)).thenReturn(reviewed);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(60001, error.getErrorCode().getCode());
    }

    @Test
    void 并发审核更新行数为0时返回10005且不重算() {
        when(submissionMapper.updateById(any(Submission.class))).thenReturn(0);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(actualRecalcService, never()).recalcTaskYear(anyLong());
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void 已归档计划在审核写入前被拒绝() {
        Plan plan = new Plan();
        plan.setStatus("ARCHIVED");
        when(planService.requirePlan(4L)).thenReturn(plan);
        doThrow(new BizException(ErrorCode.PLAN_ARCHIVED)).when(planService).requireNotArchived(plan);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(30002, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    void 历史任务不能通过新审核节点写入() {
        doThrow(new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY))
                .when(taskService).requireCurrentTask(2L);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(50006, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(actualRecalcService, never()).recalcTaskYear(anyLong());
    }

    @Test
    void 年度锁定后不能审核() {
        PlanYear year = new PlanYear();
        year.setStatus("LOCKED");
        when(planYearMapper.selectOne(any())).thenReturn(year);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(60003, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
    }

    @Test
    void 管理员待审范围不附加节点限制() {
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<>();

        reviewService.applyActionableScope(wrapper, user(99L, "ADMIN"));

        assertTrue(wrapper.getSqlSegment().isBlank());
    }

    @Test
    void 校级负责人待审范围仅终审节点() {
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<>();

        reviewService.applyActionableScope(wrapper, user(30L, "LEADER"));

        assertTrue(wrapper.getSqlSegment().contains("current_review_node"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("LEADER_REVIEW"));
        assertFalse(wrapper.getSqlSegment().contains("status"));
        assertFalse(wrapper.getSqlSegment().contains("deleted"));
    }

    @Test
    void 学院负责人待审范围限定本人任务和归属干事() {
        Task owned = new Task();
        owned.setId(2L);
        SysUser staff = new SysUser();
        staff.setId(40L);
        when(taskMapper.selectList(any())).thenReturn(List.of(owned));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(staff));
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<>();

        reviewService.applyActionableScope(wrapper, user(20L, "UNIT_OWNER"));

        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("current_review_node"));
        assertTrue(sql.contains("submitter_role"));
        assertTrue(sql.contains("task_id"));
        assertTrue(sql.contains("submitted_by"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("UNIT_REVIEW"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("STAFF"));
        assertFalse(sql.contains("status"));
        assertFalse(sql.contains("deleted"));
    }

    @Test
    void 多角色待审范围取并集() {
        Task owned = new Task();
        owned.setId(2L);
        SysUser staff = new SysUser();
        staff.setId(40L);
        when(taskMapper.selectList(any())).thenReturn(List.of(owned));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(staff));
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<>();

        reviewService.applyActionableScope(wrapper, user(20L, "LEADER", "UNIT_OWNER"));

        assertTrue(wrapper.getSqlSegment().contains("OR"), wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue("LEADER_REVIEW"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("UNIT_REVIEW"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(20L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAFF", "SCHOOL_OWNER"})
    void 无可处理角色待审范围关闭(String role) {
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<>();

        reviewService.applyActionableScope(wrapper, user(77L, role));

        assertTrue(wrapper.getSqlSegment().contains("id"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(-1L));
    }

    @Test
    void 待审列表使用节点筛选及统一可处理范围() {
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(submissionMapper.selectPage(any(), any())).thenReturn(emptyPage());
        SubmissionDtos.PendingQuery query = new SubmissionDtos.PendingQuery();
        query.setReviewNode("LEADER_REVIEW");

        reviewService.pending(query);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Submission>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(submissionMapper).selectPage(any(), captor.capture());
        captor.getValue().getSqlSegment();
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("PENDING_REVIEW"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("LEADER_REVIEW"));
    }

    @Test
    void 待审节点参数不合法时拒绝() {
        SubmissionDtos.PendingQuery query = new SubmissionDtos.PendingQuery();
        query.setReviewNode("OWNER_REVIEW");

        BizException error = assertThrows(BizException.class, () -> reviewService.pending(query));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(submissionMapper, never()).selectPage(any(), any());
    }

    private void allowUnitOwner(Long staffOwnerId, Long staffId) {
        login(20L, "UNIT_OWNER");
        SysUser staff = new SysUser();
        staff.setId(staffId);
        staff.setOwnerUserId(staffOwnerId);
        when(sysUserMapper.selectOne(any())).thenReturn(staff);
    }

    private Review capturedReview() {
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper).insert(captor.capture());
        return captor.getValue();
    }

    private String valueOfCapturedReviewJson() {
        ArgumentCaptor<SubmissionDtos.MetricValue> captor = ArgumentCaptor.forClass(SubmissionDtos.MetricValue.class);
        verify(submissionService).writeJson(captor.capture());
        return captor.getValue().getAmount().toPlainString();
    }

    private static Submission submission(Long id) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setTaskId(2L);
        submission.setTaskYearId(3L);
        submission.setPlanId(4L);
        submission.setIndicatorId(5L);
        submission.setYear(2026);
        submission.setStatCaliber("INCREMENT");
        submission.setAmount(new BigDecimal("5"));
        submission.setStatus("PENDING_REVIEW");
        submission.setSubmitterRole("UNIT_OWNER");
        submission.setCurrentReviewNode("LEADER_REVIEW");
        submission.setSubmittedBy(40L);
        submission.setRevisionNo(1);
        submission.setVersion(7);
        return submission;
    }

    private static Submission staffSubmission(Long id) {
        Submission submission = submission(id);
        submission.setSubmitterRole("STAFF");
        submission.setCurrentReviewNode("UNIT_REVIEW");
        submission.setSubmittedBy(40L);
        return submission;
    }

    private static SubmissionDtos.MetricValue value(String amount) {
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(new BigDecimal(amount));
        return value;
    }

    private static SubmissionDtos.MetricValue value(BigDecimal amount) {
        return value(amount.toPlainString());
    }

    private static SubmissionDtos.ReviewRequest reviewRequest() {
        SubmissionDtos.ReviewRequest request = new SubmissionDtos.ReviewRequest();
        request.setResult("APPROVED");
        request.setVersion(7);
        return request;
    }

    private static Page<Submission> emptyPage() {
        Page<Submission> page = new Page<>();
        page.setRecords(List.of());
        page.setTotal(0);
        return page;
    }

    private static LoginUser user(Long id, String... roles) {
        return new LoginUser(id, "user" + id, "测试用户", null,
                List.of(roles), List.of());
    }

    private static void login(Long id, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user(id, roles), null, List.of()));
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
