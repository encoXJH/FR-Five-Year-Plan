package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.MajorGroup;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.MajorGroupMapper;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 专业群管理（API 约定 15.10、PRD FR-M12-02）：列表 / 新增 / 修改（名称、所属学院、负责人、停用）。
 * 列表已登录可查（建设方向关联专业群需要选择器）；写操作 system:manage。
 */
@Service
public class MajorGroupService {

    private final MajorGroupMapper majorGroupMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final SysUserMapper sysUserMapper;

    public MajorGroupService(MajorGroupMapper majorGroupMapper, OrgUnitMapper orgUnitMapper,
                             SysUserMapper sysUserMapper) {
        this.majorGroupMapper = majorGroupMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.sysUserMapper = sysUserMapper;
    }

    public List<SystemDtos.MajorGroupView> list(Long unitId) {
        List<MajorGroup> groups = majorGroupMapper.selectList(new LambdaQueryWrapper<MajorGroup>()
                .eq(MajorGroup::getDeleted, 0L)
                .eq(unitId != null, MajorGroup::getUnitId, unitId)
                .orderByAsc(MajorGroup::getId));
        Map<Long, OrgUnit> unitMap = unitMapOf(groups);
        Map<Long, SysUser> userMap = userMapOf(groups);
        return groups.stream().map(g -> toView(g, unitMap, userMap)).toList();
    }

    @Transactional
    public SystemDtos.MajorGroupView create(SystemDtos.MajorGroupCreateRequest request) {
        requireUnit(request.getUnitId());
        requireUserIfPresent(request.getOwnerId());
        MajorGroup group = new MajorGroup();
        group.setName(request.getName().trim());
        group.setUnitId(request.getUnitId());
        group.setOwnerId(request.getOwnerId());
        group.setIsEnabled(true);
        group.setCreatedBy(SecurityUtils.getUserId());
        group.setUpdatedBy(group.getCreatedBy());
        majorGroupMapper.insert(group);
        MajorGroup saved = majorGroupMapper.selectById(group.getId());
        return toView(saved, unitMapOf(List.of(saved)), userMapOf(List.of(saved)));
    }

    @Transactional
    public SystemDtos.MajorGroupView patch(Long majorGroupId, JsonNode body) {
        PatchHelper patch = new PatchHelper(body);
        MajorGroup group = requireGroup(majorGroupId);
        int version = patch.versionRequired();
        if (!group.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + group.getVersion());
        }
        if (patch.has("name")) {
            String name = patch.text("name").trim();
            if (name.isEmpty() || name.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "专业群名称必填且不超过 200 字");
            }
            group.setName(name);
        }
        if (patch.has("unitId")) {
            Long newUnitId = patch.isNull("unitId") ? null : patch.longId("unitId");
            if (newUnitId == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "所属学院不能清空");
            }
            requireUnit(newUnitId);
            group.setUnitId(newUnitId);
        }
        if (patch.has("ownerId")) {
            Long newOwnerId = patch.isNull("ownerId") ? null : patch.longId("ownerId");
            requireUserIfPresent(newOwnerId);
            group.setOwnerId(newOwnerId);
        }
        if (patch.has("isEnabled")) {
            group.setIsEnabled(patch.bool("isEnabled"));
        }
        group.setUpdatedBy(SecurityUtils.getUserId());
        majorGroupMapper.updateById(group);
        MajorGroup saved = majorGroupMapper.selectById(majorGroupId);
        return toView(saved, unitMapOf(List.of(saved)), userMapOf(List.of(saved)));
    }

    public MajorGroup requireGroup(Long majorGroupId) {
        MajorGroup group = majorGroupMapper.selectOne(new LambdaQueryWrapper<MajorGroup>()
                .eq(MajorGroup::getId, majorGroupId)
                .eq(MajorGroup::getDeleted, 0L));
        if (group == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专业群不存在");
        }
        return group;
    }

    private void requireUnit(Long unitId) {
        Long count = orgUnitMapper.selectCount(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getId, unitId)
                .eq(OrgUnit::getDeleted, 0L));
        if (count == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所属学院不存在");
        }
    }

    private void requireUserIfPresent(Long userId) {
        if (userId == null) {
            return;
        }
        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, 0L));
        if (count == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "负责人用户不存在");
        }
    }

    private SystemDtos.MajorGroupView toView(MajorGroup group, Map<Long, OrgUnit> unitMap,
                                             Map<Long, SysUser> userMap) {
        OrgUnit unit = group.getUnitId() == null ? null : unitMap.get(group.getUnitId());
        SysUser owner = group.getOwnerId() == null ? null : userMap.get(group.getOwnerId());
        return new SystemDtos.MajorGroupView(group.getId(), group.getName(),
                group.getUnitId(), unit == null ? null : unit.getName(),
                group.getOwnerId(), owner == null ? null : owner.getName(),
                group.getIsEnabled(), group.getVersion());
    }

    private Map<Long, OrgUnit> unitMapOf(List<MajorGroup> groups) {
        List<Long> unitIds = groups.stream().map(MajorGroup::getUnitId)
                .filter(Objects::nonNull).distinct().toList();
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, unitIds)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    }

    private Map<Long, SysUser> userMapOf(List<MajorGroup> groups) {
        List<Long> ownerIds = groups.stream().map(MajorGroup::getOwnerId)
                .filter(Objects::nonNull).distinct().toList();
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, ownerIds)
                        .eq(SysUser::getDeleted, 0L))
                .stream().collect(Collectors.toMap(SysUser::getId, Function.identity()));
    }
}
