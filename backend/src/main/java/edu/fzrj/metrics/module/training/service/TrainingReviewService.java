package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import edu.fzrj.metrics.module.training.entity.TrainingProject;
import edu.fzrj.metrics.module.training.entity.TrainingReview;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.mapper.TrainingProjectMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingReviewMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TrainingReviewService {

    private final TrainingSheetMapper sheetMapper;
    private final TrainingReviewMapper reviewMapper;
    private final TrainingProjectMapper projectMapper;
    private final PlanMapper planMapper;
    private final NotificationMapper notificationMapper;
    private final TrainingService trainingService;

    public TrainingReviewService(TrainingSheetMapper sheetMapper, TrainingReviewMapper reviewMapper,
                                 TrainingProjectMapper projectMapper, PlanMapper planMapper,
                                 NotificationMapper notificationMapper, TrainingService trainingService) {
        this.sheetMapper = sheetMapper;
        this.reviewMapper = reviewMapper;
        this.projectMapper = projectMapper;
        this.planMapper = planMapper;
        this.notificationMapper = notificationMapper;
        this.trainingService = trainingService;
    }

    public PageResult<TrainingDtos.SheetSummary> pending(TrainingDtos.PendingQuery query) {
        LoginUser user = SecurityUtils.getLoginUser();
        List<TrainingSheet> actionable = actionableSheets(user, query.getPlanId(), query.getUnitId(),
                query.getReviewNode());
        int from = Math.min((query.getPage() - 1) * query.getPageSize(), actionable.size());
        int to = Math.min(from + query.getPageSize(), actionable.size());
        return new PageResult<>(trainingService.summaries(actionable.subList(from, to)),
                (long) actionable.size(), query.getPage(), query.getPageSize());
    }

    public List<TrainingDtos.SheetSummary> actionableSummaries(LoginUser user) {
        return trainingService.summaries(actionableSheets(user, null, null, null));
    }

    private List<TrainingSheet> actionableSheets(LoginUser user, Long planId, Long unitId, String reviewNode) {
        if (!user.getPermissions().contains("submission:review")) {
            return List.of();
        }
        if (reviewNode != null && !Set.of("UNIT_REVIEW", "LEADER_REVIEW").contains(reviewNode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核节点不合法");
        }
        List<TrainingSheet> sheets = sheetMapper.selectList(new LambdaQueryWrapper<TrainingSheet>()
                .eq(TrainingSheet::getStatus, "PENDING_REVIEW")
                .eq(TrainingSheet::getDeleted, 0L)
                .eq(planId != null, TrainingSheet::getPlanId, planId)
                .eq(unitId != null, TrainingSheet::getUnitId, unitId)
                .eq(reviewNode != null, TrainingSheet::getCurrentReviewNode, reviewNode)
                .orderByAsc(TrainingSheet::getSubmittedAt)
                .orderByAsc(TrainingSheet::getId));
        if (sheets.isEmpty()) {
            return List.of();
        }
        List<Long> planIds = sheets.stream().map(TrainingSheet::getPlanId).distinct().toList();
        Map<Long, Plan> plans = planMapper.selectBatchIds(planIds).stream()
                .collect(Collectors.toMap(Plan::getId, Function.identity()));
        return sheets.stream()
                .filter(sheet -> {
                    Plan plan = plans.get(sheet.getPlanId());
                    return plan != null && (plan.getDeleted() == null || plan.getDeleted() == 0L)
                            && !"ARCHIVED".equals(plan.getStatus());
                })
                .filter(sheet -> trainingService.canReview(sheet, user)).toList();
    }

    @Transactional
    public TrainingDtos.Detail review(Long sheetId, TrainingDtos.ReviewRequest request) {
        if (!Set.of("APPROVED", "REJECTED").contains(request.getResult())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核结果必须为 APPROVED 或 REJECTED");
        }
        if ("REJECTED".equals(request.getResult()) && !StringUtils.hasText(request.getComment())) {
            throw new BizException(ErrorCode.REVIEW_COMMENT_REQUIRED);
        }
        TrainingSheet sheet = trainingService.requireSheetForUpdate(sheetId);
        trainingService.requireVersion(sheet, request.getVersion());
        TrainingProject project = trainingService.requireProject(sheetId, request.getProjectId());
        LoginUser user = SecurityUtils.getLoginUser();
        if (!trainingService.canReview(sheet, project, user)) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        String reviewNode = project.getCurrentReviewNode();
        boolean proxy = isProxy(user, reviewNode);
        LocalDateTime now = LocalDateTime.now();
        TrainingReview review = new TrainingReview();
        review.setSheetId(sheetId);
        review.setProjectId(project.getId());
        review.setRevisionNo(project.getRevisionNo());
        review.setReviewNode(reviewNode);
        review.setReviewerId(user.getUserId());
        review.setResult(request.getResult());
        review.setComment(StringUtils.hasText(request.getComment()) ? request.getComment().trim() : null);
        review.setIsProxy(proxy);
        review.setProxyReason(proxy && StringUtils.hasText(request.getReason()) ? request.getReason().trim() : null);
        review.setCreatedAt(now);
        reviewMapper.insert(review);

        boolean unitApproved = "UNIT_REVIEW".equals(reviewNode) && "APPROVED".equals(request.getResult());
        project.setStatus(unitApproved ? "PENDING_REVIEW" : request.getResult());
        project.setCurrentReviewNode(unitApproved ? "LEADER_REVIEW" : null);
        project.setUpdatedBy(user.getUserId());
        projectMapper.updateById(project);
        trainingService.refreshSheetAggregate(sheet, user.getUserId());
        Plan plan = trainingService.requireTrainingPlan(sheet.getPlanId());
        if ("LEADER_REVIEW".equals(reviewNode) && "APPROVED".equals(request.getResult())) {
            plan.setStatus("ACTIVE");
            plan.setUpdatedBy(user.getUserId());
            if (planMapper.updateById(plan) == 0) {
                throw new BizException(ErrorCode.VERSION_CONFLICT);
            }
        }
        notifySubmitter(project, plan, reviewNode, request.getResult(), review.getComment());
        return trainingService.detail(sheetId);
    }

    private boolean isProxy(LoginUser user, String reviewNode) {
        if (!user.getRoles().contains("ADMIN")) {
            return false;
        }
        return "UNIT_REVIEW".equals(reviewNode)
                ? !user.getRoles().contains("UNIT_OWNER")
                : !user.getRoles().contains("LEADER");
    }

    private void notifySubmitter(TrainingProject project, Plan plan, String reviewNode,
                                 String result, String comment) {
        if (project.getSubmittedBy() == null) {
            return;
        }
        boolean unitApproved = "UNIT_REVIEW".equals(reviewNode) && "APPROVED".equals(result);
        boolean approved = "APPROVED".equals(result);
        Notification notification = new Notification();
        notification.setUserId(project.getSubmittedBy());
        notification.setType("REVIEW_RESULT");
        notification.setTitle(unitApproved ? "培训项目院审通过：" + plan.getName()
                : "培训项目审核" + (approved ? "通过：" : "驳回：") + plan.getName());
        String outcome = unitApproved ? "院审通过，待校级负责人审核。"
                : approved ? "已审核通过。" : "已被驳回，可修改后重新提交。";
        notification.setContent(plan.getName() + "第 " + project.getRowNo() + " 行项目" + outcome
                + (StringUtils.hasText(comment) ? "审核意见：" + comment : ""));
        notification.setBizType("training_project");
        notification.setBizId(project.getId());
        notification.setLink("/training/" + project.getSheetId() + "?from=training&projectId=" + project.getId());
        notification.setFeishuStatus("PENDING");
        notification.setFeishuRetryCount(0);
        notificationMapper.insert(notification);
    }
}
