package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
import edu.fzrj.metrics.module.system.service.SettingService;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.stats.service.support.CumulativeCalculator;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.support.AllocationRules;
import edu.fzrj.metrics.module.task.service.support.TaskScopeRules;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 任务查询与确认接收（API 约定 15.4）：分页列表（数据范围并集过滤）、详情、学院确认接收。
 */
@Service
public class TaskService {

    private static final Set<String> SORT_FIELDS = Set.of("created_at", "code", "status", "distributed_at");
    private static final Set<String> TASK_STATUSES = Set.of(
            "PENDING_DISTRIBUTION", "DISTRIBUTED", "IN_PROGRESS", "TRANSFERRED_OUT", "CLOSED");
    private static final Set<String> YEAR_STATUSES = Set.of(
            "NOT_ACTIVATED", "IN_PROGRESS", "PENDING_REVIEW", "ADJUSTING",
            "ACHIEVED", "NOT_ACHIEVED", "MAKEUP_PENDING", "MADE_UP");

    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final IndicatorMapper indicatorMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final PlanMapper planMapper;
    private final PlanYearMapper planYearMapper;
    private final UserRefResolver userRefResolver;
    private final DataScopeService dataScopeService;
    private final SettingService settingService;

    public TaskService(TaskMapper taskMapper, TaskYearMapper taskYearMapper,
                       IndicatorMapper indicatorMapper, OrgUnitMapper orgUnitMapper,
                       PlanMapper planMapper, PlanYearMapper planYearMapper,
                       UserRefResolver userRefResolver, DataScopeService dataScopeService,
                       SettingService settingService) {
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.indicatorMapper = indicatorMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.planMapper = planMapper;
        this.planYearMapper = planYearMapper;
        this.userRefResolver = userRefResolver;
        this.dataScopeService = dataScopeService;
        this.settingService = settingService;
    }

    /** 填报截止日（YYYY-MM-DD）：年度 + 系统参数 plan.fillDeadline（默认 12-31，界面设计规范 A8） */
    private String fillDeadlineOfYear(Integer year) {
        if (year == null) {
            return null;
        }
        return fillDeadlineOfYear(year, settingService.getValue("plan.fillDeadline", "12-31"));
    }

    private String fillDeadlineOfYear(Integer year, String md) {
        if (year == null) {
            return null;
        }
        if (md == null || !md.matches("\\d{2}-\\d{2}")) {
            md = "12-31";
        }
        return year + "-" + md;
    }

    // ---------------- 查询 ----------------

    /** 分页列表：过滤 unitId / indicatorId / year / status / yearStatus / isHistorical；按数据范围（单元 ∪ 负责人）过滤 */
    public PageResult<TaskDtos.TaskListItem> page(TaskDtos.TaskQuery query) {
        LoginUser user = SecurityUtils.getLoginUser();
        boolean isAll = dataScopeService.isAllData(user);
        boolean isUnit = dataScopeService.isUnitScope(user);
        boolean isOwner = dataScopeService.isOwnerScope(user);
        if (TaskScopeRules.noVisibleScope(isAll, isUnit, isOwner, user.getUnitId())) {
            return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
        }
        if (StringUtils.hasText(query.getStatus()) && !TASK_STATUSES.contains(query.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务状态取值不合法：" + query.getStatus());
        }
        if (StringUtils.hasText(query.getYearStatus()) && !YEAR_STATUSES.contains(query.getYearStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "年度状态取值不合法：" + query.getYearStatus());
        }

        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>().eq(Task::getDeleted, 0L);
        if (!isAll) {
            Long unitId = isUnit ? user.getUnitId() : null;
            List<Long> ownedIndicatorIds = isOwner ? ownedIndicatorIds(user.getUserId()) : List.of();
            if (unitId == null && ownedIndicatorIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
            }
            wrapper.and(w -> {
                boolean hasCondition = false;
                if (unitId != null) {
                    w.eq(Task::getUnitId, unitId);
                    hasCondition = true;
                }
                if (!ownedIndicatorIds.isEmpty()) {
                    if (hasCondition) {
                        w.or();
                    }
                    w.in(Task::getIndicatorId, ownedIndicatorIds)
                            .or().in(Task::getTopIndicatorId, ownedIndicatorIds);
                }
            });
        }
        wrapper.eq(query.getUnitId() != null, Task::getUnitId, query.getUnitId());
        if (query.getIndicatorId() != null) {
            wrapper.and(w -> w.eq(Task::getIndicatorId, query.getIndicatorId())
                    .or().eq(Task::getTopIndicatorId, query.getIndicatorId()));
        }
        wrapper.eq(StringUtils.hasText(query.getStatus()), Task::getStatus, query.getStatus());
        wrapper.eq(query.getIsHistorical() != null, Task::getIsHistorical, query.getIsHistorical());

        if (query.getYear() != null || StringUtils.hasText(query.getYearStatus())) {
            List<Long> taskIds = taskIdsByYear(query.getYear(), query.getYearStatus());
            if (taskIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
            }
            wrapper.in(Task::getId, taskIds);
        }
        Page<Task> page = taskMapper.selectPage(query.toPage(SORT_FIELDS), wrapper);
        List<TaskDtos.TaskListItem> items = toListItems(page.getRecords(), query.getYear());
        return new PageResult<>(items, page.getTotal(), query.getPage(), query.getPageSize());
    }

    public Task requireTask(Long taskId) {
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getId, taskId)
                .eq(Task::getDeleted, 0L));
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    public Task requireCurrentTask(Long taskId) {
        return requireCurrentTask(requireTask(taskId));
    }

