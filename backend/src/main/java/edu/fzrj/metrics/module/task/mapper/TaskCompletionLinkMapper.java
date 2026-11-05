package edu.fzrj.metrics.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.task.entity.TaskCompletionLink;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TaskCompletionLinkMapper extends BaseMapper<TaskCompletionLink> {

    @Select("SELECT * FROM task_completion_link WHERE deleted = 0 "
            + "AND (task_a_id = #{taskId} OR task_b_id = #{taskId}) ORDER BY id")
    List<TaskCompletionLink> selectActiveByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM task_completion_link WHERE deleted = 0 "
            + "AND task_a_id = #{taskAId} AND task_b_id = #{taskBId}")
    TaskCompletionLink selectActivePair(@Param("taskAId") Long taskAId, @Param("taskBId") Long taskBId);
}
