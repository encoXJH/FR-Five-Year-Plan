package edu.fzrj.metrics.module.plan.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板结构快照（template.content_json）：
 * S 级归属 + 指标定义 + 年度目标，不含任何执行数据。
 * S 仅用于后台持久化和实例化，模板详情响应通过 PlanService.templateView 隐藏。
 * 引用外部数据一律用业务编码（用户名 / 专业群名称），保证跨环境可移植。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TemplateContent {

    /** 来源计划的起止年份（快照时写入，用于模板「周期」展示） */
    private Integer startYear;
    private Integer endYear;
    private List<DirectionNode> directions = new ArrayList<>();
    /** 草稿阶段尚未归属 S 的 A，实例化后仍保持未归属 */
    private List<IndicatorNode> unassignedIndicators = new ArrayList<>();

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DirectionNode {
        private String subjectType;
        /** 主体为专业群时的专业群名称（实例化时按名称解析） */
        private String majorGroupName;
        private String name;
        /** S 总负责人登录名，仅用于后台实例化 */
        private String leaderUsername;
        private Integer sort;
        private List<IndicatorNode> indicators = new ArrayList<>();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IndicatorNode {
        private String level;
        private String code;
        private String name;
        private String nature;
        private String measureType;
        private String statCaliber;
        private String unitLabel;
        private String targetDirection;
        private String rollupMode;
        private Boolean isQuantifiable;
        private String description;
        private String evidenceRequirement;
        private Integer minFileCount;
        private BigDecimal baselineValue;
        private BigDecimal totalTarget;
        /** 校级项目负责人登录名（实例化时按登录名解析，找不到则置空由后续补录） */
        private String ownerUsername;
        private Integer sort;
        /** 年度目标：年 → 目标值（BOOLEAN 口径完成年为 1，其余年不出现） */
        private Map<Integer, BigDecimal> yearTargets = new LinkedHashMap<>();
        private List<IndicatorNode> children = new ArrayList<>();
    }
}
