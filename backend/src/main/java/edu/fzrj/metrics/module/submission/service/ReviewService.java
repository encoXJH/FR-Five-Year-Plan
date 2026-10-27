package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Review;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.ReviewMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.SubmissionValueRules;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 校级审核（API 约定 15.5、PRD 5.5）：待审核列表、单条审核（通过 / 驳回 / 核减）、批量通过。
 * 通过后在同一事务内重算 B 完成值并逐级刷新 A+、A 缓存（ActualRecalcService），通知填报人。
 */
@Service
public class ReviewService {

    private static final Set<String> SORT_FIELDS = Set.of("submitted_at", "created_at", "year");

    private final SubmissionMapper submissionMapper;
    private final ReviewMapper reviewMapper;
    private final IndicatorMapper indicatorMapper;
    private final PlanYearMapper planYearMapper;
    private final NotificationMapper notificationMapper;
    private final SubmissionService submissionService;
    private final ActualRecalcService actualRecalcService;
    private final IndicatorService indicatorService;
    private final TaskService taskService;
    private final TaskMapper taskMapper;
    private final DataScopeService dataScopeService;
    private final TransactionTemplate requiresNewTemplate;

    public ReviewService(SubmissionMapper submissionMapper, ReviewMapper reviewMapper,
                         IndicatorMapper indicatorMapper, PlanYearMapper planYearMapper,
                         NotificationMapper notificationMapper, SubmissionService submissionService,
                         ActualRecalcService actualRecalcService, IndicatorService indicatorService,
                         TaskService taskService, TaskMapper taskMapper, DataScopeService dataScopeService,
                         PlatformTransactionManager transactionManager) {
        this.submissionMapper = submissionMapper;
        this.reviewMapper = reviewMapper;
        this.indicatorMapper = indicatorMapper;
        this.planYearMapper = planYearMapper;
        this.notificationMapper = notificationMapper;
        this.submissionService = submissionService;
        this.actualRecalcService = actualRecalcService;
        this.indicatorService = indicatorService;
        this.taskService = taskService;
        this.taskMapper = taskMapper;
        this.dataScopeService = dataScopeService;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------- 待审核列表 ----------------

    /** 待审核列表（submission:review；SCHOOL_OWNER 只待审本人负责指标的，管理员 / 校领导全部） */
    public PageResult<SubmissionDtos.ListItem> pending(SubmissionDtos.PendingQuery query) {
        LoginUser user = SecurityUtils.getLoginUser();
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<Submission>()
                .eq(Submission::getStatus, "PENDING_REVIEW")
                .eq(Submission::getDeleted, 0L)
                .eq(query.getPlanId() != null, Submission::getPlanId, query.getPlanId())
                .eq(query.getUnitId() != null, Submission::getUnitId, query.getUnitId())
                .eq(query.getYear() != null, Submission::getYear, query.getYear())
                .orderByAsc(Submission::getSubmittedAt);
        List<Long> historicalTaskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getIsHistorical, true)
                        .eq(Task::getDeleted, 0L)
                        .select(Task::getId))
                .stream().map(Task::getId).toList();
        if (!historicalTaskIds.isEmpty()) {
            wrapper.notIn(Submission::getTaskId, historicalTaskIds);
        }
        if (!dataScopeService.isAllData(user)) {
            if (!dataScopeService.isOwnerScope(user)) {
                return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
            }
            List<Long> visible = ownedIndicatorIdsWithChildren(user.getUserId());
            if (visible.isEmpty()) {
                return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
            }
            wrapper.in(Submission::getIndicatorId, visible);
        }
        if (query.getIndicatorId() != null) {
            List<Long> ids = new ArrayList<>(List.of(query.getIndicatorId()));
            ids.addAll(childrenOf(List.of(query.getIndicatorId())));
            wrapper.in(Submission::getIndicatorId, ids);
        }
        Page<Submission> page = submissionMapper.selectPage(query.toPage(SORT_FIELDS), wrapper);
        return new PageResult<>(submissionService.toListItems(page.getRecords()), page.getTotal(),
                query.getPage(), query.getPageSize());
    }

    // ---------------- 审核 ----------------

    /** 单条审核（通过 / 驳回 / 核减）；事务内：review + submission 状态 → 重算完成值 → 逐级刷新 → 通知 */
    @Transactional
    public SubmissionDtos.Detail review(Long submissionId, SubmissionDtos.ReviewRequest request) {
        return doReview(submissionId, request);
    }

    /**
     * 批量审核通过：逐条在独立事务（REQUIRES_NEW）中处理，任一条失败不影响其他，返回每条结果。
     */
    public SubmissionDtos.BatchReviewResponse batchReview(SubmissionDtos.BatchReviewRequest request) {
        List<Long> ids = List.copyOf(new LinkedHashSet<>(request.getSubmissionIds()));
        List<SubmissionDtos.BatchItemResult> results = new ArrayList<>();
        for (Long id : ids) {
            SubmissionDtos.ReviewRequest item = new SubmissionDtos.ReviewRequest();
            item.setResult("APPROVED");
            item.setComment(request.getComment());
            item.setReason(request.getReason());
            try {
                requiresNewTemplate.executeWithoutResult(status -> doReview(id, item));
                results.add(new SubmissionDtos.BatchItemResult(id, true, 0, "成功"));
            } catch (BizException e) {
                results.add(new SubmissionDtos.BatchItemResult(id, false,
                        e.getErrorCode().getCode(), e.getMessage()));
            } catch (Exception e) {
                results.add(new SubmissionDtos.BatchItemResult(id, false,
                        ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage()));
            }
        }
        return new SubmissionDtos.BatchReviewResponse(results);
    }

    // ---------------- 内部 ----------------

    private SubmissionDtos.Detail doReview(Long submissionId, SubmissionDtos.ReviewRequest request) {
        Submission submission = submissionService.requireSubmission(submissionId);
        if (!"PENDING_REVIEW".equals(submission.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "该填报不在待审核状态");
        }
        String result = request.getResult();
        if (!"APPROVED".equals(result) && !"REJECTED".equals(result)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核结果只能是 APPROVED / REJECTED");
        }
        if ("REJECTED".equals(result) && !StringUtils.hasText(request.getComment())) {
            throw new BizException(ErrorCode.REVIEW_COMMENT_REQUIRED);
        }
        boolean proxy = requireReviewer(submission, request.getReason());
        taskService.requireCurrentTask(submission.getTaskId());
        requireYearNotLocked(submission.getPlanId(), submission.getYear());
        String caliber = submission.getStatCaliber();
        SubmissionDtos.MetricValue approved = request.getApprovedValue();
        if ("APPROVED".equals(result) && approved != null) {
            String caliberError = SubmissionValueRules.caliberError(caliber, approved.getAmount(),
                    approved.getTotal(), approved.getNumerator(), approved.getDenominator(),
                    approved.getPercent(), approved.getAchieved());
            if (caliberError != null) {
                throw new BizException(ErrorCode.VALUE_CALIBER_MISMATCH, caliberError);
            }
            boolean exceeds = SubmissionValueRules.exceedsSubmitted(caliber,
                    submission.getAmount(), submission.getTotal(), submission.getNumerator(),
                    submission.getDenominator(), submission.getPercent(), submission.getAchieved(),
                    approved.getAmount(), approved.getTotal(), approved.getNumerator(),
                    approved.getDenominator(), approved.getPercent(), approved.getAchieved());
            if (exceeds) {
                throw new BizException(ErrorCode.CONFIRMED_EXCEEDS_SUBMITTED);
            }
        }

        Long userId = SecurityUtils.getUserId();
        LocalDateTime now = LocalDateTime.now();
        Review review = new Review();
        review.setSubmissionId(submissionId);
        review.setRevisionNo(submission.getRevisionNo());
        review.setReviewerId(userId);
        review.setResult(result);
        review.setComment(request.getComment());
        SubmissionDtos.MetricValue approvedSnapshot = "APPROVED".equals(result)
                ? (approved != null ? approved : submissionService.valueViewOf(submission)) : null;
        review.setApprovedValueJson(approvedSnapshot == null ? null
                : submissionService.writeJson(approvedSnapshot));
        review.setIsProxy(proxy);
        review.setProxyReason(proxy ? request.getReason() : null);
        review.setCreatedAt(now);
        reviewMapper.insert(review);

        submission.setStatus(result);
        submission.setReviewedAt(now);
        submission.setReviewerId(userId);
        submission.setUpdatedBy(userId);
        if ("APPROVED".equals(result)) {
            SubmissionDtos.MetricValue effective = approved != null ? approved
                    : submissionService.valueViewOf(submission);
            submission.setApprovedAmount(effective.getAmount());
            submission.setApprovedTotal(effective.getTotal());
            submission.setApprovedNumerator(effective.getNumerator());
            submission.setApprovedDenominator(effective.getDenominator());
            submission.setApprovedPercent(effective.getPercent());
            submission.setApprovedAchieved(effective.getAchieved());
        }
        submissionMapper.updateById(submission);

        actualRecalcService.recalcTaskYear(submission.getTaskYearId());
        notifySubmitter(submission, result, request.getComment());
        return submissionService.detail(submissionId);
    }

    /**
     * 审核人校验：指标有效负责人本人审核；平台管理员可代审（敏感操作，必须填写原因）。
     *
     * @return true 表示管理员代审
     */
    private boolean requireReviewer(Submission submission, String reason) {
        LoginUser user = SecurityUtils.getLoginUser();
        Indicator indicator = indicatorService.requireIndicator(submission.getIndicatorId());
        Long effectiveOwner = indicatorService.effectiveOwnerOf(indicator);
        if (effectiveOwner != null && effectiveOwner.equals(user.getUserId())) {
            return false;
        }
        if (user.getRoles().contains("ADMIN")) {
            if (!StringUtils.hasText(reason)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管理员代审必须填写原因（reason）");
            }
            return true;
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    /** 年度已核算锁定不能审核（60003） */
    private void requireYearNotLocked(Long planId, Integer year) {
        PlanYear planYear = planYearMapper.selectOne(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, planId)
                .eq(PlanYear::getYear, year)
                .eq(PlanYear::getDeleted, 0L));
        if (planYear != null && "LOCKED".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.YEAR_SETTLED);
        }
    }

    private void notifySubmitter(Submission submission, String result, String comment) {
        if (submission.getCreatedBy() == null) {
            return;
        }
        Task task = taskService.requireTask(submission.getTaskId());
        Indicator indicator = indicatorService.requireIndicator(submission.getIndicatorId());
        boolean approved = "APPROVED".equals(result);
        Notification notification = new Notification();
        notification.setUserId(submission.getCreatedBy());
        notification.setType("REVIEW_RESULT");
        notification.setTitle("填报审核" + (approved ? "通过" : "驳回") + "：" + task.getCode());
        notification.setContent("【" + indicator.getName() + "】" + submission.getYear() + " 年度填报"
                + (approved ? "已审核通过。" : "已被驳回。")
                + (StringUtils.hasText(comment) ? "审核意见：" + comment : ""));
        notification.setBizType("submission");
        notification.setBizId(submission.getId());
        notification.setFeishuStatus("PENDING");
        notification.setFeishuRetryCount(0);
        notificationMapper.insert(notification);
    }

    /** 本人作为校级负责人负责的指标 id 集合（含 A 的下级 A+）；/todos 待审核聚合复用 */
    public List<Long> ownedIndicatorIdsWithChildren(Long userId) {
        List<Indicator> owned = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getOwnerId, userId)
                .eq(Indicator::getDeleted, 0L)
                .select(Indicator::getId, Indicator::getLevel));
        if (owned.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(owned.stream().map(Indicator::getId).toList());
        ids.addAll(childrenOf(ids));
        return ids;
    }

    private List<Long> childrenOf(List<Long> parentIds) {
        if (parentIds.isEmpty()) {
            return List.of();
        }
        return indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .in(Indicator::getParentId, parentIds)
                        .eq(Indicator::getDeleted, 0L)
                        .select(Indicator::getId))
                .stream().map(Indicator::getId).toList();
    }
}
