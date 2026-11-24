package edu.fzrj.metrics.module.training.service;

import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.module.training.dto.TrainingDtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class TrainingFieldSchema {

    private static final String BASIC = "BASIC";
    private static final String CONTRACT = "CONTRACT";
    private static final String IMPLEMENTATION = "IMPLEMENTATION";
    private static final String PEOPLE = "PEOPLE";
    private static final String INDUSTRY = "INDUSTRY";
    private static final String REGION = "REGION";
    private static final String TEACHER = "TEACHER";
    private static final List<String> YES_NO = List.of("是", "否");
    private static final Set<String> TOTAL_KEYS = Set.of("c32", "c44", "c54");
    private static final List<TrainingDtos.FieldDefinition> FIELDS = definitions();
    private static final Map<String, TrainingDtos.FieldDefinition> BY_KEY = FIELDS.stream()
            .collect(Collectors.toUnmodifiableMap(TrainingDtos.FieldDefinition::key, Function.identity()));

    private TrainingFieldSchema() {
    }

    public static List<TrainingDtos.FieldDefinition> fields() {
        return FIELDS;
    }

    public static ValidationResult validate(Map<String, Object> input, String reporter,
                                            boolean submitting, String path) {
        Map<String, Object> source = input == null ? Map.of() : input;
        Map<String, Object> data = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();
        for (String key : source.keySet()) {
            if (!BY_KEY.containsKey(key)) {
                errors.add(error(path, key, "不是有效的培训字段"));
            }
        }
        for (TrainingDtos.FieldDefinition field : FIELDS) {
            String key = field.key();
            Object value = "c57".equals(key) ? reporter : TOTAL_KEYS.contains(key) ? null : source.get(key);
            if (value instanceof String text) {
                value = text.trim();
            }
            boolean blank = value == null || value instanceof String text && text.isEmpty();
            if (blank) {
                data.put(key, null);
                if (submitting && field.required()) {
                    errors.add(error(path, key, field.label() + "必填"));
                }
                continue;
            }
            switch (field.type()) {
                case "TEXT" -> parseText(field, value, data, errors, path);
                case "SELECT" -> parseSelect(field, value, data, errors, path);
                case "DATE" -> parseDate(field, value, data, errors, path);
                case "DECIMAL" -> parseDecimal(field, value, data, errors, path);
                case "INTEGER" -> parseInteger(field, value, data, errors, path);
                default -> throw new IllegalStateException("未知培训字段类型：" + field.type());
            }
        }
        data.put("c32", sum(data, 18, 31));
        data.put("c44", sum(data, 33, 43));
        data.put("c54", sum(data, 45, 53));
        if (submitting && data.get("c14") instanceof Integer total
                && !total.equals(data.get("c32"))) {
            errors.add(error(path, "c14", "项目培训总人数必须等于不同人群培训人数合计"));
        }
        if (data.get("c05") instanceof String start && data.get("c06") instanceof String end
                && LocalDate.parse(end).isBefore(LocalDate.parse(start))) {
            errors.add(error(path, "c06", "项目结束日期不能早于开班日期"));
        }
        return new ValidationResult(data, List.copyOf(errors));
    }

    private static int sum(Map<String, Object> data, int start, int end) {
        BigDecimal total = BigDecimal.ZERO;
        for (int column = start; column <= end; column++) {
            Object value = data.get(key(column));
            if (value instanceof Integer number) {
                total = total.add(BigDecimal.valueOf(number));
            }
        }
        return total.intValueExact();
    }

    private static void parseText(TrainingDtos.FieldDefinition field, Object value,
                                  Map<String, Object> data, List<FieldError> errors, String path) {
        if (!(value instanceof String text)) {
            errors.add(error(path, field.key(), field.label() + "必须为文本"));
            data.put(field.key(), null);
            return;
        }
        data.put(field.key(), text);
    }

    private static void parseSelect(TrainingDtos.FieldDefinition field, Object value,
                                    Map<String, Object> data, List<FieldError> errors, String path) {
        if (!(value instanceof String text) || !field.options().contains(text)) {
            errors.add(error(path, field.key(), field.label() + "选项不合法"));
            data.put(field.key(), null);
            return;
        }
        data.put(field.key(), text);
    }

    private static void parseDate(TrainingDtos.FieldDefinition field, Object value,
                                  Map<String, Object> data, List<FieldError> errors, String path) {
        if (!(value instanceof String text)) {
            errors.add(error(path, field.key(), field.label() + "必须为 YYYY-MM-DD 日期"));
            data.put(field.key(), null);
            return;
        }
        try {
            data.put(field.key(), LocalDate.parse(text).toString());
        } catch (DateTimeParseException e) {
            errors.add(error(path, field.key(), field.label() + "必须为 YYYY-MM-DD 日期"));
            data.put(field.key(), null);
        }
    }

    private static void parseDecimal(TrainingDtos.FieldDefinition field, Object value,
                                     Map<String, Object> data, List<FieldError> errors, String path) {
        try {
            BigDecimal number = decimal(value);
            if (number.signum() < 0) {
                throw new ArithmeticException("negative");
            }
            data.put(field.key(), number);
        } catch (NumberFormatException | ArithmeticException e) {
            errors.add(error(path, field.key(), field.label() + "必须为非负数"));
            data.put(field.key(), null);
        }
    }

    private static void parseInteger(TrainingDtos.FieldDefinition field, Object value,
                                     Map<String, Object> data, List<FieldError> errors, String path) {
        try {
            int number = decimal(value).intValueExact();
            if (number < 0) {
                throw new ArithmeticException("negative");
            }
            data.put(field.key(), number);
        } catch (NumberFormatException | ArithmeticException e) {
            errors.add(error(path, field.key(), field.label() + "必须为非负整数"));
            data.put(field.key(), null);
        }
    }

    private static BigDecimal decimal(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw new NumberFormatException("not number");
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        return new BigDecimal(text);
    }

    private static FieldError error(String path, String key, String message) {
        return new FieldError(path + "." + key, message);
    }

    private static String key(int column) {
        return "c" + String.format("%02d", column);
    }

    private static List<TrainingDtos.FieldDefinition> definitions() {
        List<TrainingDtos.FieldDefinition> fields = new ArrayList<>(57);
        add(fields, "具体培训项目名称", BASIC, "项目基本", "TEXT", true);
        add(fields, "是否为免费公益项目", BASIC, "项目基本", "SELECT", true, YES_NO);
        add(fields, "是否为政府补贴性培训项目", BASIC, "项目基本", "SELECT", true, YES_NO);
        add(fields, "是否为对外培训项目", BASIC, "项目基本", "SELECT", true,
                List.of("援外项目", "对外培训", "否"));
        add(fields, "项目开班日期", BASIC, "项目基本", "DATE", true);
        add(fields, "项目结束日期", BASIC, "项目基本", "DATE", true);
        add(fields, "甲方类型", CONTRACT, "合同经费", "SELECT", true, List.of(
                "企业", "行业协会", "学校", "教育行政部门", "人力资源与社会保障部门", "农业农村部门",
                "民政部门", "工业和信息化部门", "退役军人事务部门", "工会共青团妇联残联", "社会机构",
                "学校面向社会技能培训", "其他"));
        add(fields, "甲方名称", CONTRACT, "合同经费", "TEXT", true);
        add(fields, "合同金额（万元）", CONTRACT, "合同经费", "DECIMAL", true);
        add(fields, "财政资金", CONTRACT, "合同经费", "DECIMAL", true);
        add(fields, "非财政资金", CONTRACT, "合同经费", "DECIMAL", true);
        add(fields, "培训学时", IMPLEMENTATION, "培训实施", "INTEGER", true);
        add(fields, "培训内容类型", IMPLEMENTATION, "培训实施", "SELECT", true,
                List.of("就业技能类", "农林牧渔类", "创新创业类", "新业态新技术类", "服务类", "其他"));
        add(fields, "项目培训总人数（人）", IMPLEMENTATION, "培训实施", "INTEGER", true);
        add(fields, "主要依托专业", IMPLEMENTATION, "培训实施", "TEXT", true);
        add(fields, "培训项目开展课程数（门）", IMPLEMENTATION, "培训实施", "INTEGER", true);
        add(fields, "培训方式", IMPLEMENTATION, "培训实施", "SELECT", true,
                List.of("线上", "线下", "线上线下结合"));

        List<String> people = List.of("党政领导干部", "企业职工", "学校管理人员及教师", "农村劳动者", "退役军人",
                "产业工人", "残疾人", "妇女", "高校毕业生", "农民工", "失业待业人员", "异地搬迁人员",
                "脱贫家庭（含监测帮扶对象家庭）、建档立卡脱贫家庭、困难职工家庭、社会救助对象劳动力", "其他");
        for (String label : people) {
            add(fields, label, PEOPLE, "培训对象", "INTEGER", true);
        }
        add(fields, "合计", PEOPLE, "培训对象", "INTEGER", false);

        List<String> industries = List.of("乡村振兴", "高端制造", "交通物流", "新型基建", "金融财税", "能源材料环保",
                "养老托育家政", "人工智能", "低空经济", "保密开源", "其他");
        for (String label : industries) {
            add(fields, label, INDUSTRY, "重点行业", "INTEGER", true);
        }
        add(fields, "合计", INDUSTRY, "重点行业", "INTEGER", false);

        List<String> regions = List.of("京津冀一体化", "长江经济带", "粤港澳大湾区", "长三角一体化",
                "黄河流域生态保护和高质量发展", "东北全面振兴", "革命老区", "边疆民族地区", "国家乡村振兴重点帮扶县");
        for (String label : regions) {
            add(fields, label, REGION, "区域战略", "INTEGER", true);
        }
        add(fields, "合计", REGION, "区域战略", "INTEGER", false);
        add(fields, "校内教师姓名", TEACHER, "教师填报", "TEXT", true);
        add(fields, "行业导师姓名", TEACHER, "教师填报", "TEXT", true);
        add(fields, "填报人", TEACHER, "教师填报", "TEXT", false);
        return List.copyOf(fields);
    }

    private static void add(List<TrainingDtos.FieldDefinition> fields, String label, String group,
                            String groupLabel, String type, boolean required) {
        add(fields, label, group, groupLabel, type, required, List.of());
    }

    private static void add(List<TrainingDtos.FieldDefinition> fields, String label, String group,
                            String groupLabel, String type, boolean required, List<String> options) {
        int column = fields.size() + 1;
        fields.add(new TrainingDtos.FieldDefinition(key(column), column, label, group, groupLabel,
                type, required, options));
    }

    public record ValidationResult(Map<String, Object> data, List<FieldError> errors) {
    }
}
