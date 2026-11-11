package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.module.system.entity.UserUnit;
import edu.fzrj.metrics.module.system.mapper.UserUnitMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class UserUnitService {

    private final UserUnitMapper userUnitMapper;

    public UserUnitService(UserUnitMapper userUnitMapper) {
        this.userUnitMapper = userUnitMapper;
    }

    /** 仅读取关联表，不用 sys_user.unit_id 兜底。 */
    public List<Long> unitIdsOf(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return userUnitMapper.selectList(new LambdaQueryWrapper<UserUnit>()
                        .eq(UserUnit::getUserId, userId)
                        .orderByAsc(UserUnit::getId)
                        .select(UserUnit::getUnitId))
                .stream().map(UserUnit::getUnitId).filter(Objects::nonNull).distinct().toList();
    }

    public Map<Long, List<Long>> unitIdsOf(Collection<Long> userIds) {
        List<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userUnitMapper.selectList(new LambdaQueryWrapper<UserUnit>()
                        .in(UserUnit::getUserId, ids)
                        .orderByAsc(UserUnit::getId)
                        .select(UserUnit::getUserId, UserUnit::getUnitId))
                .stream().collect(Collectors.groupingBy(UserUnit::getUserId,
                        Collectors.mapping(UserUnit::getUnitId,
                                Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), List::copyOf))));
    }

    public List<Long> userIdsOfUnit(Long unitId) {
        if (unitId == null) {
            return List.of();
        }
        return userUnitMapper.selectList(new LambdaQueryWrapper<UserUnit>()
                        .eq(UserUnit::getUnitId, unitId)
                        .select(UserUnit::getUserId))
                .stream().map(UserUnit::getUserId).distinct().toList();
    }

    @Transactional
    public void replaceUnits(Long userId, Long primaryUnitId, Collection<Long> unitIds, Long operatorId) {
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        if (primaryUnitId != null) {
            normalized.add(primaryUnitId);
        }
        if (unitIds != null) {
            unitIds.stream().filter(Objects::nonNull).forEach(normalized::add);
        }
        userUnitMapper.delete(new LambdaQueryWrapper<UserUnit>().eq(UserUnit::getUserId, userId));
        for (Long unitId : normalized) {
            UserUnit relation = new UserUnit();
            relation.setUserId(userId);
            relation.setUnitId(unitId);
            relation.setCreatedBy(operatorId);
            userUnitMapper.insert(relation);
        }
    }
}
