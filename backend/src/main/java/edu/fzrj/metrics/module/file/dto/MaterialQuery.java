package edu.fzrj.metrics.module.file.dto;

import java.util.List;

/** 材料库数据库分页查询条件。 */
public record MaterialQuery(
        Long planId,
        Long indicatorId,
        Long unitId,
        Long taskId,
        Integer year,
        String category,
        String keyword,
        Long ownerScope,
        Long unitScope,
        List<Long> fileIds,
        int limit,
        long offset) {
}
