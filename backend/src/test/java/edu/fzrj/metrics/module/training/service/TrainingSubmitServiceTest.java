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
import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import edu.fzrj.metrics.module.training.entity.TrainingProject;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingSubmitServiceTest {

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
    @Spy @InjectMocks private TrainingService trainingService;

    @BeforeAll
    static void initMybatis() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingSheet.class);
        TableInfoHelper.initTableInfo(assistant, TrainingProject.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 干事提交进入学院审核() throws Exception {
        TrainingSheet sheet = submitSheet();
        login(List.of("STAFF"));

        trainingService.submit(1L, request());

        assertEquals("PENDING_REVIEW", sheet.getStatus());
        assertEquals("STAFF", sheet.getSubmitterRole());
        assertEquals("UNIT_REVIEW", sheet.getCurrentReviewNode());
        assertEquals(1, sheet.getRevisionNo());
        assertEquals(100L, sheet.getSubmittedBy());
    }

    @Test
    void 负责人提交直接进入校级审核() throws Exception {
        TrainingSheet sheet = submitSheet();
        login(List.of("UNIT_OWNER"));

        trainingService.submit(1L, request());

        assertEquals("PENDING_REVIEW", sheet.getStatus());
        assertEquals("UNIT_OWNER", sheet.getSubmitterRole());
        assertEquals("LEADER_REVIEW", sheet.getCurrentReviewNode());
        assertEquals(1, sheet.getRevisionNo());
    }

    @Test
    void 导出只包含审核通过项目() {
        TrainingDtos.Detail detail = exportDetail();
        doReturn(detail).when(trainingService).detail(1L);
        when(excelService.build(any())).thenReturn(new byte[]{1});

        trainingService.export(1L);

        ArgumentCaptor<TrainingDtos.Detail> captor = ArgumentCaptor.forClass(TrainingDtos.Detail.class);
        org.mockito.Mockito.verify(excelService).build(captor.capture());
        assertEquals(List.of(10L), captor.getValue().projects().stream().map(TrainingDtos.Project::id).toList());
    }

    @Test
    void 没有审核通过项目时拒绝导出() {
        TrainingDtos.Detail detail = new TrainingDtos.Detail(exportDetail().sheet(), List.of(),
                List.of(exportProject(11L, "PENDING_REVIEW")), List.of());
        doReturn(detail).when(trainingService).detail(1L);

        BizException error = assertThrows(BizException.class, () -> trainingService.export(1L));

        assertEquals(60001, error.getErrorCode().getCode());
    }

    private TrainingDtos.Detail exportDetail() {
        TrainingDtos.SheetSummary sheet = new TrainingDtos.SheetSummary(1L, 2L, "培训采集",
                2025, 2026, 2024, 14L, 99L, "PENDING_REVIEW", 2, 1, null,
                "学院", "负责人", "UNIT_OWNER", "LEADER_REVIEW", 1, 100L, "干事", null, true, true);
        return new TrainingDtos.Detail(sheet, List.of(),
                List.of(exportProject(10L, "APPROVED"), exportProject(11L, "PENDING_REVIEW")), List.of());
    }

    private TrainingDtos.Project exportProject(Long id, String status) {
        return new TrainingDtos.Project(id, id.intValue(), java.util.Map.of(), status,
                "UNIT_OWNER", "PENDING_REVIEW".equals(status) ? "LEADER_REVIEW" : null,
                1, 100L, null, false, false, null);
    }

    private TrainingSheet submitSheet() throws Exception {
        TrainingSheet sheet = new TrainingSheet();
        sheet.setId(1L);
        sheet.setPlanId(2L);
        sheet.setUnitId(14L);
        sheet.setOwnerId(100L);
        sheet.setStatus("DRAFT");
        sheet.setRevisionNo(0);
        sheet.setVersion(0);
        Plan plan = new Plan();
        plan.setId(2L);
        plan.setPlanType("TRAINING");
        plan.setStatus("ACTIVE");
        when(sheetMapper.selectOne(any())).thenReturn(sheet);
        when(planMapper.selectByIdForUpdate(2L)).thenReturn(plan);
        when(sheetMapper.selectByIdForUpdate(1L)).thenReturn(sheet);
        when(sheetMapper.updateById(any(TrainingSheet.class))).thenReturn(1);
        TrainingProject project = new TrainingProject();
        project.setId(10L);
        project.setSheetId(1L);
        project.setRowNo(1);
        project.setDataJson(objectMapper.writeValueAsString(TrainingFieldSchemaTest.fullData()));
        project.setStatus("DRAFT");
        project.setRevisionNo(0);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        doReturn(new TrainingDtos.Detail(null, null, null, null)).when(trainingService).detail(1L);
        return sheet;
    }

    private TrainingDtos.VersionRequest request() {
        TrainingDtos.VersionRequest request = new TrainingDtos.VersionRequest();
        request.setVersion(0);
        request.setProjectIds(List.of(10L));
        return request;
    }

    private void login(List<String> roles) {
        LoginUser user = new LoginUser(100L, "tester", "测试人", 14L, roles, List.of("training:fill"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
