package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.dto.FileInfoResponse;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.file.service.FileService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingAttachmentServiceTest {

    @Mock private FileRefMapper fileRefMapper;
    @Mock private FileInfoMapper fileInfoMapper;
    @Mock private FileService fileService;
    @InjectMocks private TrainingAttachmentService attachmentService;

    @BeforeAll
    static void initMybatis() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FileInfo.class);
        TableInfoHelper.initTableInfo(assistant, FileRef.class);
    }

    @Test
    void 新项目插入后可绑定本人临时培训附件并去重() {
        FileInfo file = file(50L, "TRAINING_ATTACHMENT", "TEMP");
        when(fileRefMapper.selectList(any())).thenReturn(List.of());
        when(fileRefMapper.selectCount(any())).thenReturn(0L);
        when(fileService.requireBindable(50L, "TRAINING_PROJECT", 100L)).thenReturn(file);

        boolean changed = attachmentService.replace(100L, List.of(50L, 50L));

        assertTrue(changed);
        ArgumentCaptor<FileRef> ref = ArgumentCaptor.forClass(FileRef.class);
        verify(fileRefMapper).insert(ref.capture());
        assertEquals("TRAINING_PROJECT", ref.getValue().getBizType());
        assertEquals(100L, ref.getValue().getBizId());
        assertEquals(50L, ref.getValue().getFileId());
        ArgumentCaptor<LambdaUpdateWrapper<FileInfo>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fileInfoMapper).update(any(), update.capture());
        assertTrue(update.getValue().getParamNameValuePairs().containsValue("BOUND"));
    }

    @Test
    void 他人临时文件不能绑定到培训项目() {
        when(fileRefMapper.selectList(any())).thenReturn(List.of());
        doThrow(new BizException(ErrorCode.DATA_SCOPE_DENIED))
                .when(fileService).requireBindable(50L, "TRAINING_PROJECT", 100L);

        BizException error = assertThrows(BizException.class,
                () -> attachmentService.replace(100L, List.of(50L)));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(fileRefMapper, never()).insert(any(FileRef.class));
    }

    @Test
    void 已跨项目或跨采集表绑定的文件不能再次绑定() {
        FileInfo file = file(50L, "TRAINING_ATTACHMENT", "BOUND");
        when(fileRefMapper.selectList(any())).thenReturn(List.of());
        when(fileService.requireBindable(50L, "TRAINING_PROJECT", 100L)).thenReturn(file);
        when(fileRefMapper.selectCount(any())).thenReturn(1L);

        BizException error = assertThrows(BizException.class,
                () -> attachmentService.replace(100L, List.of(50L)));

        assertEquals(10001, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("其他培训项目"));
        verify(fileRefMapper, never()).insert(any(FileRef.class));
    }

    @Test
    void 非培训用途文件即使本人上传也不能绑定() {
        FileInfo file = file(50L, "SUBMISSION_ATTACHMENT", "TEMP");
        when(fileRefMapper.selectList(any())).thenReturn(List.of());
        when(fileService.requireBindable(50L, "TRAINING_PROJECT", 100L)).thenReturn(file);

        BizException error = assertThrows(BizException.class,
                () -> attachmentService.replace(100L, List.of(50L)));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(fileRefMapper, never()).insert(any(FileRef.class));
    }

    @Test
    void 移除唯一引用后文件退回临时状态() {
        FileRef existing = ref(50L);
        when(fileRefMapper.selectList(any())).thenReturn(List.of(existing));
        when(fileRefMapper.selectCount(any())).thenReturn(0L);

        boolean changed = attachmentService.replace(100L, List.of());

        assertTrue(changed);
        verify(fileRefMapper).delete(any());
        ArgumentCaptor<LambdaUpdateWrapper<FileInfo>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fileInfoMapper).update(any(), update.capture());
        assertTrue(update.getValue().getParamNameValuePairs().containsValue("TEMP"));
    }

    @Test
    void 文件仍有其他引用时解绑不会退回临时状态() {
        when(fileRefMapper.selectList(any())).thenReturn(List.of(ref(50L)));
        when(fileRefMapper.selectCount(any())).thenReturn(1L);

        attachmentService.replace(100L, List.of());

        verify(fileRefMapper).delete(any());
        verify(fileInfoMapper, never()).update(any(), any());
    }

    @Test
    void 项目附件响应直接复用完整文件信息契约() {
        FileInfo file = file(50L, "TRAINING_ATTACHMENT", "BOUND");
        FileInfoResponse response = new FileInfoResponse(50L, "材料.pdf", 128L, "application/pdf",
                "PDF", "TRAINING_ATTACHMENT", "BOUND", null, null,
                new FileInfoResponse.UserRef(88L, "上传人"));
        when(fileRefMapper.selectList(any())).thenReturn(List.of(ref(50L)));
        when(fileInfoMapper.selectList(any())).thenReturn(List.of(file));
        when(fileService.toResponse(file)).thenReturn(response);

        var files = attachmentService.filesByProjectIds(List.of(100L));

        assertSame(response, files.get(100L).get(0));
        assertEquals("TRAINING_ATTACHMENT", files.get(100L).get(0).getBizType());
        assertEquals("BOUND", files.get(100L).get(0).getStatus());
        assertEquals(88L, files.get(100L).get(0).getUploadedBy().getId());
    }

    private static FileInfo file(Long id, String purpose, String status) {
        FileInfo file = new FileInfo();
        file.setId(id);
        file.setBizType(purpose);
        file.setStatus(status);
        return file;
    }

    private static FileRef ref(Long fileId) {
        FileRef ref = new FileRef();
        ref.setBizType("TRAINING_PROJECT");
        ref.setBizId(100L);
        ref.setFileId(fileId);
        ref.setSort(0);
        return ref;
    }
}
