package edu.fzrj.metrics.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentMapper;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.stats.dto.ScreenDtos;
import edu.fzrj.metrics.module.stats.dto.StatsDtos;
import edu.fzrj.metrics.module.stats.service.support.StatsAggregator;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 可视化大屏聚合（API 约定 15.8，PRD 5.9）：一次返回大屏全部数据。
 * 本地内存缓存（ConcurrentHashMap + TTL，默认 120 秒，app.screen.cache-seconds 可配），
 * key = planId:year；大屏权限 screen:view 仅管理员 / 校领导（全校范围），缓存不含用户维度。
 * 年度核算 / 解锁 / 五年核算写操作后由 SettlementService 调用 evict 失效。
 */
@Service
public class ScreenService {

    private static final int MAX_CACHE_ENTRIES = 200;
    private static final int RECENT_ACTIVITY_LIMIT = 10;

    private record Entry(ScreenDtos.ScreenView view, long expireAtMillis) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private final PlanService planService;
    private final StatsService statsService;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final AdjustmentMapper adjustmentMapper;
    private final SubmissionMapper submissionMapper;
    private final UserRefResolver userRefResolver;
    private final IndicatorMapper indicatorMapper;
    private final FileRefMapper fileRefMapper;

    @Value("${app.screen.cache-seconds:120}")
    private long cacheSeconds;

    public ScreenService(PlanService planService, StatsService statsService,
                         TaskMapper taskMapper, TaskYearMapper taskYearMapper,
                         AdjustmentMapper adjustmentMapper, SubmissionMapper submissionMapper,
                         UserRefResolver userRefResolver,
                         IndicatorMapper indicatorMapper, FileRefMapper fileRefMapper) {
        this.planService = planService;
        this.statsService = statsService;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.submissionMapper = submissionMapper;
        this.userRefResolver = userRefResolver;
        this.indicatorMapper = indicatorMapper;
        this.fileRefMapper = fileRefMapper;
    }

