package edu.fzrj.metrics.module.indicator.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.service.support.EnumAliases;
import edu.fzrj.metrics.module.indicator.service.support.ImportRowData;
import edu.fzrj.metrics.module.indicator.service.support.ImportValidator;
import edu.fzrj.metrics.module.indicator.service.support.IndicatorRules;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 指标 Excel：导入模板下载、导入预览 / 确认、指标体系导出（API 约定 15.3 / FR-M2-02、FR-M2-06）
 */
@Slf4j
@Service
public class IndicatorExcelService {

    static final List<String> FIXED_COLUMNS = List.of(
            "层级", "编号", "上级编号", "关键产出指标", "指标性质", "计量类型", "统计口径",
            "单位", "达成方向", "汇总方式", "是否可量化", "填写说明", "基线值", "五年目标");
    private static final Set<String> REMOVED_COLUMNS = Set.of(
            "主体", "专业群", "主要建设方向", "是否可下分", "承接单位编码");
    static final String OWNER_COLUMN = "负责人账号";
    private static final Pattern YEAR_COLUMN = Pattern.compile("^(\\d{4})年目标$");
    private static final int MAX_IMPORT_ROWS = 1000;

    private final PlanService planService;
    private final IndicatorService indicatorService;
    private final ImportPreviewStore importPreviewStore;
    private final IndicatorMapper indicatorMapper;
    private final SysUserMapper sysUserMapper;
    private final DataScopeService dataScopeService;

    public IndicatorExcelService(PlanService planService, IndicatorService indicatorService,
                                 ImportPreviewStore importPreviewStore, IndicatorMapper indicatorMapper,
                                 SysUserMapper sysUserMapper, DataScopeService dataScopeService) {
        this.planService = planService;
        this.indicatorService = indicatorService;
        this.importPreviewStore = importPreviewStore;
        this.indicatorMapper = indicatorMapper;
        this.sysUserMapper = sysUserMapper;
        this.dataScopeService = dataScopeService;
    }

    // ---------------- 表头 ----------------

