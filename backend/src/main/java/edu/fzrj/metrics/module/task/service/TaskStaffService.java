package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskStaff;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TaskStaffService {

    private final TaskStaffMapper taskStaffMapper;

    public TaskStaffService(TaskStaffMapper taskStaffMapper) {
        this.taskStaffMapper = taskStaffMapper;
    }

    public List<UserRef> staffOf(Long taskId) {
        return taskStaffMapper.selectStaffByTaskId(taskId).stream()
                .map(user -> new UserRef(user.getId(), user.getName())).toList();
    }

    public List<UserRef> assignableStaffOf(Task task) {
        if (task.getOwnerId() == null || task.getUnitId() == null) {
            return List.of();
        }
        return taskStaffMapper.selectAssignableStaff(task.getOwnerId(), task.getUnitId()).stream()
                .map(user -> new UserRef(user.getId(), user.getName())).toList();
    }

    public List<Long> staffIds(Long taskId) {
        return taskStaffMapper.selectList(new LambdaQueryWrapper<TaskStaff>()
                        .eq(TaskStaff::getTaskId, taskId)
                        .orderByAsc(TaskStaff::getCreatedAt).orderByAsc(TaskStaff::getId)
                        .select(TaskStaff::getStaffId))
                .stream().map(TaskStaff::getStaffId).toList();
    }

    public MergeResult merge(Task task, List<Long> requestedStaffIds, Long operatorId) {
        if (requestedStaffIds == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "staffIds 不能为空");
        }
        Set<Long> requested = new LinkedHashSet<>();
        for (Long staffId : requestedStaffIds) {
            if (staffId == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "staffIds 不能包含空值");
            }
            requested.add(staffId);
        }
        List<TaskStaff> existing = taskStaffMapper.selectList(new LambdaQueryWrapper<TaskStaff>()
                .eq(TaskStaff::getTaskId, task.getId())
                .orderByAsc(TaskStaff::getCreatedAt).orderByAsc(TaskStaff::getId));
        Set<Long> before = existing.stream().map(TaskStaff::getStaffId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!requested.isEmpty()) {
            Map<Long, SysUser> eligible = taskStaffMapper.selectAssignableStaff(operatorId, task.getUnitId()).stream()
                    .collect(Collectors.toMap(SysUser::getId, Function.identity()));
            for (Long staffId : requested) {
                if (!eligible.containsKey(staffId)) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "干事不存在、未启用，或不属于当前负责人和任务单元：" + staffId);
                }
            }
        }

        Set<Long> removed = before.stream().filter(id -> !requested.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> added = requested.stream().filter(id -> !before.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!removed.isEmpty()) {
            taskStaffMapper.delete(new LambdaQueryWrapper<TaskStaff>()
                    .eq(TaskStaff::getTaskId, task.getId())
                    .in(TaskStaff::getStaffId, removed));
        }
        for (Long staffId : added) {
            TaskStaff row = new TaskStaff();
            row.setTaskId(task.getId());
            row.setStaffId(staffId);
            row.setAssignedBy(operatorId);
            taskStaffMapper.insert(row);
        }
        List<Long> after = new ArrayList<>(requested);
        return new MergeResult(new ArrayList<>(before), after, !removed.isEmpty() || !added.isEmpty());
    }

    public record MergeResult(List<Long> beforeStaffIds, List<Long> afterStaffIds, boolean changed) {
    }
}
