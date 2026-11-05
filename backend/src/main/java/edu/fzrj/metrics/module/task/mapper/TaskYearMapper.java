package edu.fzrj.metrics.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TaskYearMapper extends BaseMapper<TaskYear> {

    @Select("SELECT * FROM task_year WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    TaskYear selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM task_year WHERE task_id = #{taskId} AND year = #{year} AND deleted = 0 FOR UPDATE")
    TaskYear selectByTaskIdAndYearForUpdate(@Param("taskId") Long taskId, @Param("year") Integer year);

    @Select("<script>"
            + "SELECT * FROM task_year WHERE deleted = 0 AND task_id IN "
            + "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>#{taskId}</foreach>"
            + "<if test='year != null'> AND year = #{year}</if>"
            + " ORDER BY id FOR UPDATE</script>")
    List<TaskYear> selectByTaskIdsForUpdate(@Param("taskIds") List<Long> taskIds, @Param("year") Integer year);
}
