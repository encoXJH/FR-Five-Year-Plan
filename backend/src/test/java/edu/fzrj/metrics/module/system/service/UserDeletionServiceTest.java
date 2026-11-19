package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.entity.ApprovalStep;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentMapper;
import edu.fzrj.metrics.module.adjustment.mapper.ApprovalStepMapper;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.MajorGroup;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.entity.UserUnit;
import edu.fzrj.metrics.module.system.mapper.MajorGroupMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.mapper.UserUnitMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskStaff;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskStaffMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeletionServiceTest {

    private static final long USER_ID = 1L;
    private static final long OPERATOR_ID = 99L;
    private static final long PLAN_ID = 11L;

    @Mock private UserAdminService userAdminService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private UserUnitMapper userUnitMapper;
    @Mock private TaskStaffMapper taskStaffMapper;
    @Mock private PlanMapper planMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private IndicatorMapper indicatorMapper;
    @Mock private DirectionMapper directionMapper;
    @Mock private MajorGroupMapper majorGroupMapper;
    @Mock private SubmissionMapper submissionMapper;
    @Mock private AdjustmentMapper adjustmentMapper;
    @Mock private ApprovalStepMapper approvalStepMapper;
    @InjectMocks private UserDeletionService userDeletionService;

    private MockedStatic<SecurityUtils> security;
    private SysUser target;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, UserRole.class);
        TableInfoHelper.initTableInfo(assistant, UserUnit.class);
        TableInfoHelper.initTableInfo(assistant, TaskStaff.class);
        TableInfoHelper.initTableInfo(assistant, Plan.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, Direction.class);
        TableInfoHelper.initTableInfo(assistant, MajorGroup.class);
        TableInfoHelper.initTableInfo(assistant, Submission.class);
        TableInfoHelper.initTableInfo(assistant, Adjustment.class);
        TableInfoHelper.initTableInfo(assistant, ApprovalStep.class);
    }

    @BeforeEach
    void setUp() {
        target = new SysUser();
        target.setId(USER_ID);
        target.setUsername("staff");
        target.setName("干事");
        target.setPhone("13812345678");
        target.setUnitId(101L);
        target.setOwnerUserId(88L);
        target.setCreatedBy(77L);
        target.setVersion(1);
        target.setDeleted(0L);
        target.setIsEnabled(true);
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(OPERATOR_ID);
    }

    @AfterEach
    void tearDown() {
        security.close();
    }

    @Test
    void 删除成功时逻辑删除并清理账号关系且保留历史字段() {
        mockTarget();
        stubNoReferences();
        when(sysUserMapper.updateById(target)).thenReturn(1);

        userDeletionService.delete(USER_ID, request(1));

        assertEquals(USER_ID, target.getDeleted());
        assertEquals(false, target.getIsEnabled());
        assertEquals(OPERATOR_ID, target.getUpdatedBy());
        assertEquals(88L, target.getOwnerUserId());
        assertEquals(77L, target.getCreatedBy());
        assertEquals(101L, target.getUnitId());
        verify(userRoleMapper).delete(any());
        verify(userUnitMapper).delete(any());
        verify(taskStaffMapper).delete(any());
        verify(userAdminService).revokeAllRefreshTokens(USER_ID);
    }

    @Test
    void 删除审计快照复用脱敏用户视图() {
        mockTarget();
        SystemDtos.UserItem masked = new SystemDtos.UserItem(
                USER_ID, "staff", "干事", "138****5678", null,
                101L, "学院", List.of("STAFF"), true, null, 1, List.of(101L), 88L);
        when(userAdminService.toItems(List.of(target))).thenReturn(List.of(masked));

        SystemDtos.UserItem result = userDeletionService.snapshot(USER_ID);

        assertSame(masked, result);
        assertEquals("138****5678", result.phone());
    }

    @Test
    void 请求版本不一致返回10005且不删除() {
        mockTarget();

        BizException error = assertThrows(BizException.class,
                () -> userDeletionService.delete(USER_ID, request(0)));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verify(userAdminService, never()).revokeAllRefreshTokens(any());
    }

    @Test
    void 更新时版本冲突返回10005且不清理关系() {
        mockTarget();
        stubNoReferences();
        when(sysUserMapper.updateById(target)).thenReturn(0);

        BizException error = assertThrows(BizException.class,
                () -> userDeletionService.delete(USER_ID, request(1)));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(userRoleMapper, never()).delete(any());
        verify(userUnitMapper, never()).delete(any());
        verify(taskStaffMapper, never()).delete(any());
        verify(userAdminService, never()).revokeAllRefreshTokens(any());
    }

    @Test
    void 禁止删除当前登录账号() {
        mockTarget();
        security.when(SecurityUtils::getUserId).thenReturn(USER_ID);

        BizException error = assertThrows(BizException.class,
                () -> userDeletionService.delete(USER_ID, request(1)));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void 禁止删除最后一名启用管理员() {
        mockTarget();
        stubNoReferences();
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role(USER_ID, "ADMIN")));

        BizException error = assertThrows(BizException.class,
                () -> userDeletionService.delete(USER_ID, request(1)));

        assertEquals(10001, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("最后一名"));
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void 存在其他启用管理员时允许删除() {
        mockTarget();
        stubNoReferences();
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                role(USER_ID, "ADMIN"), role(2L, "ADMIN")));
        when(sysUserMapper.selectCount(any())).thenReturn(1L, 0L);
        when(sysUserMapper.updateById(target)).thenReturn(1);

        userDeletionService.delete(USER_ID, request(1));

        verify(sysUserMapper).updateById(target);
        verify(userAdminService).revokeAllRefreshTokens(USER_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "indicator", "direction", "majorGroup", "subordinate",
            "draft", "pendingSubmission", "adjustmentApplicant"})
    void 有效业务引用阻断删除(String blockerType) {
        mockTarget();
        stubNoReferences();
        stubActivePlan();
        String expectedMessage = switch (blockerType) {
            case "task" -> {
                when(taskMapper.selectCount(any())).thenReturn(1L);
                yield "有效任务负责人";
            }
            case "indicator" -> {
                when(indicatorMapper.selectCount(any())).thenReturn(1L);
                yield "指标负责人";
            }
            case "direction" -> {
                when(directionMapper.selectCount(any())).thenReturn(1L);
                yield "S 级总负责人";
            }
            case "majorGroup" -> {
                when(majorGroupMapper.selectCount(any())).thenReturn(1L);
                yield "启用专业群负责人";
            }
            case "subordinate" -> {
                when(sysUserMapper.selectCount(any())).thenReturn(1L);
                yield "下属干事";
            }
            case "draft" -> {
                when(submissionMapper.selectCount(any())).thenReturn(1L, 0L);
                yield "草稿填报创建人";
            }
            case "pendingSubmission" -> {
                when(submissionMapper.selectCount(any())).thenReturn(0L, 1L);
                yield "待审核填报提交人";
            }
            case "adjustmentApplicant" -> {
                when(adjustmentMapper.selectList(any())).thenReturn(List.of(
                        adjustment(31L, USER_ID, "OWNER_REVIEW")));
                yield "待审批调整申请人";
            }
            default -> throw new IllegalArgumentException(blockerType);
        };

        BizException error = assertThrows(BizException.class,
                () -> userDeletionService.delete(USER_ID, request(1)));

        assertEquals(40008, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains(expectedMessage));
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verify(userAdminService, never()).revokeAllRefreshTokens(any());
    }

    @Test
    void 历史步骤审批人不阻断删除() {
        mockTarget();
        stubNoReferences();
        stubActivePlan();
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(
                adjustment(31L, 88L, "LEADER_APPROVAL")));
        ApprovalStep historical = step(31L, "OWNER_REVIEW");
        historical.setDecision("APPROVE");
        when(approvalStepMapper.selectList(any())).thenReturn(List.of(historical));
        when(sysUserMapper.updateById(target)).thenReturn(1);

        userDeletionService.delete(USER_ID, request(1));

        verify(sysUserMapper).updateById(target);
        verify(userAdminService).revokeAllRefreshTokens(USER_ID);
    }

    @Test
    void 当前步骤审批人阻断删除且只查询未决定步骤() {
        mockTarget();
        stubNoReferences();
        stubActivePlan();
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(
                adjustment(31L, 88L, "LEADER_APPROVAL")));
        when(approvalStepMapper.selectList(any())).thenReturn(List.of(step(31L, "LEADER_APPROVAL")));

        BizException error = assertThrows(BizException.class,
                () -> userDeletionService.delete(USER_ID, request(1)));

        assertEquals(40008, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("当前步骤审批人"));
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(approvalStepMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().toUpperCase().contains("DECISION IS NULL"));
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void 归档计划不阻断相关业务引用() {
        mockTarget();
        stubNoReferences();
        when(sysUserMapper.updateById(target)).thenReturn(1);

        userDeletionService.delete(USER_ID, request(1));

        verify(taskMapper, never()).selectCount(any());
        verify(indicatorMapper, never()).selectCount(any());
        verify(directionMapper, never()).selectCount(any());
        verify(submissionMapper, never()).selectCount(any());
        verify(adjustmentMapper, never()).selectList(any());
        ArgumentCaptor<LambdaQueryWrapper> planCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(planMapper).selectList(planCaptor.capture());
        planCaptor.getValue().getSqlSegment();
        assertTrue(planCaptor.getValue().getParamNameValuePairs().containsValue("ARCHIVED"));
    }

    @Test
    void 在途查询限定任务终态填报状态和三种调整状态() {
        mockTarget();
        stubNoReferences();
        stubActivePlan();
        when(sysUserMapper.updateById(target)).thenReturn(1);

        userDeletionService.delete(USER_ID, request(1));

        ArgumentCaptor<LambdaQueryWrapper> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectCount(taskCaptor.capture());
        taskCaptor.getValue().getSqlSegment();
        String taskParams = taskCaptor.getValue().getParamNameValuePairs().toString();
        assertTrue(taskParams.contains("CLOSED"));
        assertTrue(taskParams.contains("TRANSFERRED_OUT"));
        assertTrue(taskParams.contains(String.valueOf(PLAN_ID)));

        ArgumentCaptor<LambdaQueryWrapper> submissionCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(submissionMapper, org.mockito.Mockito.times(2)).selectCount(submissionCaptor.capture());
        submissionCaptor.getAllValues().forEach(LambdaQueryWrapper::getSqlSegment);
        assertTrue(submissionCaptor.getAllValues().get(0).getParamNameValuePairs().containsValue("DRAFT"));
        assertTrue(submissionCaptor.getAllValues().get(1).getParamNameValuePairs().containsValue("PENDING_REVIEW"));

        ArgumentCaptor<LambdaQueryWrapper> adjustmentCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectList(adjustmentCaptor.capture());
        adjustmentCaptor.getValue().getSqlSegment();
        String adjustmentParams = adjustmentCaptor.getValue().getParamNameValuePairs().toString();
        List<String> expectedStatuses = List.of(
                "PENDING_OWNER_REVIEW", "PENDING_RECEIVER_CONFIRM", "PENDING_LEADER_APPROVAL");
        expectedStatuses.forEach(status -> assertTrue(adjustmentParams.contains(status)));
    }

    private SysUser mockTarget() {
        when(userAdminService.requireUser(USER_ID)).thenReturn(target);
        return target;
    }

    private void stubNoReferences() {
        lenient().when(userRoleMapper.selectList(any())).thenReturn(List.of());
        lenient().when(planMapper.selectList(any())).thenReturn(List.of());
        lenient().when(adjustmentMapper.selectList(any())).thenReturn(List.of());
        lenient().when(approvalStepMapper.selectList(any())).thenReturn(List.of());
        lenient().when(sysUserMapper.selectCount(any())).thenReturn(0L);
        lenient().when(taskMapper.selectCount(any())).thenReturn(0L);
        lenient().when(indicatorMapper.selectCount(any())).thenReturn(0L);
        lenient().when(directionMapper.selectCount(any())).thenReturn(0L);
        lenient().when(majorGroupMapper.selectCount(any())).thenReturn(0L);
        lenient().when(submissionMapper.selectCount(any())).thenReturn(0L);
    }

    private void stubActivePlan() {
        Plan plan = new Plan();
        plan.setId(PLAN_ID);
        plan.setStatus("ACTIVE");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
    }

    private static SystemDtos.UserDeleteRequest request(int version) {
        SystemDtos.UserDeleteRequest request = new SystemDtos.UserDeleteRequest();
        request.setVersion(version);
        request.setReason("人员离岗，确认删除");
        return request;
    }

    private static UserRole role(Long userId, String role) {
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRole(role);
        return userRole;
    }

    private static Adjustment adjustment(Long id, Long applicantId, String currentNode) {
        Adjustment adjustment = new Adjustment();
        adjustment.setId(id);
        adjustment.setApplicantId(applicantId);
        adjustment.setCurrentNode(currentNode);
        return adjustment;
    }

    private static ApprovalStep step(Long adjustmentId, String node) {
        ApprovalStep step = new ApprovalStep();
        step.setAdjustmentId(adjustmentId);
        step.setNode(node);
        return step;
    }
}
