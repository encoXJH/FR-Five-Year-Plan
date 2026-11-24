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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    @InjectMocks private TrainingService trainingService;

    @BeforeAll
    static void initMybatis() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Plan.class);
        TableInfoHelper.initTableInfo(assistant, TrainingSheet.class);
        TableInfoHelper.initTableInfo(assistant, TrainingProject.class);
        TableInfoHelper.initTableInfo(assistant, TrainingReview.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 跨学院负责人读取采集表返回20005() {
        TrainingSheet sheet = sheet(1L, 99L);
        when(sheetMapper.selectOne(any())).thenReturn(sheet);
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
    void 同学院干事可填写草稿但没有审核权限() {
        TrainingSheet sheet = sheet(1L, 99L);
        LoginUser staff = new LoginUser(100L, "staff", "干事", 14L,
                List.of("STAFF"), List.of("training:fill"));

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
    void 项目数值按语义比较而不受精度表示影响() {
        assertTrue(TrainingService.sameData(
                Map.of("c09", new BigDecimal("10.00"), "c12", new BigDecimal("8.0")),
                Map.of("c09", 10, "c12", 8)));
        assertFalse(TrainingService.sameData(
                Map.of("c09", new BigDecimal("10.00")), Map.of("c09", 10.01)));
        assertFalse(TrainingService.sameData(Map.of("c09", 10), Map.of("c10", 10)));
    }

    private void prepareUnarchivedSheet(TrainingSheet sheet) {
        when(sheetMapper.selectOne(any())).thenReturn(sheet);
        when(planMapper.selectByIdForUpdate(sheet.getPlanId())).thenReturn(trainingPlan("ACTIVE"));
        when(sheetMapper.selectByIdForUpdate(sheet.getId())).thenReturn(sheet);
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
