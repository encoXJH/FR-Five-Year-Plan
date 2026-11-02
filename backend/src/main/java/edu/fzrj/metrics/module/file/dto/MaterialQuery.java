package edu.fzrj.metrics.module.file.dto;

/** 材料库数据库分页查询条件。 */
public record MaterialQuery(
        Long planId,
        Long indicatorId,
        Long unitId,
        String category,
        String keyword,
        Long ownerScope,
        Long unitScope,
        int limit,
        long offset) {
}
