package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.entity.ApprovalStep;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentMapper;
import edu.fzrj.metrics.module.adjustment.mapper.ApprovalStepMapper;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.MajorGroup;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.entity.UserUnit;
import edu.fzrj.metrics.module.system.mapper.MajorGroupMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.mapper.UserUnitMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskStaff;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
import edu.fzrj.metrics.module.training.entity.TrainingProject;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.entity.TrainingSheetStaff;
import edu.fzrj.metrics.module.training.mapper.TrainingProjectMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetStaffMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserDeletionService {

    private static final Set<String> ENDED_TASK_STATUSES = Set.of("CLOSED", "TRANSFERRED_OUT");
    private static final Set<String> PENDING_ADJUSTMENT_STATUSES = Set.of(
            "PENDING_OWNER_REVIEW", "PENDING_RECEIVER_CONFIRM", "PENDING_LEADER_APPROVAL");

    private final UserAdminService userAdminService;
    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserUnitMapper userUnitMapper;
    private final TaskStaffMapper taskStaffMapper;
    private final PlanMapper planMapper;
    private final TaskMapper taskMapper;
    private final IndicatorMapper indicatorMapper;
    private final DirectionMapper directionMapper;
    private final MajorGroupMapper majorGroupMapper;
    private final SubmissionMapper submissionMapper;
    private final AdjustmentMapper adjustmentMapper;
    private final ApprovalStepMapper approvalStepMapper;
    private final TrainingSheetStaffMapper trainingSheetStaffMapper;
    private final TrainingSheetMapper trainingSheetMapper;
    private final TrainingProjectMapper trainingProjectMapper;

    public UserDeletionService(UserAdminService userAdminService, SysUserMapper sysUserMapper,
                               UserRoleMapper userRoleMapper, UserUnitMapper userUnitMapper,
                               TaskStaffMapper taskStaffMapper, PlanMapper planMapper,
                               TaskMapper taskMapper, IndicatorMapper indicatorMapper,
                               DirectionMapper directionMapper, MajorGroupMapper majorGroupMapper,
                               SubmissionMapper submissionMapper, AdjustmentMapper adjustmentMapper,
                               ApprovalStepMapper approvalStepMapper,
                               TrainingSheetStaffMapper trainingSheetStaffMapper,
                               TrainingSheetMapper trainingSheetMapper,
                               TrainingProjectMapper trainingProjectMapper) {
        this.userAdminService = userAdminService;
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.userUnitMapper = userUnitMapper;
        this.taskStaffMapper = taskStaffMapper;
        this.planMapper = planMapper;
        this.taskMapper = taskMapper;
        this.indicatorMapper = indicatorMapper;
        this.directionMapper = directionMapper;
        this.majorGroupMapper = majorGroupMapper;
        this.submissionMapper = submissionMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.approvalStepMapper = approvalStepMapper;
        this.trainingSheetStaffMapper = trainingSheetStaffMapper;
        this.trainingSheetMapper = trainingSheetMapper;
        this.trainingProjectMapper = trainingProjectMapper;
    }

    public SystemDtos.UserItem snapshot(Long userId) {
        SysUser user = userAdminService.requireUser(userId);
        return userAdminService.toItems(List.of(user)).get(0);
    }

    @Transactional
    public void delete(Long userId, SystemDtos.UserDeleteRequest request) {
        userAdminService.requireAdmin();
        deleteAuthorized(userAdminService.requireUser(userId), request);
    }

    @Transactional
    public void deleteManaged(Long userId, SystemDtos.UserDeleteRequest request) {
        deleteAuthorized(userAdminService.requireManagedAccount(userId), request);
    }

    @Transactional
    public SystemDtos.UserItem patchManaged(Long userId, JsonNode body) {
        String targetRole = body.has("role") && !body.get("role").isNull()
                ? body.get("role").asText() : null;
        if (targetRole != null && !Set.of("UNIT_OWNER", "STAFF").contains(targetRole)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "学院账号角色只能为 UNIT_OWNER 或 STAFF");
        }
        SystemDtos.UserItem patched = userAdminService.patchStaff(userId, body);
        if (targetRole == null || patched.roles().equals(List.of(targetRole))) {
            return patched;
        }
        String reason = body.path("reason").isTextual() ? body.path("reason").asText().trim() : "";
        if (reason.isEmpty() || reason.length() > 500) {
            throw new BizException(ErrorCode.PARAM_INVALID, "转换角色必须填写 1–500 字原因");
        }
        SystemDtos.RolesRequest rolesRequest = new SystemDtos.RolesRequest();
        rolesRequest.setRoles(List.of(targetRole));
        rolesRequest.setReason(reason);
        rolesRequest.setVersion(patched.version());
        return updateManagedRoles(userId, rolesRequest).user();
    }

    @Transactional
    public SystemDtos.RoleChangeView updateManagedRoles(Long userId, SystemDtos.RolesRequest request) {
        SysUser user = userAdminService.requireManagedAccount(userId);
        if (!user.getVersion().equals(request.getVersion())) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + user.getVersion());
        }
        List<String> before = userAdminService.rolesOf(userId);
        String currentRole = before.get(0);
        List<String> requested = request.getRoles();
        if (requested == null || requested.size() != 1
                || !Set.of("UNIT_OWNER", "STAFF").contains(requested.get(0))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "学院负责人只能将账号转换为 UNIT_OWNER 或 STAFF 单角色");
        }
        String targetRole = requested.get(0);
        if (currentRole.equals(targetRole)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标角色与当前角色相同");
        }
        List<String> blockers = findRoleChangeBlockers(userId);
        if (!blockers.isEmpty()) {
            throw new BizException(ErrorCode.HAS_CHILDREN,
                    "用户仍存在有效业务引用，不能转换角色：" + String.join("、", blockers));
        }

        user.setOwnerUserId("STAFF".equals(targetRole) ? SecurityUtils.getUserId() : null);
        user.setUpdatedBy(SecurityUtils.getUserId());
        userAdminService.replaceRoles(userId, List.of(targetRole));
        if (sysUserMapper.updateById(user) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "用户已被他人修改，请刷新后重试");
        }
        userAdminService.revokeAllRefreshTokens(userId);
        return new SystemDtos.RoleChangeView(
                userAdminService.toItems(List.of(sysUserMapper.selectById(userId))).get(0),
                before, List.of(targetRole));
    }

    private void deleteAuthorized(SysUser user, SystemDtos.UserDeleteRequest request) {
        Long userId = user.getId();
        if (!user.getVersion().equals(request.getVersion())) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + user.getVersion());
        }
        if (userId.equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能删除当前登录账号");
        }
        requireNotLastEnabledAdmin(user);
        List<String> blockers = findBlockers(userId);
        if (!blockers.isEmpty()) {
            throw new BizException(ErrorCode.HAS_CHILDREN,
                    "用户仍存在有效业务引用，不能删除：" + String.join("、", blockers));
        }

        user.setDeleted(userId);
        user.setIsEnabled(false);
        user.setUpdatedBy(SecurityUtils.getUserId());
        if (sysUserMapper.updateById(user) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "用户已被他人修改，请刷新后重试");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        userUnitMapper.delete(new LambdaQueryWrapper<UserUnit>().eq(UserUnit::getUserId, userId));
        taskStaffMapper.delete(new LambdaQueryWrapper<TaskStaff>().eq(TaskStaff::getStaffId, userId));
        trainingSheetStaffMapper.delete(new LambdaQueryWrapper<TrainingSheetStaff>()
                .eq(TrainingSheetStaff::getStaffId, userId));
        userAdminService.revokeAllRefreshTokens(userId);
    }

    private void requireNotLastEnabledAdmin(SysUser user) {
        if (!Boolean.TRUE.equals(user.getIsEnabled())) {
            return;
        }
        List<Long> adminIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRole, "ADMIN")
                        .select(UserRole::getUserId))
                .stream().map(UserRole::getUserId).distinct().toList();
        if (!adminIds.contains(user.getId())) {
            return;
        }
        List<Long> otherAdminIds = adminIds.stream().filter(id -> !user.getId().equals(id)).toList();
        if (otherAdminIds.isEmpty() || sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, otherAdminIds)
                        .eq(SysUser::getDeleted, 0L)
                        .eq(SysUser::getIsEnabled, true)) == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能删除最后一名启用状态的平台管理员");
        }
    }

    private List<String> findBlockers(Long userId) {
        List<String> blockers = new ArrayList<>();
        List<Long> activePlanIds = activePlanIds();
        if (!activePlanIds.isEmpty()) {
            if (taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                    .eq(Task::getDeleted, 0L)
                    .eq(Task::getOwnerId, userId)
                    .in(Task::getPlanId, activePlanIds)
                    .notIn(Task::getStatus, ENDED_TASK_STATUSES)) > 0) {
                blockers.add("未归档计划中的有效任务负责人");
            }
            if (indicatorMapper.selectCount(new LambdaQueryWrapper<Indicator>()
                    .eq(Indicator::getDeleted, 0L)
                    .eq(Indicator::getOwnerId, userId)
                    .in(Indicator::getPlanId, activePlanIds)) > 0) {
                blockers.add("未归档计划中的指标负责人");
            }
            if (directionMapper.selectCount(new LambdaQueryWrapper<Direction>()
                    .eq(Direction::getDeleted, 0L)
                    .eq(Direction::getLeaderId, userId)
                    .in(Direction::getPlanId, activePlanIds)) > 0) {
                blockers.add("未归档计划中的 S 级总负责人");
            }
            if (submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                    .eq(Submission::getDeleted, 0L)
                    .eq(Submission::getStatus, "DRAFT")
                    .eq(Submission::getCreatedBy, userId)
                    .in(Submission::getPlanId, activePlanIds)) > 0) {
                blockers.add("草稿填报创建人");
            }
            if (submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                    .eq(Submission::getDeleted, 0L)
                    .eq(Submission::getStatus, "PENDING_REVIEW")
                    .eq(Submission::getSubmittedBy, userId)
                    .in(Submission::getPlanId, activePlanIds)) > 0) {
                blockers.add("待审核填报提交人");
            }
            findAdjustmentBlockers(userId, activePlanIds, blockers);
        }
        if (majorGroupMapper.selectCount(new LambdaQueryWrapper<MajorGroup>()
                .eq(MajorGroup::getDeleted, 0L)
                .eq(MajorGroup::getIsEnabled, true)
                .eq(MajorGroup::getOwnerId, userId)) > 0) {
            blockers.add("启用专业群负责人");
        }
        if (sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0L)
                .eq(SysUser::getOwnerUserId, userId)) > 0) {
            blockers.add("未删除的下属干事");
        }
        return blockers;
    }

    private List<String> findRoleChangeBlockers(Long userId) {
        List<String> blockers = new ArrayList<>(findBlockers(userId));
        List<Long> activePlanIds = activePlanIds();
        if (activePlanIds.isEmpty()) {
            return blockers;
        }
        List<Long> assignedTaskIds = taskStaffMapper.selectList(new LambdaQueryWrapper<TaskStaff>()
                        .eq(TaskStaff::getStaffId, userId)
                        .select(TaskStaff::getTaskId))
                .stream().map(TaskStaff::getTaskId).toList();
        if (!assignedTaskIds.isEmpty() && taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .eq(Task::getDeleted, 0L)
                .in(Task::getId, assignedTaskIds)
                .in(Task::getPlanId, activePlanIds)
                .notIn(Task::getStatus, ENDED_TASK_STATUSES)) > 0) {
            blockers.add("未归档计划中的任务干事分配");
        }

        List<TrainingSheet> activeSheets = trainingSheetMapper.selectList(new LambdaQueryWrapper<TrainingSheet>()
                .eq(TrainingSheet::getDeleted, 0L)
                .in(TrainingSheet::getPlanId, activePlanIds)
                .select(TrainingSheet::getId, TrainingSheet::getOwnerId));
        if (activeSheets.stream().anyMatch(sheet -> userId.equals(sheet.getOwnerId()))) {
            blockers.add("未归档培训采集表负责人");
        }
        Set<Long> activeSheetIds = activeSheets.stream().map(TrainingSheet::getId).collect(Collectors.toSet());
        if (!activeSheetIds.isEmpty()) {
            if (trainingSheetStaffMapper.selectCount(new LambdaQueryWrapper<TrainingSheetStaff>()
                    .in(TrainingSheetStaff::getSheetId, activeSheetIds)
                    .eq(TrainingSheetStaff::getStaffId, userId)) > 0) {
                blockers.add("未归档培训采集表干事分配");
            }
            if (trainingProjectMapper.selectCount(new LambdaQueryWrapper<TrainingProject>()
                    .eq(TrainingProject::getDeleted, 0L)
                    .in(TrainingProject::getSheetId, activeSheetIds)
                    .in(TrainingProject::getStatus, "DRAFT", "PENDING_REVIEW")
                    .and(w -> w.eq(TrainingProject::getCreatedBy, userId)
                            .or().eq(TrainingProject::getSubmittedBy, userId))) > 0) {
                blockers.add("草稿或待审核培训项目");
            }
        }
        return blockers;
    }

    private List<Long> activePlanIds() {
        return planMapper.selectList(new LambdaQueryWrapper<Plan>()
                        .eq(Plan::getDeleted, 0L)
                        .ne(Plan::getStatus, "ARCHIVED")
                        .select(Plan::getId))
                .stream().map(Plan::getId).toList();
    }

    private void findAdjustmentBlockers(Long userId, List<Long> activePlanIds, List<String> blockers) {
        List<Adjustment> pending = adjustmentMapper.selectList(new LambdaQueryWrapper<Adjustment>()
                .eq(Adjustment::getDeleted, 0L)
                .in(Adjustment::getPlanId, activePlanIds)
                .in(Adjustment::getStatus, PENDING_ADJUSTMENT_STATUSES)
                .select(Adjustment::getId, Adjustment::getApplicantId, Adjustment::getCurrentNode));
        if (pending.stream().anyMatch(adjustment -> userId.equals(adjustment.getApplicantId()))) {
            blockers.add("待审批调整申请人");
        }
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, Adjustment> pendingById = pending.stream()
                .collect(Collectors.toMap(Adjustment::getId, Function.identity()));
        List<ApprovalStep> undecidedSteps = approvalStepMapper.selectList(new LambdaQueryWrapper<ApprovalStep>()
                .in(ApprovalStep::getAdjustmentId, pendingById.keySet())
                .eq(ApprovalStep::getExpectedApproverId, userId)
                .isNull(ApprovalStep::getDecision)
                .select(ApprovalStep::getAdjustmentId, ApprovalStep::getNode));
        boolean currentApprover = undecidedSteps.stream().anyMatch(step -> {
            Adjustment adjustment = pendingById.get(step.getAdjustmentId());
            return adjustment != null && Objects.equals(adjustment.getCurrentNode(), step.getNode());
        });
        if (currentApprover) {
            blockers.add("待审批调整当前步骤审批人");
        }
    }
}
