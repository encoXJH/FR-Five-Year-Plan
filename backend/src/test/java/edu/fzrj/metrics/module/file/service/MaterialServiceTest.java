package edu.fzrj.metrics.module.file.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.adjustment.mapper.AdjustmentMapper;
import edu.fzrj.metrics.module.file.dto.MaterialDtos;
import edu.fzrj.metrics.module.file.dto.MaterialQuery;
import edu.fzrj.metrics.module.file.dto.MaterialRow;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.file.mapper.MaterialQueryMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.milestone.mapper.MilestoneMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

    @Mock private FileRefMapper fileRefMapper;
    @Mock private FileInfoMapper fileInfoMapper;
    @Mock private IndicatorMapper indicatorMapper;
    @Mock private SubmissionMapper submissionMapper;
    @Mock private AdjustmentMapper adjustmentMapper;
    @Mock private MilestoneMapper milestoneMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private MaterialQueryMapper materialQueryMapper;
    @InjectMocks private MaterialService materialService;

    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FileInfo.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, edu.fzrj.metrics.module.task.entity.Task.class);
    }

    @AfterEach
    void tearDown() {
        if (security != null) security.close();
    }

    private void login(LoginUser user) {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getLoginUser).thenReturn(user);
    }

    @Test
    void 管理员可上传材料并绑定指标() {
        login(new LoginUser(1L, "admin", "管理员", null, List.of("ADMIN"), List.of("material:upload")));
        Indicator indicator = new Indicator();
        indicator.setId(10L);
        indicator.setPlanId(4L);
        indicator.setLevel("A");
        indicator.setDeleted(0L);
        FileInfo file = new FileInfo();
        file.setId(20L);
        file.setUploadedBy(1L);
        file.setDeleted(0L);
        when(indicatorMapper.selectOne(any())).thenReturn(indicator);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator));
        when(fileInfoMapper.selectOne(any())).thenReturn(file);
        when(fileRefMapper.selectCount(any())).thenReturn(0L);

        materialService.bind(new MaterialDtos.BindRequest(10L, List.of(20L)));

        verify(fileRefMapper).insert(any(FileRef.class));
        verify(fileInfoMapper).update(any(), any());
    }

    @Test
    void 学院用户不能绑定本学院未承接的指标() {
        login(new LoginUser(2L, "c01", "学院负责人", 100L, List.of("UNIT_OWNER"), List.of("material:upload")));
        Indicator indicator = new Indicator();
        indicator.setId(10L);
        indicator.setPlanId(4L);
        indicator.setLevel("A");
        indicator.setDeleted(0L);
        when(indicatorMapper.selectOne(any())).thenReturn(indicator);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class,
                () -> materialService.bind(new MaterialDtos.BindRequest(10L, List.of(20L))));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(fileRefMapper, never()).insert(any(FileRef.class));
    }

    @Test
    void 材料列表使用数据库分页并保留数据范围() {
        login(new LoginUser(2L, "c01", "学院负责人", 100L, List.of("UNIT_OWNER"), List.of("material:view")));
        MaterialRow row = new MaterialRow();
        row.setFileId(20L);
        row.setFileName("佐证.pdf");
        row.setSize(1024L);
        row.setCategory("PDF");
        row.setBizType("SUBMISSION");
        row.setIndicatorId(10L);
        row.setIndicatorCode("A01");
        row.setIndicatorName("关键产出指标");
        row.setUnitId(100L);
        row.setUnitCode("C01");
        row.setUnitName("智能产业学院");
        when(materialQueryMapper.countMaterials(any())).thenReturn(1L);
        when(materialQueryMapper.selectMaterials(any())).thenReturn(List.of(row));

        var result = materialService.list(4L, null, null, null, " 佐证 ", 2, 20);

        ArgumentCaptor<MaterialQuery> captor = ArgumentCaptor.forClass(MaterialQuery.class);
        verify(materialQueryMapper).selectMaterials(captor.capture());
        MaterialQuery query = captor.getValue();
        assertEquals(100L, query.unitScope());
        assertEquals("佐证", query.keyword());
        assertEquals(20, query.limit());
        assertEquals(20L, query.offset());
        assertEquals(1L, result.getTotal());
        assertEquals("A01", result.getItems().get(0).indicator().code());
        verify(fileRefMapper, never()).selectList(any());
    }
}
