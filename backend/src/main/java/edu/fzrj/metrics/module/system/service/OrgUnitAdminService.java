package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 执行单元管理（API 约定 15.10、PRD FR-M12-01）：新增 / 修改 / 停用。
 * code 用于 B 任务编号，创建后不允许修改（API 约定第 9 章）；
 * 停用前校验在途任务（DISTRIBUTED / IN_PROGRESS），有则拒绝。
 */
@Service
public class OrgUnitAdminService {

    private static final Set<String> TYPES = Set.of(
            "COLLEGE", "INTERNATIONAL_COLLEGE", "MARXISM_COLLEGE", "SCHOOL_OFFICE");
    private static final Set<String> IN_FLIGHT_TASK_STATUS = Set.of("DISTRIBUTED", "IN_PROGRESS");

    private final OrgUnitMapper orgUnitMapper;
    private final TaskMapper taskMapper;

    public OrgUnitAdminService(OrgUnitMapper orgUnitMapper, TaskMapper taskMapper) {
        this.orgUnitMapper = orgUnitMapper;
        this.taskMapper = taskMapper;
    }

    @Transactional
    public SystemDtos.OrgUnitView create(SystemDtos.OrgUnitCreateRequest request) {
        String code = request.getCode().trim().toUpperCase();
        if (!code.matches("[A-Z0-9]{1,16}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单元编码只允许大写字母与数字");
        }
        if (!TYPES.contains(request.getType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单元类型取值不合法：" + request.getType());
        }
        Long duplicate = orgUnitMapper.selectCount(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getCode, code)
                .eq(OrgUnit::getDeleted, 0L));
        if (duplicate > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单元编码已存在：" + code);
        }
        OrgUnit unit = new OrgUnit();
        unit.setCode(code);
        unit.setName(request.getName().trim());
        unit.setType(request.getType());
        unit.setSort(request.getSort() == null ? 0 : request.getSort());
        unit.setIsEnabled(true);
        unit.setIsBAssignable(Boolean.TRUE.equals(request.getIsBAssignable()));
        unit.setCreatedBy(SecurityUtils.getUserId());
        unit.setUpdatedBy(unit.getCreatedBy());
        orgUnitMapper.insert(unit);
        return toView(orgUnitMapper.selectById(unit.getId()));
    }

    @Transactional
    public SystemDtos.OrgUnitView patch(Long unitId, JsonNode body) {
        PatchHelper patch = new PatchHelper(body);
        OrgUnit unit = requireUnit(unitId);
        if (patch.has("code") && !unit.getCode().equalsIgnoreCase(patch.text("code"))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单元编码用于 B 任务编号，创建后不允许修改");
        }
        if (patch.has("type") && !unit.getType().equals(patch.text("type"))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单元类型创建后不允许修改");
        }
        if (patch.has("isEnabled") && Boolean.FALSE.equals(patch.bool("isEnabled"))) {
            requireNoInFlightTasks(unitId);
        }
        int version = patch.versionRequired();
        if (!unit.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + unit.getVersion());
        }
        if (patch.has("name")) {
            String name = patch.text("name").trim();
            if (name.isEmpty() || name.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "单元名称必填且不超过 200 字");
            }
            unit.setName(name);
        }
        if (patch.has("sort")) {
            unit.setSort(patch.integer("sort"));
        }
        if (patch.has("isEnabled")) {
            unit.setIsEnabled(patch.bool("isEnabled"));
        }
        if (patch.has("isBAssignable")) {
            unit.setIsBAssignable(patch.bool("isBAssignable"));
        }
        unit.setUpdatedBy(SecurityUtils.getUserId());
        orgUnitMapper.updateById(unit);
        return toView(orgUnitMapper.selectById(unitId));
    }

    public OrgUnit requireUnit(Long unitId) {
        OrgUnit unit = orgUnitMapper.selectOne(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getId, unitId)
                .eq(OrgUnit::getDeleted, 0L));
        if (unit == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "执行单元不存在");
        }
        return unit;
    }

    public OrgUnit requireBAssignable(Long unitId) {
        OrgUnit unit = requireUnit(unitId);
        if (!Boolean.TRUE.equals(unit.getIsEnabled()) || !Boolean.TRUE.equals(unit.getIsBAssignable())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "执行单元已停用或不可承接新 B 任务");
        }
        return unit;
    }

    /** 停用校验：历史任务只读保留，不阻止执行单元停用 */
    private void requireNoInFlightTasks(Long unitId) {
        Long inFlight = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .eq(Task::getUnitId, unitId)
                .eq(Task::getIsHistorical, false)
                .eq(Task::getDeleted, 0L)
                .in(Task::getStatus, IN_FLIGHT_TASK_STATUS));
        if (inFlight > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该单元存在 " + inFlight + " 条在途 B 任务（已下发 / 执行中），不能停用；请先完成或转移");
        }
    }

    private SystemDtos.OrgUnitView toView(OrgUnit unit) {
        return new SystemDtos.OrgUnitView(unit.getId(), unit.getCode(), unit.getName(), unit.getType(),
                unit.getSort(), unit.getIsEnabled(), unit.getIsBAssignable(), unit.getVersion());
    }

    public List<SystemDtos.OrgUnitView> listAll() {
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .eq(OrgUnit::getDeleted, 0L)
                        .orderByAsc(OrgUnit::getSort).orderByAsc(OrgUnit::getCode))
                .stream().map(this::toView).toList();
    }
}
