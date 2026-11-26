package edu.fzrj.metrics.module.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.training.entity.TrainingSheetStaff;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TrainingSheetStaffMapper extends BaseMapper<TrainingSheetStaff> {

    @Select("SELECT su.* FROM training_sheet_staff tss "
            + "JOIN sys_user su ON su.id = tss.staff_id AND su.deleted = 0 "
            + "WHERE tss.sheet_id = #{sheetId} ORDER BY tss.created_at, tss.id")
    List<SysUser> selectStaffBySheetId(@Param("sheetId") Long sheetId);

    @Select("SELECT tss.sheet_id FROM training_sheet_staff tss "
            + "JOIN training_sheet ts ON ts.id = tss.sheet_id AND ts.deleted = 0 "
            + "JOIN plan p ON p.id = ts.plan_id AND p.deleted = 0 AND p.plan_type = 'TRAINING' "
            + "WHERE tss.staff_id = #{staffId} ORDER BY tss.created_at, tss.id")
    List<Long> selectValidSheetIds(@Param("staffId") Long staffId);
}