    public Task requireCurrentTask(Task task) {
        if (Boolean.TRUE.equals(task.getIsHistorical())) {
            throw new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY);
        }
        return task;
    }

    /** 批量按 id 查任务（供填报等模块装配引用对象） */
    public List<Task> findByIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .in(Task::getId, taskIds)
                .eq(Task::getDeleted, 0L));
    }

    /** 详情：task 字段 + 各年度目标 / 完成值 / 状态 + 引用对象；按数据范围校验（越权 20005） */
    public TaskDtos.TaskDetail detail(Long taskId) {
        Task task = requireTask(taskId);
        requireAccess(task);
        return toDetail(task);
    }

    // ---------------- 确认接收 ----------------

    /** 学院确认接收（PRD 6.2：已下发 → 执行中）；仅本单元（管理员可代为操作）；重复接收幂等 */
    @Transactional
    public TaskDtos.TaskDetail accept(Long taskId, Integer version) {
        Task task = requireTask(taskId);
        dataScopeService.canAccessUnit(task.getUnitId());
        requireCurrentTask(task);
        requireTaskVersion(task, version);
        String next = AllocationRules.statusOnAccept(task.getStatus());
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            Long userId = SecurityUtils.getUserId();
            task.setStatus(next);
            task.setAcceptedAt(LocalDateTime.now());
            task.setAcceptedBy(userId);
            task.setUpdatedBy(userId);
            if (taskMapper.updateById(task) == 0) {
                Task latest = requireTask(taskId);
                throw new BizException(ErrorCode.VERSION_CONFLICT,
                        "数据已被他人修改，当前最新版本号：" + latest.getVersion());
            }
        }
        return toDetail(requireTask(taskId));
    }

    // ---------------- 数据范围 ----------------

    /** 单条任务访问校验：全校 / 本单元 / 本人负责指标，三者任一成立；否则 20005 */
    public void requireAccess(Task task) {
        LoginUser user = SecurityUtils.getLoginUser();
        Long indicatorOwner = null;
        Long topOwner = null;
        List<Indicator> indicators = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .in(Indicator::getId, List.of(task.getIndicatorId(), task.getTopIndicatorId()))
                .eq(Indicator::getDeleted, 0L));
        for (Indicator indicator : indicators) {
            if (indicator.getId().equals(task.getIndicatorId())) {
                indicatorOwner = indicator.getOwnerId();
            }
            if (indicator.getId().equals(task.getTopIndicatorId())) {
                topOwner = indicator.getOwnerId();
            }
        }
        boolean ok = TaskScopeRules.canAccess(
                dataScopeService.isAllData(user), dataScopeService.isUnitScope(user),
                dataScopeService.isOwnerScope(user), user.getUserId(), user.getUnitId(),
                task.getUnitId(), indicatorOwner, topOwner);
        if (!ok) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
    }

    private void requireTaskVersion(Task task, Integer version) {
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 version（乐观锁版本号）");
        }
        if (!task.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + task.getVersion());
        }
    }

    /** 当前用户作为校级负责人负责的指标 id 集合（A 与 A+；A+ 未设负责人时通过 top_indicator 匹配 A） */
    private List<Long> ownedIndicatorIds(Long userId) {
        return indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getOwnerId, userId)
                        .eq(Indicator::getDeleted, 0L)
                        .select(Indicator::getId))
                .stream().map(Indicator::getId).toList();
    }

    private List<Long> taskIdsByYear(Integer year, String yearStatus) {
        LambdaQueryWrapper<TaskYear> wrapper = new LambdaQueryWrapper<TaskYear>()
                .eq(TaskYear::getDeleted, 0L)
                .eq(year != null, TaskYear::getYear, year)
                .eq(StringUtils.hasText(yearStatus), TaskYear::getYearStatus, yearStatus)
                .select(TaskYear::getTaskId);
        return taskYearMapper.selectList(wrapper).stream()
                .map(TaskYear::getTaskId).distinct().toList();
    }

    // ---------------- 视图组装 ----------------

    private List<TaskDtos.TaskListItem> toListItems(List<Task> tasks, Integer queryYear) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Refs refs = loadRefs(tasks);
        List<Long> taskIds = tasks.stream().map(Task::getId).toList();
        Map<Long, List<TaskYear>> yearsByTask = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getTaskId, taskIds)
                        .eq(TaskYear::getDeleted, 0L))
                .stream().collect(Collectors.groupingBy(TaskYear::getTaskId));
        // 各计划当前激活年度（未按 year 过滤时列表行展示该年度进度）
        List<Long> planIds = tasks.stream().map(Task::getPlanId).distinct().toList();
        Map<Long, Integer> activeYearByPlan = planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                        .in(PlanYear::getPlanId, planIds)
                        .eq(PlanYear::getStatus, "ACTIVE")
                        .eq(PlanYear::getDeleted, 0L))
                .stream().collect(Collectors.toMap(PlanYear::getPlanId, PlanYear::getYear, Math::max));
        String fillDeadline = tasks.stream().anyMatch(t -> queryYear != null || activeYearByPlan.containsKey(t.getPlanId()))
                ? settingService.getValue("plan.fillDeadline", "12-31") : null;
        return tasks.stream()
                .map(t -> {
                    Indicator indicator = refs.indicators().get(t.getIndicatorId());
                    String caliber = indicator == null ? null : indicator.getStatCaliber();
                    List<TaskYear> years = yearsByTask.getOrDefault(t.getId(), List.of());
                    Integer displayYear = queryYear != null ? queryYear : activeYearByPlan.get(t.getPlanId());
                    TaskYear current = displayYear == null ? null : years.stream()
                            .filter(y -> displayYear.equals(y.getYear())).findFirst().orElse(null);
                    CumulativeCalculator.Cumulative cumulative = CumulativeCalculator.ofTask(caliber, years);
                    return new TaskDtos.TaskListItem(t.getId(), t.getPlanId(), t.getCode(),
                            indicatorRef(indicator),
                            indicatorRef(refs.indicators().get(t.getTopIndicatorId())),
                            unitRef(refs.units().get(t.getUnitId())),
                            userRefResolver.refOf(t.getOwnerId(), refs.users()),
                            t.getStatus(), t.getIsHistorical(), totalTargetOf(caliber, years),
                            current == null ? null : AllocationService.toTargetView(caliber, current.getTarget()),
                            current == null ? null : current.getActualValue(),
                            current == null ? null : current.getCompletionRate(),
                            cumulative.actual(), cumulative.rate(),
                            current == null ? null : current.getYearStatus(),
                            t.getDistributedAt(), t.getAcceptedAt(),
                            fillDeadlineOfYear(displayYear, fillDeadline), t.getVersion());
                })
                .toList();
    }

    /** B 五年目标：INCREMENT 各年之和；BOOLEAN 规划期内有计划完成年即 true；其余口径取末年目标 */
    private Object totalTargetOf(String caliber, List<TaskYear> years) {
        if (caliber == null || years.isEmpty()) {
            return null;
        }
        if ("BOOLEAN".equals(caliber)) {
            return years.stream().anyMatch(y -> y.getTarget() != null
                    && y.getTarget().compareTo(BigDecimal.ZERO) > 0);
        }
        if ("INCREMENT".equals(caliber)) {
            return years.stream().map(TaskYear::getTarget).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return years.stream().map(TaskYear::getYear).max(Integer::compareTo)
                .map(maxYear -> years.stream().filter(y -> y.getYear().equals(maxYear)).findFirst()
                        .map(TaskYear::getTarget).orElse(null))
                .orElse(null);
    }

    private TaskDtos.TaskDetail toDetail(Task task) {
        Refs refs = loadRefs(List.of(task));
        List<TaskYear> years = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                .eq(TaskYear::getTaskId, task.getId())
                .eq(TaskYear::getDeleted, 0L)
                .orderByAsc(TaskYear::getYear));
        String caliber = refs.indicators().containsKey(task.getIndicatorId())
                ? refs.indicators().get(task.getIndicatorId()).getStatCaliber() : null;
        List<TaskDtos.TaskYearView> yearViews = years.stream()
                .map(y -> new TaskDtos.TaskYearView(y.getYear(),
                        AllocationService.toTargetView(caliber, y.getTarget()),
                        y.getOriginalTarget(), y.getActualValue(),
                        y.getActualNumerator(), y.getActualDenominator(),
                        y.getCompletionRate(), y.getIsAchieved(), y.getYearStatus()))
                .toList();
        Plan plan = planMapper.selectById(task.getPlanId());
        TaskDtos.PlanRef planRef = plan == null ? null
                : new TaskDtos.PlanRef(plan.getId(), plan.getName(), plan.getStartYear(), plan.getEndYear());
        Integer activeYear = planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                        .eq(PlanYear::getPlanId, task.getPlanId())
                        .eq(PlanYear::getStatus, "ACTIVE")
                        .eq(PlanYear::getDeleted, 0L)
                        .select(PlanYear::getYear))
                .stream().map(PlanYear::getYear).max(Integer::compareTo).orElse(null);
        CumulativeCalculator.Cumulative cumulative = CumulativeCalculator.ofTask(caliber, years);
        return new TaskDtos.TaskDetail(task.getId(), task.getPlanId(), task.getCode(),
                indicatorRef(refs.indicators().get(task.getIndicatorId())),
                indicatorRef(refs.indicators().get(task.getTopIndicatorId())),
                unitRef(refs.units().get(task.getUnitId())),
                userRefResolver.refOf(task.getOwnerId(), refs.users()),
                task.getStatus(), task.getIsHistorical(), planRef, task.getDistributedAt(),
                userRefResolver.refOf(task.getDistributedBy(), refs.users()),
                task.getAcceptedAt(), userRefResolver.refOf(task.getAcceptedBy(), refs.users()),
                yearViews, cumulative.actual(), cumulative.rate(), fillDeadlineOfYear(activeYear),
                task.getVersion(), task.getCreatedAt(), task.getUpdatedAt());
    }

    private record Refs(Map<Long, Indicator> indicators, Map<Long, OrgUnit> units, Map<Long, SysUser> users) {
    }

    private Refs loadRefs(List<Task> tasks) {
        List<Long> indicatorIds = tasks.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getIndicatorId(), t.getTopIndicatorId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Indicator> indicators = indicatorIds.isEmpty() ? Map.of()
                : indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .in(Indicator::getId, indicatorIds)
                        .eq(Indicator::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Indicator::getId, Function.identity()));
        List<Long> unitIds = tasks.stream().map(Task::getUnitId).filter(Objects::nonNull).distinct().toList();
        Map<Long, OrgUnit> units = unitIds.isEmpty() ? Map.of()
                : orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, unitIds)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
        Map<Long, SysUser> users = userRefResolver.mapOf(tasks.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getOwnerId(), t.getDistributedBy(), t.getAcceptedBy()))
                .toList());
        return new Refs(indicators, units, users);
    }

    private TaskDtos.IndicatorRef indicatorRef(Indicator indicator) {
        return indicator == null ? null
                : new TaskDtos.IndicatorRef(indicator.getId(), indicator.getCode(), indicator.getName(),
                        indicator.getStatCaliber(), indicator.getUnitLabel(), indicator.getEvidenceRequirement());
    }

    private TaskDtos.UnitRef unitRef(OrgUnit unit) {
        return unit == null ? null : new TaskDtos.UnitRef(unit.getId(), unit.getCode(), unit.getName());
    }
}
