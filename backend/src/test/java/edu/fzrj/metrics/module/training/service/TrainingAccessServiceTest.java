package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.training.entity.TrainingProject;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.entity.TrainingSheetStaff;
import edu.fzrj.metrics.module.training.mapper.TrainingProjectMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetStaffMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingAccessServiceTest {

    @Mock private TrainingSheetMapper sheetMapper;
    @Mock private TrainingProjectMapper projectMapper;
    @Mock private TrainingSheetStaffMapper staffMapper;
    @InjectMocks private TrainingAccessService accessService;

    @BeforeAll
    static void initMybatis() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingSheet.class);
        TableInfoHelper.initTableInfo(assistant, TrainingProject.class);
        TableInfoHelper.initTableInfo(assistant, TrainingSheetStaff.class);
    }

    @Test
    void 纯干事分配后可读可填且移除后立即失效() {
        TrainingSheet sheet = sheet(1L, 99L);
        LoginUser staff = user(List.of("STAFF"));

        when(staffMapper.selectCount(any())).thenReturn(1L);
        assertTrue(accessService.canRead(sheet, staff));
        assertTrue(accessService.canFill(sheet, staff));

        when(staffMapper.selectCount(any())).thenReturn(0L);
        assertFalse(accessService.canRead(sheet, staff));
        assertFalse(accessService.canFill(sheet, staff));
        BizException error = assertThrows(BizException.class,
                () -> accessService.requireReadAccess(sheet, staff));
        assertEquals(20005, error.getErrorCode().getCode());
    }

    @Test
    void 多角色填报范围是本人负责表与已分配表的并集() {
        TrainingSheet owned = sheet(1L, 100L);
        TrainingSheet assigned = sheet(2L, 99L);
        TrainingSheet unrelated = sheet(3L, 98L);
        LoginUser user = user(List.of("STAFF", "UNIT_OWNER"));

        assertTrue(accessService.canFill(owned, user));
        when(staffMapper.selectCount(any())).thenReturn(1L, 0L);
        assertTrue(accessService.canFill(assigned, user));
        assertFalse(accessService.canFill(unrelated, user));
    }

    @Test
    void 项目附件读取沿用实时分配且项目或采集表删除后拒绝() {
        TrainingProject project = new TrainingProject();
        project.setId(10L);
        project.setSheetId(1L);
        TrainingSheet sheet = sheet(1L, 99L);
        LoginUser staff = user(List.of("STAFF"));
        when(projectMapper.selectOne(any())).thenReturn(project);
        when(sheetMapper.selectOne(any())).thenReturn(sheet);
        when(staffMapper.selectCount(any())).thenReturn(0L);

        BizException error = assertThrows(BizException.class,
                () -> accessService.requireProjectReadAccess(10L, staff));

        assertEquals(20005, error.getErrorCode().getCode());
    }

    @Test
    void 有效分配查询排除已删除采集表和计划() throws Exception {
        org.apache.ibatis.annotations.Select select = TrainingSheetStaffMapper.class
                .getMethod("selectValidSheetIds", Long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);

        String sql = String.join(" ", select.value());

        assertTrue(sql.contains("ts.deleted = 0"));
        assertTrue(sql.contains("p.deleted = 0"));
        assertTrue(sql.contains("p.plan_type = 'TRAINING'"));
    }

    private static TrainingSheet sheet(Long id, Long ownerId) {
        TrainingSheet sheet = new TrainingSheet();
        sheet.setId(id);
        sheet.setPlanId(5L);
        sheet.setUnitId(14L);
        sheet.setOwnerId(ownerId);
        return sheet;
    }

    private static LoginUser user(List<String> roles) {
        return new LoginUser(100L, "staff", "干事", 14L, roles, List.of("training:fill"));
    }
}
