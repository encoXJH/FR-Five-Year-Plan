package edu.fzrj.metrics.module.system.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.RolePermissions;
import edu.fzrj.metrics.security.SecurityUtils;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class UserImportService {

    static final List<String> COLUMNS = List.of("姓名", "工号", "手机号", "角色编码", "主单元编码", "其他单元编码");
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 200;

    private final SysUserMapper sysUserMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final UserAdminService userAdminService;
    private final UserImportPreviewStore previewStore;

    public UserImportService(SysUserMapper sysUserMapper, OrgUnitMapper orgUnitMapper,
                             UserAdminService userAdminService, UserImportPreviewStore previewStore) {
        this.sysUserMapper = sysUserMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.userAdminService = userAdminService;
        this.previewStore = previewStore;
    }

    public byte[] buildImportTemplate() {
        LoginUser creator = SecurityUtils.getLoginUser();
        List<String> roles = userAdminService.creatableRoles(creator.getRoles());
        List<OrgUnit> units = loadUnits();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            WriteSheet dataSheet = EasyExcel.writerSheet(0, "用户导入").head(head(COLUMNS)).build();
            writer.write(List.of(List.of("", "", "", "", "", "")), dataSheet);

            List<List<Object>> notes = List.of(
                    List.of("在“用户导入”工作表填写，姓名和工号必填；工号、手机号、角色及单元编码均按文本填写。"),
                    List.of("多角色、其他单元编码可用中文或英文逗号分隔；角色编码见“可创建角色”工作表。"),
                    List.of("主单元编码填写一个单元，其他单元编码可留空；单元字典动态读取当前执行单元。"),
                    List.of("STAFF 干事只能单角色，主单元和其他单元必须留空，服务端自动继承创建人全部学院。"),
                    List.of("统一初始密码在确认导入时输入，长度 8–64 位，不写入 Excel、预览或导入批次。"),
                    List.of("仅支持 .xlsx，文件不超过 10MB，单次最多 200 行；任一预检错误都会禁止确认。"),
                    List.of("确认导入整批成功或整批回滚；工号重复、角色越权或学院越权均需先修正。"));
            WriteSheet noteSheet = EasyExcel.writerSheet(1, "填写说明")
                    .head(List.of(List.of("填写说明"))).build();
            writer.write(notes, noteSheet);

            List<List<Object>> roleRows = roles.stream()
                    .map(role -> List.<Object>of(role, RolePermissions.roleLabel(role)))
                    .toList();
            WriteSheet roleSheet = EasyExcel.writerSheet(2, "可创建角色")
                    .head(List.of(List.of("角色编码"), List.of("角色名称"))).build();
            writer.write(roleRows, roleSheet);

            List<List<Object>> unitRows = units.stream()
                    .map(unit -> List.<Object>of(unit.getCode(), unit.getName(),
                            Boolean.TRUE.equals(unit.getIsEnabled()) ? "是" : "否"))
                    .toList();
            WriteSheet unitSheet = EasyExcel.writerSheet(3, "单元字典")
                    .head(List.of(List.of("单元编码"), List.of("单元名称"), List.of("是否启用"))).build();
            writer.write(unitRows, unitSheet);
        }
        return applyTextStyle(out.toByteArray());
    }

    public SystemDtos.UserImportPreviewResponse preview(MultipartFile file) {
        validateFile(file);
        List<Map<Integer, String>> rawRows = readRows(file);
        if (rawRows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件为空");
        }
        Map<String, Integer> headers = headers(rawRows.get(0));
        List<String> missing = COLUMNS.stream().filter(column -> !headers.containsKey(column)).toList();
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少必需列：" + String.join("、", missing));
        }

        LoginUser creator = SecurityUtils.getLoginUser();
        Map<String, OrgUnit> unitByCode = new HashMap<>();
        loadUnits().forEach(unit -> unitByCode.put(unit.getCode(), unit));
        Set<String> existingUsernames = new HashSet<>(sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getDeleted, 0L)
                        .select(SysUser::getUsername))
                .stream().map(SysUser::getUsername).map(UserImportService::normalizeUsername).toList());
        Set<String> fileUsernames = new HashSet<>();
        List<SystemDtos.UserImportRowResult> results = new ArrayList<>();
        List<SystemDtos.UserImportDraftRow> drafts = new ArrayList<>();

        for (int i = 1; i < rawRows.size(); i++) {
            Map<Integer, String> cells = rawRows.get(i);
            if (isBlankRow(cells)) {
                continue;
            }
            if (results.size() >= MAX_IMPORT_ROWS) {
                throw new BizException(ErrorCode.PARAM_INVALID, "单次最多导入 " + MAX_IMPORT_ROWS + " 行");
            }
            int rowNum = i + 1;
            String name = cell(cells, headers, "姓名");
            String username = cell(cells, headers, "工号");
            String phone = cell(cells, headers, "手机号");
            List<String> roles = split(cell(cells, headers, "角色编码"));
            String primaryUnitCode = cell(cells, headers, "主单元编码");
            List<String> otherUnitCodes = split(cell(cells, headers, "其他单元编码"));
            if (primaryUnitCode != null) {
                otherUnitCodes.remove(primaryUnitCode);
            }
            List<String> errors = new ArrayList<>();
            if (!StringUtils.hasText(name) || name.length() > 64) {
                errors.add("姓名必填且不超过 64 字");
            }
            if (!StringUtils.hasText(username) || !username.matches("[A-Za-z0-9_.-]{1,64}")) {
                errors.add("工号必填，且只允许字母、数字、下划线、点、短横线，不超过 64 字");
            } else {
                String normalized = normalizeUsername(username);
                if (existingUsernames.contains(normalized)) {
                    errors.add("工号已存在：" + username);
                }
                if (!fileUsernames.add(normalized)) {
                    errors.add("文件内工号重复：" + username);
                }
            }
            if (phone != null && phone.length() > 32) {
                errors.add("手机号不超过 32 字");
            }

            List<String> validRoles = null;
            try {
                validRoles = userAdminService.validateCreationRoles(roles, creator.getRoles());
            } catch (BizException e) {
                errors.add(e.getMessage());
            }
            List<String> unknownUnits = new ArrayList<>();
            if (primaryUnitCode != null && !unitByCode.containsKey(primaryUnitCode)) {
                unknownUnits.add(primaryUnitCode);
            }
            unknownUnits.addAll(otherUnitCodes.stream().filter(code -> !unitByCode.containsKey(code)).toList());
            if (!unknownUnits.isEmpty()) {
                errors.add("单元编码不存在：" + String.join("、", unknownUnits));
            }

            UserAdminService.UnitSelection selection = null;
            if (validRoles != null && unknownUnits.isEmpty()) {
                Long primaryUnitId = primaryUnitCode == null ? null : unitByCode.get(primaryUnitCode).getId();
                List<Long> otherUnitIds = otherUnitCodes.stream().map(code -> unitByCode.get(code).getId()).toList();
                try {
                    selection = userAdminService.resolveCreationUnits(validRoles, primaryUnitId, otherUnitIds, creator);
                } catch (BizException e) {
                    errors.add(e.getMessage());
                }
            }

            boolean valid = errors.isEmpty();
            if (valid) {
                SystemDtos.UserImportDraftRow draft = new SystemDtos.UserImportDraftRow();
                draft.setRowNum(rowNum);
                draft.setName(name);
                draft.setUsername(username);
                draft.setPhone(phone);
                draft.setRoles(validRoles);
                draft.setPrimaryUnitId(selection.primaryUnitId());
                draft.setUnitIds(selection.unitIds());
                drafts.add(draft);
            }
            results.add(new SystemDtos.UserImportRowResult(rowNum, name, username, maskPhone(phone),
                    roles, primaryUnitCode, List.copyOf(otherUnitCodes), valid, List.copyOf(errors)));
        }
        if (results.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "没有可导入的数据行");
        }
        String importId = previewStore.save(creator.getUserId(), drafts, drafts.size() != results.size());
        return new SystemDtos.UserImportPreviewResponse(importId, results.size(), drafts.size(),
                results.size() - drafts.size(), results);
    }

    @Transactional
    public SystemDtos.UserImportConfirmResponse confirm(String importId, String initialPassword) {
        UserImportPreviewStore.Batch batch = previewStore.get(importId);
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导入批次不存在或已过期，请重新上传");
        }
        if (!batch.getCreatorId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "不能确认其他创建人的导入批次");
        }
        if (batch.isHasInvalidRows()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "存在校验失败的行，请修正后重新上传");
        }
        if (!StringUtils.hasText(initialPassword) || initialPassword.length() < 8 || initialPassword.length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID, "统一初始密码长度必须为 8–64 位");
        }

        List<Long> userIds = new ArrayList<>();
        for (SystemDtos.UserImportDraftRow row : batch.getRows()) {
            SystemDtos.UserCreateRequest request = new SystemDtos.UserCreateRequest();
            request.setName(row.getName());
            request.setUsername(row.getUsername());
            request.setPhone(row.getPhone());
            request.setPassword(initialPassword);
            request.setRoles(row.getRoles());
            if (!row.getRoles().contains("STAFF")) {
                request.setUnitId(row.getPrimaryUnitId());
                request.setUnitIds(row.getUnitIds());
            }
            SystemDtos.UserItem created = userAdminService.create(request);
            userIds.add(created.id());
        }
        previewStore.remove(importId);
        return new SystemDtos.UserImportConfirmResponse(userIds.size(), userIds);
    }

    private List<OrgUnit> loadUnits() {
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getDeleted, 0L)
                .orderByAsc(OrgUnit::getSort)
                .orderByAsc(OrgUnit::getId));
    }

    private void validateFile(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx")) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "仅支持 .xlsx 文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE, "用户导入文件不能超过 10MB");
        }
        if (file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件为空");
        }
    }

    private List<Map<Integer, String>> readRows(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return EasyExcel.read(in).sheet(0).headRowNumber(0).doReadSync();
        } catch (Exception e) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "Excel 解析失败，请使用平台提供的模板");
        }
    }

    private Map<String, Integer> headers(Map<Integer, String> firstRow) {
        Map<String, Integer> headers = new HashMap<>();
        for (Map.Entry<Integer, String> entry : firstRow.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!value.isEmpty()) {
                headers.put(value, entry.getKey());
            }
        }
        return headers;
    }

    private String cell(Map<Integer, String> cells, Map<String, Integer> headers, String column) {
        Integer index = headers.get(column);
        String value = index == null ? null : cells.get(index);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean isBlankRow(Map<Integer, String> cells) {
        return cells == null || cells.values().stream().allMatch(value -> !StringUtils.hasText(value));
    }

    private List<String> split(String value) {
        if (!StringUtils.hasText(value)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(new LinkedHashSet<>(List.of(value.split("[,，]"))).stream()
                .map(String::trim).filter(StringUtils::hasText).toList());
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private List<List<String>> head(List<String> columns) {
        return columns.stream().map(List::of).toList();
    }

    private byte[] applyTextStyle(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int i = 0; i < COLUMNS.size(); i++) {
                sheet.setDefaultColumnStyle(i, textStyle);
                sheet.setColumnWidth(i, i == 0 || i == 3 ? 24 * 256 : 22 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成用户导入模板失败", e);
        }
    }
}
