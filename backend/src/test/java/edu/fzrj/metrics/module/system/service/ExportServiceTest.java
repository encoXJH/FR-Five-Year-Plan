package edu.fzrj.metrics.module.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.module.file.service.MaterialService;
import edu.fzrj.metrics.module.file.service.SignedUrlService;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.dto.ExportDtos;
import edu.fzrj.metrics.module.system.entity.ExportJob;
import edu.fzrj.metrics.module.system.mapper.ExportJobMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock private ExportJobMapper exportJobMapper;
    @Mock private ExportJobExecutor exportJobExecutor;
    @Mock private PlanService planService;
    @Mock private DataScopeService dataScopeService;
    @Mock private SignedUrlService signedUrlService;
    @Mock private MaterialService materialService;

    private MockedStatic<SecurityUtils> security;

    @AfterEach
    void tearDown() {
        if (security != null) security.close();
    }

    @Test
    void 材料ZIP创建时去重并固定已授权文件ID() throws Exception {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getLoginUser).thenReturn(
                new LoginUser(1L, "admin", "管理员", null, List.of("ADMIN"), List.of("material:view")));
        ObjectMapper objectMapper = new ObjectMapper();
        ExportService exportService = new ExportService(exportJobMapper, exportJobExecutor, planService,
                dataScopeService, signedUrlService, materialService, objectMapper);
        AtomicReference<ExportJob> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            ExportJob job = invocation.getArgument(0);
            job.setId(99L);
            stored.set(job);
            return null;
        }).when(exportJobMapper).insert(any(ExportJob.class));
        when(exportJobMapper.selectById(99L)).thenAnswer(invocation -> stored.get());
        ExportDtos.CreateRequest request = new ExportDtos.CreateRequest();
        request.setType("MATERIAL_ZIP");
        request.getParams().put("fileIds", List.of("20", "20", 21));

        ExportDtos.JobView result = exportService.create(request);

        assertEquals(99L, result.id());
        assertEquals("MATERIAL_ZIP", result.type());
        verify(materialService).requireExportable(List.of(20L, 21L));
        verify(exportJobExecutor).run(99L);
        assertEquals("[\"20\",\"21\"]", objectMapper.readTree(stored.get().getParams()).get("fileIds").toString());
    }
}
