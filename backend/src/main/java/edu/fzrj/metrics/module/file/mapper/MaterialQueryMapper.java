package edu.fzrj.metrics.module.file.mapper;

import edu.fzrj.metrics.module.file.dto.MaterialQuery;
import edu.fzrj.metrics.module.file.dto.MaterialRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MaterialQueryMapper {

    long countMaterials(@Param("query") MaterialQuery query);

    List<MaterialRow> selectMaterials(@Param("query") MaterialQuery query);
}
