package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.submission.dto.YearUploadDtos;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 当年已上传指标清单：所有已提交审核的填报记录，按数据范围过滤。 */
@Service
public class YearUploadService {

    private final SubmissionMapper submissionMapper;
    private final IndicatorMapper indicatorMapper;
    private final TaskMapper taskMapper;
    private final SysUserMapper sysUserMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final ReviewService reviewService;

    public YearUploadService(SubmissionMapper submissionMapper, IndicatorMapper indicatorMapper,
                             TaskMapper taskMapper, SysUserMapper sysUserMapper,
                             OrgUnitMapper orgUnitMapper, ReviewService reviewService) {
        this.submissionMapper = submissionMapper;
        this.indicatorMapper = indicatorMapper;
        this.taskMapper = taskMapper;
        this.sysUserMapper = sysUserMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.reviewService = reviewService;
    }

    public List<YearUploadDtos.UploadItem> list(Long planId, Integer year) {
        LoginUser user = SecurityUtils.getLoginUser();
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<Submission>()
                .eq(Submission::getPlanId, planId)
                .eq(Submission::getYear, year)
                .in(Submission::getStatus, List.of("PENDING_REVIEW", "APPROVED", "REJECTED"))
                .eq(Submission::getDeleted, 0L)
                .orderByDesc(Submission::getSubmittedAt);
        boolean allData = user.getRoles().contains("ADMIN") || user.getRoles().contains("LEADER");
        if (!allData) {
            List<Long> visible = user.getRoles().contains("SCHOOL_OWNER")
                    ? reviewService.ownedIndicatorIdsWithChildren(user.getUserId()) : List.of();
            boolean ownerScope = !visible.isEmpty();
            boolean unitScope = user.getRoles().contains("UNIT_OWNER") && !user.getUnitIds().isEmpty();
            if (!ownerScope && !unitScope) {
                return List.of();
            }
            wrapper.and(scope -> {
                if (ownerScope) {
                    scope.in(Submission::getIndicatorId, visible);
                }
                if (unitScope) {
                    if (ownerScope) {
                        scope.or();
                    }
                    scope.in(Submission::getUnitId, user.getUnitIds());
                }
            });
        }
        List<Submission> submissions = submissionMapper.selectList(wrapper);
        if (submissions.isEmpty()) {
            return List.of();
        }
        Map<Long, Indicator> indicators = indicatorMapper.selectBatchIds(
                        submissions.stream().map(Submission::getIndicatorId).distinct().toList())
                .stream().collect(Collectors.toMap(Indicator::getId, Function.identity()));
        List<Long> parentIds = indicators.values().stream().map(Indicator::getParentId)
                .filter(Objects::nonNull).distinct().toList();
        if (!parentIds.isEmpty()) {
            indicatorMapper.selectBatchIds(parentIds)
                    .forEach(parent -> indicators.putIfAbsent(parent.getId(), parent));
        }
        Map<Long, Task> tasks = taskMapper.selectBatchIds(
                        submissions.stream().map(Submission::getTaskId).distinct().toList())
                .stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        Map<Long, SysUser> users = sysUserMapper.selectBatchIds(
                        submissions.stream().map(Submission::getSubmittedBy).filter(Objects::nonNull)
                                .distinct().toList())
                .stream().collect(Collectors.toMap(SysUser::getId, Function.identity()));
        Map<Long, OrgUnit> units = orgUnitMapper.selectBatchIds(
                        submissions.stream().map(Submission::getUnitId).filter(Objects::nonNull)
                                .distinct().toList())
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
        return submissions.stream().map(s -> {
            Indicator indicator = indicators.get(s.getIndicatorId());
            if (indicator != null && indicator.getParentId() != null) {
                indicator = indicators.getOrDefault(indicator.getParentId(), indicator);
            }
            Task task = tasks.get(s.getTaskId());
            SysUser submitter = users.get(s.getSubmittedBy());
            OrgUnit unit = units.get(s.getUnitId());
            return new YearUploadDtos.UploadItem(
                    s.getId(), indicator == null ? s.getIndicatorId() : indicator.getId(),
                    indicator == null ? null : indicator.getCode(),
                    indicator == null ? null : indicator.getName(),
                    task == null ? null : task.getCode(),
                    unit == null ? null : unit.getName(),
                    s.getSubmittedAt(),
                    submitter == null ? null : submitter.getName(),
                    s.getStatus());
        }).toList();
    }
}
