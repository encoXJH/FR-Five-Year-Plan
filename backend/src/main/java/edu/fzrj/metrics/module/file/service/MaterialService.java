package edu.fzrj.metrics.module.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.dto.MaterialDtos;
import edu.fzrj.metrics.module.file.dto.MaterialQuery;
import edu.fzrj.metrics.module.file.dto.MaterialRow;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.file.mapper.MaterialQueryMapper;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 材料库：跨学院检索、指标关联绑定与数据范围控制。 */
@Service
public class MaterialService {

    private final FileRefMapper fileRefMapper;
    private final FileInfoMapper fileInfoMapper;
    private final IndicatorMapper indicatorMapper;
    private final DirectionMapper directionMapper;
    private final TaskMapper taskMapper;
    private final MaterialQueryMapper materialQueryMapper;

    public MaterialService(FileRefMapper fileRefMapper, FileInfoMapper fileInfoMapper,
                           IndicatorMapper indicatorMapper, DirectionMapper directionMapper, TaskMapper taskMapper,
                           MaterialQueryMapper materialQueryMapper) {
        this.fileRefMapper = fileRefMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.indicatorMapper = indicatorMapper;
        this.directionMapper = directionMapper;
        this.taskMapper = taskMapper;
        this.materialQueryMapper = materialQueryMapper;
    }

