package edu.fzrj.metrics.module.indicator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.support.IndicatorRules;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 指标预算：A 级总预算维护与支出明细（金额单位万元）。
 * 已花预算仅统计校审 APPROVED 的认定支出，随填报两级审核认定，不走独立调整审批。
 */
@Service
public class IndicatorBudgetService {

    private static final String BUDGET_FILE_BIZ_TYPE = "SUBMISSION_BUDGET";
    private static final Set<String> SORT_FIELDS = Set.of("submitted_at", "reviewed_at", "year", "created_at");

    private final IndicatorService indicatorService;
    private final SubmissionMapper submissionMapper;
    private final FileRefMapper fileRefMapper;
    private final FileInfoMapper fileInfoMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final UserRefResolver userRefResolver;
    private final PlanService planService;
    private final DataScopeService dataScopeService;

    public IndicatorBudgetService(IndicatorService indicatorService, SubmissionMapper submissionMapper,
                                  FileRefMapper fileRefMapper, FileInfoMapper fileInfoMapper,
                                  OrgUnitMapper orgUnitMapper, UserRefResolver userRefResolver,
                                  PlanService planService, DataScopeService dataScopeService) {
        this.indicatorService = indicatorService;
        this.submissionMapper = submissionMapper;
        this.fileRefMapper = fileRefMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.userRefResolver = userRefResolver;
        this.planService = planService;
        this.dataScopeService = dataScopeService;
    }

    /** 维护总预算：不受指标下发保护限制（预算与目标值独立），带 version 乐观锁。 */
    @Transactional
    public IndicatorDtos.Detail putBudget(Long indicatorId, IndicatorDtos.BudgetPutRequest request) {
        Indicator indicator = indicatorService.requireIndicator(indicatorId);
        if (!IndicatorRules.LEVEL_A.equals(indicator.getLevel())) {
            throw new BizException(ErrorCode.LEVEL_INVALID, "预算只能维护在 A 级指标上");
        }
        Plan plan = planService.requirePlan(indicator.getPlanId());
        planService.requireNotArchived(plan);
        requireBudgetManageScope(indicator);
        planService.requireVersion(indicator.getVersion(), request.getVersion());
        BigDecimal budget = request.getTotalBudget();
        if (budget != null) {
            if (budget.signum() < 0 || budget.scale() > 2) {
                throw new BizException(ErrorCode.PARAM_INVALID, "总预算必须为非负数且最多两位小数（万元）");
            }
            BigDecimal spent = indicatorService.spentOfIndicator(indicator);
            if (budget.compareTo(spent) < 0) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "总预算不能低于已认定支出 " + spent.stripTrailingZeros().toPlainString() + " 万元");
            }
        }
        indicator.setTotalBudget(budget);
        indicator.setUpdatedBy(SecurityUtils.getUserId());
        if (indicatorService.updateIndicator(indicator) == 0) {
            Indicator latest = indicatorService.requireIndicator(indicatorId);
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + latest.getVersion());
        }
        return indicatorService.detail(indicatorId);
    }

    /** 预算概览 + 支出明细：明细按当前用户可见任务过滤，汇总为 A 级全量口径。 */
    public IndicatorDtos.BudgetView view(Long indicatorId, IndicatorDtos.BudgetQuery query) {
        Indicator indicator = indicatorService.requireIndicator(indicatorId);
        indicatorService.requireDetailAccess(indicator);
        BigDecimal totalBudget = IndicatorRules.LEVEL_A.equals(indicator.getLevel())
                ? indicator.getTotalBudget() : null;
        BigDecimal spent = indicatorService.spentOfIndicator(indicator);
        List<Task> tasks = indicatorService.visibleTasks(indicator);
        if (tasks.isEmpty()) {
            return new IndicatorDtos.BudgetView(totalBudget, spent,
                    new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize()));
        }
        Map<Long, Task> taskById = tasks.stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        Page<Submission> page = submissionMapper.selectPage(query.toPage(SORT_FIELDS),
                new LambdaQueryWrapper<Submission>()
                        .in(Submission::getTaskId, taskById.keySet())
                        .eq(Submission::getDeleted, 0L)
                        .isNotNull(Submission::getExpenseAmount)
                        .eq(query.getYear() != null, Submission::getYear, query.getYear())
                        .orderByDesc(Submission::getSubmittedAt));
        List<Submission> records = page.getRecords();
        if (records.isEmpty()) {
            return new IndicatorDtos.BudgetView(totalBudget, spent,
                    new PageResult<>(List.of(), page.getTotal(), query.getPage(), query.getPageSize()));
        }

        List<Long> unitIds = tasks.stream().map(Task::getUnitId).filter(Objects::nonNull).distinct().toList();
        Map<Long, OrgUnit> units = unitIds.isEmpty() ? Map.of()
                : orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>().in(OrgUnit::getId, unitIds))
                        .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
        Map<Long, SysUser> users = userRefResolver.mapOf(
                records.stream().map(Submission::getSubmittedBy).toList());

        List<Long> submissionIds = records.stream().map(Submission::getId).toList();
        Map<Long, List<FileRef>> budgetRefs = fileRefMapper.selectList(new LambdaQueryWrapper<FileRef>()
                        .eq(FileRef::getBizType, BUDGET_FILE_BIZ_TYPE)
                        .in(FileRef::getBizId, submissionIds)
                        .orderByAsc(FileRef::getSort))
                .stream().collect(Collectors.groupingBy(FileRef::getBizId));
        Set<Long> fileIds = budgetRefs.values().stream().flatMap(List::stream)
                .map(FileRef::getFileId).collect(Collectors.toSet());
        Map<Long, FileInfo> files = fileIds.isEmpty() ? Map.of()
                : fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                                .in(FileInfo::getId, fileIds)
                                .eq(FileInfo::getDeleted, 0L))
                        .stream().collect(Collectors.toMap(FileInfo::getId, Function.identity()));

        List<IndicatorDtos.BudgetExpenseItem> items = records.stream().map(s -> {
            Task task = taskById.get(s.getTaskId());
            OrgUnit unit = task == null || task.getUnitId() == null ? null : units.get(task.getUnitId());
            List<SubmissionDtos.FileView> budgetFiles = budgetRefs.getOrDefault(s.getId(), List.of()).stream()
                    .map(ref -> files.get(ref.getFileId()))
                    .filter(Objects::nonNull)
                    .map(f -> new SubmissionDtos.FileView(f.getId(), f.getFileName(), f.getSize(),
                            f.getExt(), f.getCategory(), f.getMimeType()))
                    .toList();
            return new IndicatorDtos.BudgetExpenseItem(s.getId(),
                    task == null ? null : task.getCode(),
                    unit == null ? null : new IndicatorDtos.UnitRef(unit.getId(), unit.getCode(), unit.getName()),
                    s.getYear(), s.getExpenseAmount(), s.getApprovedExpenseAmount(), s.getStatus(),
                    s.getSubmittedAt(), userRefResolver.refOf(s.getSubmittedBy(), users),
                    s.getReviewedAt(), budgetFiles);
        }).toList();
        return new IndicatorDtos.BudgetView(totalBudget, spent,
                new PageResult<>(items, page.getTotal(), query.getPage(), query.getPageSize()));
    }

    /** ADMIN / LEADER 全校；SCHOOL_OWNER 限本人负责的 A（学院角色无预算维护权） */
    private void requireBudgetManageScope(Indicator indicator) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isAllData(user)) {
            return;
        }
        if (dataScopeService.isOwnerScope(user)
                && user.getUserId().equals(indicatorService.effectiveOwnerOf(indicator))) {
            return;
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }
}
