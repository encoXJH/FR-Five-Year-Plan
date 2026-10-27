package edu.fzrj.metrics.module.adjustment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.api.FieldError;
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
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调整审批（API 约定 15.6、PRD 5.6.2）：服务端按 current_node 判定当前环节与处理人（70005），
 * 任一环节驳回整个申请结束并释放冻结额度；最后环节通过后在一个事务里生效
 * （数据库设计规则 6：目标修改 + 快照 + target_version+1 + 接收方自动建任务 + 通知）。
 */
@Service
public class AdjustmentDecisionService {

    private final AdjustmentMapper adjustmentMapper;
    private final AdjustmentItemMapper itemMapper;
    private final ApprovalStepMapper stepMapper;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final PlanYearMapper planYearMapper;
    private final OrgUnitAdminService orgUnitAdminService;
    private final AdjustmentService adjustmentService;
    private final TaskService taskService;
    private final IndicatorService indicatorService;
    private final PlanService planService;

    public AdjustmentDecisionService(AdjustmentMapper adjustmentMapper, AdjustmentItemMapper itemMapper,
                                     ApprovalStepMapper stepMapper, TaskMapper taskMapper,
                                     TaskYearMapper taskYearMapper,
                                     IndicatorYearTargetMapper yearTargetMapper, PlanYearMapper planYearMapper,
                                     OrgUnitAdminService orgUnitAdminService, AdjustmentService adjustmentService,
                                     TaskService taskService, IndicatorService indicatorService,
                                     PlanService planService) {
        this.adjustmentMapper = adjustmentMapper;
        this.itemMapper = itemMapper;
        this.stepMapper = stepMapper;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.planYearMapper = planYearMapper;
        this.orgUnitAdminService = orgUnitAdminService;
        this.adjustmentService = adjustmentService;
        this.taskService = taskService;
        this.indicatorService = indicatorService;
        this.planService = planService;
    }

