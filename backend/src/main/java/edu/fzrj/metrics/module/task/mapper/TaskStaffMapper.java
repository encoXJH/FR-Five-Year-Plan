package edu.fzrj.metrics.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.task.entity.TaskStaff;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TaskStaffMapper extends BaseMapper<TaskStaff> {

    @Select("SELECT su.* FROM task_staff ts JOIN sys_user su ON su.id = ts.staff_id AND su.deleted = 0 "
            + "WHERE ts.task_id = #{taskId} ORDER BY ts.created_at, ts.id")
    List<SysUser> selectStaffByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT DISTINCT su.* FROM sys_user su "
            + "JOIN user_role ur ON ur.user_id = su.id AND ur.role = 'STAFF' "
            + "JOIN user_unit uu ON uu.user_id = su.id AND uu.unit_id = #{unitId} "
            + "WHERE su.deleted = 0 AND su.is_enabled = 1 "
            + "AND NOT EXISTS (SELECT 1 FROM user_role other "
            + "WHERE other.user_id = su.id AND other.role <> 'STAFF') "
            + "ORDER BY su.name, su.id")
    List<SysUser> selectAssignableStaff(@Param("unitId") Long unitId);
}
