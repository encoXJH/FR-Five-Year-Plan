package edu.fzrj.metrics.module.indicator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.indicator.dto.DirectionDtos;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.MajorGroup;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.MajorGroupMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主要S 级任务（API 约定 15.3）
 */
@Service
public class DirectionService {

    private final DirectionMapper directionMapper;
    private final IndicatorMapper indicatorMapper;
    private final MajorGroupMapper majorGroupMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserRefResolver userRefResolver;
    private final PlanService planService;

    public DirectionService(DirectionMapper directionMapper, IndicatorMapper indicatorMapper,
                            MajorGroupMapper majorGroupMapper, UserRoleMapper userRoleMapper,
                            UserRefResolver userRefResolver, PlanService planService) {
        this.directionMapper = directionMapper;
        this.indicatorMapper = indicatorMapper;
        this.majorGroupMapper = majorGroupMapper;
        this.userRoleMapper = userRoleMapper;
        this.userRefResolver = userRefResolver;
        this.planService = planService;
    }

    public List<DirectionDtos.Item> list(Long planId) {
        planService.requirePlan(planId);
        List<Direction> directions = directionMapper.selectList(new LambdaQueryWrapper<Direction>()
                .eq(Direction::getPlanId, planId)
                .eq(Direction::getDeleted, 0L)
                .orderByAsc(Direction::getSort).orderByAsc(Direction::getId));
        Map<Long, MajorGroup> groupMap = majorGroupMapper.selectList(new LambdaQueryWrapper<MajorGroup>()
                        .eq(MajorGroup::getDeleted, 0L))
                .stream().collect(Collectors.toMap(MajorGroup::getId, Function.identity()));
        Map<Long, Long> countMap = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getPlanId, planId)
                        .eq(Indicator::getDeleted, 0L))
                .stream().filter(i -> i.getDirectionId() != null)
                .collect(Collectors.groupingBy(Indicator::getDirectionId, Collectors.counting()));
        Map<Long, SysUser> leaderMap = userRefResolver.mapOf(directions.stream()
                .map(Direction::getLeaderId).toList());
        return directions.stream().map(d -> toItem(d, groupMap, countMap, leaderMap)).toList();
    }

    @Transactional
    public DirectionDtos.Item create(Long planId, DirectionDtos.CreateRequest request) {
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireNotFinalized(plan);
        Direction direction = new Direction();
        direction.setPlanId(planId);
        applySubject(direction, request.getSubjectType(), request.getMajorGroupId());
        direction.setName(request.getName());
        direction.setLeaderId(requireLeaderIfPresent(request.getLeaderId()));
        direction.setSort(request.getSort() == null ? 0 : request.getSort());
        Long userId = SecurityUtils.getUserId();
        direction.setCreatedBy(userId);
        direction.setUpdatedBy(userId);
        directionMapper.insert(direction);
        return toItem(requireDirection(direction.getId()), Map.of(), Map.of(), Map.of());
    }

    @Transactional
    public DirectionDtos.Item patch(Long directionId, PatchHelper patch) {
        Direction direction = requireDirection(directionId);
        Plan plan = planService.requirePlan(direction.getPlanId());
        planService.requireNotArchived(plan);
        boolean structureTouched = patch.has("subjectType") || patch.has("name")
                || patch.has("sort") || patch.has("majorGroupId");
        if (structureTouched) {
            planService.requireNotFinalized(plan);
        }
        int version = patch.versionRequired();
        planService.requireVersion(direction.getVersion(), version);
        if (patch.has("subjectType")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "主体类型不能修改");
        }
        if (patch.has("name")) {
            String name = patch.text("name");
            if (!StringUtils.hasText(name) || name.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "名称必填且不超过 200 字");
            }
            direction.setName(name);
        }
        if (patch.has("sort")) {
            direction.setSort(patch.integer("sort"));
        }
        if (patch.has("majorGroupId")) {
            if (!"MAJOR_GROUP".equals(direction.getSubjectType())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "校级主体不能设置专业群");
            }
            direction.setMajorGroupId(requireMajorGroup(patch.longId("majorGroupId")).getId());
        }
        if (patch.has("leaderId")) {
            direction.setLeaderId(patch.isNull("leaderId")
                    ? null : requireLeaderIfPresent(patch.longId("leaderId")));
        }
        direction.setUpdatedBy(SecurityUtils.getUserId());
        if (directionMapper.updateById(direction) == 0) {
            Direction latest = requireDirection(directionId);
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + latest.getVersion());
        }
        return toItem(requireDirection(directionId), Map.of(), Map.of(), Map.of());
    }

    @Transactional
    public void delete(Long directionId) {
        Direction direction = requireDirection(directionId);
        Plan plan = planService.requirePlan(direction.getPlanId());
        planService.requireNotArchived(plan);
        planService.requireNotFinalized(plan);
        Long count = indicatorMapper.selectCount(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getDirectionId, directionId)
                .eq(Indicator::getDeleted, 0L));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.HAS_CHILDREN, "该S 级任务下存在指标，不能删除");
        }
        direction.setDeleted(direction.getId());
        direction.setUpdatedBy(SecurityUtils.getUserId());
        directionMapper.updateById(direction);
    }

    public Direction requireDirection(Long directionId) {
        Direction direction = directionMapper.selectOne(new LambdaQueryWrapper<Direction>()
                .eq(Direction::getId, directionId)
                .eq(Direction::getDeleted, 0L));
        if (direction == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "S 级任务不存在");
        }
        return direction;
    }

    private void applySubject(Direction direction, String subjectType, Long majorGroupId) {
        if (!"SCHOOL".equals(subjectType) && !"MAJOR_GROUP".equals(subjectType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "主体只支持 SCHOOL / MAJOR_GROUP");
        }
        direction.setSubjectType(subjectType);
        if ("MAJOR_GROUP".equals(subjectType)) {
            if (majorGroupId == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "主体为专业群时必须提供 majorGroupId");
            }
            direction.setMajorGroupId(requireMajorGroup(majorGroupId).getId());
        } else {
            direction.setMajorGroupId(null);
        }
    }

    private MajorGroup requireMajorGroup(Long majorGroupId) {
        MajorGroup group = majorGroupMapper.selectOne(new LambdaQueryWrapper<MajorGroup>()
                .eq(MajorGroup::getId, majorGroupId)
                .eq(MajorGroup::getIsEnabled, true)
                .eq(MajorGroup::getDeleted, 0L));
        if (group == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "专业群不存在或已停用");
        }
        return group;
    }

    private Long requireLeaderIfPresent(Long leaderId) {
        if (leaderId == null) {
            return null;
        }
        SysUser leader = userRefResolver.mapOf(List.of(leaderId)).get(leaderId);
        if (leader == null || !Boolean.TRUE.equals(leader.getIsEnabled())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "S 级总负责人不存在或已停用");
        }
        Long leaderRoles = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, leaderId)
                .eq(UserRole::getRole, "LEADER"));
        if (leaderRoles == null || leaderRoles == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "S 级总负责人必须具有校领导角色");
        }
        return leaderId;
    }

    private DirectionDtos.Item toItem(Direction direction, Map<Long, MajorGroup> groupMap,
                                      Map<Long, Long> countMap, Map<Long, SysUser> leaderMap) {
        MajorGroup group = null;
        if (direction.getMajorGroupId() != null) {
            group = groupMap.isEmpty()
                    ? majorGroupMapper.selectById(direction.getMajorGroupId())
                    : groupMap.get(direction.getMajorGroupId());
        }
        DirectionDtos.MajorGroupRef groupRef = group == null ? null
                : new DirectionDtos.MajorGroupRef(group.getId(), group.getName());
        Map<Long, SysUser> resolvedLeaderMap = leaderMap;
        if (direction.getLeaderId() != null && !resolvedLeaderMap.containsKey(direction.getLeaderId())) {
            resolvedLeaderMap = userRefResolver.mapOf(List.of(direction.getLeaderId()));
        }
        UserRef leader = userRefResolver.refOf(direction.getLeaderId(), resolvedLeaderMap);
        return new DirectionDtos.Item(direction.getId(), direction.getPlanId(), direction.getSubjectType(),
                groupRef, direction.getName(), direction.getLeaderId(), leader, direction.getSort(),
                countMap.getOrDefault(direction.getId(), 0L).intValue(),
                direction.getVersion(), direction.getCreatedAt(), direction.getUpdatedAt());
    }
}