    public List<MaterialDtos.IndicatorOption> accessibleIndicators(Long planId) {
        LoginUser user = SecurityUtils.getLoginUser();
        List<Indicator> indicators = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getLevel, "A")
                .eq(Indicator::getDeleted, 0L)
                .eq(planId != null, Indicator::getPlanId, planId)
                .orderByAsc(Indicator::getCode));
        Map<Long, Direction> directions = directionMapper.selectList(new LambdaQueryWrapper<Direction>()
                        .eq(planId != null, Direction::getPlanId, planId)
                        .eq(Direction::getDeleted, 0L)
                        .orderByAsc(Direction::getSort))
                .stream()
                .collect(Collectors.toMap(Direction::getId, Function.identity(), (left, right) -> left));
        Set<Long> visibleIds = visibleIndicatorIds(user, planId);
        return indicators.stream()
                .filter(item -> visibleIds.contains(item.getId()))
                .map(item -> {
                    Direction direction = directions.get(item.getDirectionId());
                    return new MaterialDtos.IndicatorOption(item.getId(), item.getCode(), item.getName(),
                            direction == null ? null : direction.getCode(),
                            direction == null ? null : direction.getName());
                })
                .toList();
    }

    public PageResult<MaterialDtos.MaterialItem> list(Long planId, Long indicatorId, Long unitId,
                                                       Long taskId, Integer year,
                                                       String category, String keyword,
                                                       int page, int pageSize) {
        LoginUser user = SecurityUtils.getLoginUser();
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        MaterialScope scope = resolveScope(user);
        if (scope == null) {
            return new PageResult<>(List.of(), 0L, safePage, safePageSize);
        }
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;
        MaterialQuery query = new MaterialQuery(
                planId, indicatorId, unitId, taskId, year, category, normalizedKeyword,
                scope.ownerScope(), scope.unitScopeIds(),
                null, safePageSize, (long) (safePage - 1) * safePageSize);
        long total = materialQueryMapper.countMaterials(query);
        List<MaterialDtos.MaterialItem> items = total == 0 ? List.of() : materialQueryMapper.selectMaterials(query)
                .stream().map(this::toItem).toList();
        return new PageResult<>(items, total, safePage, safePageSize);
    }

    /** 按关键产出指标统计材料数量（指标列表内联展示用，避免逐指标请求） */
    public List<MaterialDtos.IndicatorMaterialCount> counts(Long planId) {
        MaterialScope scope = resolveScope(SecurityUtils.getLoginUser());
        if (scope == null) {
            return List.of();
        }
        return materialQueryMapper.countByIndicator(
                new MaterialQuery(planId, null, null, null, null, null, null,
                        scope.ownerScope(), scope.unitScopeIds(), null, 0, 0));
    }

    /** ZIP 创建前在登录上下文逐文件校验材料库可见性，异步线程只接收已授权的文件 ID。 */
    public void requireExportable(List<Long> fileIds) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (!user.getPermissions().contains("material:view")) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有材料导出权限");
        }
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请至少选择一份材料");
        }
        MaterialScope scope = resolveScope(user);
        if (scope == null) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        for (Long fileId : fileIds) {
            FileInfo file = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getId, fileId)
                    .eq(FileInfo::getDeleted, 0L));
            if (file == null) {
                throw new BizException(ErrorCode.FILE_NOT_FOUND, "材料文件不存在或已删除：" + fileId);
            }
        }
        List<MaterialRow> rows = materialQueryMapper.selectMaterials(new MaterialQuery(
                null, null, null, null, null, null, null,
                scope.ownerScope(), scope.unitScopeIds(), fileIds, 0, 0));
        Set<Long> visibleIds = rows.stream().map(MaterialRow::getFileId).collect(Collectors.toSet());
        for (Long fileId : fileIds) {
            if (!visibleIds.contains(fileId)) {
                throw new BizException(ErrorCode.DATA_SCOPE_DENIED,
                        "仅可导出数据范围内审核通过的材料：" + fileId);
            }
        }
    }

    private record MaterialScope(Long ownerScope, List<Long> unitScopeIds) {
    }

    /** 无任何可见范围时返回 null；全校范围以两个空范围表示。 */
    private MaterialScope resolveScope(LoginUser user) {
        if (user.getRoles().contains("ADMIN") || user.getRoles().contains("LEADER")) {
            return new MaterialScope(null, List.of());
        }
        Long ownerScope = user.getRoles().contains("SCHOOL_OWNER") ? user.getUserId() : null;
        List<Long> unitScopeIds = user.getRoles().contains("UNIT_OWNER") ? user.getUnitIds() : List.of();
        if (ownerScope == null && unitScopeIds.isEmpty()) {
            return null;
        }
        return new MaterialScope(ownerScope, unitScopeIds);
    }

    @Transactional
    public void bind(MaterialDtos.BindRequest request) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (!user.getPermissions().contains("material:upload")) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (!user.getRoles().contains("ADMIN") && !user.getRoles().contains("SCHOOL_OWNER")) {
            throw new BizException(ErrorCode.FORBIDDEN, "学院负责人请通过任务填报上传佐证材料");
        }
        Indicator indicator = indicatorMapper.selectOne(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getId, request.indicatorId())
                .eq(Indicator::getLevel, "A")
                .eq(Indicator::getDeleted, 0L));
        if (indicator == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "关键产出指标不存在");
        }
        if (!visibleIndicatorIds(user, indicator.getPlanId()).contains(indicator.getId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        for (Long fileId : request.fileIds().stream().distinct().toList()) {
            FileInfo file = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getId, fileId)
                    .eq(FileInfo::getDeleted, 0L));
            if (file == null) {
                throw new BizException(ErrorCode.FILE_NOT_FOUND);
            }
            if (!user.getRoles().contains("ADMIN") && !Objects.equals(user.getUserId(), file.getUploadedBy())) {
                throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
            }
            Long existing = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                    .eq(FileRef::getBizType, "INDICATOR")
                    .eq(FileRef::getBizId, indicator.getId())
                    .eq(FileRef::getFileId, fileId));
            if (existing == 0) {
                FileRef ref = new FileRef();
                ref.setBizType("INDICATOR");
                ref.setBizId(indicator.getId());
                ref.setFileId(fileId);
                ref.setSort(0);
                fileRefMapper.insert(ref);
            }
            fileInfoMapper.update(null, new LambdaUpdateWrapper<FileInfo>()
                    .eq(FileInfo::getId, fileId)
                    .set(FileInfo::getStatus, "BOUND")
                    .set(FileInfo::getBoundAt, LocalDateTime.now()));
        }
    }

    private MaterialDtos.MaterialItem toItem(MaterialRow row) {
        return new MaterialDtos.MaterialItem(row.getFileId(), row.getFileName(), row.getSize(),
                row.getMimeType(), row.getCategory(), row.getBizType(), row.getUploadedAt(),
                row.getSubmissionYear(), row.getTaskCode(),
                new MaterialDtos.IndicatorRef(row.getIndicatorId(), row.getIndicatorCode(), row.getIndicatorName()),
                row.getUnitId() == null ? null
                        : new MaterialDtos.UnitRef(row.getUnitId(), row.getUnitCode(), row.getUnitName()),
                row.getUploaderId() == null ? null
                        : new MaterialDtos.UserRef(row.getUploaderId(), row.getUploaderName()));
    }

    private Set<Long> visibleIndicatorIds(LoginUser user, Long planId) {
        if (user.getRoles().contains("ADMIN") || user.getRoles().contains("LEADER")) {
            return indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                            .eq(Indicator::getLevel, "A")
                            .eq(planId != null, Indicator::getPlanId, planId)
                            .eq(Indicator::getDeleted, 0L))
                    .stream().map(Indicator::getId).collect(Collectors.toSet());
        }
        Set<Long> visibleIds = new HashSet<>();
        if (user.getRoles().contains("SCHOOL_OWNER")) {
            indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                            .eq(Indicator::getLevel, "A")
                            .eq(Indicator::getOwnerId, user.getUserId())
                            .eq(planId != null, Indicator::getPlanId, planId)
                            .eq(Indicator::getDeleted, 0L))
                    .stream().map(Indicator::getId).forEach(visibleIds::add);
        }
        if (user.getRoles().contains("UNIT_OWNER") && !user.getUnitIds().isEmpty()) {
            taskMapper.selectList(new LambdaQueryWrapper<Task>()
                            .in(Task::getUnitId, user.getUnitIds())
                            .eq(planId != null, Task::getPlanId, planId)
                            .eq(Task::getDeleted, 0L))
                    .stream().map(Task::getTopIndicatorId).filter(Objects::nonNull).forEach(visibleIds::add);
        }
        return visibleIds;
    }

}
