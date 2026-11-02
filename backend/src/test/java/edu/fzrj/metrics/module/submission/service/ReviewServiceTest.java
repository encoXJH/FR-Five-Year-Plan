package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
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
    @Mock private DataScopeService dataScopeService;
    @Mock private PlanService planService;
    @Mock private AnnualTargetGuard annualTargetGuard;

    private ReviewService reviewService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, Submission.class);
    }

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(submissionMapper, reviewMapper, indicatorMapper, planYearMapper,
                notificationMapper, submissionService, actualRecalcService, indicatorService, taskService,
                taskMapper, dataScopeService, planService, transactionManager(), annualTargetGuard);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(20L, "owner", "校级负责人", null,
                        List.of("SCHOOL_OWNER"), List.of()), null, List.of()));

        Indicator indicator = new Indicator();
        indicator.setOwnerId(20L);
        indicator.setTargetDirection("HIGHER_BETTER");
        lenient().when(indicatorService.requireIndicator(5L)).thenReturn(indicator);
        lenient().when(indicatorService.effectiveOwnerOf(indicator)).thenReturn(20L);
        lenient().when(submissionService.requireSubmission(anyLong())).thenAnswer(invocation -> submission(invocation.getArgument(0)));
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(new BigDecimal("5"));
        lenient().when(submissionService.valueViewOf(any())).thenReturn(value);
        lenient().doThrow(new BizException(ErrorCode.ANNUAL_TARGET_EXCEEDED))
                .when(annualTargetGuard).checkReview(any(), any(), any());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 单笔审核上限校验失败时不写审核和填报() {
        SubmissionDtos.ReviewRequest request = reviewRequest();

        BizException error = assertThrows(BizException.class, () -> reviewService.review(1L, request));

        assertEquals(60007, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    void 批量审核逐条复用上限校验并返回60007() {
        SubmissionDtos.BatchReviewRequest request = new SubmissionDtos.BatchReviewRequest();
        request.setSubmissionIds(List.of(1L, 2L));

        SubmissionDtos.BatchReviewResponse response = reviewService.batchReview(request);

        assertEquals(2, response.results().size());
        for (SubmissionDtos.BatchItemResult result : response.results()) {
            assertFalse(result.success());
            assertEquals(60007, result.code());
        }
        verify(annualTargetGuard, times(2)).checkReview(any(), any(), any());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    void 审核版本不一致返回10005且不写数据() {
        SubmissionDtos.ReviewRequest request = reviewRequest();
        request.setVersion(6);

        BizException error = assertThrows(BizException.class, () -> reviewService.review(1L, request));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    void 当前版本但非待审核状态仍返回60001() {
        Submission reviewed = submission(1L);
        reviewed.setStatus("APPROVED");
        when(submissionService.requireSubmission(1L)).thenReturn(reviewed);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(60001, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    void 并发审核导致更新行数为0时返回10005且不重算() {
        doNothing().when(annualTargetGuard).checkReview(any(), any(), any());
        when(submissionMapper.updateById(any(Submission.class))).thenReturn(0);

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, reviewRequest()));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(submissionMapper).updateById(any(Submission.class));
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
        verify(submissionMapper, never()).updateById(any(Submission.class));
        verify(actualRecalcService, never()).recalcTaskYear(anyLong());
    }

    @Test
    void 待审关键字无匹配时不查询分页并返回空列表() {
        when(dataScopeService.isAllData(any())).thenReturn(true);
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(indicatorMapper.selectList(any())).thenReturn(List.of());
        SubmissionDtos.PendingQuery query = new SubmissionDtos.PendingQuery();
        query.setKeyword("不存在的指标");

        var result = reviewService.pending(query);

        assertEquals(0, result.getTotal());
        assertTrue(result.getItems().isEmpty());
        verify(submissionMapper, never()).selectPage(any(), any());
    }

    @Test
    void 管理员代审不填原因仍允许审核并记录代审标识() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(201L, "admin", "系统管理员", null,
                        List.of("ADMIN"), List.of()), null, List.of()));
        doNothing().when(annualTargetGuard).checkReview(any(), any(), any());
        when(submissionMapper.updateById(any(Submission.class))).thenReturn(1);

        reviewService.review(1L, reviewRequest());

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper).insert(captor.capture());
        assertTrue(captor.getValue().getIsProxy());
        assertEquals(null, captor.getValue().getProxyReason());
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
        submission.setRevisionNo(1);
        submission.setVersion(7);
        return submission;
    }

    private static SubmissionDtos.ReviewRequest reviewRequest() {
        SubmissionDtos.ReviewRequest request = new SubmissionDtos.ReviewRequest();
        request.setResult("APPROVED");
        request.setVersion(7);
        return request;
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
