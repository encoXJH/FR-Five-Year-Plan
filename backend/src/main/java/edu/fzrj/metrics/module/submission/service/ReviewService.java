package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Review;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.ReviewMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.AnnualTargetGuard;
import edu.fzrj.metrics.module.submission.service.support.SubmissionValueRules;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
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
 * 两级填报审核：干事由学院负责人初审、校级负责人终审，负责人填报直接终审。
 * 终审通过后在同一事务内重算完成值并逐级刷新缓存，通知填报人。
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
    private final SysUserMapper sysUserMapper;
    private final PlanService planService;
    private final TransactionTemplate requiresNewTemplate;
    private final AnnualTargetGuard annualTargetGuard;

    public ReviewService(SubmissionMapper submissionMapper, ReviewMapper reviewMapper,
                         IndicatorMapper indicatorMapper, PlanYearMapper planYearMapper,
                         NotificationMapper notificationMapper, SubmissionService submissionService,
                         ActualRecalcService actualRecalcService, IndicatorService indicatorService,
                         TaskService taskService, TaskMapper taskMapper, SysUserMapper sysUserMapper,
                         PlanService planService, PlatformTransactionManager transactionManager,
                         AnnualTargetGuard annualTargetGuard) {
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
        this.sysUserMapper = sysUserMapper;
        this.planService = planService;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.annualTargetGuard = annualTargetGuard;
    }

    // ---------------- 待审核列表 ----------------

    /** 待审核列表只包含当前用户可处理的节点与非历史任务。 */
    public PageResult<SubmissionDtos.ListItem> pending(SubmissionDtos.PendingQuery query) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (StringUtils.hasText(query.getReviewNode())
                && !Set.of("UNIT_REVIEW", "LEADER_REVIEW").contains(query.getReviewNode())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核节点取值不合法：" + query.getReviewNode());
        }
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<Submission>()
                .eq(Submission::getStatus, "PENDING_REVIEW")
                .eq(Submission::getDeleted, 0L)
                .eq(query.getPlanId() != null, Submission::getPlanId, query.getPlanId())
                .eq(query.getUnitId() != null, Submission::getUnitId, query.getUnitId())
                .eq(query.getYear() != null, Submission::getYear, query.getYear())
                .eq(StringUtils.hasText(query.getReviewNode()),
                        Submission::getCurrentReviewNode, query.getReviewNode())
                .orderByAsc(Submission::getSubmittedAt);
        List<Long> historicalTaskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getIsHistorical, true)
                        .eq(Task::getDeleted, 0L)
                        .select(Task::getId))
                .stream().map(Task::getId).toList();
        if (!historicalTaskIds.isEmpty()) {
            wrapper.notIn(Submission::getTaskId, historicalTaskIds);
        }
        applyActionableScope(wrapper, user);
        if (query.getIndicatorId() != null) {
            List<Long> ids = new ArrayList<>(List.of(query.getIndicatorId()));
            ids.addAll(childrenOf(List.of(query.getIndicatorId())));
            wrapper.in(Submission::getIndicatorId, ids);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                            .like(Task::getCode, keyword)
                            .eq(Task::getDeleted, 0L)
                            .select(Task::getId))
                    .stream().map(Task::getId).toList();
            List<Long> indicatorIds = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                            .and(w -> w.like(Indicator::getCode, keyword).or().like(Indicator::getName, keyword))
                            .eq(Indicator::getDeleted, 0L)
                            .select(Indicator::getId))
                    .stream().map(Indicator::getId).toList();
            if (taskIds.isEmpty() && indicatorIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
            }
            wrapper.and(w -> {
                if (!taskIds.isEmpty()) {
                    w.in(Submission::getTaskId, taskIds);
                }
                if (!indicatorIds.isEmpty()) {
                    if (!taskIds.isEmpty()) {
                        w.or();
                    }
                    w.in(Submission::getIndicatorId, indicatorIds);
                }
            });
        }
        wrapper.ge(query.getSubmittedAtFrom() != null, Submission::getSubmittedAt, query.getSubmittedAtFrom())
                .le(query.getSubmittedAtTo() != null, Submission::getSubmittedAt, query.getSubmittedAtTo());
        Page<Submission> page = submissionMapper.selectPage(query.toPage(SORT_FIELDS), wrapper);
        return new PageResult<>(submissionService.toListItems(page.getRecords()), page.getTotal(),
                query.getPage(), query.getPageSize());
    }

    /** 添加当前用户可处理的审核节点及数据范围，不附加状态或逻辑删除条件。 */
    public void applyActionableScope(LambdaQueryWrapper<Submission> wrapper, LoginUser user) {
        if (user.getRoles().contains("ADMIN")) {
            return;
        }
        boolean leader = user.getRoles().contains("LEADER");
        boolean unitOwner = user.getRoles().contains("UNIT_OWNER");
        List<Long> ownedTaskIds = List.of();
        List<Long> ownedStaffIds = List.of();
        if (unitOwner) {
            ownedTaskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                            .eq(Task::getOwnerId, user.getUserId())
                            .eq(Task::getDeleted, 0L)
                            .select(Task::getId))
                    .stream().map(Task::getId).toList();
            if (!ownedTaskIds.isEmpty()) {
                ownedStaffIds = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                                .eq(SysUser::getOwnerUserId, user.getUserId())
                                .eq(SysUser::getDeleted, 0L)
                                .select(SysUser::getId))
                        .stream().map(SysUser::getId).toList();
            }
        }
        if (!leader && (ownedTaskIds.isEmpty() || ownedStaffIds.isEmpty())) {
            wrapper.eq(Submission::getId, -1L);
            return;
        }
        List<Long> unitTaskIds = ownedTaskIds;
        List<Long> unitStaffIds = ownedStaffIds;
        boolean hasUnitScope = !unitTaskIds.isEmpty() && !unitStaffIds.isEmpty();
        wrapper.and(scope -> {
            if (leader) {
                scope.eq(Submission::getCurrentReviewNode, "LEADER_REVIEW");
                if (unitOwner) {
                    scope.ne(Submission::getSubmittedBy, user.getUserId());
                }
                if (hasUnitScope) {
                    scope.or(unit -> unit.eq(Submission::getCurrentReviewNode, "UNIT_REVIEW")
                            .eq(Submission::getSubmitterRole, "STAFF")
                            .in(Submission::getTaskId, unitTaskIds)
                            .in(Submission::getSubmittedBy, unitStaffIds));
                }
            } else {
                scope.and(unit -> unit.eq(Submission::getCurrentReviewNode, "UNIT_REVIEW")
                        .eq(Submission::getSubmitterRole, "STAFF")
                        .in(Submission::getTaskId, unitTaskIds)
                        .in(Submission::getSubmittedBy, unitStaffIds));
            }
        });
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
                requiresNewTemplate.executeWithoutResult(status -> {
                    item.setVersion(submissionService.requireSubmission(id).getVersion());
                    doReview(id, item);
                });
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
        requireVersion(submission, request.getVersion());
        if (!"PENDING_REVIEW".equals(submission.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "该填报不在待审核状态");
        }
        String reviewNode = submission.getCurrentReviewNode();
        if (!"UNIT_REVIEW".equals(reviewNode) && !"LEADER_REVIEW".equals(reviewNode)) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "待审核填报缺少有效审核节点");
        }
        String result = request.getResult();
        if (!"APPROVED".equals(result) && !"REJECTED".equals(result)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核结果只能是 APPROVED / REJECTED");
        }
        if ("REJECTED".equals(result) && !StringUtils.hasText(request.getComment())) {
            throw new BizException(ErrorCode.REVIEW_COMMENT_REQUIRED);
        }
        boolean proxy = requireReviewer(submission);
        taskService.requireCurrentTask(submission.getTaskId());
        planService.requireNotArchived(planService.requirePlan(submission.getPlanId()));
        requireYearNotLocked(submission.getPlanId(), submission.getYear());
        String caliber = submission.getStatCaliber();
        SubmissionDtos.MetricValue requested = request.getApprovedValue();
        if ("APPROVED".equals(result) && requested != null) {
            String caliberError = SubmissionValueRules.caliberError(caliber, requested.getAmount(),
                    requested.getTotal(), requested.getNumerator(), requested.getDenominator(),
                    requested.getPercent(), requested.getAchieved());
            if (caliberError != null) {
                throw new BizException(ErrorCode.VALUE_CALIBER_MISMATCH, caliberError);
            }
            SubmissionDtos.MetricValue reviewBase = submissionService.valueViewOf(submission);
            SubmissionDtos.MetricValue unitApproved = "LEADER_REVIEW".equals(reviewNode)
                    ? submissionService.approvedValueViewOf(submission) : null;
            if (unitApproved != null) {
                reviewBase = unitApproved;
            }
            boolean exceeds = SubmissionValueRules.exceedsSubmitted(caliber,
                    reviewBase.getAmount(), reviewBase.getTotal(), reviewBase.getNumerator(),
                    reviewBase.getDenominator(), reviewBase.getPercent(), reviewBase.getAchieved(),
                    requested.getAmount(), requested.getTotal(), requested.getNumerator(),
                    requested.getDenominator(), requested.getPercent(), requested.getAchieved());
            if (exceeds) {
                throw new BizException(ErrorCode.CONFIRMED_EXCEEDS_SUBMITTED);
            }
        }
        SubmissionDtos.MetricValue effective = null;
        if ("APPROVED".equals(result)) {
            effective = requested;
            if (effective == null && "LEADER_REVIEW".equals(reviewNode)) {
                effective = submissionService.approvedValueViewOf(submission);
            }
            if (effective == null) {
                effective = submissionService.valueViewOf(submission);
            }
        }
        actualRecalcService.lockAggregationForReview(submission.getTaskYearId());
        if ("APPROVED".equals(result)) {
            annualTargetGuard.checkReview(submission,
                    indicatorService.requireIndicator(submission.getIndicatorId()), effective);
        } else {
            annualTargetGuard.lockForReview(submission);
        }

        Long userId = SecurityUtils.getUserId();
        LocalDateTime now = LocalDateTime.now();
        Review review = new Review();
        review.setSubmissionId(submissionId);
        review.setRevisionNo(submission.getRevisionNo());
        review.setReviewNode(reviewNode);
        review.setReviewerId(userId);
        review.setResult(result);
        review.setComment(request.getComment());
        review.setApprovedValueJson(effective == null ? null : submissionService.writeJson(effective));
        review.setIsProxy(proxy);
        review.setProxyReason(proxy ? request.getReason() : null);
        review.setCreatedAt(now);
        reviewMapper.insert(review);

        boolean unitApproved = "UNIT_REVIEW".equals(reviewNode) && "APPROVED".equals(result);
        submission.setStatus(unitApproved ? "PENDING_REVIEW" : result);
        submission.setCurrentReviewNode(unitApproved ? "LEADER_REVIEW" : null);
        submission.setReviewedAt(now);
        submission.setReviewerId(userId);
        submission.setUpdatedBy(userId);
        if ("APPROVED".equals(result)) {
            applyApprovedValue(submission, effective);
        }
        if (submissionMapper.updateById(submission) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + requireSubmissionVersion(submissionId));
        }

        if ("LEADER_REVIEW".equals(reviewNode) || "REJECTED".equals(result)) {
            actualRecalcService.recalcTaskYear(submission.getTaskYearId());
        }
        notifySubmitter(submission, reviewNode, result, request.getComment());
        return submissionService.detail(submissionId);
    }

    private void applyApprovedValue(Submission submission, SubmissionDtos.MetricValue value) {
        submission.setApprovedAmount(value.getAmount());
        submission.setApprovedTotal(value.getTotal());
        submission.setApprovedNumerator(value.getNumerator());
        submission.setApprovedDenominator(value.getDenominator());
        submission.setApprovedPercent(value.getPercent());
        submission.setApprovedAchieved(value.getAchieved());
    }

    private void requireVersion(Submission submission, Integer version) {
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 version（乐观锁版本号）");
        }
        if (!submission.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + submission.getVersion());
        }
    }

    private Integer requireSubmissionVersion(Long submissionId) {
        return submissionService.requireSubmission(submissionId).getVersion();
    }

    /**
     * 按数据库当前节点鉴权：学院负责人仅审归属干事，校级负责人仅终审，管理员可代审。
     *
     * @return true 表示管理员代审
     */
    private boolean requireReviewer(Submission submission) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (!user.getRoles().contains("ADMIN") && user.getRoles().contains("UNIT_OWNER")
                && user.getUserId().equals(submission.getSubmittedBy())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        if ("UNIT_REVIEW".equals(submission.getCurrentReviewNode())
                && user.getRoles().contains("UNIT_OWNER")) {
            Task task = taskService.requireTask(submission.getTaskId());
            if (!user.getUserId().equals(task.getOwnerId())
                    || !"STAFF".equals(submission.getSubmitterRole())
                    || submission.getSubmittedBy() == null) {
                throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
            }
            SysUser submitter = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getId, submission.getSubmittedBy())
                    .eq(SysUser::getDeleted, 0L));
            if (submitter == null || !user.getUserId().equals(submitter.getOwnerUserId())) {
                throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
            }
            return false;
        }
        if ("LEADER_REVIEW".equals(submission.getCurrentReviewNode())
                && user.getRoles().contains("LEADER")) {
            return false;
        }
        if (user.getRoles().contains("ADMIN")) {
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

    private void notifySubmitter(Submission submission, String reviewNode, String result, String comment) {
        Long recipientId = submission.getSubmittedBy() != null
                ? submission.getSubmittedBy() : submission.getCreatedBy();
        if (recipientId == null) {
            return;
        }
        Task task = taskService.requireTask(submission.getTaskId());
        Indicator indicator = indicatorService.requireIndicator(submission.getIndicatorId());
        boolean unitApproved = "UNIT_REVIEW".equals(reviewNode) && "APPROVED".equals(result);
        boolean approved = "APPROVED".equals(result);
        Notification notification = new Notification();
        notification.setUserId(recipientId);
        notification.setType("REVIEW_RESULT");
        notification.setTitle(unitApproved ? "填报院审通过：" + task.getCode()
                : "填报审核" + (approved ? "通过" : "驳回") + "：" + task.getCode());
        String outcome = unitApproved ? "院审通过，待校级负责人审核。"
                : approved ? "已审核通过。" : "已被驳回。";
        notification.setContent("【" + indicator.getName() + "】" + submission.getYear() + " 年度填报"
                + outcome + (StringUtils.hasText(comment) ? "审核意见：" + comment : ""));
        notification.setBizType("submission");
        notification.setBizId(submission.getId());
        notification.setLink("/tasks/" + submission.getTaskId() + "/submit?year=" + submission.getYear());
        notification.setFeishuStatus("PENDING");
        notification.setFeishuRetryCount(0);
        notificationMapper.insert(notification);
    }

    /** 本人作为建设方向负责人负责的指标 id 集合（含 A 的下级 A+）；/todos 待审核聚合复用 */
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
