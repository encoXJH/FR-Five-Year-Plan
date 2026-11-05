package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUnitAdminServiceTest {

    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private TaskMapper taskMapper;
    @InjectMocks
    private OrgUnitAdminService orgUnitAdminService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
    }

    @BeforeEach
    void login() {
        LoginUser user = new LoginUser(99L, "admin", "管理员", null,
                List.of("ADMIN"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 新增执行单元忽略历史字母编码从01开始() {
        SystemDtos.OrgUnitView result = createWithExisting(List.of(unit("C01"), unit("S01")));

        assertEquals("01", result.code());
    }

    @Test
    void 新增执行单元按纯数字最大编码递增且不复用删除编号() {
        OrgUnit deleted = unit("03");
        deleted.setDeleted(103L);

        SystemDtos.OrgUnitView result = createWithExisting(
                List.of(unit("C01"), unit("01"), unit("02"), deleted));

        assertEquals("04", result.code());
    }

    private SystemDtos.OrgUnitView createWithExisting(List<OrgUnit> existing) {
        when(orgUnitMapper.selectList(any())).thenReturn(existing);
        when(orgUnitMapper.selectCount(any())).thenReturn(0L);
        AtomicReference<OrgUnit> persisted = new AtomicReference<>();
        doAnswer(invocation -> {
            OrgUnit unit = invocation.getArgument(0);
            unit.setId(999L);
            unit.setVersion(0);
            persisted.set(unit);
            return 1;
        }).when(orgUnitMapper).insert(any(OrgUnit.class));
        when(orgUnitMapper.selectById(999L)).thenAnswer(invocation -> persisted.get());
        SystemDtos.OrgUnitCreateRequest request = new SystemDtos.OrgUnitCreateRequest();
        request.setName("新增学院");
        request.setType("COLLEGE");
        request.setIsBAssignable(true);

        return orgUnitAdminService.create(request);
    }

    private OrgUnit unit(String code) {
        OrgUnit unit = new OrgUnit();
        unit.setCode(code);
        unit.setDeleted(0L);
        return unit;
    }
}
