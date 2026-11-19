package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.submission.dto.YearUploadDtos;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.service.TaskReadScopeService;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YearUploadServiceTest {

    @Mock private SubmissionMapper submissionMapper;
    @Mock private IndicatorMapper indicatorMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private ReviewService reviewService;
    @Mock private TaskReadScopeService taskReadScopeService;
    @InjectMocks private YearUploadService yearUploadService;

    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Submission.class);
    }

    @AfterEach
    void tearDown() {
        if (security != null) security.close();
    }

    @Test
    void 兼任校级和学院负责人时已上传清单按并集查询() {
        login();
        when(reviewService.ownedIndicatorIdsWithChildren(2L)).thenReturn(List.of(301L, 302L));
        when(submissionMapper.selectList(any())).thenReturn(List.of());

        yearUploadService.list(4L, 2026);

        LambdaQueryWrapper<Submission> wrapper = capturedSubmissionQuery();
        assertTrue(wrapper.getSqlSegment().contains("OR"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(301L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(100L));
    }

    @Test
    void 兼任负责人没有负责指标时仍保留本学院范围() {
        login();
        when(reviewService.ownedIndicatorIdsWithChildren(2L)).thenReturn(List.of());
        when(submissionMapper.selectList(any())).thenReturn(List.of());

        yearUploadService.list(4L, 2026);

        LambdaQueryWrapper<Submission> wrapper = capturedSubmissionQuery();
        assertTrue(wrapper.getSqlSegment().contains("unit_id"), wrapper::getSqlSegment);
    }

    @Test
    void 干事已上传清单仅按分配任务查询() {
        LoginUser user = new LoginUser(2L, "staff", "干事", 100L,
                List.of("STAFF"), List.of("submission:view"));
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getLoginUser).thenReturn(user);
        when(taskReadScopeService.staffTaskIds(user)).thenReturn(java.util.Set.of(7L));
        when(submissionMapper.selectList(any())).thenReturn(List.of());

        yearUploadService.list(4L, 2026);

        LambdaQueryWrapper<Submission> wrapper = capturedSubmissionQuery();
        assertTrue(wrapper.getSqlSegment().contains("task_id"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    }

    @Test
    void 已上传清单返回当前审核节点和提交来源() {
        LoginUser user = new LoginUser(1L, "admin", "管理员", null,
                List.of("ADMIN"), List.of("submission:view"));
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getLoginUser).thenReturn(user);
        Submission submission = new Submission();
        submission.setId(7L);
        submission.setTaskId(8L);
        submission.setIndicatorId(9L);
        submission.setUnitId(10L);
        submission.setSubmittedBy(2L);
        submission.setStatus("PENDING_REVIEW");
        submission.setCurrentReviewNode("UNIT_REVIEW");
        submission.setSubmitterRole("STAFF");
        when(submissionMapper.selectList(any())).thenReturn(List.of(submission));
        Indicator indicator = new Indicator();
        indicator.setId(9L);
        indicator.setCode("A01");
        indicator.setName("指标一");
        when(indicatorMapper.selectBatchIds(any())).thenReturn(List.of(indicator));
        Task task = new Task();
        task.setId(8L);
        task.setCode("A01-01");
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(task));
        SysUser submitter = new SysUser();
        submitter.setId(2L);
        submitter.setName("干事");
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(submitter));
        OrgUnit unit = new OrgUnit();
        unit.setId(10L);
        unit.setName("智能产业学院");
        when(orgUnitMapper.selectBatchIds(any())).thenReturn(List.of(unit));

        List<YearUploadDtos.UploadItem> result = yearUploadService.list(4L, 2026);

        assertEquals(1, result.size());
        assertEquals("PENDING_REVIEW", result.get(0).status());
        assertEquals("UNIT_REVIEW", result.get(0).currentReviewNode());
        assertEquals("STAFF", result.get(0).submitterRole());
    }

    private void login() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getLoginUser).thenReturn(new LoginUser(2L, "owner", "兼任负责人", 100L,
                List.of("SCHOOL_OWNER", "UNIT_OWNER"), List.of("submission:view")));
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Submission> capturedSubmissionQuery() {
        ArgumentCaptor<LambdaQueryWrapper<Submission>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(submissionMapper).selectList(captor.capture());
        return captor.getValue();
    }
}
