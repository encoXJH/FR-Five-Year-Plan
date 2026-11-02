package edu.fzrj.metrics.module.file.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.Sha256;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.file.dto.FileInfoResponse;
import edu.fzrj.metrics.module.file.dto.MultipartInitRequest;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.entity.FileUploadSession;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.file.mapper.FileUploadSessionMapper;
import edu.fzrj.metrics.module.milestone.entity.Milestone;
import edu.fzrj.metrics.module.milestone.service.MilestoneService;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.service.SubmissionService;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock
    private FileInfoMapper fileInfoMapper;
    @Mock
    private FileUploadSessionMapper sessionMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private StorageService storageService;
    @Spy
    private FileTypeRegistry typeRegistry;
    @Spy
    private ObjectMapper objectMapper;
    @Mock
    private FileRefMapper fileRefMapper;
    @Mock
    private SubmissionService submissionService;
    @Mock
    private AdjustmentService adjustmentService;
    @Mock
    private MilestoneService milestoneService;
    @Mock
    private ObjectProvider<DirectStorageService> directStorageProvider;
    @Mock
    private DirectStorageService directStorageService;

    @InjectMocks
    private FileService fileService;

    @TempDir
    private Path partDir;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FileInfo.class);
        TableInfoHelper.initTableInfo(assistant, FileRef.class);
        TableInfoHelper.initTableInfo(assistant, FileUploadSession.class);
    }

    @BeforeEach
    void login() {
        LoginUser user = new LoginUser(99L, "user", "用户", 31L,
                List.of("UNIT_OWNER"), List.of("submission:submit"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 临时文件仅上传人可读() {
        FileInfo file = file("TEMP", 88L);
        when(fileInfoMapper.selectOne(any())).thenReturn(file);

        BizException error = assertThrows(BizException.class, () -> fileService.detail(1L));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(fileRefMapper, never()).selectList(any());
    }

    @Test
    void 临时文件上传人可以读取() {
        FileInfo file = file("TEMP", 99L);
        when(fileInfoMapper.selectOne(any())).thenReturn(file);

        assertSame(file, fileService.requireReadable(1L));
    }

    @Test
    void 本人上传的临时或已绑定文件都可绑定() {
        when(fileInfoMapper.selectOne(any()))
                .thenReturn(file("TEMP", 99L), file("BOUND", 99L));

        assertNotNull(fileService.requireBindable(1L, "SUBMISSION", 51L));
        assertNotNull(fileService.requireBindable(1L, "SUBMISSION", 52L));
        verify(fileRefMapper, never()).selectCount(any());
    }

    @Test
    void 相同内容按上传者去重并可绑定新业务() throws Exception {
        byte[] content = "%PDF-1.4\nshared".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile upload = new MockMultipartFile(
                "file", "shared.pdf", "application/pdf", content);
        when(fileInfoMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(0, InputStream.class).readAllBytes();
            return null;
        }).when(storageService).save(any(), any(), anyLong());
        doAnswer(invocation -> {
            invocation.getArgument(0, FileInfo.class).setId(2L);
            return 1;
        }).when(fileInfoMapper).insert(any(FileInfo.class));

        FileInfoResponse response = fileService.upload(upload, "SUBMISSION_ATTACHMENT");

        assertEquals(2L, response.getId());
        ArgumentCaptor<FileInfo> fileCaptor = ArgumentCaptor.forClass(FileInfo.class);
        verify(fileInfoMapper).insert(fileCaptor.capture());
        assertEquals(99L, fileCaptor.getValue().getUploadedBy());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<FileInfo>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(fileInfoMapper).selectList(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("uploaded_by"));
        when(fileInfoMapper.selectOne(any())).thenReturn(fileCaptor.getValue());
        assertSame(fileCaptor.getValue(), fileService.requireBindable(2L, "SUBMISSION", 51L));
    }

    @Test
    void 他人临时或已绑定文件都不能借新业务绑定() {
        when(fileInfoMapper.selectOne(any()))
                .thenReturn(file("TEMP", 88L), file("BOUND", 88L));
        when(fileRefMapper.selectCount(any())).thenReturn(0L);

        BizException tempError = assertThrows(BizException.class,
                () -> fileService.requireBindable(1L, "SUBMISSION", 51L));
        BizException boundError = assertThrows(BizException.class,
                () -> fileService.requireBindable(1L, "ADJUSTMENT", 52L));

        assertEquals(20005, tempError.getErrorCode().getCode());
        assertEquals(20005, boundError.getErrorCode().getCode());
    }

    @Test
    void 非上传人可保留同一业务已有文件引用() {
        FileInfo file = file("BOUND", 88L);
        when(fileInfoMapper.selectOne(any())).thenReturn(file);
        when(fileRefMapper.selectCount(any())).thenReturn(1L);

        assertSame(file, fileService.requireBindable(1L, "SUBMISSION", 51L));
    }

    @Test
    void 管理员可以绑定他人文件() {
        LoginUser admin = new LoginUser(100L, "admin", "管理员", null,
                List.of("ADMIN"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of()));
        FileInfo file = file("BOUND", 88L);
        when(fileInfoMapper.selectOne(any())).thenReturn(file);

        assertSame(file, fileService.requireBindable(1L, "ADJUSTMENT", 52L));
        verify(fileRefMapper, never()).selectCount(any());
    }

    @Test
    void 填报附件复用填报数据范围() {
        FileInfo file = file("BOUND", 88L);
        FileRef ref = ref("SUBMISSION", 51L);
        Submission submission = new Submission();
        submission.setId(51L);
        when(fileInfoMapper.selectOne(any())).thenReturn(file);
        when(fileRefMapper.selectList(any())).thenReturn(List.of(ref));
        when(submissionService.requireSubmission(51L)).thenReturn(submission);
        doThrow(new BizException(ErrorCode.DATA_SCOPE_DENIED))
                .when(submissionService).requireAccess(submission);

        BizException error = assertThrows(BizException.class, () -> fileService.requireReadable(1L));

        assertEquals(20005, error.getErrorCode().getCode());
    }

    @Test
    void 调整附件按调整权限允许读取() {
        FileInfo file = file("BOUND", 88L);
        Adjustment adjustment = new Adjustment();
        adjustment.setId(52L);
        when(fileInfoMapper.selectOne(any())).thenReturn(file);
        when(fileRefMapper.selectList(any())).thenReturn(List.of(ref("ADJUSTMENT", 52L)));
        when(adjustmentService.requireAdjustment(52L)).thenReturn(adjustment);

        assertSame(file, fileService.requireReadable(1L));
        verify(adjustmentService).requireAccess(adjustment);
    }

    @Test
    void 里程碑附件复用里程碑数据范围() {
        FileInfo file = file("BOUND", 88L);
        Milestone milestone = new Milestone();
        milestone.setId(53L);
        when(fileInfoMapper.selectOne(any())).thenReturn(file);
        when(fileRefMapper.selectList(any())).thenReturn(List.of(ref("MILESTONE", 53L)));
        when(milestoneService.requireMilestone(53L)).thenReturn(milestone);
        doThrow(new BizException(ErrorCode.DATA_SCOPE_DENIED))
                .when(milestoneService).requireAccess(milestone);

        BizException error = assertThrows(BizException.class, () -> fileService.requireReadable(1L));

        assertEquals(20005, error.getErrorCode().getCode());
    }

    @Test
    void 已绑定文件没有业务关联时拒绝读取() {
        when(fileInfoMapper.selectOne(any())).thenReturn(file("BOUND", 88L));
        when(fileRefMapper.selectList(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class, () -> fileService.requireReadable(1L));

        assertEquals(20005, error.getErrorCode().getCode());
    }

    @Test
    void 分片合并复核实际字节数() throws Exception {
        byte[] content = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        Files.write(partDir.resolve("1"), content);
        FileUploadSession session = session("size.pdf", content.length + 1, Sha256.hex(content));
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(storageService.partDir("upload")).thenReturn(partDir);

        BizException error = assertThrows(BizException.class, () -> fileService.completeMultipart("upload"));

        assertEquals(ErrorCode.PART_INVALID.getCode(), error.getErrorCode().getCode());
        verify(storageService, never()).save(any(), any(), anyLong());
    }

    @Test
    void 分片合并不信声明Mime而校验魔数() throws Exception {
        byte[] content = "not a pdf".getBytes(StandardCharsets.UTF_8);
        Files.write(partDir.resolve("1"), content);
        FileUploadSession session = session("fake.pdf", content.length, Sha256.hex(content));
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(storageService.partDir("upload")).thenReturn(partDir);

        BizException error = assertThrows(BizException.class, () -> fileService.completeMultipart("upload"));

        assertEquals(ErrorCode.FILE_TYPE_UNSUPPORTED.getCode(), error.getErrorCode().getCode());
        verify(storageService, never()).save(any(), any(), anyLong());
    }

    @Test
    void 合法分片按实际内容完成合并() throws Exception {
        byte[] content = "%PDF-1.4\ncontent".getBytes(StandardCharsets.UTF_8);
        Files.write(partDir.resolve("1"), content);
        FileUploadSession session = session("valid.pdf", content.length, Sha256.hex(content));
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(storageService.partDir("upload")).thenReturn(partDir);
        when(fileInfoMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(0, InputStream.class).readAllBytes();
            return null;
        }).when(storageService).save(any(), any(), anyLong());
        doAnswer(invocation -> {
            invocation.getArgument(0, FileInfo.class).setId(1L);
            return 1;
        }).when(fileInfoMapper).insert(any(FileInfo.class));

        FileInfoResponse response = fileService.completeMultipart("upload");

        assertEquals(content.length, response.getSize());
        assertEquals("valid.pdf", response.getFileName());
        verify(fileInfoMapper).insert(any(FileInfo.class));
    }

    @Test
    void 本地分片初始化保持后端上传模式() {
        when(fileInfoMapper.selectList(any())).thenReturn(List.of());

        FileService.InitResult result = fileService.initMultipart(multipartRequest());

        assertFalse(result.directUpload());
        assertEquals(5 * 1024 * 1024, result.partSize());
        ArgumentCaptor<FileUploadSession> captor = ArgumentCaptor.forClass(FileUploadSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertEquals("LOCAL", captor.getValue().getStorageType());
        verify(directStorageProvider, never()).getIfAvailable();
    }

    @Test
    void MINIO分片初始化创建远端会话并返回直传模式() {
        ReflectionTestUtils.setField(fileService, "storageType", "MINIO");
        when(fileInfoMapper.selectList(any())).thenReturn(List.of());
        when(directStorageProvider.getIfAvailable()).thenReturn(directStorageService);
        when(directStorageService.createMultipart(any())).thenReturn("remote-upload");

        FileService.InitResult result = fileService.initMultipart(multipartRequest());

        assertTrue(result.directUpload());
        ArgumentCaptor<FileUploadSession> captor = ArgumentCaptor.forClass(FileUploadSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertEquals("MINIO", captor.getValue().getStorageType());
        assertEquals("remote-upload", captor.getValue().getRemoteUploadId());
        assertNotNull(captor.getValue().getStorageKey());
    }

    @Test
    void MINIO分片地址使用预签名直传() {
        FileUploadSession session = directSession("direct.pdf", 20, Sha256.hex("direct"));
        when(sessionMapper.selectOne(any())).thenReturn(session);
        SignedUrlService.SignedUrl signed = new SignedUrlService.SignedUrl(
                "http://localhost:9000/bucket/key?signature", LocalDateTime.now().plusMinutes(10));
        when(directStorageProvider.getIfAvailable()).thenReturn(directStorageService);
        when(directStorageService.presignPart(session, 1)).thenReturn(signed);

        assertSame(signed, fileService.partUploadUrl("upload", 1));
    }

    @Test
    void MINIO分片地址拒绝非会话发起人() {
        FileUploadSession session = directSession("direct.pdf", 20, Sha256.hex("direct"));
        session.setCreatedBy(88L);
        when(sessionMapper.selectOne(any())).thenReturn(session);

        BizException error = assertThrows(BizException.class, () -> fileService.partUploadUrl("upload", 1));

        assertEquals(ErrorCode.DATA_SCOPE_DENIED.getCode(), error.getErrorCode().getCode());
        verify(directStorageProvider, never()).getIfAvailable();
    }

    @Test
    void MINIO完成后复核魔数与整文件哈希再入库() throws Exception {
        byte[] content = "%PDF-1.4\ndirect".getBytes(StandardCharsets.UTF_8);
        FileUploadSession session = directSession("direct.pdf", content.length, Sha256.hex(content));
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(directStorageProvider.getIfAvailable()).thenReturn(directStorageService);
        when(directStorageService.listParts(session))
                .thenReturn(List.of(new DirectStorageService.DirectPart(1, "etag")));
        when(directStorageService.sizeOf(session.getStorageKey())).thenReturn((long) content.length);
        when(directStorageService.read(session.getStorageKey()))
                .thenAnswer(invocation -> new ByteArrayInputStream(content));
        when(fileInfoMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(0, FileInfo.class).setId(1L);
            return 1;
        }).when(fileInfoMapper).insert(any(FileInfo.class));

        FileInfoResponse response = fileService.completeMultipart("upload");

        assertEquals(content.length, response.getSize());
        ArgumentCaptor<FileInfo> captor = ArgumentCaptor.forClass(FileInfo.class);
        verify(fileInfoMapper).insert(captor.capture());
        assertEquals("MINIO", captor.getValue().getStorageType());
        assertEquals(Sha256.hex(content), captor.getValue().getSha256());
        verify(directStorageService).complete(any(), any());
    }

    @Test
    void MINIO整文件哈希不符时删除对象且不写入文件记录() throws Exception {
        byte[] content = "%PDF-1.4\ndirect".getBytes(StandardCharsets.UTF_8);
        FileUploadSession session = directSession("direct.pdf", content.length, Sha256.hex("other"));
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(directStorageProvider.getIfAvailable()).thenReturn(directStorageService);
        when(directStorageService.listParts(session))
                .thenReturn(List.of(new DirectStorageService.DirectPart(1, "etag")));
        when(directStorageService.sizeOf(session.getStorageKey())).thenReturn((long) content.length);
        when(directStorageService.read(session.getStorageKey()))
                .thenAnswer(invocation -> new ByteArrayInputStream(content));

        BizException error = assertThrows(BizException.class, () -> fileService.completeMultipart("upload"));

        assertEquals(ErrorCode.PART_INVALID.getCode(), error.getErrorCode().getCode());
        verify(directStorageService).delete(session.getStorageKey());
        verify(fileInfoMapper, never()).insert(any(FileInfo.class));
    }

    @Test
    void 新视频无需转码即可用原文件预览() throws Exception {
        byte[] content = new byte[]{0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70,
                0x69, 0x73, 0x6f, 0x6d, 0, 0, 0, 0};
        MockMultipartFile upload = new MockMultipartFile(
                "file", "material.mp4", "video/mp4", content);
        when(fileInfoMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(0, InputStream.class).readAllBytes();
            return null;
        }).when(storageService).save(any(), any(), anyLong());
        doAnswer(invocation -> {
            invocation.getArgument(0, FileInfo.class).setId(1L);
            return 1;
        }).when(fileInfoMapper).insert(any(FileInfo.class));

        FileInfoResponse response = fileService.upload(upload, "SUBMISSION_ATTACHMENT");

        assertEquals("VIDEO", response.getCategory());
        assertEquals("SUCCEEDED", response.getTranscodeStatus());
        ArgumentCaptor<FileInfo> captor = ArgumentCaptor.forClass(FileInfo.class);
        verify(fileInfoMapper).insert(captor.capture());
        assertEquals("SUCCEEDED", captor.getValue().getTranscodeStatus());
    }

    private static MultipartInitRequest multipartRequest() {
        MultipartInitRequest request = new MultipartInitRequest();
        request.setFileName("material.pdf");
        request.setSize(6 * 1024 * 1024L);
        request.setMimeType("application/pdf");
        request.setSha256(Sha256.hex("material"));
        request.setBizType("SUBMISSION_ATTACHMENT");
        return request;
    }

    private static FileUploadSession directSession(String fileName, long size, String sha256) {
        FileUploadSession session = session(fileName, size, sha256);
        session.setStorageType("MINIO");
        session.setStorageKey("2026/10/direct.pdf");
        session.setRemoteUploadId("remote-upload");
        return session;
    }

    private static FileInfo file(String status, Long uploadedBy) {
        FileInfo file = new FileInfo();
        file.setId(1L);
        file.setStatus(status);
        file.setUploadedBy(uploadedBy);
        return file;
    }

    private static FileRef ref(String bizType, Long bizId) {
        FileRef ref = new FileRef();
        ref.setFileId(1L);
        ref.setBizType(bizType);
        ref.setBizId(bizId);
        return ref;
    }

    private static FileUploadSession session(String fileName, long size, String sha256) {
        FileUploadSession session = new FileUploadSession();
        session.setId(10L);
        session.setUploadId("upload");
        session.setFileName(fileName);
        session.setSize(size);
        session.setMimeType("application/pdf");
        session.setSha256(sha256);
        session.setPartCount(1);
        session.setUploadedParts("[1]");
        session.setStatus("UPLOADING");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        session.setCreatedBy(99L);
        return session;
    }
}
