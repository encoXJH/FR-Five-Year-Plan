package edu.fzrj.metrics.module.indicator.service.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Excel 导入的一行原始数据（均为字符串，校验通过后再类型化）。
 */
public class ImportRowData {

    /** Excel 行号（表头为第 1 行，数据从第 2 行起） */
    public int rowNum;
    public String level;
    public String code;
    public String parentCode;
    public String name;
    public String nature;
    public String measureType;
    public String statCaliber;
    public String unitLabel;
    public String targetDirection;
    public String rollupMode;
    public String quantifiable;
    public String description;
    public String baseline;
    public String totalTarget;
    public String ownerUsername;
    /** 年 → 原始值（BOOLEAN 口径完成年填「是」） */
    public Map<Integer, String> yearTargets = new LinkedHashMap<>();

    /** 校验上下文（由调用方按数据库与文件内容组装，纯数据） */
    public static class Context {
        public int startYear;
        public int endYear;
        /** 计划内已存在的指标编号 */
        public Set<String> existingCodes = Set.of();
        /** 文件内重复出现的编号 */
        public Set<String> duplicatedCodesInFile = Set.of();
        /** 可挂载的 A 编号 → 是否可量化（含文件内 A 行与库内已有 A） */
        public Map<String, Boolean> aQuantifiableByCode = Map.of();
        /** 存在的用户登录名 */
        public Set<String> validUsernames = Set.of();

        public Context(int startYear, int endYear) {
            this.startYear = startYear;
            this.endYear = endYear;
        }
    }
}
