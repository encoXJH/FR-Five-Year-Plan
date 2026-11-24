package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingReviewServiceTest {

    @Mock private TrainingSheetMapper sheetMapper;
    @Mock private TrainingReviewMapper reviewMapper;
    @Mock private TrainingProjectMapper projectMapper;
    @Mock private PlanMapper planMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private TrainingService trainingService;
    @InjectMocks private TrainingReviewService reviewService;

    @BeforeAll
    static void initMybatis() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingSheet.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 院审通过只转校审并留下审核记录和通知() {
        TrainingSheet sheet = sheet("UNIT_REVIEW");
        Plan plan = plan();
        TrainingProject project = prepareReview(sheet, plan);
        login(List.of("UNIT_OWNER"));

        reviewService.review(1L, request("APPROVED", "院审通过"));

        assertEquals("PENDING_REVIEW", project.getStatus());
        assertEquals("LEADER_REVIEW", project.getCurrentReviewNode());
        verify(trainingService).refreshSheetAggregate(sheet, 200L);
        ArgumentCaptor<TrainingReview> captor = ArgumentCaptor.forClass(TrainingReview.class);
        verify(reviewMapper).insert(captor.capture());
        assertEquals("UNIT_REVIEW", captor.getValue().getReviewNode());
        assertEquals(10L, captor.getValue().getProjectId());
        assertEquals(1, captor.getValue().getRevisionNo());
        verify(notificationMapper).insert(any(Notification.class));
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @Test
    void 校审通过后采集表和计划进入最终状态() {
        TrainingSheet sheet = sheet("LEADER_REVIEW");
        Plan plan = plan();
        TrainingProject project = prepareReview(sheet, plan);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);
        login(List.of("LEADER"));

        reviewService.review(1L, request("APPROVED", null));

        assertEquals("APPROVED", project.getStatus());
        assertNull(project.getCurrentReviewNode());
        verify(trainingService).refreshSheetAggregate(sheet, 200L);
        assertEquals("ACTIVE", plan.getStatus());
        verify(planMapper).updateById(plan);
        verify(notificationMapper).insert(any(Notification.class));
    }

    @Test
    void 驳回必须填写意见() {
        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, request("REJECTED", " ")));

        assertEquals(60004, error.getErrorCode().getCode());
        verifyNoInteractions(sheetMapper, reviewMapper, projectMapper, planMapper, notificationMapper);
    }

    @Test
    void 非当前节点审核人返回20005() {
        TrainingSheet sheet = sheet("UNIT_REVIEW");
        when(trainingService.requireSheetForUpdate(1L)).thenReturn(sheet);
        when(trainingService.requireProject(1L, 10L)).thenReturn(project("UNIT_REVIEW"));
        when(trainingService.canReview(any(), any(), any())).thenReturn(false);
        login(List.of("UNIT_OWNER"));

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, request("APPROVED", null)));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(reviewMapper, never()).insert(any(TrainingReview.class));
    }

    @Test
    void 归档后审核返回30002且不写入审核数据() {
        when(trainingService.requireSheetForUpdate(1L))
                .thenThrow(new BizException(ErrorCode.PLAN_ARCHIVED));
        login(List.of("LEADER"));

        BizException error = assertThrows(BizException.class,
                () -> reviewService.review(1L, request("APPROVED", null)));

        assertEquals(30002, error.getErrorCode().getCode());
        verify(trainingService, never()).requireProject(any(), any());
        verifyNoInteractions(reviewMapper, projectMapper, notificationMapper);
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @Test
    void 待办批量过滤已归档和已删除培训计划() {
        TrainingSheet active = sheet("LEADER_REVIEW");
        TrainingSheet archived = sheet("LEADER_REVIEW");
        archived.setId(3L);
        archived.setPlanId(3L);
        TrainingSheet deleted = sheet("LEADER_REVIEW");
        deleted.setId(4L);
        deleted.setPlanId(4L);
        Plan activePlan = plan();
        activePlan.setStatus("ACTIVE");
        activePlan.setDeleted(0L);
        Plan archivedPlan = plan();
        archivedPlan.setId(3L);
        archivedPlan.setStatus("ARCHIVED");
        Plan deletedPlan = plan();
        deletedPlan.setId(4L);
        deletedPlan.setDeleted(4L);
        when(sheetMapper.selectList(any())).thenReturn(List.of(active, archived, deleted));
        when(planMapper.selectBatchIds(any())).thenReturn(List.of(activePlan, archivedPlan, deletedPlan));
        when(trainingService.canReview(any(), any())).thenReturn(true);
        when(trainingService.summaries(List.of(active))).thenReturn(List.of());
        login(List.of("LEADER"));
        LoginUser user = (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        assertEquals(List.of(), reviewService.actionableSummaries(user));

        verify(trainingService).canReview(active, user);
        verify(trainingService).summaries(List.of(active));
    }

    @Test
    void 审核结束节点和汇总字段允许更新为null() throws NoSuchFieldException {
        TableField projectNode = TrainingProject.class.getDeclaredField("currentReviewNode")
                .getAnnotation(TableField.class);
        TableField sheetNode = TrainingSheet.class.getDeclaredField("currentReviewNode")
                .getAnnotation(TableField.class);
        TableField submittedAt = TrainingSheet.class.getDeclaredField("submittedAt")
                .getAnnotation(TableField.class);
        TableField submittedBy = TrainingSheet.class.getDeclaredField("submittedBy")
                .getAnnotation(TableField.class);

        assertEquals(FieldStrategy.ALWAYS, projectNode.updateStrategy());
        assertEquals(FieldStrategy.ALWAYS, sheetNode.updateStrategy());
        assertEquals(FieldStrategy.ALWAYS, submittedAt.updateStrategy());
        assertEquals(FieldStrategy.ALWAYS, submittedBy.updateStrategy());
    }

    private TrainingProject prepareReview(TrainingSheet sheet, Plan plan) {
        TrainingProject project = project(sheet.getCurrentReviewNode());
        when(trainingService.requireSheetForUpdate(1L)).thenReturn(sheet);
        when(trainingService.requireProject(1L, 10L)).thenReturn(project);
        when(trainingService.canReview(any(), any(), any())).thenReturn(true);
        when(trainingService.requireTrainingPlan(2L)).thenReturn(plan);
        doReturn(new TrainingDtos.Detail(null, null, null, null)).when(trainingService).detail(1L);
        return project;
    }

    private TrainingProject project(String node) {
        TrainingProject project = new TrainingProject();
        project.setId(10L);
        project.setSheetId(1L);
        project.setRowNo(1);
        project.setStatus("PENDING_REVIEW");
        project.setSubmitterRole("STAFF");
        project.setCurrentReviewNode(node);
        project.setRevisionNo(1);
        project.setSubmittedBy(100L);
        project.setSubmittedAt(java.time.LocalDateTime.now());
        return project;
    }

    private TrainingSheet sheet(String node) {
        TrainingSheet sheet = new TrainingSheet();
        sheet.setId(1L);
        sheet.setPlanId(2L);
        sheet.setUnitId(14L);
        sheet.setOwnerId(99L);
        sheet.setStatus("PENDING_REVIEW");
        sheet.setSubmitterRole("STAFF");
        sheet.setCurrentReviewNode(node);
        sheet.setRevisionNo(1);
        sheet.setSubmittedBy(100L);
        sheet.setVersion(2);
        return sheet;
    }

    private Plan plan() {
        Plan plan = new Plan();
        plan.setId(2L);
        plan.setName("培训采集计划");
        plan.setPlanType("TRAINING");
        plan.setStatus("DRAFT");
        plan.setVersion(0);
        return plan;
    }

    private TrainingDtos.ReviewRequest request(String result, String comment) {
        TrainingDtos.ReviewRequest request = new TrainingDtos.ReviewRequest();
        request.setProjectId(10L);
        request.setVersion(2);
        request.setResult(result);
        request.setComment(comment);
        return request;
    }

    private void login(List<String> roles) {
        LoginUser user = new LoginUser(200L, "reviewer", "审核人", 14L,
                roles, List.of("submission:review"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
