package edu.fzrj.metrics.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TaskYearMapper extends BaseMapper<TaskYear> {

    @Select("SELECT * FROM task_year WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    TaskYear selectByIdForUpdate(@Param("id") Long id);
}
