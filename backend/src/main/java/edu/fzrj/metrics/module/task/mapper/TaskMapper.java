package edu.fzrj.metrics.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TaskMapper extends BaseMapper<Task> {

    @Select("SELECT * FROM task WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Task selectByIdForUpdate(@Param("id") Long id);
}
