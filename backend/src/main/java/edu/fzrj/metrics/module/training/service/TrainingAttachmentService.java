package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.dto.FileInfoResponse;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.file.service.FileService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TrainingAttachmentService {

    public static final String FILE_PURPOSE = "TRAINING_ATTACHMENT";
    public static final String FILE_REF_TYPE = "TRAINING_PROJECT";

    private final FileRefMapper fileRefMapper;
    private final FileInfoMapper fileInfoMapper;
    private final FileService fileService;

    public TrainingAttachmentService(FileRefMapper fileRefMapper, FileInfoMapper fileInfoMapper,
                                     FileService fileService) {
        this.fileRefMapper = fileRefMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.fileService = fileService;
    }

    public List<Long> normalizeFileIds(List<Long> fileIds) {
        if (fileIds == null) {
            return null;
        }
        Set<Long> normalized = new LinkedHashSet<>();
        for (Long fileId : fileIds) {
            if (fileId == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "fileIds 不能包含空值");
            }
            normalized.add(fileId);
        }
        return new ArrayList<>(normalized);
    }

    public boolean sameFiles(List<Long> existingFileIds, List<Long> requestedFileIds) {
        return requestedFileIds == null
                || new LinkedHashSet<>(existingFileIds).equals(new LinkedHashSet<>(requestedFileIds));
    }

    public Map<Long, List<Long>> fileIdsByProjectIds(Collection<Long> projectIds) {
        Map<Long, List<Long>> result = emptyProjectMap(projectIds);
        if (projectIds.isEmpty()) {
            return result;
        }
        refsOf(projectIds).forEach(ref -> result.get(ref.getBizId()).add(ref.getFileId()));
        return result;
    }

    public Map<Long, List<FileInfoResponse>> filesByProjectIds(Collection<Long> projectIds) {
        Map<Long, List<FileInfoResponse>> result = emptyProjectMap(projectIds);
        if (projectIds.isEmpty()) {
            return result;
        }
        List<FileRef> refs = refsOf(projectIds);
        if (refs.isEmpty()) {
            return result;
        }
        List<Long> fileIds = refs.stream().map(FileRef::getFileId).distinct().toList();
        Map<Long, FileInfo> files = fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                        .in(FileInfo::getId, fileIds)
                        .eq(FileInfo::getDeleted, 0L))
                .stream().collect(Collectors.toMap(FileInfo::getId, Function.identity()));
        for (FileRef ref : refs) {
            FileInfo file = files.get(ref.getFileId());
            if (file != null) {
                result.get(ref.getBizId()).add(fileService.toResponse(file));
            }
        }
        return result;
    }

    public boolean replace(Long projectId, List<Long> requestedFileIds) {
        List<Long> keep = normalizeFileIds(requestedFileIds);
        if (keep == null) {
            return false;
        }
        List<FileRef> existing = refsOf(List.of(projectId));
        List<Long> existingIds = existing.stream().map(FileRef::getFileId).toList();
        List<Long> removed = existingIds.stream().filter(id -> !keep.contains(id)).toList();
        if (!removed.isEmpty()) {
            fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                    .eq(FileRef::getBizType, FILE_REF_TYPE)
                    .eq(FileRef::getBizId, projectId)
                    .in(FileRef::getFileId, removed));
            removed.forEach(this::unbindIfOrphan);
        }
        List<Long> added = keep.stream().filter(id -> !existingIds.contains(id)).toList();
        int sort = existing.stream().map(FileRef::getSort).max(Integer::compareTo).orElse(-1) + 1;
        for (Long fileId : added) {
            bind(projectId, fileId, sort++);
        }
        return !removed.isEmpty() || !added.isEmpty();
    }

    public void removeAll(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return;
        }
        List<FileRef> refs = refsOf(projectIds);
        if (refs.isEmpty()) {
            return;
        }
        fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getBizType, FILE_REF_TYPE)
                .in(FileRef::getBizId, projectIds));
        refs.stream().map(FileRef::getFileId).distinct().forEach(this::unbindIfOrphan);
    }

    private void bind(Long projectId, Long fileId, int sort) {
        FileInfo file = fileService.requireBindable(fileId, FILE_REF_TYPE, projectId);
        if (!FILE_PURPOSE.equals(file.getBizType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "培训项目只能绑定培训项目附件：" + fileId);
        }
        Long otherRefs = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId));
        if (otherRefs > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件已绑定其他业务或其他培训项目：" + fileId);
        }
        FileRef ref = new FileRef();
        ref.setBizType(FILE_REF_TYPE);
        ref.setBizId(projectId);
        ref.setFileId(fileId);
        ref.setSort(sort);
        fileRefMapper.insert(ref);
        if ("TEMP".equals(file.getStatus())) {
            fileInfoMapper.update(null, new LambdaUpdateWrapper<FileInfo>()
                    .eq(FileInfo::getId, fileId)
                    .eq(FileInfo::getStatus, "TEMP")
                    .set(FileInfo::getStatus, "BOUND")
                    .set(FileInfo::getBoundAt, LocalDateTime.now()));
        }
    }

    private void unbindIfOrphan(Long fileId) {
        Long refs = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId));
        if (refs == 0) {
            fileInfoMapper.update(null, new LambdaUpdateWrapper<FileInfo>()
                    .eq(FileInfo::getId, fileId)
                    .eq(FileInfo::getStatus, "BOUND")
                    .set(FileInfo::getStatus, "TEMP")
                    .set(FileInfo::getBoundAt, null));
        }
    }

    private List<FileRef> refsOf(Collection<Long> projectIds) {
        return fileRefMapper.selectList(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getBizType, FILE_REF_TYPE)
                .in(FileRef::getBizId, projectIds)
                .orderByAsc(FileRef::getSort)
                .orderByAsc(FileRef::getId));
    }

    private static <T> Map<Long, List<T>> emptyProjectMap(Collection<Long> projectIds) {
        Map<Long, List<T>> result = new LinkedHashMap<>();
        projectIds.forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }
}
