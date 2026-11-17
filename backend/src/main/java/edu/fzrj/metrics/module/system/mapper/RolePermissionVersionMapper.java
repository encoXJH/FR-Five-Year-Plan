package edu.fzrj.metrics.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.system.entity.RolePermissionVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RolePermissionVersionMapper extends BaseMapper<RolePermissionVersion> {

    @Select("SELECT role, version, updated_at, updated_by FROM role_permission_version WHERE role = #{role} FOR UPDATE")
    RolePermissionVersion lockByRole(@Param("role") String role);

    @Update("UPDATE role_permission_version SET version = version + 1, updated_at = CURRENT_TIMESTAMP(3), "
            + "updated_by = #{operator} WHERE role = #{role} AND version = #{expectedVersion}")
    int incrementVersion(@Param("role") String role, @Param("expectedVersion") Integer expectedVersion,
                         @Param("operator") Long operator);
}
