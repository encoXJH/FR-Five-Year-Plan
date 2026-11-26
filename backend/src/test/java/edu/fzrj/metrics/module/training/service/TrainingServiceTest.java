package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.mapper.UserUnitMapper;
import edu.fzrj.metrics.module.training.controller.TrainingController;
import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import edu.fzrj.metrics.module.training.entity.TrainingProject;
import edu.fzrj.metrics.module.training.entity.TrainingReview;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.mapper.TrainingProjectMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingReviewMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingServiceTest {

    @Mock private PlanMapper planMapper;
    @Mock private TrainingSheetMapper sheetMapper;
    @Mock private TrainingProjectMapper projectMapper;
    @Mock private TrainingReviewMapper reviewMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private UserUnitMapper userUnitMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private TrainingExcelService excelService;
    @Mock private TrainingAccessService accessService;
    @Mock private TrainingAttachmentService attachmentService;
    @Spy @InjectMocks private TrainingService trainingService;

    @BeforeAll
    static void initMybatis() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Plan.class);
        TableInfoHelper.initTableInfo(assistant, TrainingSheet.class);
        TableInfoHelper.initTableInfo(assistant, TrainingProject.class);
        TableInfoHelper.initTableInfo(assistant, TrainingReview.class);
    }

    @BeforeEach
    void stubAttachments() {
        lenient().when(attachmentService.fileIdsByProjectIds(any())).thenReturn(Map.of());
        lenient().when(attachmentService.filesByProjectIds(any())).thenReturn(Map.of());
        lenient().when(attachmentService.normalizeFileIds(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(attachmentService.sameFiles(any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 跨学院负责人读取采集表返回20005() {
        TrainingSheet sheet = sheet(1L, 99L);
        when(sheetMapper.selectOne(any())).thenReturn(sheet);
        doThrow(new BizException(edu.fzrj.metrics.common.error.ErrorCode.DATA_SCOPE_DENIED))
                .when(accessService).requireReadAccess(any(), any());
        login(100L, 15L);

        BizException error = assertThrows(BizException.class, () -> trainingService.detail(1L));

        assertEquals(20005, error.getErrorCode().getCode());
        verifyNoInteractions(planMapper, projectMapper);
    }

    @Test
    void 校领导可读取采集表列表用于计划卡进入() throws NoSuchMethodException {
        LoginUser leader = new LoginUser(200L, "leader", "校领导", null,
                List.of("LEADER"), List.of("submission:review"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(leader, null, List.of()));
        when(sheetMapper.selectList(any())).thenReturn(List.of());

        assertTrue(trainingService.list(null).isEmpty());
        verify(sheetMapper).selectList(any());
        PreAuthorize endpoint = TrainingController.class.getDeclaredMethod("list", Long.class)
                .getAnnotation(PreAuthorize.class);
        assertEquals("hasAnyAuthority('training:fill', 'submission:review')", endpoint.value());
    }

    @Test
    void 保存草稿版本冲突返回10005并按计划到采集表顺序加锁() {
        TrainingSheet sheet = sheet(1L, 99L);
        sheet.setVersion(2);
        prepareUnarchivedSheet(sheet);
        login(99L, 14L);
        TrainingDtos.SaveProjectsRequest request = new TrainingDtos.SaveProjectsRequest();
        request.setVersion(1);
        request.setProjects(List.of());

        BizException error = assertThrows(BizException.class, () -> trainingService.saveProjects(1L, request));

        assertEquals(10005, error.getErrorCode().getCode());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sheetMapper, planMapper);
        order.verify(sheetMapper).selectOne(any());
        order.verify(planMapper).selectByIdForUpdate(2L);
        order.verify(sheetMapper).selectByIdForUpdate(1L);
        verifyNoInteractions(projectMapper);
    }

    @Test
    void 没有培训项目时不能提交() {
        TrainingSheet sheet = sheet(1L, 99L);
        prepareUnarchivedSheet(sheet);
        when(projectMapper.selectList(any())).thenReturn(List.of());
        login(99L, 14L);
        TrainingDtos.VersionRequest request = new TrainingDtos.VersionRequest();
        request.setVersion(0);
        request.setProjectIds(List.of(10L));

        BizException error = assertThrows(BizException.class, () -> trainingService.submit(1L, request));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(sheetMapper, never()).updateById(any(TrainingSheet.class));
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @Test
    void 归档后保存和提交均返回30002() {
        TrainingSheet sheet = sheet(1L, 99L);
        when(sheetMapper.selectOne(any())).thenReturn(sheet);
        when(planMapper.selectByIdForUpdate(2L)).thenReturn(trainingPlan("ARCHIVED"));
        login(99L, 14L);
        TrainingDtos.SaveProjectsRequest saveRequest = new TrainingDtos.SaveProjectsRequest();
        saveRequest.setVersion(0);
        saveRequest.setProjects(List.of());
        TrainingDtos.VersionRequest submitRequest = new TrainingDtos.VersionRequest();
        submitRequest.setVersion(0);
        submitRequest.setProjectIds(List.of(10L));

        BizException saveError = assertThrows(BizException.class,
                () -> trainingService.saveProjects(1L, saveRequest));
        BizException submitError = assertThrows(BizException.class,
                () -> trainingService.submit(1L, submitRequest));

        assertEquals(30002, saveError.getErrorCode().getCode());
        assertEquals(30002, submitError.getErrorCode().getCode());
        verify(sheetMapper, never()).selectByIdForUpdate(any());
        verifyNoInteractions(projectMapper);
    }

    @Test
    void 归档采集表详情保留只读但关闭编辑和审核() {
        TrainingSheet sheet = sheet(1L, 99L);
        sheet.setStatus("PENDING_REVIEW");
        TrainingProject project = new TrainingProject();
        project.setId(10L);
        project.setSheetId(1L);
        project.setRowNo(1);
        project.setDataJson("{}");
        project.setStatus("PENDING_REVIEW");
        project.setSubmitterRole("STAFF");
        project.setCurrentReviewNode("LEADER_REVIEW");
        project.setRevisionNo(1);
        project.setSubmittedBy(100L);
        when(sheetMapper.selectOne(any())).thenReturn(sheet);
        when(planMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(trainingPlan("ARCHIVED")));
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectBatchIds(any())).thenReturn(List.of());
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of());
        LoginUser admin = new LoginUser(99L, "admin", "管理员", null,
                List.of("ADMIN"), List.of("training:fill", "submission:review"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of()));

        TrainingDtos.Detail detail = trainingService.detail(1L);

        assertFalse(detail.sheet().canEdit());
        assertFalse(detail.sheet().canReview());
        assertFalse(detail.projects().get(0).canEdit());
        assertFalse(detail.projects().get(0).canReview());
    }

    @Test
    void 已分配干事可填写草稿但没有审核权限() {
        TrainingSheet sheet = sheet(1L, 99L);
        LoginUser staff = new LoginUser(100L, "staff", "干事", 14L,
                List.of("STAFF"), List.of("training:fill"));
        when(accessService.canFill(sheet, staff)).thenReturn(true);

        assertTrue(trainingService.canEdit(sheet, staff));
        assertFalse(trainingService.canReview(sheet, staff));
    }

    @Test
    void 院审禁止本人审核且校领导只能处理校审() {
        TrainingSheet sheet = sheet(1L, 99L);
        TrainingProject project = new TrainingProject();
        project.setStatus("PENDING_REVIEW");
        project.setSubmitterRole("STAFF");
        project.setCurrentReviewNode("UNIT_REVIEW");
        project.setSubmittedBy(100L);
        LoginUser owner = new LoginUser(100L, "owner", "负责人", 14L,
                List.of("UNIT_OWNER"), List.of("submission:review"));
        LoginUser leader = new LoginUser(200L, "leader", "校领导", null,
                List.of("LEADER"), List.of("submission:review"));

        assertFalse(trainingService.canReview(sheet, project, owner));
        assertFalse(trainingService.canReview(sheet, project, leader));
        project.setSubmittedBy(101L);
        assertTrue(trainingService.canReview(sheet, project, owner));
        project.setCurrentReviewNode("LEADER_REVIEW");
        assertFalse(trainingService.canReview(sheet, project, owner));
        assertTrue(trainingService.canReview(sheet, project, leader));
    }

    @Test
    void 多角色列表返回本人负责表与已分配表的并集() {
        TrainingSheet owned = sheet(1L, 100L);
        TrainingSheet assigned = sheet(2L, 99L);
        TrainingSheet unrelated = sheet(3L, 98L);
        LoginUser user = new LoginUser(100L, "staff", "干事", 14L,
                List.of("STAFF", "UNIT_OWNER"), List.of("training:fill"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(accessService.validAssignedSheetIds(100L)).thenReturn(Set.of(2L));
        when(accessService.isOwner(owned, user)).thenReturn(true);
        when(sheetMapper.selectList(any())).thenReturn(List.of(owned, assigned, unrelated));
        when(planMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(trainingPlan("ACTIVE")));
        when(projectMapper.selectList(any())).thenReturn(List.of());

        List<Long> sheetIds = trainingService.list(null).stream().map(TrainingDtos.SheetSummary::id).toList();

        assertEquals(List.of(1L, 2L), sheetIds);
    }

    @Test
    void 新项目插入取得项目编号后再绑定附件() {
        TrainingSheet sheet = sheet(1L, 99L);
        prepareUnarchivedSheet(sheet);
        when(projectMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(0, TrainingProject.class).setId(55L);
            return 1;
        }).when(projectMapper).insert(any(TrainingProject.class));
        when(sheetMapper.updateById(any(TrainingSheet.class))).thenReturn(1);
        doReturn(new TrainingDtos.Detail(null, List.of(), List.of(), List.of()))
                .when(trainingService).detail(1L);
        login(99L, 14L);
        TrainingDtos.SaveProjectsRequest request = saveRequest(projectInput(null, 1, Map.of(), List.of(50L)));

        trainingService.saveProjects(1L, request);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(projectMapper, attachmentService);
        order.verify(projectMapper).insert(any(TrainingProject.class));
        order.verify(attachmentService).replace(55L, List.of(50L));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 仅新增或移除附件也视为变化并推进采集表版本(boolean added) {
        TrainingSheet sheet = sheet(1L, 99L);
        prepareUnarchivedSheet(sheet);
        TrainingProject project = project(10L, "DRAFT", "{\"c57\":\"测试人\"}");
        List<Long> requestedFiles = added ? List.of(50L) : List.of();
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        if (!added) {
            when(attachmentService.fileIdsByProjectIds(any())).thenReturn(Map.of(10L, List.of(50L)));
        }
        when(attachmentService.replace(10L, requestedFiles)).thenReturn(true);
        doAnswer(invocation -> {
            TrainingSheet updated = invocation.getArgument(0);
            updated.setVersion(updated.getVersion() + 1);
            return 1;
        }).when(sheetMapper).updateById(any(TrainingSheet.class));
        doReturn(new TrainingDtos.Detail(null, List.of(), List.of(), List.of()))
                .when(trainingService).detail(1L);
        login(99L, 14L);
        TrainingDtos.SaveProjectsRequest request = saveRequest(
                projectInput(10L, 1, Map.of("c57", "测试人"), requestedFiles));

        trainingService.saveProjects(1L, request);

        assertEquals(1, sheet.getVersion());
        verify(projectMapper, never()).updateById(any(TrainingProject.class));
        verify(attachmentService).replace(10L, requestedFiles);
        verify(sheetMapper).updateById(sheet);
    }

    @Test
    void 待审核项目数据不变时仍禁止附件变化() {
        TrainingSheet sheet = sheet(1L, 99L);
        prepareUnarchivedSheet(sheet);
        TrainingProject project = project(10L, "PENDING_REVIEW", "{}");
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        when(attachmentService.fileIdsByProjectIds(any())).thenReturn(Map.of(10L, List.of(50L)));
        when(attachmentService.sameFiles(any(), any())).thenReturn(false);
        login(99L, 14L);
        TrainingDtos.SaveProjectsRequest request = saveRequest(
                projectInput(10L, 1, Map.of(), List.of(51L)));

        BizException error = assertThrows(BizException.class, () -> trainingService.saveProjects(1L, request));

        assertEquals(10001, error.getErrorCode().getCode());
        assertTrue(error.getErrors().stream().anyMatch(field -> field.getField().endsWith("fileIds")));
        verify(attachmentService, never()).replace(any(), any());
        verify(sheetMapper, never()).updateById(any(TrainingSheet.class));
    }

    @Test
    void 项目数值按语义比较而不受精度表示影响() {
        assertTrue(TrainingService.sameData(
                Map.of("c09", new BigDecimal("10.00"), "c12", new BigDecimal("8.0")),
                Map.of("c09", 10, "c12", 8)));
        assertFalse(TrainingService.sameData(
                Map.of("c09", new BigDecimal("10.00")), Map.of("c09", 10.01)));
        assertFalse(TrainingService.sameData(Map.of("c09", 10), Map.of("c10", 10)));
    }

    private static TrainingProject project(Long id, String status, String dataJson) {
        TrainingProject project = new TrainingProject();
        project.setId(id);
        project.setSheetId(1L);
        project.setRowNo(1);
        project.setStatus(status);
        project.setRevisionNo(0);
        project.setDataJson(dataJson);
        return project;
    }

    private static TrainingDtos.ProjectInput projectInput(Long id, Integer rowNo,
                                                          Map<String, Object> data, List<Long> fileIds) {
        TrainingDtos.ProjectInput input = new TrainingDtos.ProjectInput();
        input.setId(id);
        input.setRowNo(rowNo);
        input.setData(data);
        input.setFileIds(fileIds);
        return input;
    }

    private static TrainingDtos.SaveProjectsRequest saveRequest(TrainingDtos.ProjectInput... projects) {
        TrainingDtos.SaveProjectsRequest request = new TrainingDtos.SaveProjectsRequest();
        request.setVersion(0);
        request.setProjects(List.of(projects));
        return request;
    }

    private void prepareUnarchivedSheet(TrainingSheet sheet) {
        when(sheetMapper.selectOne(any())).thenReturn(sheet);
        when(planMapper.selectByIdForUpdate(sheet.getPlanId())).thenReturn(trainingPlan("ACTIVE"));
        when(sheetMapper.selectByIdForUpdate(sheet.getId())).thenReturn(sheet);
        lenient().when(accessService.canFill(any(), any())).thenReturn(true);
    }

    private Plan trainingPlan(String status) {
        Plan plan = new Plan();
        plan.setId(2L);
        plan.setName("培训采集计划");
        plan.setPlanType("TRAINING");
        plan.setStatus(status);
        plan.setDeleted(0L);
        return plan;
    }

    private TrainingSheet sheet(Long id, Long ownerId) {
        TrainingSheet sheet = new TrainingSheet();
        sheet.setId(id);
        sheet.setPlanId(2L);
        sheet.setUnitId(14L);
        sheet.setOwnerId(ownerId);
        sheet.setStatus("DRAFT");
        sheet.setVersion(0);
        return sheet;
    }

    private void login(Long userId, Long unitId) {
        LoginUser user = new LoginUser(userId, "tester", "测试人", unitId,
                List.of("UNIT_OWNER"), List.of("training:fill"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
