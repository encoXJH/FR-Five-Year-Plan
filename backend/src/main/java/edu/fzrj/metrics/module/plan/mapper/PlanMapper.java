package edu.fzrj.metrics.module.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PlanMapper extends BaseMapper<Plan> {

    @Select("SELECT * FROM plan WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Plan selectByIdForUpdate(@Param("id") Long id);
}
