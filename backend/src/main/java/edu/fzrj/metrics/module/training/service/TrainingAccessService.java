package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.training.entity.TrainingProject;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.entity.TrainingSheetStaff;
import edu.fzrj.metrics.module.training.mapper.TrainingProjectMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetStaffMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@Service
public class TrainingAccessService {

    private final TrainingSheetMapper sheetMapper;
    private final TrainingProjectMapper projectMapper;
    private final TrainingSheetStaffMapper staffMapper;

    public TrainingAccessService(TrainingSheetMapper sheetMapper, TrainingProjectMapper projectMapper,
                                 TrainingSheetStaffMapper staffMapper) {
        this.sheetMapper = sheetMapper;
        this.projectMapper = projectMapper;
        this.staffMapper = staffMapper;
    }

    public TrainingSheet requireSheet(Long sheetId) {
        TrainingSheet sheet = sheetMapper.selectOne(new LambdaQueryWrapper<TrainingSheet>()
                .eq(TrainingSheet::getId, sheetId)
                .eq(TrainingSheet::getDeleted, 0L));
        if (sheet == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训采集表不存在");
        }
        return sheet;
    }

    public Set<Long> validAssignedSheetIds(Long staffId) {
        return new LinkedHashSet<>(staffMapper.selectValidSheetIds(staffId));
    }

    public boolean isAssigned(Long sheetId, Long staffId) {
        return staffMapper.selectCount(new LambdaQueryWrapper<TrainingSheetStaff>()
                .eq(TrainingSheetStaff::getSheetId, sheetId)
                .eq(TrainingSheetStaff::getStaffId, staffId)) > 0;
    }

    public boolean isOwner(TrainingSheet sheet, LoginUser user) {
        return user.getRoles().contains("UNIT_OWNER")
                && Objects.equals(user.getUserId(), sheet.getOwnerId())
                && user.getUnitIds().contains(sheet.getUnitId());
    }

    public boolean canRead(TrainingSheet sheet, LoginUser user) {
        if (user.getRoles().contains("ADMIN")
                || user.getRoles().contains("LEADER")
                && user.getPermissions().contains("submission:review")) {
            return true;
        }
        return user.getRoles().contains("UNIT_OWNER") && user.getUnitIds().contains(sheet.getUnitId())
                || user.getRoles().contains("STAFF") && isAssigned(sheet.getId(), user.getUserId());
    }

    public void requireReadAccess(TrainingSheet sheet, LoginUser user) {
        if (!canRead(sheet, user)) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
    }

    public boolean canFill(TrainingSheet sheet, LoginUser user) {
        return user.getRoles().contains("ADMIN") || isOwner(sheet, user)
                || user.getRoles().contains("STAFF") && isAssigned(sheet.getId(), user.getUserId());
    }

    public boolean canManageStaff(TrainingSheet sheet, LoginUser user) {
        return user.getRoles().contains("ADMIN") || isOwner(sheet, user);
    }

    public boolean canAssignStaff(TrainingSheet sheet, Plan plan, LoginUser user) {
        return plan != null && !"ARCHIVED".equals(plan.getStatus()) && canManageStaff(sheet, user);
    }

    public void requireStaffManager(TrainingSheet sheet, LoginUser user) {
        if (!canManageStaff(sheet, user)) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "只有管理员或采集表负责人可以分配干事");
        }
    }

    public void requireProjectReadAccess(Long projectId, LoginUser user) {
        TrainingProject project = projectMapper.selectOne(new LambdaQueryWrapper<TrainingProject>()
                .eq(TrainingProject::getId, projectId)
                .eq(TrainingProject::getDeleted, 0L));
        if (project == null) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        TrainingSheet sheet = sheetMapper.selectOne(new LambdaQueryWrapper<TrainingSheet>()
                .eq(TrainingSheet::getId, project.getSheetId())
                .eq(TrainingSheet::getDeleted, 0L));
        if (sheet == null) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        requireReadAccess(sheet, user);
    }
}
