package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.entity.TrainingSheetStaff;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetStaffMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingStaffServiceTest {

    @Mock private TrainingSheetMapper sheetMapper;
    @Mock private TrainingSheetStaffMapper staffMapper;
    @Mock private TaskStaffMapper taskStaffMapper;
    @Mock private PlanMapper planMapper;
    @Mock private TrainingService trainingService;
    @Mock private TrainingAccessService accessService;
    @InjectMocks private TrainingStaffService staffService;

    @BeforeAll
    static void initMybatis() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingSheet.class);
        TableInfoHelper.initTableInfo(assistant, TrainingSheetStaff.class);
        TableInfoHelper.initTableInfo(assistant, Plan.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 多人分配自动去重且仅实际变化推进采集表版本() {
        TrainingSheet sheet = sheet();
        SysUser adminCreatedStaff = user(100L);
        adminCreatedStaff.setOwnerUserId(88L);
        adminCreatedStaff.setCreatedBy(99L);
        prepareSuccessfulAssign(sheet, List.of(), List.of(adminCreatedStaff, user(101L)));
        TrainingDtos.StaffPutRequest request = request(0, 100L, 100L, 101L);

        TrainingDtos.StaffView result = staffService.assign(1L, request);

        ArgumentCaptor<TrainingSheetStaff> inserted = ArgumentCaptor.forClass(TrainingSheetStaff.class);
        verify(staffMapper, times(2)).insert(inserted.capture());
        assertEquals(List.of(100L, 101L), inserted.getAllValues().stream()
                .map(TrainingSheetStaff::getStaffId).toList());
        verify(sheetMapper).updateById(sheet);
        assertEquals(List.of(100L, 101L), result.staff().stream().map(staff -> staff.id()).toList());
        assertEquals(List.of(100L, 101L), result.assignableStaff().stream().map(staff -> staff.id()).toList());
        assertTrue(result.canAssignStaff());
    }

    @Test
    void 重复保存相同干事不更新采集表版本() {
        TrainingSheetStaff existing = new TrainingSheetStaff();
        existing.setSheetId(1L);
        existing.setStaffId(100L);
        TrainingSheet sheet = sheet();
        prepareSuccessfulAssign(sheet, List.of(existing), List.of(user(100L)));
        when(staffMapper.selectStaffBySheetId(1L)).thenReturn(List.of(user(100L)));

        staffService.assign(1L, request(0, 100L, 100L));

        verify(staffMapper, never()).insert(any(TrainingSheetStaff.class));
        verify(staffMapper, never()).delete(any());
        verify(sheetMapper, never()).updateById(any(TrainingSheet.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"跨学院干事", "非纯干事", "已停用干事"})
    void 候选人查询之外的账号不能分配(String scenario) {
        TrainingSheet sheet = sheet();
        when(trainingService.requireSheetForUpdate(1L)).thenReturn(sheet);
        when(staffMapper.selectList(any())).thenReturn(List.of());
        when(taskStaffMapper.selectAssignableStaff(14L)).thenReturn(List.of(user(100L)));
        login();

        BizException error = assertThrows(BizException.class,
                () -> staffService.assign(1L, request(0, 101L)), scenario);

        assertEquals(10001, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("不属于采集表学院")
                || error.getMessage().contains("不是纯干事") || error.getMessage().contains("未启用"));
        verify(staffMapper, never()).insert(any(TrainingSheetStaff.class));
        verify(sheetMapper, never()).updateById(any(TrainingSheet.class));
    }

    @Test
    void 分配版本冲突时不改关系() {
        TrainingSheet sheet = sheet();
        when(trainingService.requireSheetForUpdate(1L)).thenReturn(sheet);
        doThrow(new BizException(ErrorCode.VERSION_CONFLICT)).when(trainingService).requireVersion(sheet, 1);
        login();

        BizException error = assertThrows(BizException.class,
                () -> staffService.assign(1L, request(1, 100L)));

        assertEquals(10005, error.getErrorCode().getCode());
        verifyNoInteractions(staffMapper, taskStaffMapper);
    }

    @Test
    void 归档采集表禁止分配并返回30002() {
        doThrow(new BizException(ErrorCode.PLAN_ARCHIVED)).when(trainingService).requireSheetForUpdate(1L);
        login();

        BizException error = assertThrows(BizException.class,
                () -> staffService.assign(1L, request(0, 100L)));

        assertEquals(30002, error.getErrorCode().getCode());
        verifyNoInteractions(staffMapper, taskStaffMapper);
    }

    @Test
    void 非采集表负责人分配返回20005() {
        TrainingSheet sheet = sheet();
        when(trainingService.requireSheetForUpdate(1L)).thenReturn(sheet);
        doThrow(new BizException(ErrorCode.DATA_SCOPE_DENIED))
                .when(accessService).requireStaffManager(any(), any());
        login();

        BizException error = assertThrows(BizException.class,
                () -> staffService.assign(1L, request(0, 100L)));

        assertEquals(20005, error.getErrorCode().getCode());
        verifyNoInteractions(staffMapper, taskStaffMapper);
    }

    private void prepareSuccessfulAssign(TrainingSheet sheet, List<TrainingSheetStaff> existing,
                                         List<SysUser> assignable) {
        when(trainingService.requireSheetForUpdate(1L)).thenReturn(sheet);
        when(accessService.requireSheet(1L)).thenReturn(sheet);
        when(accessService.canAssignStaff(any(), any(), any())).thenReturn(true);
        when(planMapper.selectOne(any())).thenReturn(plan());
        when(staffMapper.selectList(any())).thenReturn(existing);
        when(staffMapper.selectStaffBySheetId(1L)).thenReturn(assignable);
        when(taskStaffMapper.selectAssignableStaff(14L)).thenReturn(assignable);
        org.mockito.Mockito.lenient().when(sheetMapper.updateById(sheet)).thenReturn(1);
        login();
    }

    private static TrainingSheet sheet() {
        TrainingSheet sheet = new TrainingSheet();
        sheet.setId(1L);
        sheet.setPlanId(2L);
        sheet.setUnitId(14L);
        sheet.setOwnerId(99L);
        sheet.setVersion(0);
        return sheet;
    }

    private static Plan plan() {
        Plan plan = new Plan();
        plan.setId(2L);
        plan.setPlanType("TRAINING");
        plan.setStatus("ACTIVE");
        return plan;
    }

    private static SysUser user(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setName("干事" + id);
        return user;
    }

    private static TrainingDtos.StaffPutRequest request(int version, Long... staffIds) {
        TrainingDtos.StaffPutRequest request = new TrainingDtos.StaffPutRequest();
        request.setVersion(version);
        request.setStaffIds(List.of(staffIds));
        return request;
    }

    private static void login() {
        LoginUser user = new LoginUser(99L, "owner", "负责人", 14L,
                List.of("UNIT_OWNER"), List.of("training:fill"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