    /** 当前环节处理：APPROVE 推进 / 最后环节生效；REJECT 结束并释放冻结额度 */
    @Transactional
    public AdjustmentDtos.Detail decide(Long adjustmentId, AdjustmentDtos.DecisionRequest request) {
        // 行锁防并发生效（数据库设计规则 6.1）
        Adjustment adjustment = lockForUpdate(adjustmentId);
        if (!AdjustmentRules.isPending(adjustment.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "该申请已结束，不能审批");
        }
        adjustmentService.requireVersion(adjustment, request.getVersion());
        String decision = request.getDecision();
        if (!AdjustmentRules.APPROVE.equals(decision) && !AdjustmentRules.REJECT.equals(decision)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审批决定只能是 APPROVE / REJECT");
        }
        if (AdjustmentRules.REJECT.equals(decision) && !StringUtils.hasText(request.getComment())) {
            throw new BizException(ErrorCode.REVIEW_COMMENT_REQUIRED);
        }
        LoginUser user = SecurityUtils.getLoginUser();
        Long userId = user.getUserId();
        String node = adjustment.getCurrentNode();
        ApprovalStep step = currentStep(adjustmentId, node);
        requireHandler(step, user);

        List<AdjustmentItem> items = adjustmentService.itemsOf(adjustmentId);
        adjustmentService.requireCurrentTasks(adjustment, items);
        requireYearsNotLocked(adjustment, items);

        LocalDateTime now = LocalDateTime.now();
        step.setDecision(decision);
        step.setComment(request.getComment());
        step.setDecidedAt(now);
        step.setDecidedBy(userId);
        stepMapper.updateById(step);

        Plan plan = planService.requirePlan(adjustment.getPlanId());
        boolean receiverConfirm = !Boolean.FALSE.equals(plan.getIsReceiverConfirmRequired());
        String nextStatus = AdjustmentRules.statusOnDecision(adjustment.getType(),
                adjustment.getStatus(), decision, receiverConfirm);
        if (nextStatus == null) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "该申请已结束，不能审批");
        }
        String bizCode = adjustmentService.bizCode(adjustment);

        if (AdjustmentRules.REJECTED.equals(nextStatus)) {
            adjustmentService.releaseFreeze(items, userId);
            adjustment.setStatus(AdjustmentRules.REJECTED);
            adjustment.setCurrentNode(null);
            adjustment.setUpdatedBy(userId);
            if (adjustmentMapper.updateById(adjustment) == 0) {
                throw adjustmentService.versionConflict(adjustmentId);
            }
            adjustmentService.notifyUsers(List.of(adjustment.getApplicantId()), "ADJUSTMENT_RESULT",
                    "调整申请已驳回：" + bizCode,
                    AdjustmentService.typeLabel(adjustment.getType()) + " 申请（" + bizCode + "）在「"
                            + nodeLabel(node) + "」环节被驳回。意见：" + request.getComment(),
                    adjustmentId);
        } else if (AdjustmentRules.APPROVED.equals(nextStatus)) {
            applyEffective(adjustment, items, plan, userId, now);
            adjustment.setStatus(AdjustmentRules.APPROVED);
            adjustment.setCurrentNode(null);
            adjustment.setEffectiveAt(now);
            adjustment.setUpdatedBy(userId);
            if (adjustmentMapper.updateById(adjustment) == 0) {
                throw adjustmentService.versionConflict(adjustmentId);
            }
            notifyEffective(adjustment, items, bizCode);
        } else {
            adjustment.setStatus(nextStatus);
            adjustment.setCurrentNode(AdjustmentRules.nodeOfStatus(nextStatus));
            adjustment.setUpdatedBy(userId);
            if (adjustmentMapper.updateById(adjustment) == 0) {
                throw adjustmentService.versionConflict(adjustmentId);
            }
            adjustmentService.notifyNodeHandlers(adjustment, adjustment.getCurrentNode(), "ADJUSTMENT_TODO",
                    "调整申请待处理：" + bizCode,
                    AdjustmentService.typeLabel(adjustment.getType()) + " 申请（" + bizCode + "）已通过「"
                            + nodeLabel(node) + "」，等待您处理。");
        }
        return adjustmentService.toDetail(adjustmentService.requireAdjustment(adjustmentId));
    }

    // ---------------- 生效 ----------------

    /**
     * 全部审批通过后在一个事务内生效（数据库设计规则 6）：
     * 锁定顺序 adjustment → task_year（id 升序）→ task → indicator_year_target。
     */
    private void applyEffective(Adjustment adjustment, List<AdjustmentItem> items, Plan plan,
                                Long userId, LocalDateTime now) {
        if (AdjustmentRules.TARGET_CHANGE.equals(adjustment.getType())) {
            applyTargetChange(adjustment, items, userId);
            return;
        }
        applyMove(adjustment, items, plan, userId);
    }

    /** 延期 / 转移类生效：移动 B 年度目标、同步 A+ 年度分布、必要时自动新建接收任务 */
    private void applyMove(Adjustment adjustment, List<AdjustmentItem> items, Plan plan,
                           Long userId) {
        Task fromTask = taskService.requireCurrentTask(adjustment.getTaskId());
        Indicator indicator = indicatorService.requireIndicator(adjustment.getIndicatorId());
        String caliber = indicator.getStatCaliber();
        boolean isBoolean = "BOOLEAN".equals(caliber);

        // 接收任务：转移类且接收单元原无该 A+ 的 B 时自动新建
        Task receiverTask = fromTask;
        if (adjustment.getToUnitId() != null) {
            OrgUnit receiverUnit = orgUnitAdminService.requireBAssignable(adjustment.getToUnitId());
            receiverTask = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                    .eq(Task::getIndicatorId, indicator.getId())
                    .eq(Task::getUnitId, adjustment.getToUnitId())
                    .eq(Task::getDeleted, 0L));
            if (receiverTask == null) {
                receiverTask = createReceiverTask(adjustment, fromTask, indicator, plan, receiverUnit, userId);
            } else {
                receiverTask = taskService.requireCurrentTask(receiverTask.getId());
            }
        }

        // 按 task_year id 升序逐条处理（规则 6.2 加锁顺序）
        record MoveRow(AdjustmentItem item, TaskYear fromTy, TaskYear toTy) {
        }
        List<MoveRow> rows = new ArrayList<>();
        for (AdjustmentItem item : items) {
            TaskYear fromTy = requireTaskYear(fromTask.getId(), item.getFromYear());
            TaskYear toTy = receiverTask.getId().equals(fromTask.getId())
                    && item.getToYear().equals(item.getFromYear())
                    ? fromTy : requireTaskYear(receiverTask.getId(), item.getToYear());
            rows.add(new MoveRow(item, fromTy, toTy));
        }
        rows.sort(Comparator.comparing(r -> r.fromTy().getId()));

        for (MoveRow row : rows) {
            AdjustmentItem item = row.item();
            TaskYear fromTy = row.fromTy();
            // 生效复核：目标 − 已完成 −（冻结 − 本单冻结）≥ 调整量，否则 70001 回滚
            BigDecimal frozenOthers = (fromTy.getFrozenAmount() == null ? BigDecimal.ZERO
                    : fromTy.getFrozenAmount()).subtract(item.getAmount());
            BigDecimal remaining = AdjustmentRules.remaining(fromTy.getTarget(),
                    fromTy.getActualValue(), frozenOthers.max(BigDecimal.ZERO));
            if (item.getAmount().compareTo(remaining) > 0) {
                throw new BizException(ErrorCode.ADJUST_AMOUNT_EXCEEDED,
                        fromTy.getYear() + " 年尚未完成的量已不足（审批期间有新数据审核通过），请驳回后重新申请");
            }
            if (isBoolean) {
                fromTy.setTarget(null);
            } else {
                fromTy.setTarget(fromTy.getTarget() == null ? null
                        : fromTy.getTarget().subtract(item.getAmount()));
            }
            fromTy.setTargetVersion(fromTy.getTargetVersion() + 1);
            fromTy.setFrozenAmount(frozenOthers.max(BigDecimal.ZERO));
            if ("ADJUSTING".equals(fromTy.getYearStatus())) {
                fromTy.setYearStatus(fromTy.getStatusBeforeAdjusting() == null
                        ? "IN_PROGRESS" : fromTy.getStatusBeforeAdjusting());
                fromTy.setStatusBeforeAdjusting(null);
            }
            fromTy.setUpdatedBy(userId);
            refreshTaskYearAchievement(fromTy, indicator);
            if (taskYearMapper.updateById(fromTy) == 0) {
                throw new BizException(ErrorCode.VERSION_CONFLICT, "任务年度目标已被他人修改，请重试");
            }

            TaskYear toTy = row.toTy();
            if (!toTy.getId().equals(fromTy.getId())) {
                if (isBoolean) {
                    toTy.setTarget(BigDecimal.ONE);
                } else {
                    toTy.setTarget((toTy.getTarget() == null ? BigDecimal.ZERO : toTy.getTarget())
                            .add(item.getAmount()));
                }
                toTy.setTargetVersion(toTy.getTargetVersion() + 1);
                toTy.setUpdatedBy(userId);
                refreshTaskYearAchievement(toTy, indicator);
                if (taskYearMapper.updateById(toTy) == 0) {
                    throw new BizException(ErrorCode.VERSION_CONFLICT, "任务年度目标已被他人修改，请重试");
                }
            }
            // 回填接收任务
            item.setToTaskId(toTy.getTaskId());
            item.setUpdatedBy(userId);
            itemMapper.updateById(item);
        }

        // 同步 A+ 年度目标分布（延期改变年度分布；总量不变）
        for (MoveRow row : rows) {
            if (AdjustmentRules.isPostpone(adjustment.getType())) {
                shiftIndicatorYearTarget(indicator, row.item().getFromYear(), row.item().getToYear(),
                        row.item().getAmount(), isBoolean, userId);
            }
        }

        // 转出方全部年度目标为 0（或空）时任务置为已全部转出（规则 6.4）
        if (!receiverTask.getId().equals(fromTask.getId())) {
            List<TaskYear> fromYears = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                    .eq(TaskYear::getTaskId, fromTask.getId())
                    .eq(TaskYear::getDeleted, 0L));
            boolean allZero = fromYears.stream().allMatch(y -> y.getTarget() == null
                    || y.getTarget().compareTo(BigDecimal.ZERO) == 0);
            if (allZero) {
                fromTask.setStatus("TRANSFERRED_OUT");
                fromTask.setUpdatedBy(userId);
                taskMapper.updateById(fromTask);
            }
        }

        // 调整前后快照（before 申请时已写；after 按试算规则重算，与 preview 一致）
        adjustment.setSnapshotAfter(adjustmentService.writeSnapshot(computeMoveAfter(adjustment, items)));

        // 防御性守恒复核（规则 6.8）：ΣB 逐年 = A+ 逐年，不满足则回滚
        List<String> conservation = AdjustmentRules.conservationErrors(caliber, "A+ 目标",
                adjustmentService.bSumByYear(indicator.getId()),
                adjustmentService.indicatorTargets(indicator.getId()));
        if (!conservation.isEmpty()) {
            throw new BizException(ErrorCode.CONSERVATION_VIOLATED,
                    ErrorCode.CONSERVATION_VIOLATED.getMessage(),
                    conservation.stream().map(m -> new FieldError("items", m)).toList());
        }
    }

    /** 目标调整生效：只改 indicator_year_target / total_target 与快照，不动 B（数据库设计规则 6 末注） */
    private void applyTargetChange(Adjustment adjustment, List<AdjustmentItem> items, Long userId) {
        Indicator indicator = indicatorService.requireIndicator(adjustment.getIndicatorId());
        // 生效前复核守恒（申请后数据可能已变化），不满足 70002 回滚
        List<FieldError> errors = adjustmentService.targetChangeErrors(indicator, items.stream()
                .map(i -> new AdjustmentService.PreparedItemView(i.getTargetIndicatorId(),
                        i.getToYear(), i.getNewTarget()))
                .toList());
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.CONSERVATION_VIOLATED,
                    ErrorCode.CONSERVATION_VIOLATED.getMessage(), errors);
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Indicator> indicatorCache = new HashMap<>();
        for (AdjustmentItem item : items) {
            Indicator target = indicatorCache.computeIfAbsent(item.getTargetIndicatorId(),
                    indicatorService::requireIndicator);
            if (item.getToYear() == null) {
                target.setTotalTarget(item.getNewTarget());
                target.setUpdatedBy(userId);
                if (indicatorService.updateIndicator(target) == 0) {
                    throw new BizException(ErrorCode.VERSION_CONFLICT, "指标已被他人修改，请重试");
                }
                continue;
            }
            IndicatorYearTarget row = yearTargetMapper.selectOne(new LambdaQueryWrapper<IndicatorYearTarget>()
                    .eq(IndicatorYearTarget::getIndicatorId, item.getTargetIndicatorId())
                    .eq(IndicatorYearTarget::getYear, item.getToYear())
                    .eq(IndicatorYearTarget::getDeleted, 0L));
            if (row == null) {
                row = new IndicatorYearTarget();
                row.setIndicatorId(item.getTargetIndicatorId());
                row.setYear(item.getToYear());
                row.setTarget(item.getNewTarget());
                row.setCreatedBy(userId);
                row.setUpdatedBy(userId);
                yearTargetMapper.insert(row);
            } else {
                row.setTarget(item.getNewTarget());
                row.setUpdatedBy(userId);
                refreshIndicatorAchievement(row, target, now);
                yearTargetMapper.updateById(row);
            }
        }
        adjustment.setSnapshotAfter(adjustmentService.writeSnapshot(computeTargetChangeAfter(adjustment, items)));
    }

    // ---------------- 生效内部 ----------------

    /** 接收方原无该 A+ 的 B 时自动新建（规则 6.3）：编号 {A+编号}-{单元编码}，初始目标 0，待接收方确认接收 */
    private Task createReceiverTask(Adjustment adjustment, Task fromTask, Indicator indicator, Plan plan,
                                    OrgUnit unit, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        Task task = new Task();
        task.setPlanId(plan.getId());
        task.setIndicatorId(indicator.getId());
        task.setTopIndicatorId(fromTask.getTopIndicatorId());
        task.setUnitId(unit.getId());
        task.setCode(indicator.getCode() + "-" + unit.getCode());
        List<Long> unitOwners = adjustmentService.unitOwnerIds(unit.getId());
        task.setOwnerId(unitOwners.isEmpty() ? null : unitOwners.get(0));
        task.setStatus("DISTRIBUTED");
        task.setIsHistorical(false);
        task.setDistributedAt(now);
        task.setDistributedBy(userId);
        task.setSourceAdjustmentId(adjustment.getId());
        task.setCreatedBy(userId);
        task.setUpdatedBy(userId);
        taskMapper.insert(task);
        for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
            TaskYear taskYear = new TaskYear();
            taskYear.setTaskId(task.getId());
            taskYear.setYear(year);
            taskYear.setPlanId(plan.getId());
            taskYear.setIndicatorId(indicator.getId());
            taskYear.setUnitId(unit.getId());
            taskYear.setTarget(BigDecimal.ZERO);
            taskYear.setOriginalTarget(BigDecimal.ZERO);
            taskYear.setTargetVersion(1);
            taskYear.setFrozenAmount(BigDecimal.ZERO);
            taskYear.setYearStatus("NOT_ACTIVATED");
            taskYear.setCreatedBy(userId);
            taskYear.setUpdatedBy(userId);
            taskYearMapper.insert(taskYear);
        }
        return task;
    }

    /** A+ 年度目标同步移动（延期类）：fromYear −amount、toYear +amount；BOOLEAN 为 null/1 互换 */
    private void shiftIndicatorYearTarget(Indicator indicator, Integer fromYear, Integer toYear,
                                          BigDecimal amount, boolean isBoolean, Long userId) {
        IndicatorYearTarget from = requireYearTarget(indicator.getId(), fromYear);
        IndicatorYearTarget to = requireYearTarget(indicator.getId(), toYear);
        if (isBoolean) {
            from.setTarget(null);
            to.setTarget(BigDecimal.ONE);
        } else {
            from.setTarget(from.getTarget() == null ? null : from.getTarget().subtract(amount));
            to.setTarget((to.getTarget() == null ? BigDecimal.ZERO : to.getTarget()).add(amount));
        }
        LocalDateTime now = LocalDateTime.now();
        from.setUpdatedBy(userId);
        to.setUpdatedBy(userId);
        refreshIndicatorAchievement(from, indicator, now);
        refreshIndicatorAchievement(to, indicator, now);
        yearTargetMapper.updateById(from);
        yearTargetMapper.updateById(to);
    }

    /** 目标变化后按缓存完成值重算完成率与达标标记；IN_PROGRESS / ACHIEVED 之间按新目标重判 */
    private void refreshTaskYearAchievement(TaskYear taskYear, Indicator indicator) {
        MetricCalculator.YearResult cached = new MetricCalculator.YearResult(taskYear.getActualValue(),
                taskYear.getActualNumerator(), taskYear.getActualDenominator(),
                taskYear.getActualTotal(), null, null);
        String caliber = indicator.getStatCaliber();
        taskYear.setCompletionRate(MetricCalculator.completionRate(caliber, cached, taskYear.getTarget()));
        taskYear.setIsAchieved(MetricCalculator.achieved(indicator.getTargetDirection(), caliber,
                cached, taskYear.getTarget()));
        if ("IN_PROGRESS".equals(taskYear.getYearStatus()) || "ACHIEVED".equals(taskYear.getYearStatus())) {
            taskYear.setYearStatus(Boolean.TRUE.equals(taskYear.getIsAchieved()) ? "ACHIEVED" : "IN_PROGRESS");
        }
    }

    private void refreshIndicatorAchievement(IndicatorYearTarget row, Indicator indicator, LocalDateTime now) {
        MetricCalculator.YearResult cached = new MetricCalculator.YearResult(row.getActualValue(),
                row.getActualNumerator(), row.getActualDenominator(), row.getActualTotal(), null, null);
        String caliber = indicator.getStatCaliber();
        row.setCompletionRate(MetricCalculator.completionRate(caliber, cached, row.getTarget()));
        row.setAchieveStatus(MetricCalculator.achieveStatus(indicator.getTargetDirection(), caliber,
                cached, row.getTarget()));
        row.setCalculatedAt(now);
    }

    /** 移动类 after 快照：申请时 before + 明细移动（与 preview 同规则） */
    private List<AdjustmentRules.TargetEntry> computeMoveAfter(Adjustment adjustment,
                                                               List<AdjustmentItem> items) {
        List<AdjustmentRules.TargetEntry> before = new ArrayList<>();
        List<AdjustmentDtos.SnapshotEntry> stored = adjustmentService.readSnapshot(adjustment.getSnapshotBefore());
        if (stored != null) {
            stored.forEach(e -> before.add(new AdjustmentRules.TargetEntry(e.taskCode(), e.indicatorCode(),
                    e.year(), e.target())));
        }
        Map<Long, String> codeByTaskId = new HashMap<>();
        List<AdjustmentRules.MoveItem> moves = new ArrayList<>();
        for (AdjustmentItem item : items) {
            String fromCode = codeByTaskId.computeIfAbsent(item.getFromTaskId(),
                    id -> taskService.requireTask(id).getCode());
            Task toTask = taskService.requireTask(item.getToTaskId());
            codeByTaskId.putIfAbsent(toTask.getId(), toTask.getCode());
            moves.add(new AdjustmentRules.MoveItem(fromCode, item.getFromYear(),
                    toTask.getCode(), item.getToYear(), item.getAmount()));
        }
        return AdjustmentRules.applyMoves(before, moves);
    }

    private List<AdjustmentRules.TargetEntry> computeTargetChangeAfter(Adjustment adjustment,
                                                                       List<AdjustmentItem> items) {
        List<AdjustmentRules.TargetEntry> before = new ArrayList<>();
        List<AdjustmentDtos.SnapshotEntry> stored = adjustmentService.readSnapshot(adjustment.getSnapshotBefore());
        if (stored != null) {
            stored.forEach(e -> before.add(new AdjustmentRules.TargetEntry(e.taskCode(), e.indicatorCode(),
                    e.year(), e.target())));
        }
        Map<Long, String> codeByIndicatorId = new HashMap<>();
        List<AdjustmentRules.SetItem> setItems = items.stream()
                .map(i -> new AdjustmentRules.SetItem(
                        codeByIndicatorId.computeIfAbsent(i.getTargetIndicatorId(),
                                id -> indicatorService.requireIndicator(id).getCode()),
                        i.getToYear(), i.getNewTarget()))
                .toList();
        return AdjustmentRules.applySetItems(before, setItems);
    }

    // ---------------- 处理人 / 前置校验 ----------------

    /** 当前环节处理人判定（70005）：初审=指标有效负责人（空则管理员）；接收确认=接收单元负责人；终审=校领导 */
    private void requireHandler(ApprovalStep step, LoginUser user) {
        if (!AdjustmentRules.canDecide(step.getNode(), step.getExpectedApproverId(),
                step.getExpectedUnitId(), user.getUserId(), user.getUnitId(), user.getRoles())) {
            throw new BizException(ErrorCode.NOT_CURRENT_APPROVER);
        }
    }

    /** 年度已核算锁定不能审批生效（60003；规则 7） */
    private void requireYearsNotLocked(Adjustment adjustment, List<AdjustmentItem> items) {
        Set<Integer> years = new HashSet<>();
        for (AdjustmentItem item : items) {
            if (item.getFromYear() != null) {
                years.add(item.getFromYear());
            }
            if (item.getToYear() != null) {
                years.add(item.getToYear());
            }
        }
        for (Integer year : years) {
            PlanYear planYear = planYearMapper.selectOne(new LambdaQueryWrapper<PlanYear>()
                    .eq(PlanYear::getPlanId, adjustment.getPlanId())
                    .eq(PlanYear::getYear, year)
                    .eq(PlanYear::getDeleted, 0L));
            if (planYear != null && "LOCKED".equals(planYear.getStatus())) {
                throw new BizException(ErrorCode.YEAR_SETTLED, year + " 年已核算锁定，不能审批生效");
            }
        }
    }

    private ApprovalStep currentStep(Long adjustmentId, String node) {
        return adjustmentService.stepsOf(adjustmentId).stream()
                .filter(s -> s.getNode().equals(node) && s.getDecision() == null)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SUBMISSION_STATUS_INVALID,
                        "该申请已结束，不能审批"));
    }

    private Adjustment lockForUpdate(Long adjustmentId) {
        Adjustment adjustment = adjustmentMapper.selectOne(new LambdaQueryWrapper<Adjustment>()
                .eq(Adjustment::getId, adjustmentId)
                .eq(Adjustment::getDeleted, 0L)
                .last("FOR UPDATE"));
        if (adjustment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "调整申请不存在");
        }
        return adjustment;
    }

    private TaskYear requireTaskYear(Long taskId, Integer year) {
        TaskYear taskYear = adjustmentService.taskYearOf(taskId, year);
        if (taskYear == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务年度不存在：" + year);
        }
        return taskYear;
    }

    private IndicatorYearTarget requireYearTarget(Long indicatorId, Integer year) {
        IndicatorYearTarget row = yearTargetMapper.selectOne(new LambdaQueryWrapper<IndicatorYearTarget>()
                .eq(IndicatorYearTarget::getIndicatorId, indicatorId)
                .eq(IndicatorYearTarget::getYear, year)
                .eq(IndicatorYearTarget::getDeleted, 0L));
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "指标年度目标不存在：" + year);
        }
        return row;
    }

    private void notifyEffective(Adjustment adjustment, List<AdjustmentItem> items, String bizCode) {
        List<Long> userIds = new ArrayList<>();
        userIds.add(adjustment.getApplicantId());
        if (adjustment.getTaskId() != null) {
            Task fromTask = taskMapper.selectById(adjustment.getTaskId());
            if (fromTask != null) {
                userIds.add(fromTask.getOwnerId());
            }
        }
        for (AdjustmentItem item : items) {
            if (item.getToTaskId() != null) {
                Task toTask = taskMapper.selectById(item.getToTaskId());
                if (toTask != null && toTask.getOwnerId() != null) {
                    userIds.add(toTask.getOwnerId());
                }
            }
        }
        if (adjustment.getToUnitId() != null) {
            userIds.addAll(adjustmentService.unitOwnerIds(adjustment.getToUnitId()));
        }
        adjustmentService.notifyUsers(userIds, "ADJUSTMENT_RESULT",
                "调整申请已生效：" + bizCode,
                AdjustmentService.typeLabel(adjustment.getType()) + " 申请（" + bizCode
                        + "）已完成全部审批并生效，年度目标已更新。",
                adjustment.getId());
    }

    private static String nodeLabel(String node) {
        return switch (node) {
            case AdjustmentRules.OWNER_REVIEW -> "校级负责人初审";
            case AdjustmentRules.RECEIVER_CONFIRM -> "接收方确认";
            case AdjustmentRules.LEADER_APPROVAL -> "校领导审批";
            default -> node;
        };
    }
}
