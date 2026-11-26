package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.entity.TrainingSheetStaff;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetStaffMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TrainingStaffService {

    private final TrainingSheetMapper sheetMapper;
    private final TrainingSheetStaffMapper staffMapper;
    private final TaskStaffMapper taskStaffMapper;
    private final PlanMapper planMapper;
    private final TrainingService trainingService;
    private final TrainingAccessService accessService;

    public TrainingStaffService(TrainingSheetMapper sheetMapper, TrainingSheetStaffMapper staffMapper,
                                TaskStaffMapper taskStaffMapper, PlanMapper planMapper,
                                TrainingService trainingService, TrainingAccessService accessService) {
        this.sheetMapper = sheetMapper;
        this.staffMapper = staffMapper;
        this.taskStaffMapper = taskStaffMapper;
        this.planMapper = planMapper;
        this.trainingService = trainingService;
        this.accessService = accessService;
    }

    public TrainingDtos.StaffView staff(Long sheetId) {
        TrainingSheet sheet = accessService.requireSheet(sheetId);
        LoginUser user = SecurityUtils.getLoginUser();
        accessService.requireStaffManager(sheet, user);
        Plan plan = planMapper.selectOne(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getId, sheet.getPlanId())
                .eq(Plan::getPlanType, "TRAINING")
                .eq(Plan::getDeleted, 0L));
        if (plan == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训计划不存在");
        }
        return view(sheet, accessService.canAssignStaff(sheet, plan, user));
    }

    @Transactional
    public TrainingDtos.StaffView assign(Long sheetId, TrainingDtos.StaffPutRequest request) {
        TrainingSheet sheet = trainingService.requireSheetForUpdate(sheetId);
        LoginUser user = SecurityUtils.getLoginUser();
        accessService.requireStaffManager(sheet, user);
        trainingService.requireVersion(sheet, request.getVersion());
        if (merge(sheet, request.getStaffIds(), user.getUserId())) {
            sheet.setUpdatedBy(user.getUserId());
            if (sheetMapper.updateById(sheet) == 0) {
                throw new BizException(ErrorCode.VERSION_CONFLICT);
            }
        }
        return staff(sheetId);
    }

    public Map<String, Object> auditSnapshot(Long sheetId) {
        TrainingSheet sheet = accessService.requireSheet(sheetId);
        accessService.requireStaffManager(sheet, SecurityUtils.getLoginUser());
        return Map.of("version", sheet.getVersion(), "staffIds", staffIds(sheetId));
    }

    private TrainingDtos.StaffView view(TrainingSheet sheet, boolean canAssign) {
        List<UserRef> staff = staffMapper.selectStaffBySheetId(sheet.getId()).stream()
                .map(user -> new UserRef(user.getId(), user.getName())).toList();
        List<UserRef> assignable = canAssign
                ? taskStaffMapper.selectAssignableStaff(sheet.getUnitId()).stream()
                .map(user -> new UserRef(user.getId(), user.getName())).toList()
                : List.of();
        return new TrainingDtos.StaffView(sheet.getVersion(), staff, assignable, canAssign);
    }

    private List<Long> staffIds(Long sheetId) {
        return staffMapper.selectList(new LambdaQueryWrapper<TrainingSheetStaff>()
                        .eq(TrainingSheetStaff::getSheetId, sheetId)
                        .orderByAsc(TrainingSheetStaff::getCreatedAt)
                        .orderByAsc(TrainingSheetStaff::getId)
                        .select(TrainingSheetStaff::getStaffId))
                .stream().map(TrainingSheetStaff::getStaffId).toList();
    }

    private boolean merge(TrainingSheet sheet, List<Long> requestedStaffIds, Long operatorId) {
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
        Set<Long> before = new LinkedHashSet<>(staffIds(sheet.getId()));
        if (!requested.isEmpty()) {
            Map<Long, SysUser> eligible = taskStaffMapper.selectAssignableStaff(sheet.getUnitId()).stream()
                    .collect(Collectors.toMap(SysUser::getId, Function.identity()));
            for (Long staffId : requested) {
                if (!eligible.containsKey(staffId)) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "干事不存在、未启用、不是纯干事，或不属于采集表学院：" + staffId);
                }
            }
        }
        Set<Long> removed = before.stream().filter(id -> !requested.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> added = requested.stream().filter(id -> !before.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!removed.isEmpty()) {
            staffMapper.delete(new LambdaQueryWrapper<TrainingSheetStaff>()
                    .eq(TrainingSheetStaff::getSheetId, sheet.getId())
                    .in(TrainingSheetStaff::getStaffId, removed));
        }
        for (Long staffId : added) {
            TrainingSheetStaff row = new TrainingSheetStaff();
            row.setSheetId(sheet.getId());
            row.setStaffId(staffId);
            row.setAssignedBy(operatorId);
            staffMapper.insert(row);
        }
        return !removed.isEmpty() || !added.isEmpty();
    }
}