    private List<List<String>> head(Plan plan) {
        List<List<String>> head = new ArrayList<>();
        for (String column : FIXED_COLUMNS) {
            head.add(List.of(column));
        }
        for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
            head.add(List.of(year + "年目标"));
        }
        head.add(List.of(OWNER_COLUMN));
        return head;
    }

    /** 导入模板（含一行 A 示例、一行 A+ 示例；第二 sheet 为填写说明） */
    public byte[] buildImportTemplate(Plan plan) {
        List<Object> exampleA = new ArrayList<>(List.of(
                "A", "A01", "", "新增省级及以上教学成果奖", "约束性", "数值", "增量累计型",
                "项", "越大越好", "自动汇总", "是", "以省教育厅公布的获奖文件为准", "", 5));
        List<Object> exampleAp = new ArrayList<>(List.of(
                "A+", "A01-1", "A01", "新增省级教学成果奖", "约束性", "数值", "增量累计型",
                "项", "越大越好", "", "", "按第一完成单位统计", "", 4));
        for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
            exampleA.add(1);
            exampleAp.add(year == plan.getStartYear() ? 0 : 1);
        }
        exampleA.add("owner1");
        exampleAp.add("owner1");

        List<List<Object>> notes = new ArrayList<>();
        notes.add(List.of("枚举列可填编码或中文：指标性质（CONSTRAINT 约束性 / DEVELOPMENT 发展性）"));
        notes.add(List.of("计量类型：NUMBER 数值 / BOOLEAN 是否 / PERCENT_QUANTIFIABLE 百分比-可量化 / PERCENT_DIRECT 百分比-不可量化"));
        notes.add(List.of("统计口径：INCREMENT 增量累计型 / STOCK 存量时点型 / GROWTH 增长率型 / RATIO 占比型 / DIRECT_PERCENT 直填百分比型 / BOOLEAN 是否型"));
        notes.add(List.of("合法组合：数值→增量/存量；是否→是否型；百分比-可量化→增长率/占比；百分比-不可量化→直填百分比"));
        notes.add(List.of("达成方向：HIGHER_BETTER 越大越好 / LOWER_BETTER 越小越好；汇总方式：AUTO_SUM 自动汇总 / MANUAL 手工认定（仅可量化的 A 必填）"));
        notes.add(List.of("层级：A / A+；A+ 必须填上级编号且上级 A 可量化；编号可留空由系统生成（A01 / A01-1）"));
        notes.add(List.of("不可量化 A 的计量类型、统计口径、单位、达成方向、汇总方式、基线值、五年目标和年度目标均须留空"));
        notes.add(List.of("导入的 A 均为未归属 S 的草稿，S 归属由 ADMIN 在系统管理中另行配置"));
        notes.add(List.of("增量累计型：各年度目标之和必须等于五年目标；是否型：在计划完成年份的目标列填「是」，其余留空"));
        notes.add(List.of("增长率型 / 存量时点型：必须填基线值；负责人账号须为平台已存在的登录名（A+ 留空则沿用上级 A）"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            WriteSheet dataSheet = EasyExcel.writerSheet(0, "指标体系").head(head(plan)).build();
            writer.write(List.of(exampleA, exampleAp), dataSheet);
            WriteSheet noteSheet = EasyExcel.writerSheet(1, "填写说明").head(List.of(List.of("填写说明"))).build();
            writer.write(notes, noteSheet);
        }
        return out.toByteArray();
    }

    // ---------------- 导入预览 ----------------

    public IndicatorDtos.ImportPreviewResponse preview(Long planId, MultipartFile file) {
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireNotFinalized(plan);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!filename.endsWith(".xlsx")) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "仅支持 .xlsx 文件");
        }
        List<Map<Integer, String>> rawRows;
        try (InputStream in = file.getInputStream()) {
            rawRows = EasyExcel.read(in).sheet(0).headRowNumber(0).doReadSync();
        } catch (Exception e) {
            log.warn("Excel 解析失败 planId={}", planId, e);
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "Excel 解析失败，请使用平台提供的模板");
        }
        if (rawRows.isEmpty()) {
            throw new BizException(ErrorCode.EXCEL_IMPORT_INVALID, "文件为空");
        }
        // 表头
        Map<String, Integer> fixedIdx = new HashMap<>();
        Map<Integer, Integer> yearIdx = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : rawRows.get(0).entrySet()) {
            String name = entry.getValue() == null ? "" : entry.getValue().trim();
            if (name.isEmpty()) {
                continue;
            }
            Matcher yearMatcher = YEAR_COLUMN.matcher(name);
            if (yearMatcher.matches()) {
                yearIdx.put(entry.getKey(), Integer.parseInt(yearMatcher.group(1)));
            } else {
                fixedIdx.put(name, entry.getKey());
            }
        }
        List<String> removed = fixedIdx.keySet().stream().filter(REMOVED_COLUMNS::contains).sorted().toList();
        if (!removed.isEmpty()) {
            throw new BizException(ErrorCode.EXCEL_IMPORT_INVALID,
                    "模板包含已移除列：" + String.join("、", removed) + "，请下载最新模板");
        }
        List<String> missing = new ArrayList<>();
        for (String column : FIXED_COLUMNS) {
            if (!fixedIdx.containsKey(column)) {
                missing.add(column);
            }
        }
        if (!fixedIdx.containsKey(OWNER_COLUMN)) {
            missing.add(OWNER_COLUMN);
        }
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.EXCEL_IMPORT_INVALID, "缺少必需列：" + String.join("、", missing));
        }
        String yearError = ImportValidator.checkYearColumns(new ArrayList<>(yearIdx.values()),
                plan.getStartYear(), plan.getEndYear());
        if (yearError != null) {
            throw new BizException(ErrorCode.EXCEL_IMPORT_INVALID, yearError);
        }
        // 数据行
        List<ImportRowData> rows = new ArrayList<>();
        for (int i = 1; i < rawRows.size(); i++) {
            Map<Integer, String> cells = rawRows.get(i);
            if (cells.values().stream().allMatch(v -> v == null || v.trim().isEmpty())) {
                continue;
            }
            if (rows.size() >= MAX_IMPORT_ROWS) {
                throw new BizException(ErrorCode.EXCEL_IMPORT_INVALID, "单次最多导入 " + MAX_IMPORT_ROWS + " 行");
            }
            ImportRowData row = new ImportRowData();
            row.rowNum = i + 1;
            row.level = cell(cells, fixedIdx, "层级");
            row.code = cell(cells, fixedIdx, "编号");
            row.parentCode = cell(cells, fixedIdx, "上级编号");
            row.name = cell(cells, fixedIdx, "关键产出指标");
            row.nature = cell(cells, fixedIdx, "指标性质");
            row.measureType = cell(cells, fixedIdx, "计量类型");
            row.statCaliber = cell(cells, fixedIdx, "统计口径");
            row.unitLabel = cell(cells, fixedIdx, "单位");
            row.targetDirection = cell(cells, fixedIdx, "达成方向");
            row.rollupMode = cell(cells, fixedIdx, "汇总方式");
            row.quantifiable = cell(cells, fixedIdx, "是否可量化");
            row.description = cell(cells, fixedIdx, "填写说明");
            row.baseline = cell(cells, fixedIdx, "基线值");
            row.totalTarget = cell(cells, fixedIdx, "五年目标");
            row.ownerUsername = cell(cells, fixedIdx, OWNER_COLUMN);
            for (Map.Entry<Integer, Integer> yearEntry : yearIdx.entrySet()) {
                String value = cells.get(yearEntry.getKey());
                if (value != null && !value.trim().isEmpty()) {
                    row.yearTargets.put(yearEntry.getValue(), value.trim());
                }
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.EXCEL_IMPORT_INVALID, "没有可导入的数据行");
        }
        // 校验上下文
        ImportRowData.Context ctx = buildContext(plan, rows);
        // 逐行校验
        LoginUser loginUser = SecurityUtils.getLoginUser();
        boolean ownerScopeOnly = !dataScopeService.isAllData(loginUser)
                && dataScopeService.isOwnerScope(loginUser);
        ParsedImport draft = new ParsedImport();
        draft.setPlanId(planId);
        draft.setUserId(loginUser.getUserId());
        List<IndicatorDtos.ImportRowResult> results = new ArrayList<>();
        int validRows = 0;
        for (ImportRowData row : rows) {
            List<String> errors = ImportValidator.validate(row, ctx);
            if (ownerScopeOnly && !ImportValidator.isBlank(row.ownerUsername)
                    && !loginUser.getUsername().equals(row.ownerUsername.trim())) {
                errors.add("只能导入本人负责的指标（负责人账号应为本人）");
            }
            boolean valid = errors.isEmpty();
            if (valid) {
                validRows++;
                draft.getRows().add(toTypedRow(row, plan));
            } else {
                draft.setHasInvalidRows(true);
            }
            results.add(new IndicatorDtos.ImportRowResult(row.rowNum,
                    EnumAliases.parse(EnumAliases.LEVEL, row.level), row.code, row.name, valid, errors));
        }
        String importId = importPreviewStore.save(draft);
        return new IndicatorDtos.ImportPreviewResponse(importId, rows.size(), validRows,
                rows.size() - validRows, results);
    }

    private ImportRowData.Context buildContext(Plan plan, List<ImportRowData> rows) {
        ImportRowData.Context ctx = new ImportRowData.Context(plan.getStartYear(), plan.getEndYear());
        ctx.existingCodes = indicatorService.existingCodes(plan.getId());
        Map<String, Boolean> aQuantifiable = new HashMap<>();
        indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getPlanId, plan.getId())
                        .eq(Indicator::getLevel, "A")
                        .eq(Indicator::getDeleted, 0L))
                .forEach(a -> aQuantifiable.put(a.getCode(), Boolean.TRUE.equals(a.getIsQuantifiable())));
        Map<String, Integer> codeCount = new HashMap<>();
        for (ImportRowData row : rows) {
            if (!ImportValidator.isBlank(row.code)) {
                codeCount.merge(row.code.trim(), 1, Integer::sum);
            }
            if ("A".equals(EnumAliases.parse(EnumAliases.LEVEL, row.level))
                    && !ImportValidator.isBlank(row.code)) {
                aQuantifiable.put(row.code.trim(), Boolean.TRUE.equals(EnumAliases.parseYesNo(row.quantifiable)));
            }
        }
        ctx.duplicatedCodesInFile = codeCount.entrySet().stream()
                .filter(e -> e.getValue() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());
        ctx.aQuantifiableByCode = aQuantifiable;
        ctx.validUsernames = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getIsEnabled, true).eq(SysUser::getDeleted, 0L))
                .stream().map(SysUser::getUsername).collect(Collectors.toSet());
        return ctx;
    }

    private ParsedImport.Row toTypedRow(ImportRowData row, Plan plan) {
        ParsedImport.Row typed = new ParsedImport.Row();
        typed.setRowNum(row.rowNum);
        typed.setLevel(EnumAliases.parse(EnumAliases.LEVEL, row.level));
        typed.setCode(ImportValidator.isBlank(row.code) ? null : row.code.trim());
        typed.setParentCode(ImportValidator.isBlank(row.parentCode) ? null : row.parentCode.trim());
        typed.setName(row.name.trim());
        typed.setNature(EnumAliases.parse(EnumAliases.NATURE, row.nature));
        typed.setQuantifiable(EnumAliases.parseYesNo(row.quantifiable));
        typed.setDescription(ImportValidator.isBlank(row.description) ? null : row.description.trim());
        typed.setOwnerUsername(ImportValidator.isBlank(row.ownerUsername) ? null : row.ownerUsername.trim());
        if (!IndicatorRules.isQuantifiable(typed.getLevel(), typed.getQuantifiable())) {
            return typed;
        }
        typed.setMeasureType(EnumAliases.parse(EnumAliases.MEASURE_TYPE, row.measureType));
        typed.setStatCaliber(EnumAliases.parse(EnumAliases.STAT_CALIBER, row.statCaliber));
        typed.setUnitLabel(row.unitLabel.trim());
        typed.setTargetDirection(ImportValidator.isBlank(row.targetDirection)
                ? "HIGHER_BETTER" : EnumAliases.parse(EnumAliases.TARGET_DIRECTION, row.targetDirection));
        typed.setRollupMode(EnumAliases.parse(EnumAliases.ROLLUP_MODE, row.rollupMode));
        typed.setBaselineValue(ImportValidator.parseDecimal(row.baseline));
        typed.setTotalTarget("BOOLEAN".equals(typed.getStatCaliber())
                ? BigDecimal.ONE : ImportValidator.parseDecimal(row.totalTarget));
        Map<Integer, BigDecimal> targets = new LinkedHashMap<>();
        for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
            String raw = row.yearTargets.get(year);
            if ("BOOLEAN".equals(typed.getStatCaliber())) {
                targets.put(year, Boolean.TRUE.equals(EnumAliases.parseYesNo(raw)) ? BigDecimal.ONE : null);
            } else {
                targets.put(year, ImportValidator.parseDecimal(raw));
            }
        }
        typed.setYearTargets(targets);
        return typed;
    }

    // ---------------- 确认导入 ----------------

    @Transactional
    public IndicatorDtos.ImportConfirmResponse confirm(Long planId, String importId) {
        ParsedImport draft = importPreviewStore.get(importId);
        if (draft == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导入批次不存在或已过期，请重新上传");
        }
        if (!draft.getPlanId().equals(planId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "importId 与本计划不匹配");
        }
        if (!draft.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        if (draft.isHasInvalidRows()) {
            throw new BizException(ErrorCode.EXCEL_IMPORT_INVALID, "存在校验失败的行，请修正后重新上传");
        }
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireNotFinalized(plan);

        Long userId = SecurityUtils.getUserId();
        Map<String, SysUser> userByUsername = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getIsEnabled, true).eq(SysUser::getDeleted, 0L))
                .stream().collect(Collectors.toMap(SysUser::getUsername, Function.identity()));
        Set<String> codes = new HashSet<>(indicatorService.existingCodes(planId));
        Map<String, Indicator> aByCode = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getPlanId, planId)
                        .eq(Indicator::getLevel, "A")
                        .eq(Indicator::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Indicator::getCode, Function.identity()));

        int aCount = 0;
        int aPlusCount = 0;
        Map<String, Indicator> createdA = new HashMap<>();
        for (ParsedImport.Row row : draft.getRows()) {
            if ("A".equals(row.getLevel())) {
                Indicator a = buildIndicator(planId, null, null, row,
                        row.getCode() == null ? IndicatorRules.nextACode(codes) : row.getCode(),
                        userByUsername, userId);
                indicatorMapper.insert(a);
                codes.add(a.getCode());
                createdA.put(a.getCode(), a);
                indicatorService.insertYearTargetRows(a.getId(), row.getYearTargets(), userId);
                aCount++;
            }
        }
        for (ParsedImport.Row row : draft.getRows()) {
            if (!"A_PLUS".equals(row.getLevel())) {
                continue;
            }
            Indicator parent = createdA.getOrDefault(row.getParentCode(), aByCode.get(row.getParentCode()));
            if (parent == null) {
                throw new BizException(ErrorCode.EXCEL_IMPORT_INVALID,
                        "上级编号不存在：" + row.getParentCode());
            }
            IndicatorRules.requireAPlusAttachable(parent.getLevel(), parent.getIsQuantifiable());
            String code = row.getCode() == null
                    ? IndicatorRules.nextAPlusCode(parent.getCode(), codes) : row.getCode();
            Indicator ap = buildIndicator(planId, parent.getDirectionId(), parent.getId(), row, code,
                    userByUsername, userId);
            indicatorMapper.insert(ap);
            codes.add(ap.getCode());
            indicatorService.insertYearTargetRows(ap.getId(), row.getYearTargets(), userId);
            aPlusCount++;
        }
        importPreviewStore.remove(importId);
        return new IndicatorDtos.ImportConfirmResponse(0, aCount, aPlusCount);
    }

    private Indicator buildIndicator(Long planId, Long directionId, Long parentId, ParsedImport.Row row,
                                     String code, Map<String, SysUser> userByUsername, Long userId) {
        Indicator indicator = new Indicator();
        indicator.setPlanId(planId);
        indicator.setDirectionId(directionId);
        indicator.setParentId(parentId);
        indicator.setLevel(row.getLevel());
        indicator.setCode(code);
        indicator.setName(row.getName());
        indicator.setNature(row.getNature());
        if (IndicatorRules.isQuantifiable(row.getLevel(), row.getQuantifiable())) {
            indicator.setMeasureType(row.getMeasureType());
            indicator.setStatCaliber(row.getStatCaliber());
            indicator.setUnitLabel(row.getUnitLabel());
            indicator.setTargetDirection(row.getTargetDirection());
            indicator.setRollupMode(row.getRollupMode());
            indicator.setBaselineValue(row.getBaselineValue());
            indicator.setTotalTarget(row.getTotalTarget());
        }
        if (parentId == null) {
            indicator.setIsQuantifiable(Boolean.TRUE.equals(row.getQuantifiable()));
        }
        indicator.setDescription(row.getDescription());
        indicator.setMinFileCount(0);
        SysUser owner = row.getOwnerUsername() == null ? null : userByUsername.get(row.getOwnerUsername());
        indicator.setOwnerId(owner == null ? null : owner.getId());
        indicator.setSort(0);
        indicator.setStatus("DRAFT");
        indicator.setCreatedBy(userId);
        indicator.setUpdatedBy(userId);
        return indicator;
    }

    // ---------------- 指标体系导出 ----------------

    /**
     * 生成指标体系导出 xlsx。
     *
     * @param scopeOwner null = 全校；-1 = 无可见数据（仅 UNIT_OWNER）；其余 = 负责人 id
     */
    public byte[] buildIndicatorsExport(Long planId, Long scopeOwner) {
        Plan plan = planService.requirePlan(planId);
        List<Indicator> all = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, planId)
                .eq(Indicator::getDeleted, 0L)
                .orderByAsc(Indicator::getSort).orderByAsc(Indicator::getCode));
        Map<Long, Indicator> byId = all.stream().collect(Collectors.toMap(Indicator::getId, Function.identity()));
        List<Indicator> visible = new ArrayList<>();
        for (Indicator indicator : all) {
            if (scopeOwner == null) {
                visible.add(indicator);
                continue;
            }
            if (scopeOwner < 0) {
                continue;
            }
            Long owner = indicator.getOwnerId();
            if (owner == null && indicator.getParentId() != null) {
                Indicator parent = byId.get(indicator.getParentId());
                owner = parent == null ? null : parent.getOwnerId();
            }
            if (scopeOwner.equals(owner)) {
                visible.add(indicator);
            }
        }
        Set<Long> userIds = visible.stream().map(Indicator::getOwnerId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        all.stream().map(Indicator::getOwnerId).filter(Objects::nonNull).forEach(userIds::add);
        Map<Long, SysUser> users = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, userIds).eq(SysUser::getDeleted, 0L))
                .stream().collect(Collectors.toMap(SysUser::getId, Function.identity()));
        Map<Long, Map<Integer, BigDecimal>> targets = indicatorService.loadTargets(
                visible.stream().map(Indicator::getId).toList());

        List<List<Object>> data = new ArrayList<>();
        for (Indicator a : visible.stream().filter(i -> i.getParentId() == null).toList()) {
            data.add(toExcelRow(a, plan, users, targets, byId));
            for (Indicator ap : visible.stream().filter(i -> a.getId().equals(i.getParentId())).toList()) {
                data.add(toExcelRow(ap, plan, users, targets, byId));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(head(plan)).sheet("指标体系").doWrite(data);
        return out.toByteArray();
    }

    private List<Object> toExcelRow(Indicator indicator, Plan plan, Map<Long, SysUser> users,
                                    Map<Long, Map<Integer, BigDecimal>> targets, Map<Long, Indicator> byId) {
        Indicator parent = indicator.getParentId() == null ? null : byId.get(indicator.getParentId());
        SysUser owner = indicator.getOwnerId() == null ? null : users.get(indicator.getOwnerId());
        boolean quantifiable = IndicatorRules.isQuantifiable(indicator.getLevel(), indicator.getIsQuantifiable());
        String statCaliberLabel = !quantifiable ? "" : "BOOLEAN".equals(indicator.getStatCaliber())
                ? "是否型" : EnumAliases.label(indicator.getStatCaliber());
        List<Object> row = new ArrayList<>();
        row.add("A".equals(indicator.getLevel()) ? "A" : "A+");
        row.add(indicator.getCode());
        row.add(parent == null ? "" : parent.getCode());
        row.add(indicator.getName());
        row.add(EnumAliases.label(indicator.getNature()));
        row.add(quantifiable ? EnumAliases.label(indicator.getMeasureType()) : "");
        row.add(statCaliberLabel);
        row.add(quantifiable ? indicator.getUnitLabel() : "");
        row.add(quantifiable ? EnumAliases.label(indicator.getTargetDirection()) : "");
        row.add(!quantifiable || indicator.getRollupMode() == null ? "" : EnumAliases.label(indicator.getRollupMode()));
        row.add(!"A".equals(indicator.getLevel()) || indicator.getIsQuantifiable() == null ? ""
                : Boolean.TRUE.equals(indicator.getIsQuantifiable()) ? "是" : "否");
        row.add(indicator.getDescription() == null ? "" : indicator.getDescription());
        row.add(!quantifiable || indicator.getBaselineValue() == null ? "" : indicator.getBaselineValue());
        row.add(!quantifiable || indicator.getTotalTarget() == null ? "" : indicator.getTotalTarget());
        Map<Integer, BigDecimal> yearTargets = quantifiable
                ? targets.getOrDefault(indicator.getId(), Map.of()) : Map.of();
        for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
            BigDecimal value = yearTargets.get(year);
            if (quantifiable && "BOOLEAN".equals(indicator.getStatCaliber())) {
                row.add(value != null && value.compareTo(BigDecimal.ONE) == 0 ? "是" : "");
            } else {
                row.add(value == null ? "" : value);
            }
        }
        row.add(owner == null ? "" : owner.getUsername());
        return row;
    }

    private String cell(Map<Integer, String> cells, Map<String, Integer> fixedIdx, String name) {
        Integer idx = fixedIdx.get(name);
        if (idx == null) {
            return null;
        }
        String value = cells.get(idx);
        return value == null ? null : value.trim();
    }
}
