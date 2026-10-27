package edu.fzrj.metrics.module.indicator.service;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析后的导入批次（import-preview 暂存，import-confirm 消费）
 */
@Data
public class ParsedImport {

    private String importId;
    private Long planId;
    private Long userId;
    private LocalDateTime createdAt;
    private List<Row> rows = new ArrayList<>();
    /** 是否存在校验失败的行（存在则 confirm 返回 40001） */
    private boolean hasInvalidRows;

    @Data
    public static class Row {
        private int rowNum;
        private String level;
        /** 可空：confirm 时系统生成 */
        private String code;
        private String parentCode;
        private String name;
        private String nature;
        private String measureType;
        private String statCaliber;
        private String unitLabel;
        private String targetDirection;
        private String rollupMode;
        private Boolean quantifiable;
        private String description;
        private BigDecimal baselineValue;
        private BigDecimal totalTarget;
        private String ownerUsername;
        /** 年 → 目标（BOOLEAN 口径完成年为 1，其余年为 null） */
        private Map<Integer, BigDecimal> yearTargets = new LinkedHashMap<>();
    }
}