    /** 大屏聚合（命中缓存直接返回；缓存到期后重算） */
    public ScreenDtos.ScreenView screen(Long planId, Integer year) {
        Plan plan = planService.requirePlan(planId);
        int resolvedYear = statsService.resolveYear(plan, year);
        String key = planId + ":" + resolvedYear;
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expireAtMillis() > now) {
            return cached.view();
        }
        ScreenDtos.ScreenView view = compute(plan, resolvedYear);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().expireAtMillis() <= now);
        }
        cache.put(key, new Entry(view, now + cacheSeconds * 1000));
        return view;
    }

    /** 使某计划的全部大屏缓存失效（核算 / 解锁 / 调整生效等写操作后调用） */
    public void evict(Long planId) {
        cache.keySet().removeIf(k -> k.startsWith(planId + ":"));
    }

    private ScreenDtos.ScreenView compute(Plan plan, int year) {
        StatsDtos.Overview overview = statsService.overview(plan.getId(), year, StatsService.ScopeFilter.allData());
        List<StatsDtos.UnitStat> byUnit = statsService.byUnit(plan.getId(), year);
        List<StatsDtos.IndicatorStat> byIndicator = statsService.byIndicator(plan.getId(), year);
        List<ScreenDtos.YearTrend> trend = yearTrend(plan);
        ScreenDtos.AdjustmentStats adjustmentStats = adjustmentStats(plan.getId());
        List<ScreenDtos.ActivityItem> activities = recentActivities(plan.getId());
        ScreenDtos.CoreNumbers coreNumbers = coreNumbers(plan, year, overview.taskCount());
        return new ScreenDtos.ScreenView(
                new ScreenDtos.PlanRef(plan.getId(), plan.getName(), plan.getStartYear(), plan.getEndYear()),
                year, LocalDateTime.now(), overview, coreNumbers, byUnit, byIndicator, trend, adjustmentStats,
                activities);
    }

    /** 核心数字条（界面设计规范 9.1④）：A / A+ 指标数、B 任务数、佐证材料数、本年审核通过数 */
    private ScreenDtos.CoreNumbers coreNumbers(Plan plan, int year, long taskCount) {
        long aCount = indicatorMapper.selectCount(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, plan.getId())
                .eq(Indicator::getLevel, "A")
                .eq(Indicator::getIsQuantifiable, true)
                .eq(Indicator::getDeleted, 0L));
        long aPlusCount = indicatorMapper.selectCount(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, plan.getId())
                .eq(Indicator::getLevel, "A_PLUS")
                .eq(Indicator::getDeleted, 0L));
        long approvedThisYear = submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getPlanId, plan.getId())
                .eq(Submission::getYear, year)
                .eq(Submission::getStatus, "APPROVED")
                .eq(Submission::getDeleted, 0L));
        // 佐证材料：挂在该计划填报记录（SUBMISSION 业务）下的文件数
        List<Long> submissionIds = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                        .eq(Submission::getPlanId, plan.getId())
                        .eq(Submission::getDeleted, 0L)
                        .select(Submission::getId))
                .stream().map(Submission::getId).toList();
        long materialCount = submissionIds.isEmpty() ? 0L
                : fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                        .eq(FileRef::getBizType, "SUBMISSION")
                        .in(FileRef::getBizId, submissionIds));
        return new ScreenDtos.CoreNumbers(aCount, aPlusCount, taskCount, materialCount, approvedThisYear);
    }

    /** 2026–2030 年度趋势（目标与实际对比，按任务计数口径与总览一致） */
    private List<ScreenDtos.YearTrend> yearTrend(Plan plan) {
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getPlanId, plan.getId())
                .ne(Task::getStatus, "PENDING_DISTRIBUTION")
                .eq(Task::getIsHistorical, false)
                .eq(Task::getDeleted, 0L));
        List<TaskYear> allYears = tasks.isEmpty() ? List.of()
                : taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getTaskId, tasks.stream().map(Task::getId).toList())
                        .eq(TaskYear::getDeleted, 0L));
        Map<Integer, List<TaskYear>> byYear = allYears.stream()
                .collect(Collectors.groupingBy(TaskYear::getYear));
        List<ScreenDtos.YearTrend> trend = new ArrayList<>();
        for (int y = plan.getStartYear(); y <= plan.getEndYear(); y++) {
            List<TaskYear> countable = byYear.getOrDefault(y, List.of()).stream()
                    .filter(ty -> StatsAggregator.countable(ty.getTarget())).toList();
            long achieved = countable.stream()
                    .filter(ty -> StatsAggregator.isAchievedStatus(ty.getYearStatus())).count();
            trend.add(new ScreenDtos.YearTrend(y, countable.size(), achieved,
                    StatsAggregator.rate(achieved, countable.size())));
        }
        return trend;
    }

    private ScreenDtos.AdjustmentStats adjustmentStats(Long planId) {
        List<Adjustment> adjustments = adjustmentMapper.selectList(new LambdaQueryWrapper<Adjustment>()
                .eq(Adjustment::getPlanId, planId)
                .eq(Adjustment::getDeleted, 0L)
                .select(Adjustment::getId, Adjustment::getStatus, Adjustment::getType));
        long pending = adjustments.stream().filter(a -> a.getStatus() != null
                && a.getStatus().startsWith("PENDING_")).count();
        long approved = adjustments.stream().filter(a -> "APPROVED".equals(a.getStatus())).count();
        // 「延期+转移」双重计入延期与转移两个分项（界面设计规范 9.1③）
        long postpone = adjustments.stream().filter(a -> "POSTPONE".equals(a.getType())
                || "POSTPONE_TRANSFER".equals(a.getType())).count();
        long transfer = adjustments.stream().filter(a -> "TRANSFER".equals(a.getType())
                || "POSTPONE_TRANSFER".equals(a.getType())).count();
        return new ScreenDtos.AdjustmentStats(adjustments.size(), pending, approved, postpone, transfer);
    }

    /** 最近审核动态（FR-M9-05）：最近审核通过 / 驳回的填报 */
    private List<ScreenDtos.ActivityItem> recentActivities(Long planId) {
        List<Submission> reviewed = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getPlanId, planId)
                .in(Submission::getStatus, List.of("APPROVED", "REJECTED"))
                .isNotNull(Submission::getReviewedAt)
                .eq(Submission::getDeleted, 0L)
                .orderByDesc(Submission::getReviewedAt)
                .orderByDesc(Submission::getId)
                .last("LIMIT " + RECENT_ACTIVITY_LIMIT));
        if (reviewed.isEmpty()) {
            return List.of();
        }
        Map<Long, Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .in(Task::getId, reviewed.stream().map(Submission::getTaskId).distinct().toList())
                        .eq(Task::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        Map<Long, SysUser> users = userRefResolver.mapOf(
                reviewed.stream().map(Submission::getReviewerId).filter(Objects::nonNull).toList());
        return reviewed.stream()
                .map(s -> {
                    var reviewer = userRefResolver.refOf(s.getReviewerId(), users);
                    return new ScreenDtos.ActivityItem(s.getReviewedAt(),
                            reviewer == null ? null : reviewer.name(),
                            tasks.containsKey(s.getTaskId()) ? tasks.get(s.getTaskId()).getCode() : null,
                            s.getYear(), s.getStatus());
                })
                .toList();
    }
}
