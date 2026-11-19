package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskStaff;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TaskReadScopeService {

    private final TaskStaffMapper taskStaffMapper;
    private final TaskMapper taskMapper;
    private final IndicatorMapper indicatorMapper;
    private final DataScopeService dataScopeService;

    public TaskReadScopeService(TaskStaffMapper taskStaffMapper, TaskMapper taskMapper,
                                IndicatorMapper indicatorMapper, DataScopeService dataScopeService) {
        this.taskStaffMapper = taskStaffMapper;
        this.taskMapper = taskMapper;
        this.indicatorMapper = indicatorMapper;
        this.dataScopeService = dataScopeService;
    }

    public Set<Long> staffTaskIds() {
        return staffTaskIds(SecurityUtils.getLoginUser());
    }

    public Set<Long> staffTaskIds(LoginUser user) {
        if (!dataScopeService.isAssignedScope(user)) {
            return Set.of();
        }
        return taskStaffMapper.selectList(new LambdaQueryWrapper<TaskStaff>()
                        .eq(TaskStaff::getStaffId, user.getUserId())
                        .select(TaskStaff::getTaskId))
                .stream().map(TaskStaff::getTaskId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isAssigned(Long taskId, LoginUser user) {
        if (!dataScopeService.isAssignedScope(user) || taskId == null) {
            return false;
        }
        Long count = taskStaffMapper.selectCount(new LambdaQueryWrapper<TaskStaff>()
                .eq(TaskStaff::getTaskId, taskId)
                .eq(TaskStaff::getStaffId, user.getUserId()));
        return count != null && count > 0;
    }

    /** 当前用户可见任务 ID：ALL / OWNER / UNIT / ASSIGNED 的实时并集；无范围时返回空集合。 */
    public Set<Long> visibleTaskIds(LoginUser user) {
        LambdaQueryWrapper<Task> query = new LambdaQueryWrapper<Task>()
                .eq(Task::getDeleted, 0L).select(Task::getId);
        if (!dataScopeService.isAllData(user)) {
            List<Long> unitIds = dataScopeService.isUnitScope(user) ? user.getUnitIds() : List.of();
            List<Long> ownedIndicatorIds = dataScopeService.isOwnerScope(user)
                    ? ownedIndicatorIds(user.getUserId()) : List.of();
            Set<Long> assignedTaskIds = staffTaskIds(user);
            if (unitIds.isEmpty() && ownedIndicatorIds.isEmpty() && assignedTaskIds.isEmpty()) {
                return Set.of();
            }
            query.and(scope -> {
                boolean hasCondition = false;
                if (!unitIds.isEmpty()) {
                    scope.in(Task::getUnitId, unitIds);
                    hasCondition = true;
                }
                if (!ownedIndicatorIds.isEmpty()) {
                    if (hasCondition) {
                        scope.or();
                    }
                    scope.in(Task::getIndicatorId, ownedIndicatorIds)
                            .or().in(Task::getTopIndicatorId, ownedIndicatorIds);
                    hasCondition = true;
                }
                if (!assignedTaskIds.isEmpty()) {
                    if (hasCondition) {
                        scope.or();
                    }
                    scope.in(Task::getId, assignedTaskIds);
                }
            });
        }
        return taskMapper.selectList(query).stream().map(Task::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** 填报、补做等执行写操作只允许本单元负责人、已分配干事或管理员，不开放 OWNER 只读范围。 */
    public void requireSubmitter(Task task) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (user.getRoles().contains("ADMIN")) {
            return;
        }
        if (dataScopeService.isUnitScope(user) && user.getUnitIds().contains(task.getUnitId())) {
            return;
        }
        if (isAssigned(task.getId(), user)) {
            return;
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    private List<Long> ownedIndicatorIds(Long userId) {
        return indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getOwnerId, userId)
                        .eq(Indicator::getDeleted, 0L)
                        .select(Indicator::getId))
                .stream().map(Indicator::getId).toList();
    }
}
