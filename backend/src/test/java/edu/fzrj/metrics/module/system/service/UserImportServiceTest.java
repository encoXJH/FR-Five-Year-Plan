package edu.fzrj.metrics.module.system.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.system.controller.UserController;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.RefreshTokenMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserImportServiceTest {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Mock private SysUserMapper sysUserMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private RefreshTokenMapper refreshTokenMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserUnitService userUnitService;

    private UserAdminService userAdminService;
    private UserImportPreviewStore previewStore;
    private UserImportService userImportService;
    private ValidatorFactory validatorFactory;
    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
    }

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        userAdminService = spy(new UserAdminService(sysUserMapper, userRoleMapper, orgUnitMapper,
                refreshTokenMapper, passwordEncoder, userUnitService, validatorFactory.getValidator()));
        previewStore = new UserImportPreviewStore();
        userImportService = new UserImportService(sysUserMapper, orgUnitMapper, userAdminService, previewStore);
        security = Mockito.mockStatic(SecurityUtils.class);
    }

    @AfterEach
    void tearDown() {
        security.close();
        validatorFactory.close();
    }

    @Test
    void 模板包含动态角色单元字典填写说明和文本列() throws Exception {
        mockCreator("UNIT_OWNER", List.of(101L, 102L));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(101L, "01", "智能产业学院"), unit(102L, "02", "数字金融产业学院")));

        byte[] bytes = userImportService.buildImportTemplate();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(4, workbook.getNumberOfSheets());
            assertEquals("用户导入", workbook.getSheetName(0));
            assertEquals("填写说明", workbook.getSheetName(1));
            assertEquals("可创建角色", workbook.getSheetName(2));
            assertEquals("单元字典", workbook.getSheetName(3));
            Sheet data = workbook.getSheetAt(0);
            Row header = data.getRow(0);
            for (int i = 0; i < UserImportService.COLUMNS.size(); i++) {
                assertEquals(UserImportService.COLUMNS.get(i), header.getCell(i).getStringCellValue());
                assertEquals("@", data.getColumnStyle(i).getDataFormatString());
            }
            Sheet roles = workbook.getSheetAt(2);
            assertEquals("UNIT_OWNER", roles.getRow(1).getCell(0).getStringCellValue());
            assertEquals("STAFF", roles.getRow(2).getCell(0).getStringCellValue());
            assertEquals(2, roles.getLastRowNum());
            Sheet units = workbook.getSheetAt(3);
            assertEquals("01", units.getRow(1).getCell(0).getStringCellValue());
            assertEquals("数字金融产业学院", units.getRow(2).getCell(1).getStringCellValue());
            String notes = workbook.getSheetAt(1).getRow(5).getCell(0).getStringCellValue();
            assertTrue(notes.contains("不写入 Excel"));
        }
    }

    @Test
    void 预览支持中英文逗号并保存规范化角色和单元() {
        mockCreator("ADMIN", List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(101L, "01", "智能产业学院"), unit(102L, "02", "数字金融产业学院"),
                unit(103L, "03", "数字影视传媒产业学院")));
        when(orgUnitMapper.selectCount(any())).thenReturn(1L);
        when(sysUserMapper.selectList(any())).thenReturn(List.of());

        SystemDtos.UserImportPreviewResponse response = userImportService.preview(excel(List.of(
                List.of("张三", "user001", "13812345678", "LEADER，SCHOOL_OWNER", "01", "02,03"))));

        assertEquals(1, response.totalRows());
        assertEquals(1, response.validRows());
        assertEquals(0, response.invalidRows());
        SystemDtos.UserImportRowResult row = response.rows().get(0);
        assertEquals(2, row.rowNum());
        assertEquals(List.of("LEADER", "SCHOOL_OWNER"), row.roles());
        assertEquals(List.of("02", "03"), row.otherUnitCodes());
        assertEquals("138****5678", row.phone());
        UserImportPreviewStore.Batch batch = previewStore.get(response.importId());
        assertEquals(99L, batch.getCreatorId());
        assertEquals(101L, batch.getRows().get(0).getPrimaryUnitId());
        assertEquals(List.of(101L, 102L, 103L), batch.getRows().get(0).getUnitIds());
        assertEquals(Duration.ofMinutes(30), Duration.between(batch.getCreatedAt(), batch.getExpiresAt()));
    }

    @Test
    void 重复工号逐行报错且错误批次禁止确认() {
        mockCreator("ADMIN", List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of(existingUser("existing")));

        SystemDtos.UserImportPreviewResponse response = userImportService.preview(excel(List.of(
                List.of("已存在", "existing", "", "LEADER", "", ""),
                List.of("第一次", "duplicate", "", "LEADER", "", ""),
                List.of("第二次", "DUPLICATE", "", "LEADER", "", ""))));

        assertEquals(3, response.totalRows());
        assertEquals(1, response.validRows());
        assertEquals(2, response.invalidRows());
        assertTrue(response.rows().get(0).errors().get(0).contains("工号已存在"));
        assertTrue(response.rows().get(2).errors().stream().anyMatch(error -> error.contains("文件内工号重复")));
        BizException error = assertThrows(BizException.class,
                () -> userImportService.confirm(response.importId(), "password"));
        assertEquals(10001, error.getErrorCode().getCode());
        verify(userAdminService, never()).create(any());
    }

    @Test
    void 预览拒绝角色越权() {
        mockCreator("LEADER", List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of());

        SystemDtos.UserImportPreviewResponse response = userImportService.preview(excel(List.of(
                List.of("越权角色", "admin002", "", "ADMIN", "", ""))));

        assertEquals(1, response.invalidRows());
        assertTrue(response.rows().get(0).errors().stream().anyMatch(error -> error.contains("不能创建角色：ADMIN")));
    }

    @Test
    void 预览拒绝学院越权() {
        mockCreator("UNIT_OWNER", List.of(101L));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(101L, "01", "智能产业学院"), unit(102L, "02", "数字金融产业学院")));
        when(orgUnitMapper.selectCount(any())).thenReturn(1L);
        when(sysUserMapper.selectList(any())).thenReturn(List.of());

        SystemDtos.UserImportPreviewResponse response = userImportService.preview(excel(List.of(
                List.of("越权学院", "owner002", "", "UNIT_OWNER", "02", ""))));

        assertEquals(1, response.invalidRows());
        assertTrue(response.rows().get(0).errors().stream().anyMatch(error -> error.contains("超出当前创建人的学院范围")));
    }

    @Test
    void 干事预览继承学院且确认请求仍保持单元留空() {
        mockCreator("UNIT_OWNER", List.of(101L, 102L));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(101L, "01", "智能产业学院"), unit(102L, "02", "数字金融产业学院")));
        when(sysUserMapper.selectList(any())).thenReturn(List.of());
        SystemDtos.UserImportPreviewResponse preview = userImportService.preview(excel(List.of(
                List.of("干事", "staff001", "", "STAFF", "", ""))));
        assertEquals(1, preview.validRows());
        UserImportPreviewStore.Batch batch = previewStore.get(preview.importId());
        assertEquals(101L, batch.getRows().get(0).getPrimaryUnitId());
        assertEquals(List.of(101L, 102L), batch.getRows().get(0).getUnitIds());
        doAnswer(invocation -> userItem(1001L, invocation.getArgument(0)))
                .when(userAdminService).create(any());

        SystemDtos.UserImportConfirmResponse response = userImportService.confirm(preview.importId(), "password");

        assertEquals(1, response.createdCount());
        assertEquals(List.of(1001L), response.userIds());
        ArgumentCaptor<SystemDtos.UserCreateRequest> captor =
                ArgumentCaptor.forClass(SystemDtos.UserCreateRequest.class);
        verify(userAdminService).create(captor.capture());
        assertEquals("password", captor.getValue().getPassword());
        assertEquals(List.of("STAFF"), captor.getValue().getRoles());
        assertNull(captor.getValue().getUnitId());
        assertNull(captor.getValue().getUnitIds());
        assertNull(previewStore.get(preview.importId()));
    }

    @Test
    void 干事在Excel指定单元会预检失败() {
        mockCreator("UNIT_OWNER", List.of(101L));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit(101L, "01", "智能产业学院")));
        when(sysUserMapper.selectList(any())).thenReturn(List.of());

        SystemDtos.UserImportPreviewResponse response = userImportService.preview(excel(List.of(
                List.of("干事", "staff002", "", "STAFF", "01", ""))));

        assertEquals(1, response.invalidRows());
        assertTrue(response.rows().get(0).errors().stream().anyMatch(error -> error.contains("单元必须留空")));
    }

    @Test
    void 确认整批调用用户创建并统一密码且响应不含密码() throws Exception {
        mockCreator("ADMIN", List.of());
        String importId = previewStore.save(99L, List.of(
                draft(2, "用户一", "user101", "LEADER"), draft(3, "用户二", "user102", "SCHOOL_OWNER")), false);
        long[] nextId = {2001L};
        doAnswer(invocation -> userItem(nextId[0]++, invocation.getArgument(0)))
                .when(userAdminService).create(any());

        SystemDtos.UserImportConfirmResponse response = userImportService.confirm(importId, "secret123");

        assertEquals(2, response.createdCount());
        assertEquals(List.of(2001L, 2002L), response.userIds());
        ArgumentCaptor<SystemDtos.UserCreateRequest> requests =
                ArgumentCaptor.forClass(SystemDtos.UserCreateRequest.class);
        verify(userAdminService, org.mockito.Mockito.times(2)).create(requests.capture());
        assertTrue(requests.getAllValues().stream().allMatch(request -> "secret123".equals(request.getPassword())));
        String json = new ObjectMapper().writeValueAsString(response);
        assertFalse(json.contains("secret123"));
        assertFalse(json.contains("initialPassword"));
        assertNull(previewStore.get(importId));
    }

    @Test
    void 跨创建人确认返回20005() {
        mockCreator("ADMIN", List.of());
        String importId = previewStore.save(100L, List.of(draft(2, "用户", "user201", "LEADER")), false);

        BizException error = assertThrows(BizException.class,
                () -> userImportService.confirm(importId, "password"));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(userAdminService, never()).create(any());
    }

    @Test
    void 批次过期返回10003() {
        mockCreator("ADMIN", List.of());
        String importId = previewStore.save(99L, List.of(draft(2, "用户", "user202", "LEADER")), false);
        previewStore.get(importId).setExpiresAt(Instant.now().minusSeconds(1));

        BizException error = assertThrows(BizException.class,
                () -> userImportService.confirm(importId, "password"));

        assertEquals(10003, error.getErrorCode().getCode());
        verify(userAdminService, never()).create(any());
    }

    @Test
    void 确认密码长度在服务层再次校验() {
        mockCreator("ADMIN", List.of());
        String importId = previewStore.save(99L, List.of(draft(2, "用户", "user203", "LEADER")), false);

        BizException error = assertThrows(BizException.class,
                () -> userImportService.confirm(importId, "short"));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(userAdminService, never()).create(any());
    }

    @Test
    void 文件格式和大小分别复用80001与80002() {
        MockMultipartFile wrongType = new MockMultipartFile("file", "users.xls", XLSX_MIME, new byte[]{1});
        BizException typeError = assertThrows(BizException.class, () -> userImportService.preview(wrongType));
        assertEquals(80001, typeError.getErrorCode().getCode());

        MockMultipartFile tooLarge = new MockMultipartFile("file", "users.xlsx", XLSX_MIME,
                new byte[10 * 1024 * 1024 + 1]);
        BizException sizeError = assertThrows(BizException.class, () -> userImportService.preview(tooLarge));
        assertEquals(80002, sizeError.getErrorCode().getCode());
    }

    @Test
    void 超过200个非空数据行被拒绝() {
        mockCreator("ADMIN", List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of());
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            rows.add(List.of("用户" + i, "bulk" + i, "", "LEADER", "", ""));
        }

        BizException error = assertThrows(BizException.class, () -> userImportService.preview(excel(rows)));

        assertEquals(10001, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("最多导入 200 行"));
    }

    @Test
    void 控制器确认接口具备幂等和脱敏审计且模板具备下载审计() throws Exception {
        Method confirm = UserController.class.getMethod("importConfirm", SystemDtos.UserImportConfirmRequest.class);
        assertNotNull(confirm.getAnnotation(Idempotent.class));
        AuditLog confirmAudit = confirm.getAnnotation(AuditLog.class);
        assertEquals("IMPORT", confirmAudit.action());
        assertEquals("#result", confirmAudit.after());
        Method template = UserController.class.getMethod("importTemplate");
        AuditLog templateAudit = template.getAnnotation(AuditLog.class);
        assertEquals("FILE_DOWNLOAD", templateAudit.action());
        assertEquals("", templateAudit.after());
    }

    private void mockCreator(String role, List<Long> unitIds) {
        Long primary = unitIds.stream().findFirst().orElse(null);
        LoginUser creator = new LoginUser(99L, "creator", "创建人", primary, List.of(role), List.of(), unitIds);
        security.when(SecurityUtils::getLoginUser).thenReturn(creator);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
    }

    private MockMultipartFile excel(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(UserImportService.COLUMNS.stream().map(List::of).toList())
                .sheet(0, "用户导入").doWrite(rows);
        return new MockMultipartFile("file", "users.xlsx", XLSX_MIME, out.toByteArray());
    }

    private static SystemDtos.UserImportDraftRow draft(int rowNum, String name, String username, String role) {
        SystemDtos.UserImportDraftRow row = new SystemDtos.UserImportDraftRow();
        row.setRowNum(rowNum);
        row.setName(name);
        row.setUsername(username);
        row.setRoles(List.of(role));
        row.setUnitIds(List.of());
        return row;
    }

    private static SystemDtos.UserItem userItem(Long id, SystemDtos.UserCreateRequest request) {
        return new SystemDtos.UserItem(id, request.getUsername(), request.getName(), request.getPhone(), null,
                request.getUnitId(), null, request.getRoles(), true, null, 1);
    }

    private static SysUser existingUser(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setDeleted(0L);
        return user;
    }

    private static OrgUnit unit(Long id, String code, String name) {
        OrgUnit unit = new OrgUnit();
        unit.setId(id);
        unit.setCode(code);
        unit.setName(name);
        unit.setIsEnabled(true);
        unit.setDeleted(0L);
        return unit;
    }
}
