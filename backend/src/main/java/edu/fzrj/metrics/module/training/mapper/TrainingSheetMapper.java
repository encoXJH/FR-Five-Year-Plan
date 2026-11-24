package edu.fzrj.metrics.module.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TrainingSheetMapper extends BaseMapper<TrainingSheet> {

    @Select("SELECT * FROM training_sheet WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    TrainingSheet selectByIdForUpdate(@Param("id") Long id);
}
