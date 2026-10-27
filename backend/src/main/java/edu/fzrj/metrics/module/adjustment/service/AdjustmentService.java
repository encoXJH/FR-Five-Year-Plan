package edu.fzrj.metrics.module.adjustment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.adjustment.dto.AdjustmentDtos;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.entity.AdjustmentItem;
import edu.fzrj.metrics.module.adjustment.entity.ApprovalStep;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentItemMapper;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentMapper;
import edu.fzrj.metrics.module.adjustment.mapper.ApprovalStepMapper;
import edu.fzrj.metrics.module.adjustment.service.support.AdjustmentRules;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 指标调整（API 约定 15.6、PRD 5.6）：试算、发起申请（含额度冻结）、列表 / 详情 / 时间线、撤回。
 * 审批处理与生见 AdjustmentDecisionService。
 */
@Service
public class AdjustmentService {

    private static final Set<String> SORT_FIELDS = Set.of("created_at", "status", "type");
    private static final Set<String> STATUSES = Set.of(
            "PENDING_OWNER_REVIEW", "PENDING_RECEIVER_CONFIRM", "PENDING_LEADER_APPROVAL",
            "APPROVED", "REJECTED", "WITHDRAWN");
    private static final Set<String> SCOPES = Set.of("MINE", "TODO", "ALL");
    private static final String FILE_BIZ_TYPE = "ADJUSTMENT";

    private final AdjustmentMapper adjustmentMapper;
    private final AdjustmentItemMapper itemMapper;
    private final ApprovalStepMapper stepMapper;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final IndicatorMapper indicatorMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final PlanYearMapper planYearMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final FileRefMapper fileRefMapper;
    private final FileInfoMapper fileInfoMapper;
    private final NotificationMapper notificationMapper;
    private final TaskService taskService;
    private final IndicatorService indicatorService;
    private final PlanService planService;
    private final OrgUnitAdminService orgUnitAdminService;
    private final DataScopeService dataScopeService;
    private final UserRefResolver userRefResolver;
    private final ObjectMapper objectMapper;

    public AdjustmentService(AdjustmentMapper adjustmentMapper, AdjustmentItemMapper itemMapper,
                             ApprovalStepMapper stepMapper, TaskMapper taskMapper,
                             TaskYearMapper taskYearMapper, IndicatorMapper indicatorMapper,
                             IndicatorYearTargetMapper yearTargetMapper, PlanYearMapper planYearMapper,
                             OrgUnitMapper orgUnitMapper, SysUserMapper sysUserMapper,
                             UserRoleMapper userRoleMapper, FileRefMapper fileRefMapper,
                             FileInfoMapper fileInfoMapper, NotificationMapper notificationMapper,
                             TaskService taskService, IndicatorService indicatorService,
                             PlanService planService, OrgUnitAdminService orgUnitAdminService,
                             DataScopeService dataScopeService, UserRefResolver userRefResolver,
                             ObjectMapper objectMapper) {
        this.adjustmentMapper = adjustmentMapper;
        this.itemMapper = itemMapper;
        this.stepMapper = stepMapper;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.planYearMapper = planYearMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.fileRefMapper = fileRefMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.notificationMapper = notificationMapper;
        this.taskService = taskService;
        this.indicatorService = indicatorService;
        this.planService = planService;
        this.orgUnitAdminService = orgUnitAdminService;
        this.dataScopeService = dataScopeService;
        this.userRefResolver = userRefResolver;
        this.objectMapper = objectMapper;
    }

    // ---------------- 试算（不保存） ----------------

    public AdjustmentDtos.PreviewResponse preview(AdjustmentDtos.CreateRequest request) {
        Prepared prepared = prepare(request);
        List<FieldError> errors = prepared.errors().stream()
                .map(e -> new FieldError(e.field(), e.message())).toList();
        return new AdjustmentDtos.PreviewResponse(errors.isEmpty(), errors,
                new AdjustmentDtos.Snapshot(toSnapshotEntries(prepared.before()),
                        toSnapshotEntries(prepared.after())),
                prepared.toTaskExists());
    }

    // ---------------- 发起申请 ----------------

    @Transactional
    public AdjustmentDtos.Detail create(AdjustmentDtos.CreateRequest request) {
        LoginUser user = SecurityUtils.getLoginUser();
        Prepared prepared = prepare(request);
        // 申请人数据范围：本单元负责人 / 指标有效负责人 / 管理员；TARGET_CHANGE 仅管理员（prepare 已拦）
        if (!AdjustmentRules.TARGET_CHANGE.equals(prepared.type())) {
            requireApplicantScope(prepared.fromTask(), prepared.indicator(), user);
        }
        if (!prepared.errors().isEmpty()) {
            VError first = prepared.errors().get(0);
            throw new BizException(first.code(), first.message(),
                    prepared.errors().stream().map(e -> new FieldError(e.field(), e.message())).toList());
        }
        Plan plan = prepared.plan();
        boolean receiverConfirm = !Boolean.FALSE.equals(plan.getIsReceiverConfirmRequired());
        List<String> nodes = AdjustmentRules.nodesOf(prepared.type(), receiverConfirm);

        // 冻结转出年度额度（目标调整无 B 额度，不冻结）；冻结前复核任务未转为历史
        if (!AdjustmentRules.TARGET_CHANGE.equals(prepared.type())) {
            taskService.requireCurrentTask(prepared.fromTask().getId());
            for (PreparedItem item : prepared.items()) {
                freeze(item.fromTaskYear(), item.amount(), user.getUserId());
            }
        }

        Adjustment adjustment = new Adjustment();
        adjustment.setPlanId(plan.getId());
        adjustment.setType(prepared.type());
        adjustment.setTaskId(prepared.fromTask() == null ? null : prepared.fromTask().getId());
        adjustment.setIndicatorId(prepared.indicator().getId());
        adjustment.setFromUnitId(prepared.fromTask() == null ? null : prepared.fromTask().getUnitId());
        adjustment.setToUnitId(prepared.toUnit() == null ? null : prepared.toUnit().getId());
        adjustment.setStatus(AdjustmentRules.statusOfNode(nodes.get(0)));
        adjustment.setCurrentNode(nodes.get(0));
        adjustment.setApplicantId(user.getUserId());
        adjustment.setReason(request.getReason());
        adjustment.setSnapshotBefore(writeSnapshot(prepared.before()));
        adjustment.setCreatedBy(user.getUserId());
        adjustment.setUpdatedBy(user.getUserId());
        adjustmentMapper.insert(adjustment);

        for (PreparedItem item : prepared.items()) {
            AdjustmentItem row = new AdjustmentItem();
            row.setAdjustmentId(adjustment.getId());
            row.setFromTaskId(item.fromTaskId());
            row.setFromYear(item.fromYear());
            row.setToUnitId(prepared.toUnit() == null ? null : prepared.toUnit().getId());
            row.setToYear(item.toYear());
            row.setAmount(item.amount());
            row.setNewTarget(item.newTarget());
            row.setTargetIndicatorId(item.targetIndicatorId());
            row.setCreatedBy(user.getUserId());
            row.setUpdatedBy(user.getUserId());
            itemMapper.insert(row);
        }

        Long ownerId = indicatorService.effectiveOwnerOf(prepared.indicator());
        for (int i = 0; i < nodes.size(); i++) {
            ApprovalStep step = new ApprovalStep();
            step.setAdjustmentId(adjustment.getId());
            step.setSeq(i + 1);
            step.setNode(nodes.get(i));
            if (AdjustmentRules.OWNER_REVIEW.equals(nodes.get(i))) {
                step.setExpectedApproverId(ownerId);
            }
            if (AdjustmentRules.RECEIVER_CONFIRM.equals(nodes.get(i))) {
                step.setExpectedUnitId(prepared.toUnit().getId());
            }
            stepMapper.insert(step);
        }

        bindFiles(adjustment.getId(), request.getFileIds());
        notifyCurrentNode(adjustment, nodes.get(0), ownerId);
        return toDetail(requireAdjustment(adjustment.getId()));
    }

