package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.submission.service.ActualRecalcService;
import edu.fzrj.metrics.module.task.dto.TaskCompletionLinkDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskCompletionLink;
import edu.fzrj.metrics.module.task.mapper.TaskCompletionLinkMapper;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class TaskCompletionLinkService {

    private final TaskCompletionLinkMapper completionLinkMapper;
    private final TaskMapper taskMapper;
    private final IndicatorMapper indicatorMapper;
    private final ActualRecalcService actualRecalcService;

    public TaskCompletionLinkService(TaskCompletionLinkMapper completionLinkMapper, TaskMapper taskMapper,
                                     IndicatorMapper indicatorMapper, ActualRecalcService actualRecalcService) {
        this.completionLinkMapper = completionLinkMapper;
        this.taskMapper = taskMapper;
        this.indicatorMapper = indicatorMapper;
        this.actualRecalcService = actualRecalcService;
    }

    @Transactional
    public TaskCompletionLinkDtos.View create(TaskCompletionLinkDtos.CreateRequest request) {
        if (request.getTaskAId().equals(request.getTaskBId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "关联任务不能相同");
        }
        Long taskAId = Math.min(request.getTaskAId(), request.getTaskBId());
        Long taskBId = Math.max(request.getTaskAId(), request.getTaskBId());
        Task taskA = requireCurrentTask(taskAId);
        Task taskB = requireCurrentTask(taskBId);
        Indicator indicatorA = requireIndicator(taskA.getIndicatorId());
        Indicator indicatorB = requireIndicator(taskB.getIndicatorId());
        if (!Objects.equals(indicatorA.getStatCaliber(), indicatorB.getStatCaliber())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "关联任务的统计口径必须一致");
        }
        if (completionLinkMapper.selectActivePair(taskAId, taskBId) != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务完成进度关联已存在");
        }

        Long userId = SecurityUtils.getUserId();
        TaskCompletionLink link = new TaskCompletionLink();
        link.setTaskAId(taskAId);
        link.setTaskBId(taskBId);
        link.setSourceNote(request.getSourceNote());
        link.setCreatedBy(userId);
        link.setUpdatedBy(userId);
        completionLinkMapper.insert(link);
        actualRecalcService.syncExistingProgress(taskAId, taskBId);
        return toView(link);
    }

    public List<TaskCompletionLinkDtos.View> list(Long taskId) {
        List<TaskCompletionLink> links = taskId == null
                ? completionLinkMapper.selectList(new LambdaQueryWrapper<TaskCompletionLink>()
                        .eq(TaskCompletionLink::getDeleted, 0L)
                        .orderByAsc(TaskCompletionLink::getId))
                : completionLinkMapper.selectActiveByTaskId(taskId);
        return links.stream().map(TaskCompletionLinkService::toView).toList();
    }

    private Task requireCurrentTask(Long taskId) {
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getId, taskId)
                .eq(Task::getDeleted, 0L));
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        if (Boolean.TRUE.equals(task.getIsHistorical())) {
            throw new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY);
        }
        return task;
    }

    private Indicator requireIndicator(Long indicatorId) {
        Indicator indicator = indicatorMapper.selectOne(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getId, indicatorId)
                .eq(Indicator::getDeleted, 0L));
        if (indicator == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "指标不存在");
        }
        return indicator;
    }

    private static TaskCompletionLinkDtos.View toView(TaskCompletionLink link) {
        return new TaskCompletionLinkDtos.View(link.getId(), link.getTaskAId(), link.getTaskBId(),
                link.getSourceNote(), link.getVersion());
    }
}
