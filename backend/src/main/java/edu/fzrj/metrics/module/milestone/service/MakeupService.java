package edu.fzrj.metrics.module.milestone.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.milestone.dto.MakeupDtos;
import edu.fzrj.metrics.module.milestone.entity.Makeup;
import edu.fzrj.metrics.module.milestone.mapper.MakeupMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 待补做（API 约定 15.7、PRD 5.7 FR-M7-03）：记录由阶段 6 年度核算生成；
 * 本阶段提供按任务查询与补做完成（DONE 后把来源年度从 MAKEUP_PENDING 标记为 MADE_UP）。
 */
@Service
public class MakeupService {

    private final MakeupMapper makeupMapper;
    private final TaskYearMapper taskYearMapper;
    private final TaskService taskService;
    private final DataScopeService dataScopeService;

    public MakeupService(MakeupMapper makeupMapper, TaskYearMapper taskYearMapper,
                         TaskService taskService, DataScopeService dataScopeService) {
        this.makeupMapper = makeupMapper;
        this.taskYearMapper = taskYearMapper;
        this.taskService = taskService;
        this.dataScopeService = dataScopeService;
    }

    /** 某 B 任务的待补做记录（按数据范围） */
    public List<MakeupDtos.View> listByTask(Long taskId) {
        Task task = taskService.requireTask(taskId);
        taskService.requireAccess(task);
        return makeupMapper.selectList(new LambdaQueryWrapper<Makeup>()
                        .eq(Makeup::getTaskId, taskId)
                        .eq(Makeup::getDeleted, 0L)
                        .orderByAsc(Makeup::getFromYear).orderByAsc(Makeup::getId))
                .stream().map(m -> toView(m, task)).toList();
    }

    /** 补做完成：PENDING → DONE，来源任务年度 MAKEUP_PENDING → MADE_UP */
    @Transactional
    public MakeupDtos.View complete(Long makeupId, Integer version) {
        Makeup makeup = requireMakeup(makeupId);
        Task task = taskService.requireTask(makeup.getTaskId());
        dataScopeService.canAccessUnit(task.getUnitId());
        taskService.requireCurrentTask(task);
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 version（乐观锁版本号）");
        }
        if (!makeup.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + makeup.getVersion());
        }
        if (!"PENDING".equals(makeup.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "只有待补做记录可以标记完成");
        }
        Long userId = SecurityUtils.getUserId();
        makeup.setStatus("DONE");
        makeup.setMadeUpAmount(makeup.getGapAmount());
        makeup.setUpdatedBy(userId);
        if (makeupMapper.updateById(makeup) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + requireMakeup(makeupId).getVersion());
        }
        TaskYear fromYear = taskYearMapper.selectById(makeup.getFromTaskYearId());
        if (fromYear != null && "MAKEUP_PENDING".equals(fromYear.getYearStatus())) {
            fromYear.setYearStatus("MADE_UP");
            fromYear.setUpdatedBy(userId);
            taskYearMapper.updateById(fromYear);
        }
        return toView(requireMakeup(makeupId), task);
    }

    private Makeup requireMakeup(Long makeupId) {
        Makeup makeup = makeupMapper.selectOne(new LambdaQueryWrapper<Makeup>()
                .eq(Makeup::getId, makeupId)
                .eq(Makeup::getDeleted, 0L));
        if (makeup == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "待补做记录不存在");
        }
        return makeup;
    }

    private MakeupDtos.View toView(Makeup m, Task task) {
        return new MakeupDtos.View(m.getId(), new MakeupDtos.TaskRef(task.getId(), task.getCode()),
                m.getFromTaskYearId(), m.getFromYear(), m.getToYear(), m.getGapAmount(),
                m.getMadeUpAmount(), m.getStatus(), m.getAdjustmentId(), m.getVersion(),
                m.getCreatedAt(), m.getUpdatedAt());
    }
}