    // ---------------- 列表 / 详情 / 时间线 ----------------

    /** 申请列表：scope = MINE 我发起的 / TODO 待我处理 / ALL 全部（按数据范围） */
    public PageResult<AdjustmentDtos.ListItem> page(AdjustmentDtos.AdjustmentQuery query) {
        String scope = StringUtils.hasText(query.getScope()) ? query.getScope() : "MINE";
        if (!SCOPES.contains(scope)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "scope 只支持 MINE / TODO / ALL");
        }
        if (StringUtils.hasText(query.getStatus()) && !STATUSES.contains(query.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "调整状态取值不合法：" + query.getStatus());
        }
        if (StringUtils.hasText(query.getType()) && !AdjustmentRules.isKnownType(query.getType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "调整类型取值不合法：" + query.getType());
        }
        LoginUser user = SecurityUtils.getLoginUser();
        LambdaQueryWrapper<Adjustment> wrapper = new LambdaQueryWrapper<Adjustment>()
                .eq(Adjustment::getDeleted, 0L)
                .eq(query.getPlanId() != null, Adjustment::getPlanId, query.getPlanId())
                .eq(StringUtils.hasText(query.getStatus()), Adjustment::getStatus, query.getStatus())
                .eq(StringUtils.hasText(query.getType()), Adjustment::getType, query.getType())
                .orderByDesc(Adjustment::getCreatedAt);
        switch (scope) {
            case "MINE" -> wrapper.eq(Adjustment::getApplicantId, user.getUserId());
            case "TODO" -> {
                List<Long> ids = todoAdjustmentIds(user);
                if (ids.isEmpty()) {
                    return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
                }
                wrapper.in(Adjustment::getId, ids);
            }
            case "ALL" -> applyDataScope(wrapper, user);
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "scope 只支持 MINE / TODO / ALL");
        }
        Page<Adjustment> page = adjustmentMapper.selectPage(query.toPage(SORT_FIELDS), wrapper);
        return new PageResult<>(toListItems(page.getRecords()), page.getTotal(),
                query.getPage(), query.getPageSize());
    }

    public Adjustment requireAdjustment(Long adjustmentId) {
        Adjustment adjustment = adjustmentMapper.selectOne(new LambdaQueryWrapper<Adjustment>()
                .eq(Adjustment::getId, adjustmentId)
                .eq(Adjustment::getDeleted, 0L));
        if (adjustment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "调整申请不存在");
        }
        return adjustment;
    }

    /** 详情（含审批环节、调整前后快照、附件）；按数据范围 */
    public AdjustmentDtos.Detail detail(Long adjustmentId) {
        Adjustment adjustment = requireAdjustment(adjustmentId);
        requireAccess(adjustment);
        return toDetail(adjustment);
    }

    /** 某 B 任务的调整历史时间线（含作为转出方 / 接收方 / 由调整新建的记录） */
    public List<AdjustmentDtos.Detail> timelineOfTask(Long taskId) {
        Task task = taskService.requireTask(taskId);
        taskService.requireAccess(task);
        Set<Long> ids = new LinkedHashSet<>();
        adjustmentMapper.selectList(new LambdaQueryWrapper<Adjustment>()
                        .eq(Adjustment::getTaskId, taskId)
                        .eq(Adjustment::getDeleted, 0L)
                        .select(Adjustment::getId))
                .forEach(a -> ids.add(a.getId()));
        itemMapper.selectList(new LambdaQueryWrapper<AdjustmentItem>()
                        .eq(AdjustmentItem::getDeleted, 0L)
                        .and(w -> w.eq(AdjustmentItem::getFromTaskId, taskId)
                                .or().eq(AdjustmentItem::getToTaskId, taskId))
                        .select(AdjustmentItem::getAdjustmentId))
                .forEach(i -> ids.add(i.getAdjustmentId()));
        if (task.getSourceAdjustmentId() != null) {
            ids.add(task.getSourceAdjustmentId());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return adjustmentMapper.selectList(new LambdaQueryWrapper<Adjustment>()
                        .in(Adjustment::getId, ids)
                        .eq(Adjustment::getDeleted, 0L)
                        .orderByAsc(Adjustment::getCreatedAt))
                .stream().map(this::toDetail).toList();
    }

    // ---------------- 撤回 ----------------

    /** 申请人撤回（仅进行中可撤回）；释放冻结额度并恢复年度状态 */
    @Transactional
    public AdjustmentDtos.Detail withdraw(Long adjustmentId, Integer version) {
        Adjustment adjustment = requireAdjustment(adjustmentId);
        LoginUser user = SecurityUtils.getLoginUser();
        if (!adjustment.getApplicantId().equals(user.getUserId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "只有申请人可以撤回");
        }
        requireVersion(adjustment, version);
        if (!AdjustmentRules.canWithdraw(adjustment.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "只有审批中的申请可以撤回");
        }
        String pendingNode = adjustment.getCurrentNode();
        List<AdjustmentItem> items = itemsOf(adjustmentId);
        requireCurrentTasks(adjustment, items);
        releaseFreeze(items, user.getUserId());
        adjustment.setStatus(AdjustmentRules.WITHDRAWN);
        adjustment.setCurrentNode(null);
        adjustment.setWithdrawnAt(LocalDateTime.now());
        adjustment.setUpdatedBy(user.getUserId());
        if (adjustmentMapper.updateById(adjustment) == 0) {
            throw versionConflict(adjustmentId);
        }
        notifyNodeHandlers(adjustment, pendingNode, "ADJUSTMENT_RESULT",
                "调整申请已撤回：" + bizCode(adjustment),
                "申请人已撤回 " + typeLabel(adjustment.getType()) + " 申请，无需再处理。");
        return toDetail(requireAdjustment(adjustmentId));
    }

    // ---------------- 供审批模块复用 ----------------

    public List<AdjustmentItem> itemsOf(Long adjustmentId) {
        return itemMapper.selectList(new LambdaQueryWrapper<AdjustmentItem>()
                .eq(AdjustmentItem::getAdjustmentId, adjustmentId)
                .eq(AdjustmentItem::getDeleted, 0L)
                .orderByAsc(AdjustmentItem::getId));
    }

    public List<ApprovalStep> stepsOf(Long adjustmentId) {
        return stepMapper.selectList(new LambdaQueryWrapper<ApprovalStep>()
                .eq(ApprovalStep::getAdjustmentId, adjustmentId)
                .orderByAsc(ApprovalStep::getSeq));
    }

    /** 历史在途单应由迁移终止并释放；运行时再次释放须拒绝，避免重复扣减冻结额度 */
    public void releaseFreeze(List<AdjustmentItem> items, Long userId) {
        items.stream().map(AdjustmentItem::getFromTaskId).filter(Objects::nonNull).distinct()
                .forEach(taskService::requireCurrentTask);
        for (AdjustmentItem item : items) {
            if (item.getFromTaskId() == null || item.getFromYear() == null || item.getAmount() == null) {
                continue;
            }
            TaskYear taskYear = taskYearOf(item.getFromTaskId(), item.getFromYear());
            if (taskYear == null) {
                continue;
            }
            BigDecimal frozen = taskYear.getFrozenAmount() == null ? BigDecimal.ZERO : taskYear.getFrozenAmount();
            taskYear.setFrozenAmount(frozen.subtract(item.getAmount()).max(BigDecimal.ZERO));
            if ("ADJUSTING".equals(taskYear.getYearStatus())) {
                taskYear.setYearStatus(taskYear.getStatusBeforeAdjusting() == null
                        ? "IN_PROGRESS" : taskYear.getStatusBeforeAdjusting());
                taskYear.setStatusBeforeAdjusting(null);
            }
            taskYear.setUpdatedBy(userId);
            taskYearMapper.updateById(taskYear);
        }
    }

    /** 审批、撤回及最终生效共用：任何关联任务转为历史后，整个在途单只读 */
    public void requireCurrentTasks(Adjustment adjustment, List<AdjustmentItem> items) {
        Set<Long> taskIds = new LinkedHashSet<>();
        if (adjustment.getTaskId() != null) {
            taskIds.add(adjustment.getTaskId());
        }
        items.forEach(item -> {
            if (item.getFromTaskId() != null) {
                taskIds.add(item.getFromTaskId());
            }
            if (item.getToTaskId() != null) {
                taskIds.add(item.getToTaskId());
            }
        });
        taskIds.forEach(taskService::requireCurrentTask);
    }

    public void requireVersion(Adjustment adjustment, Integer version) {
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 version（乐观锁版本号）");
        }
        if (!adjustment.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + adjustment.getVersion());
        }
    }

    public BizException versionConflict(Long adjustmentId) {
        return new BizException(ErrorCode.VERSION_CONFLICT,
                "数据已被他人修改，当前最新版本号：" + requireAdjustment(adjustmentId).getVersion());
    }

    /** 详情访问：全校 / 申请人 / 转出与接收单元 / 指标有效负责人 */
    public void requireAccess(Adjustment adjustment) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isAllData(user) || adjustment.getApplicantId().equals(user.getUserId())) {
            return;
        }
        if (dataScopeService.isUnitScope(user) && user.getUnitId() != null
                && (user.getUnitId().equals(adjustment.getFromUnitId())
                || user.getUnitId().equals(adjustment.getToUnitId()))) {
            return;
        }
        if (dataScopeService.isOwnerScope(user)) {
            Indicator indicator = indicatorService.requireIndicator(adjustment.getIndicatorId());
            Long owner = indicatorService.effectiveOwnerOf(indicator);
            if (owner != null && owner.equals(user.getUserId())) {
                return;
            }
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    /** 站内通知（飞书推送由阶段 8 消费 feishuStatus=PENDING 的记录） */
    public void notifyUsers(List<Long> userIds, String type, String title, String content, Long adjustmentId) {
        for (Long userId : new LinkedHashSet<>(userIds)) {
            if (userId == null) {
                continue;
            }
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setType(type);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setBizType("adjustment");
            notification.setBizId(adjustmentId);
            notification.setFeishuStatus("PENDING");
            notification.setFeishuRetryCount(0);
            notificationMapper.insert(notification);
        }
    }

    /** 通知某环节的处理人：初审=指标有效负责人（空则管理员）；接收确认=接收单元负责人；校领导审批=全部校领导 */
    public void notifyNodeHandlers(Adjustment adjustment, String node, String type, String title, String content) {
        if (node == null) {
            return;
        }
        List<Long> userIds = switch (node) {
            case AdjustmentRules.OWNER_REVIEW -> {
                Long owner = indicatorService.effectiveOwnerOf(
                        indicatorService.requireIndicator(adjustment.getIndicatorId()));
                yield owner != null ? List.of(owner) : userIdsWithRole("ADMIN");
            }
            case AdjustmentRules.RECEIVER_CONFIRM -> unitOwnerIds(adjustment.getToUnitId());
            case AdjustmentRules.LEADER_APPROVAL -> userIdsWithRole("LEADER");
            default -> List.of();
        };
        notifyUsers(userIds, type, title, content, adjustment.getId());
    }

    /** 当前环节处理人待办通知（申请提交后调用） */
    public void notifyCurrentNode(Adjustment adjustment, String node, Long ownerId) {
        notifyNodeHandlers(adjustment, node, "ADJUSTMENT_TODO",
                "调整申请待处理：" + bizCode(adjustment),
                typeLabel(adjustment.getType()) + " 申请（" + bizCode(adjustment) + "）等待您处理，原因："
                        + adjustment.getReason());
    }

    public String bizCode(Adjustment adjustment) {
        if (adjustment.getTaskId() != null) {
            Task task = taskMapper.selectById(adjustment.getTaskId());
            if (task != null) {
                return task.getCode();
            }
        }
        Indicator indicator = indicatorMapper.selectById(adjustment.getIndicatorId());
        return indicator == null ? String.valueOf(adjustment.getId()) : indicator.getCode();
    }

    public static String typeLabel(String type) {
        return switch (type) {
            case AdjustmentRules.POSTPONE -> "延期";
            case AdjustmentRules.TRANSFER -> "转移";
            case AdjustmentRules.POSTPONE_TRANSFER -> "延期+转移";
            case AdjustmentRules.TARGET_CHANGE -> "目标调整";
            default -> type;
        };
    }

    // ---------------- 试算 / 校验（preview 与 create 共用） ----------------

    private record VError(ErrorCode code, String field, String message) {
    }

    private record PreparedItem(Long fromTaskId, Integer fromYear, Integer toYear, BigDecimal amount,
                                BigDecimal newTarget, Long targetIndicatorId,
                                TaskYear fromTaskYear) {
    }

    private record Prepared(String type, Plan plan, Indicator indicator, Task fromTask, OrgUnit toUnit,
                            List<PreparedItem> items,
                            List<AdjustmentRules.TargetEntry> before, List<AdjustmentRules.TargetEntry> after,
                            Boolean toTaskExists, List<VError> errors) {
    }

    private Prepared prepare(AdjustmentDtos.CreateRequest request) {
        if (!AdjustmentRules.isKnownType(request.getType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "调整类型取值不合法：" + request.getType());
        }
        if (AdjustmentRules.TARGET_CHANGE.equals(request.getType())) {
            return prepareTargetChange(request);
        }
        return prepareMove(request);
    }

    /** 延期 / 转移类的试算与校验（错误收集到 Prepared.errors，不抛出） */
    private Prepared prepareMove(AdjustmentDtos.CreateRequest request) {
        String type = request.getType();
        List<VError> errors = new ArrayList<>();
        if (request.getTaskId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "taskId 必填");
        }
        Task task = taskService.requireCurrentTask(request.getTaskId());
        Plan plan = planService.requirePlan(task.getPlanId());
        planService.requireNotArchived(plan);
        if (!"DISTRIBUTED".equals(task.getStatus()) && !"IN_PROGRESS".equals(task.getStatus())) {
            errors.add(new VError(ErrorCode.SUBMISSION_STATUS_INVALID, "taskId",
                    "任务当前状态不允许调整（需已下发 / 执行中）"));
        }
        Indicator indicator = indicatorService.requireIndicator(task.getIndicatorId());
        String caliber = indicator.getStatCaliber();
        if (!AdjustmentRules.supports(caliber, type)) {
            errors.add(new VError(ErrorCode.ADJUST_TYPE_UNSUPPORTED, "type",
                    caliber + " 口径不支持 " + typeLabel(type) + " 调整"));
        }

        OrgUnit toUnit = null;
        if (AdjustmentRules.isTransfer(type)) {
            if (request.getToUnitId() == null) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, "toUnitId", "转移类调整必须指定接收执行单元"));
            } else {
                toUnit = orgUnitAdminService.requireBAssignable(request.getToUnitId());
                if (toUnit.getId().equals(task.getUnitId())) {
                    errors.add(new VError(ErrorCode.PARAM_INVALID, "toUnitId", "接收单元不能是转出单元"));
                }
            }
        } else if (request.getToUnitId() != null) {
            errors.add(new VError(ErrorCode.PARAM_INVALID, "toUnitId", "延期调整不涉及接收单元"));
        }

        Map<Integer, TaskYear> yearsOfTask = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .eq(TaskYear::getTaskId, task.getId())
                        .eq(TaskYear::getDeleted, 0L))
                .stream().collect(Collectors.toMap(TaskYear::getYear, Function.identity()));

        Task receiverTask = toUnit == null ? null : taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getIndicatorId, indicator.getId())
                .eq(Task::getUnitId, toUnit.getId())
                .eq(Task::getDeleted, 0L));
        if (receiverTask != null) {
            receiverTask = taskService.requireCurrentTask(receiverTask.getId());
        }
        String toCode = toUnit == null ? task.getCode()
                : indicator.getCode() + "-" + toUnit.getCode();

        Set<Integer> seenYears = new HashSet<>();
        List<PreparedItem> items = new ArrayList<>();
        List<AdjustmentRules.MoveItem> moves = new ArrayList<>();
        int index = 0;
        for (AdjustmentDtos.ItemRequest item : request.getItems()) {
            String field = "items[" + index++ + "]";
            Integer fromYear = item.getFromYear();
            if (fromYear == null || fromYear < plan.getStartYear() || fromYear > plan.getEndYear()) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".fromYear",
                        "转出年度缺失或不在计划起止年份内"));
                continue;
            }
            if (!seenYears.add(fromYear)) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".fromYear",
                        "同一申请内转出年度不能重复：" + fromYear));
                continue;
            }
            Integer toYear = item.getToYear();
            if (AdjustmentRules.TRANSFER.equals(type)) {
                if (toYear != null && !toYear.equals(fromYear)) {
                    errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".toYear",
                            "转移的接收年度应与转出年度相同；跨年转移请使用延期+转移"));
                    continue;
                }
                toYear = fromYear;
            } else {
                if (toYear == null) {
                    errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".toYear", "目标年份必填"));
                    continue;
                }
                String yearError = AdjustmentRules.yearError(type, fromYear, toYear,
                        plan.getStartYear(), plan.getEndYear());
                if (yearError != null) {
                    errors.add(new VError(ErrorCode.ADJUST_YEAR_INVALID, field + ".toYear", yearError));
                    continue;
                }
            }
            TaskYear fromTaskYear = yearsOfTask.get(fromYear);
            if (fromTaskYear == null) {
                errors.add(new VError(ErrorCode.NOT_FOUND, field + ".fromYear", "任务年度不存在：" + fromYear));
                continue;
            }
            BigDecimal amount = item.getAmount();
            if ("BOOLEAN".equals(caliber)) {
                if (amount != null && amount.compareTo(BigDecimal.ONE) != 0) {
                    errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".amount",
                            "是否型延期数量固定为 1"));
                    continue;
                }
                amount = BigDecimal.ONE;
                if (fromTaskYear.getTarget() == null
                        || fromTaskYear.getTarget().compareTo(BigDecimal.ONE) != 0) {
                    errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".fromYear",
                            fromYear + " 年不是该任务的计划完成年"));
                    continue;
                }
            } else if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".amount", "调整数量必须大于 0"));
                continue;
            }
            if (isYearLocked(plan.getId(), fromYear) || isYearLocked(plan.getId(), toYear)) {
                errors.add(new VError(ErrorCode.YEAR_SETTLED, field, "涉及年度已核算锁定，不能调整"));
                continue;
            }
            if ("ADJUSTING".equals(fromTaskYear.getYearStatus())) {
                errors.add(new VError(ErrorCode.ADJUST_IN_PROGRESS, field,
                        fromYear + " 年已有进行中的调整申请（额度已冻结）"));
                continue;
            }
            BigDecimal remaining = AdjustmentRules.remaining(fromTaskYear.getTarget(),
                    fromTaskYear.getActualValue(), fromTaskYear.getFrozenAmount());
            if (amount.compareTo(remaining) > 0) {
                errors.add(new VError(ErrorCode.ADJUST_AMOUNT_EXCEEDED, field + ".amount",
                        "调整数量 " + amount.stripTrailingZeros().toPlainString() + " 超过 " + fromYear
                                + " 年尚未完成的量 " + remaining.stripTrailingZeros().toPlainString()));
                continue;
            }
            items.add(new PreparedItem(task.getId(), fromYear, toYear, amount, null, null, fromTaskYear));
            moves.add(new AdjustmentRules.MoveItem(task.getCode(), fromYear, toCode, toYear, amount));
        }

        // 快照：转出任务涉及年 + 接收任务（已存在时）涉及年
        List<AdjustmentRules.TargetEntry> before = new ArrayList<>();
        Set<Integer> fromYears = items.stream().map(PreparedItem::fromYear).collect(Collectors.toSet());
        Set<Integer> toYears = items.stream().map(PreparedItem::toYear).collect(Collectors.toSet());
        Set<Integer> taskYears = new LinkedHashSet<>();
        taskYears.addAll(fromYears);
        taskYears.addAll(toYears);
        for (Integer year : taskYears) {
            TaskYear ty = yearsOfTask.get(year);
            before.add(new AdjustmentRules.TargetEntry(task.getCode(), null, year,
                    ty == null ? null : ty.getTarget()));
        }
        if (receiverTask != null) {
            Map<Integer, TaskYear> receiverYears = taskYearMapper.selectList(
                            new LambdaQueryWrapper<TaskYear>()
                                    .eq(TaskYear::getTaskId, receiverTask.getId())
                                    .eq(TaskYear::getDeleted, 0L))
                    .stream().collect(Collectors.toMap(TaskYear::getYear, Function.identity()));
            for (Integer year : toYears) {
                TaskYear ty = receiverYears.get(year);
                before.add(new AdjustmentRules.TargetEntry(receiverTask.getCode(), null, year,
                        ty == null ? null : ty.getTarget()));
            }
        }
        List<AdjustmentRules.TargetEntry> after = moves.isEmpty() ? before
                : AdjustmentRules.applyMoves(before, moves);

        // 防御性守恒复核：移动后 ΣB 与 A+ 年度目标应仍一致（两侧同步移动）
        if (errors.isEmpty() && !moves.isEmpty()) {
            Map<Integer, BigDecimal> bSum = bSumByYear(indicator.getId());
            Map<Integer, BigDecimal> aplusTargets = indicatorTargets(indicator.getId());
            for (AdjustmentRules.MoveItem move : moves) {
                if (AdjustmentRules.isPostpone(type)) {
                    bSum.merge(move.fromYear(), move.amount().negate(), BigDecimal::add);
                    bSum.merge(move.toYear(), move.amount(), BigDecimal::add);
                    aplusTargets.merge(move.fromYear(), move.amount().negate(), BigDecimal::add);
                    aplusTargets.merge(move.toYear(), move.amount(), BigDecimal::add);
                }
            }
            for (String error : AdjustmentRules.conservationErrors(caliber, "A+ 目标", bSum, aplusTargets)) {
                errors.add(new VError(ErrorCode.CONSERVATION_VIOLATED, "items", error));
            }
        }
        return new Prepared(type, plan, indicator, task, toUnit, items, before, after,
                toUnit == null ? null : receiverTask != null, errors);
    }

    /** 目标调整（TARGET_CHANGE，仅管理员）的试算与校验；五年总量变更建模为 toYear=null 的明细项 */
    private Prepared prepareTargetChange(AdjustmentDtos.CreateRequest request) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (!user.getRoles().contains("ADMIN")) {
            throw new BizException(ErrorCode.FORBIDDEN, "目标调整仅平台管理员可发起");
        }
        if (request.getIndicatorId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "indicatorId 必填");
        }
        Indicator indicator = indicatorService.requireIndicator(request.getIndicatorId());
        requireTargetChangeTarget(indicator);
        Plan plan = planService.requirePlan(indicator.getPlanId());
        planService.requireNotArchived(plan);
        if (plan.getFinalizedAt() == null) {
            throw new BizException(ErrorCode.PLAN_STATUS_INVALID, "指标体系未定稿，可直接编辑目标，无需目标调整");
        }
        List<VError> errors = new ArrayList<>();
        List<PreparedItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (request.getNewTotalTarget() != null) {
            if (request.getNewTotalTarget().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, "newTotalTarget", "五年总量不能为负数"));
            } else if ("BOOLEAN".equals(indicator.getStatCaliber())
                    && request.getNewTotalTarget().compareTo(BigDecimal.ONE) != 0) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, "newTotalTarget", "是否型指标五年总量固定为 1"));
            } else {
                items.add(new PreparedItem(null, null, null, null,
                        request.getNewTotalTarget(), indicator.getId(), null));
                seen.add(indicator.getId() + "@null");
            }
        }
        int index = 0;
        for (AdjustmentDtos.ItemRequest item : request.getItems()) {
            String field = "items[" + index++ + "]";
            Long targetIndicatorId = item.getTargetIndicatorId() == null
                    ? indicator.getId() : item.getTargetIndicatorId();
            if (item.getNewTarget() == null || item.getNewTarget().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".newTarget", "新目标值必填且不能为负"));
                continue;
            }
            if (item.getYear() == null) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".year", "年度必填"));
                continue;
            }
            if (item.getYear() < plan.getStartYear() || item.getYear() > plan.getEndYear()) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".year", "年度不在计划起止年份内"));
                continue;
            }
            if (!seen.add(targetIndicatorId + "@" + item.getYear())) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, field, "同一指标同一年度只能调整一次"));
                continue;
            }
            Indicator itemTarget = indicatorService.requireIndicator(targetIndicatorId);
            requireTargetChangeTarget(itemTarget);
            if ("BOOLEAN".equals(itemTarget.getStatCaliber())
                    && item.getNewTarget().compareTo(BigDecimal.ONE) > 0) {
                errors.add(new VError(ErrorCode.PARAM_INVALID, field + ".newTarget",
                        "是否型指标年度目标只能为 0 或 1"));
                continue;
            }
            if (isYearLocked(plan.getId(), item.getYear())) {
                errors.add(new VError(ErrorCode.YEAR_SETTLED, field + ".year",
                        item.getYear() + " 年已核算锁定，不能调整"));
                continue;
            }
            items.add(new PreparedItem(null, null, item.getYear(), null,
                    item.getNewTarget(), targetIndicatorId, null));
        }
        // 受影响指标（可含同计划内其他 A / A+，用于配套调整保持守恒）
        Map<Long, Indicator> affected = new LinkedHashMap<>();
        affected.put(indicator.getId(), indicator);
        for (PreparedItem item : items) {
            if (!affected.containsKey(item.targetIndicatorId())) {
                Indicator target = indicatorService.requireIndicator(item.targetIndicatorId());
                if (!target.getPlanId().equals(plan.getId())) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "目标调整的指标必须属于同一计划：" + item.targetIndicatorId());
                }
                affected.put(target.getId(), target);
            }
        }

        // 快照 before / after
        List<AdjustmentRules.TargetEntry> before = new ArrayList<>();
        Map<Long, Map<Integer, BigDecimal>> currentTargets = new HashMap<>();
        for (Map.Entry<Long, Indicator> entry : affected.entrySet()) {
            currentTargets.put(entry.getKey(), indicatorTargets(entry.getKey()));
        }
        for (PreparedItem item : items) {
            Indicator target = affected.get(item.targetIndicatorId());
            BigDecimal current = item.toYear() == null ? target.getTotalTarget()
                    : currentTargets.get(item.targetIndicatorId()).get(item.toYear());
            before.add(new AdjustmentRules.TargetEntry(null, target.getCode(), item.toYear(), current));
        }
        List<AdjustmentRules.SetItem> setItems = items.stream()
                .map(i -> new AdjustmentRules.SetItem(affected.get(i.targetIndicatorId()).getCode(),
                        i.toYear(), i.newTarget()))
                .toList();
        List<AdjustmentRules.TargetEntry> after = AdjustmentRules.applySetItems(before, setItems);

        if (errors.isEmpty()) {
            List<PreparedItemView> checkItems = items.stream()
                    .map(i -> new PreparedItemView(i.targetIndicatorId(), i.toYear(), i.newTarget()))
                    .toList();
            for (FieldError error : targetChangeErrors(indicator, checkItems)) {
                errors.add(new VError(ErrorCode.CONSERVATION_VIOLATED, error.getField(), error.getMessage()));
            }
        }
        return new Prepared(AdjustmentRules.TARGET_CHANGE, plan, indicator, null, null,
                items, before, after, null, errors);
    }

    private void requireTargetChangeTarget(Indicator indicator) {
        if ("A".equals(indicator.getLevel()) && Boolean.FALSE.equals(indicator.getIsQuantifiable())) {
            throw new BizException(ErrorCode.ADJUST_TYPE_UNSUPPORTED, "不可量化 A 无目标可调整");
        }
    }

    /**
     * 目标调整守恒校验（70002；创建试算与生效前复核共用）：
     * INCREMENT / BOOLEAN 各年和 = 五年总量；A+ 逐年 = ΣB 逐年；AUTO_SUM 的 A 逐年 = ΣA+ 逐年。
     * items 中 toYear 为 null 表示调整该指标的五年总量。
     */
    public List<FieldError> targetChangeErrors(Indicator rootIndicator, List<PreparedItemView> items) {
        List<FieldError> errors = new ArrayList<>();
        Map<Long, Indicator> affected = new LinkedHashMap<>();
        affected.put(rootIndicator.getId(), rootIndicator);
        for (PreparedItemView item : items) {
            if (!affected.containsKey(item.targetIndicatorId())) {
                affected.put(item.targetIndicatorId(), indicatorService.requireIndicator(item.targetIndicatorId()));
            }
        }
        Map<Long, Map<Integer, BigDecimal>> afterByIndicator = new HashMap<>();
        Map<Long, BigDecimal> afterTotalByIndicator = new HashMap<>();
        for (Map.Entry<Long, Indicator> entry : affected.entrySet()) {
            afterByIndicator.put(entry.getKey(), new HashMap<>(indicatorTargets(entry.getKey())));
            afterTotalByIndicator.put(entry.getKey(), entry.getValue().getTotalTarget());
        }
        for (PreparedItemView item : items) {
            if (item.toYear() == null) {
                afterTotalByIndicator.put(item.targetIndicatorId(), item.newTarget());
            } else {
                afterByIndicator.get(item.targetIndicatorId()).put(item.toYear(), item.newTarget());
            }
        }
        for (Map.Entry<Long, Indicator> entry : affected.entrySet()) {
            Indicator target = entry.getValue();
            Map<Integer, BigDecimal> afterTargets = afterByIndicator.get(entry.getKey());
            if (!AdjustmentRules.yearSumMatchesTotal(target.getStatCaliber(), afterTargets,
                    afterTotalByIndicator.get(entry.getKey()))) {
                errors.add(new FieldError("items", target.getCode() + " 各年度目标之和不等于五年总量"));
            }
            if ("A_PLUS".equals(target.getLevel())) {
                for (String error : AdjustmentRules.conservationErrors(target.getStatCaliber(),
                        target.getCode() + " 目标", bSumByYear(target.getId()), afterTargets)) {
                    errors.add(new FieldError("items", error));
                }
                Indicator parent = indicatorService.requireIndicator(target.getParentId());
                if ("AUTO_SUM".equals(parent.getRollupMode()) && !affected.containsKey(parent.getId())) {
                    Map<Integer, BigDecimal> aplusSum = siblingSumByYear(parent.getId(),
                            entry.getKey(), afterTargets);
                    for (String error : AdjustmentRules.conservationErrors(parent.getStatCaliber(),
                            parent.getCode() + " 目标", aplusSum, indicatorTargets(parent.getId()))) {
                        errors.add(new FieldError("items", error));
                    }
                }
            } else if ("AUTO_SUM".equals(target.getRollupMode())) {
                Map<Integer, BigDecimal> aplusSum = siblingSumByYear(target.getId(), null, null);
                for (String error : AdjustmentRules.conservationErrors(target.getStatCaliber(),
                        "ΣA+ 目标", aplusSum, afterTargets)) {
                    errors.add(new FieldError("items", error));
                }
            }
        }
        return errors;
    }

    /** 目标调整明细视图（守恒复核入参；targetIndicatorId + toYear + newTarget，toYear 为 null 表示五年总量） */
    public record PreparedItemView(Long targetIndicatorId, Integer toYear, BigDecimal newTarget) {
    }

    /** Σ 各 A+ 年度目标（replaceIndicatorId 的年度目标用 override 替换，用于试算） */
    public Map<Integer, BigDecimal> siblingSumByYear(Long parentId, Long replaceIndicatorId,
                                                     Map<Integer, BigDecimal> override) {
        List<Indicator> children = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getParentId, parentId)
                .eq(Indicator::getDeleted, 0L)
                .select(Indicator::getId));
        Map<Integer, BigDecimal> sum = new HashMap<>();
        for (Indicator child : children) {
            Map<Integer, BigDecimal> targets = child.getId().equals(replaceIndicatorId)
                    ? override : indicatorTargets(child.getId());
            targets.forEach((year, target) -> {
                if (target != null) {
                    sum.merge(year, target, BigDecimal::add);
                }
            });
        }
        return sum;
    }

    /** Σ 该 A+ 下全部 B 的逐年目标；历史任务目标作为冻结基数保留，不从守恒合计中排除 */
    public Map<Integer, BigDecimal> bSumByYear(Long indicatorId) {
        List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getIndicatorId, indicatorId)
                        .eq(Task::getDeleted, 0L)
                        .select(Task::getId))
                .stream().map(Task::getId).toList();
        Map<Integer, BigDecimal> sum = new HashMap<>();
        if (taskIds.isEmpty()) {
            return sum;
        }
        taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getTaskId, taskIds)
                        .eq(TaskYear::getDeleted, 0L)
                        .select(TaskYear::getYear, TaskYear::getTarget))
                .forEach(ty -> {
                    if (ty.getTarget() != null) {
                        sum.merge(ty.getYear(), ty.getTarget(), BigDecimal::add);
                    }
                });
        return sum;
    }

    public Map<Integer, BigDecimal> indicatorTargets(Long indicatorId) {
        return yearTargetMapper.selectList(new LambdaQueryWrapper<IndicatorYearTarget>()
                        .eq(IndicatorYearTarget::getIndicatorId, indicatorId)
                        .eq(IndicatorYearTarget::getDeleted, 0L))
                .stream().collect(Collectors.toMap(IndicatorYearTarget::getYear,
                        IndicatorYearTarget::getTarget, (a, b) -> a));
    }

    // ---------------- 冻结 ----------------

    /**
     * 冻结某任务年度的调整额度（数据库设计规则 5：条件更新防并发，影响行数为 0 时区分 70004 / 70001）。
     */
    private void freeze(TaskYear taskYear, BigDecimal amount, Long userId) {
        int rows = taskYearMapper.update(null, new LambdaUpdateWrapper<TaskYear>()
                .eq(TaskYear::getId, taskYear.getId())
                .eq(TaskYear::getDeleted, 0L)
                .eq(TaskYear::getVersion, taskYear.getVersion())
                .ne(TaskYear::getYearStatus, "ADJUSTING")
                .apply("IFNULL(target,0) - IFNULL(actual_value,0) - IFNULL(frozen_amount,0) >= {0}", amount)
                .set(TaskYear::getYearStatus, "ADJUSTING")
                .set(TaskYear::getStatusBeforeAdjusting, taskYear.getYearStatus())
                .set(TaskYear::getFrozenAmount,
                        (taskYear.getFrozenAmount() == null ? BigDecimal.ZERO : taskYear.getFrozenAmount())
                                .add(amount))
                .set(TaskYear::getVersion, taskYear.getVersion() + 1)
                .set(TaskYear::getUpdatedBy, userId));
        if (rows == 0) {
            TaskYear latest = taskYearMapper.selectById(taskYear.getId());
            if (latest != null && "ADJUSTING".equals(latest.getYearStatus())) {
                throw new BizException(ErrorCode.ADJUST_IN_PROGRESS,
                        taskYear.getYear() + " 年已有进行中的调整申请（额度已冻结）");
            }
            throw new BizException(ErrorCode.ADJUST_AMOUNT_EXCEEDED,
                    taskYear.getYear() + " 年尚未完成的量不足，或数据已被他人修改");
        }
    }

    // ---------------- 数据范围 ----------------

    /** 申请人范围：本单元负责人 / 指标有效负责人（校级）/ 管理员 */
    private void requireApplicantScope(Task task, Indicator indicator, LoginUser user) {
        if (user.getRoles().contains("ADMIN")) {
            return;
        }
        if (dataScopeService.isUnitScope(user) && task.getUnitId().equals(user.getUnitId())) {
            return;
        }
        if (dataScopeService.isOwnerScope(user)) {
            Long owner = indicatorService.effectiveOwnerOf(indicator);
            if (owner != null && owner.equals(user.getUserId())) {
                return;
            }
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    /** scope=ALL 的数据范围：全校角色全部；学院负责人看本单元（转出或接收）及本人申请；校级负责人看本人负责指标及本人申请 */
    private void applyDataScope(LambdaQueryWrapper<Adjustment> wrapper, LoginUser user) {
        if (dataScopeService.isAllData(user)) {
            return;
        }
        List<Long> ownedIndicatorIds = dataScopeService.isOwnerScope(user)
                ? ownedIndicatorIdsWithChildren(user.getUserId()) : List.of();
        Long unitId = dataScopeService.isUnitScope(user) ? user.getUnitId() : null;
        wrapper.and(w -> {
            w.eq(Adjustment::getApplicantId, user.getUserId());
            if (unitId != null) {
                w.or(x -> x.eq(Adjustment::getFromUnitId, unitId))
                        .or(x -> x.eq(Adjustment::getToUnitId, unitId));
            }
            if (!ownedIndicatorIds.isEmpty()) {
                w.or(x -> x.in(Adjustment::getIndicatorId, ownedIndicatorIds));
            }
        });
    }

    /** 待我处理的调整申请（当前环节 = 期望处理人），供列表 scope=TODO 与 /todos 聚合复用 */
    public List<Adjustment> todoAdjustments(LoginUser user) {
        List<Long> ids = todoAdjustmentIds(user);
        if (ids.isEmpty()) {
            return List.of();
        }
        return adjustmentMapper.selectList(new LambdaQueryWrapper<Adjustment>()
                .in(Adjustment::getId, ids)
                .eq(Adjustment::getDeleted, 0L)
                .orderByAsc(Adjustment::getCreatedAt));
    }

    private List<Long> todoAdjustmentIds(LoginUser user) {
        boolean admin = user.getRoles().contains("ADMIN");
        boolean leader = user.getRoles().contains("LEADER");
        boolean unitOwner = user.getRoles().contains("UNIT_OWNER") && user.getUnitId() != null;
        LambdaQueryWrapper<ApprovalStep> wrapper = new LambdaQueryWrapper<ApprovalStep>()
                .isNull(ApprovalStep::getDecision);
        wrapper.and(main -> {
            main.nested(x -> x.eq(ApprovalStep::getNode, AdjustmentRules.OWNER_REVIEW)
                    .eq(ApprovalStep::getExpectedApproverId, user.getUserId()));
            if (admin) {
                main.or(x -> x.eq(ApprovalStep::getNode, AdjustmentRules.OWNER_REVIEW)
                        .isNull(ApprovalStep::getExpectedApproverId));
            }
            if (leader) {
                main.or(x -> x.eq(ApprovalStep::getNode, AdjustmentRules.LEADER_APPROVAL));
            }
            if (unitOwner) {
                main.or(x -> x.eq(ApprovalStep::getNode, AdjustmentRules.RECEIVER_CONFIRM)
                        .eq(ApprovalStep::getExpectedUnitId, user.getUnitId()));
            }
        });
        List<ApprovalStep> steps = stepMapper.selectList(wrapper);
        if (steps.isEmpty()) {
            return List.of();
        }
        Map<Long, Adjustment> adjustmentById = adjustmentMapper.selectList(
                        new LambdaQueryWrapper<Adjustment>()
                                .in(Adjustment::getId, steps.stream().map(ApprovalStep::getAdjustmentId).toList())
                                .eq(Adjustment::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Adjustment::getId, Function.identity()));
        List<Long> ids = steps.stream()
                .filter(s -> {
                    Adjustment adjustment = adjustmentById.get(s.getAdjustmentId());
                    return adjustment != null && Objects.equals(adjustment.getCurrentNode(), s.getNode());
                })
                .map(ApprovalStep::getAdjustmentId).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<AdjustmentItem> items = itemMapper.selectList(new LambdaQueryWrapper<AdjustmentItem>()
                .in(AdjustmentItem::getAdjustmentId, ids)
                .eq(AdjustmentItem::getDeleted, 0L)
                .select(AdjustmentItem::getAdjustmentId, AdjustmentItem::getFromTaskId,
                        AdjustmentItem::getToTaskId));
        Set<Long> taskIds = new HashSet<>();
        ids.forEach(id -> {
            Long taskId = adjustmentById.get(id).getTaskId();
            if (taskId != null) {
                taskIds.add(taskId);
            }
        });
        items.forEach(item -> {
            if (item.getFromTaskId() != null) {
                taskIds.add(item.getFromTaskId());
            }
            if (item.getToTaskId() != null) {
                taskIds.add(item.getToTaskId());
            }
        });
        if (taskIds.isEmpty()) {
            return ids;
        }
        Set<Long> historicalTaskIds = taskService.findByIds(List.copyOf(taskIds)).stream()
                .filter(task -> Boolean.TRUE.equals(task.getIsHistorical()))
                .map(Task::getId).collect(Collectors.toSet());
        if (historicalTaskIds.isEmpty()) {
            return ids;
        }
        Set<Long> historicalAdjustmentIds = new HashSet<>();
        ids.forEach(id -> {
            if (historicalTaskIds.contains(adjustmentById.get(id).getTaskId())) {
                historicalAdjustmentIds.add(id);
            }
        });
        items.forEach(item -> {
            if (historicalTaskIds.contains(item.getFromTaskId())
                    || historicalTaskIds.contains(item.getToTaskId())) {
                historicalAdjustmentIds.add(item.getAdjustmentId());
            }
        });
        return ids.stream().filter(id -> !historicalAdjustmentIds.contains(id)).toList();
    }

    /** 本人作为校级负责人负责的指标 id 集合（含 A 的下级 A+） */
    private List<Long> ownedIndicatorIdsWithChildren(Long userId) {
        List<Indicator> owned = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getOwnerId, userId)
                .eq(Indicator::getDeleted, 0L)
                .select(Indicator::getId));
        if (owned.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(owned.stream().map(Indicator::getId).toList());
        List<Long> children = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .in(Indicator::getParentId, ids)
                        .eq(Indicator::getDeleted, 0L)
                        .select(Indicator::getId))
                .stream().map(Indicator::getId).toList();
        ids.addAll(children);
        return ids;
    }

    // ---------------- 用户查询 ----------------

    public List<Long> unitOwnerIds(Long unitId) {
        if (unitId == null) {
            return List.of();
        }
        List<Long> roleUserIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRole, "UNIT_OWNER")
                        .select(UserRole::getUserId))
                .stream().map(UserRole::getUserId).toList();
        if (roleUserIds.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, roleUserIds)
                        .eq(SysUser::getUnitId, unitId)
                        .eq(SysUser::getIsEnabled, true)
                        .eq(SysUser::getDeleted, 0L)
                        .select(SysUser::getId))
                .stream().map(SysUser::getId).toList();
    }

    public List<Long> userIdsWithRole(String role) {
        List<Long> roleUserIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRole, role)
                        .select(UserRole::getUserId))
                .stream().map(UserRole::getUserId).toList();
        if (roleUserIds.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, roleUserIds)
                        .eq(SysUser::getIsEnabled, true)
                        .eq(SysUser::getDeleted, 0L)
                        .select(SysUser::getId))
                .stream().map(SysUser::getId).toList();
    }

    // ---------------- 文件 ----------------

    private void bindFiles(Long adjustmentId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        int sort = 0;
        for (Long fileId : new LinkedHashSet<>(fileIds)) {
            FileInfo fileInfo = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getId, fileId)
                    .eq(FileInfo::getDeleted, 0L));
            if (fileInfo == null) {
                throw new BizException(ErrorCode.FILE_NOT_FOUND);
            }
            FileRef ref = new FileRef();
            ref.setBizType(FILE_BIZ_TYPE);
            ref.setBizId(adjustmentId);
            ref.setFileId(fileId);
            ref.setSort(sort++);
            fileRefMapper.insert(ref);
            if ("TEMP".equals(fileInfo.getStatus())) {
                fileInfoMapper.update(null, new LambdaUpdateWrapper<FileInfo>()
                        .eq(FileInfo::getId, fileId)
                        .set(FileInfo::getStatus, "BOUND")
                        .set(FileInfo::getBoundAt, LocalDateTime.now()));
            }
        }
    }

    // ---------------- 视图组装 ----------------

    public List<AdjustmentDtos.ListItem> toListItems(List<Adjustment> adjustments) {
        if (adjustments.isEmpty()) {
            return List.of();
        }
        Refs refs = loadRefs(adjustments);
        List<Long> ids = adjustments.stream().map(Adjustment::getId).toList();
        Map<Long, List<AdjustmentDtos.ItemSummary>> itemsByAdjustment = itemMapper.selectList(
                        new LambdaQueryWrapper<AdjustmentItem>()
                                .in(AdjustmentItem::getAdjustmentId, ids)
                                .eq(AdjustmentItem::getDeleted, 0L)
                                .orderByAsc(AdjustmentItem::getId))
                .stream().collect(Collectors.groupingBy(AdjustmentItem::getAdjustmentId,
                        Collectors.mapping(i -> new AdjustmentDtos.ItemSummary(i.getFromYear(),
                                        i.getToYear(), i.getAmount(), i.getNewTarget()),
                                Collectors.toList())));
        return adjustments.stream()
                .map(a -> new AdjustmentDtos.ListItem(a.getId(), a.getPlanId(), a.getType(),
                        taskRef(refs.tasks().get(a.getTaskId())),
                        indicatorRef(refs.indicators().get(a.getIndicatorId())),
                        unitRef(refs.units().get(a.getFromUnitId())),
                        unitRef(refs.units().get(a.getToUnitId())),
                        a.getStatus(), a.getCurrentNode(),
                        userRefResolver.refOf(a.getApplicantId(), refs.users()),
                        a.getReason(), itemsByAdjustment.getOrDefault(a.getId(), List.of()),
                        a.getEffectiveAt(), a.getVersion(), a.getCreatedAt()))
                .toList();
    }

    public AdjustmentDtos.Detail toDetail(Adjustment adjustment) {
        Refs refs = loadRefs(List.of(adjustment));
        List<AdjustmentItem> items = itemsOf(adjustment.getId());
        List<ApprovalStep> steps = stepsOf(adjustment.getId());

        Set<Long> extraTaskIds = items.stream()
                .flatMap(i -> java.util.stream.Stream.of(i.getFromTaskId(), i.getToTaskId()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        extraTaskIds.removeIf(refs.tasks()::containsKey);
        Map<Long, Task> tasks = new HashMap<>(refs.tasks());
        if (!extraTaskIds.isEmpty()) {
            taskService.findByIds(List.copyOf(extraTaskIds))
                    .forEach(t -> tasks.put(t.getId(), t));
        }
        Set<Long> extraUnitIds = new LinkedHashSet<>();
        items.stream().map(AdjustmentItem::getToUnitId).filter(Objects::nonNull).forEach(extraUnitIds::add);
        steps.stream().map(ApprovalStep::getExpectedUnitId).filter(Objects::nonNull).forEach(extraUnitIds::add);
        Map<Long, OrgUnit> units = new HashMap<>(refs.units());
        extraUnitIds.removeIf(units::containsKey);
        if (!extraUnitIds.isEmpty()) {
            orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                            .in(OrgUnit::getId, extraUnitIds)
                            .eq(OrgUnit::getDeleted, 0L))
                    .forEach(u -> units.put(u.getId(), u));
        }
        Set<Long> extraUserIds = new LinkedHashSet<>();
        steps.forEach(s -> {
            if (s.getExpectedApproverId() != null) {
                extraUserIds.add(s.getExpectedApproverId());
            }
            if (s.getDecidedBy() != null) {
                extraUserIds.add(s.getDecidedBy());
            }
        });
        Set<Long> extraIndicatorIds = items.stream().map(AdjustmentItem::getTargetIndicatorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Indicator> indicators = new HashMap<>(refs.indicators());
        extraIndicatorIds.removeIf(indicators::containsKey);
        if (!extraIndicatorIds.isEmpty()) {
            indicatorService.findByIds(List.copyOf(extraIndicatorIds))
                    .forEach(i -> indicators.put(i.getId(), i));
        }
        Map<Long, SysUser> users = new HashMap<>(refs.users());
        extraUserIds.removeIf(users::containsKey);
        if (!extraUserIds.isEmpty()) {
            users.putAll(userRefResolver.mapOf(extraUserIds));
        }

        Map<Long, Task> finalTasks = tasks;
        Map<Long, OrgUnit> finalUnits = units;
        Map<Long, Indicator> finalIndicators = indicators;
        Map<Long, SysUser> finalUsers = users;
        List<AdjustmentDtos.ItemView> itemViews = items.stream()
                .map(i -> new AdjustmentDtos.ItemView(i.getId(),
                        taskRef(finalTasks.get(i.getFromTaskId())), i.getFromYear(),
                        taskRef(finalTasks.get(i.getToTaskId())),
                        unitRef(finalUnits.get(i.getToUnitId())), i.getToYear(),
                        i.getAmount(), i.getNewTarget(),
                        indicatorRef(finalIndicators.get(i.getTargetIndicatorId()))))
                .toList();
        List<AdjustmentDtos.StepView> stepViews = steps.stream()
                .map(s -> new AdjustmentDtos.StepView(s.getId(), s.getSeq(), s.getNode(),
                        userRefResolver.refOf(s.getExpectedApproverId(), finalUsers),
                        unitRef(finalUnits.get(s.getExpectedUnitId())),
                        s.getDecision(), s.getComment(), s.getDecidedAt(),
                        userRefResolver.refOf(s.getDecidedBy(), finalUsers)))
                .toList();
        List<AdjustmentDtos.FileView> files = fileViewsOf(adjustment.getId());
        return new AdjustmentDtos.Detail(adjustment.getId(), adjustment.getPlanId(), adjustment.getType(),
                taskRef(finalTasks.get(adjustment.getTaskId())),
                indicatorRef(finalIndicators.get(adjustment.getIndicatorId())),
                unitRef(finalUnits.get(adjustment.getFromUnitId())),
                unitRef(finalUnits.get(adjustment.getToUnitId())),
                adjustment.getStatus(), adjustment.getCurrentNode(),
                userRefResolver.refOf(adjustment.getApplicantId(), finalUsers),
                adjustment.getReason(), itemViews, stepViews,
                new AdjustmentDtos.Snapshot(readSnapshot(adjustment.getSnapshotBefore()),
                        readSnapshot(adjustment.getSnapshotAfter())),
                files, adjustment.getEffectiveAt(), adjustment.getWithdrawnAt(),
                adjustment.getVersion(), adjustment.getCreatedAt(), adjustment.getUpdatedAt());
    }

    private List<AdjustmentDtos.FileView> fileViewsOf(Long adjustmentId) {
        List<FileRef> refs = fileRefMapper.selectList(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getBizType, FILE_BIZ_TYPE)
                .eq(FileRef::getBizId, adjustmentId)
                .orderByAsc(FileRef::getSort));
        if (refs.isEmpty()) {
            return List.of();
        }
        return fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                        .in(FileInfo::getId, refs.stream().map(FileRef::getFileId).toList())
                        .eq(FileInfo::getDeleted, 0L))
                .stream().map(f -> new AdjustmentDtos.FileView(f.getId(), f.getFileName(),
                        f.getSize(), f.getExt(), f.getCategory()))
                .toList();
    }

    private record Refs(Map<Long, Task> tasks, Map<Long, Indicator> indicators,
                        Map<Long, OrgUnit> units, Map<Long, SysUser> users) {
    }

    private Refs loadRefs(List<Adjustment> adjustments) {
        List<Long> taskIds = adjustments.stream().map(Adjustment::getTaskId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Task> tasks = taskIds.isEmpty() ? Map.of()
                : taskService.findByIds(taskIds).stream()
                        .collect(Collectors.toMap(Task::getId, Function.identity()));
        List<Long> indicatorIds = adjustments.stream().map(Adjustment::getIndicatorId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Indicator> indicators = indicatorIds.isEmpty() ? Map.of()
                : indicatorService.findByIds(indicatorIds).stream()
                        .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        List<Long> unitIds = adjustments.stream()
                .flatMap(a -> java.util.stream.Stream.of(a.getFromUnitId(), a.getToUnitId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, OrgUnit> units = unitIds.isEmpty() ? Map.of()
                : orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, unitIds)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
        Map<Long, SysUser> users = userRefResolver.mapOf(
                adjustments.stream().map(Adjustment::getApplicantId).toList());
        return new Refs(tasks, indicators, units, users);
    }

    private AdjustmentDtos.TaskRef taskRef(Task task) {
        return task == null ? null
                : new AdjustmentDtos.TaskRef(task.getId(), task.getCode(), task.getIsHistorical());
    }

    private AdjustmentDtos.IndicatorRef indicatorRef(Indicator indicator) {
        return indicator == null ? null
                : new AdjustmentDtos.IndicatorRef(indicator.getId(), indicator.getCode(), indicator.getName(),
                        indicator.getStatCaliber(), indicator.getUnitLabel());
    }

    private AdjustmentDtos.UnitRef unitRef(OrgUnit unit) {
        return unit == null ? null : new AdjustmentDtos.UnitRef(unit.getId(), unit.getCode(), unit.getName());
    }

    // ---------------- 快照序列化 ----------------

    public String writeSnapshot(List<AdjustmentRules.TargetEntry> entries) {
        try {
            return objectMapper.writeValueAsString(toSnapshotEntries(entries));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "快照序列化失败");
        }
    }

    public List<AdjustmentDtos.SnapshotEntry> readSnapshot(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private List<AdjustmentDtos.SnapshotEntry> toSnapshotEntries(List<AdjustmentRules.TargetEntry> entries) {
        return entries.stream()
                .map(e -> new AdjustmentDtos.SnapshotEntry(e.taskCode(), e.indicatorCode(), e.year(), e.target()))
                .toList();
    }

    // ---------------- 小工具 ----------------

    public TaskYear taskYearOf(Long taskId, Integer year) {
        return taskYearMapper.selectOne(new LambdaQueryWrapper<TaskYear>()
                .eq(TaskYear::getTaskId, taskId)
                .eq(TaskYear::getYear, year)
                .eq(TaskYear::getDeleted, 0L));
    }

    private boolean isYearLocked(Long planId, Integer year) {
        PlanYear planYear = planYearMapper.selectOne(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, planId)
                .eq(PlanYear::getYear, year)
                .eq(PlanYear::getDeleted, 0L));
        return planYear != null && "LOCKED".equals(planYear.getStatus());
    }
}
