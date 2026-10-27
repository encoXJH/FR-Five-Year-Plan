package edu.fzrj.metrics.module.audit.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.audit.dto.AuditLogDtos;
import edu.fzrj.metrics.module.audit.entity.AuditLog;
import edu.fzrj.metrics.module.audit.mapper.AuditLogMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审计日志查询与导出（API 约定 15.9 / 16.6 / FR-M10-04）。
 * 权限 audit-log:view（查询）/ audit-log:export（导出），持有者均为全校数据范围，无额外行级过滤。
 * 日志只追加，不提供任何修改 / 删除入口（库层触发器双保险）。
 */
@Service
public class AuditLogService {

    /** 导出最大行数（防 OOM；日志量按数据库设计 9.2 估算 5 年 < 100 万行，单次导出截取最近 5 万条） */
    private static final int EXPORT_MAX_ROWS = 50_000;
    private static final java.util.Set<String> SORT_FIELDS = java.util.Set.of("occurred_at", "id");

    private final AuditLogMapper auditLogMapper;
    private final OrgUnitMapper orgUnitMapper;

    public AuditLogService(AuditLogMapper auditLogMapper, OrgUnitMapper orgUnitMapper) {
        this.auditLogMapper = auditLogMapper;
        this.orgUnitMapper = orgUnitMapper;
    }

    // ---------------- 查询 ----------------

    public PageResult<AuditLogDtos.LogItem> page(AuditLogDtos.AuditQuery query) {
        LambdaQueryWrapper<AuditLog> wrapper = buildWrapper(
                parseDateTime(query.getOccurredAtFrom(), "occurredAtFrom"),
                parseDateTime(query.getOccurredAtTo(), "occurredAtTo"),
                query.getUnitId(), query.getAction(), query.getOperatorId(),
                query.getResourceType(), query.getResourceId());
        // 默认按发生时间倒序（最新在前）
        Page<AuditLog> page = query.toPage(SORT_FIELDS);
        if (!StringUtils.hasText(query.getSort())) {
            page.addOrder(com.baomidou.mybatisplus.core.metadata.OrderItem.desc("occurred_at"));
        }
        Page<AuditLog> result = auditLogMapper.selectPage(page, wrapper);
        Map<Long, OrgUnit> unitMap = unitMapOf(result.getRecords());
        List<AuditLogDtos.LogItem> items = result.getRecords().stream()
                .map(log -> toItem(log, unitMap))
                .toList();
        return new PageResult<>(items, result.getTotal(), query.getPage(), query.getPageSize());
    }

    // ---------------- 导出 ----------------

    /** 导出（异步线程调用）：与查询同过滤条件，按发生时间正序，截取最近 EXPORT_MAX_ROWS 条 */
    public byte[] buildExport(Map<String, Object> params) {
        LambdaQueryWrapper<AuditLog> wrapper = buildWrapper(
                parseDateTime(asString(params.get("occurredAtFrom")), "occurredAtFrom"),
                parseDateTime(asString(params.get("occurredAtTo")), "occurredAtTo"),
                asLong(params.get("unitId")), asString(params.get("action")),
                asLong(params.get("operatorId")), asString(params.get("resourceType")),
                asLong(params.get("resourceId")));
        wrapper.orderByAsc(AuditLog::getOccurredAt).last("LIMIT " + EXPORT_MAX_ROWS);
        List<AuditLog> logs = auditLogMapper.selectList(wrapper);
        Map<Long, OrgUnit> unitMap = unitMapOf(logs);

        List<List<Object>> rows = logs.stream().map(log -> {
            List<Object> row = new ArrayList<>();
            row.add(log.getOccurredAt() == null ? "" : log.getOccurredAt().toString().replace('T', ' '));
            row.add(log.getOperatorName() == null ? "" : log.getOperatorName());
            row.add(log.getOperatorId() == null ? "" : log.getOperatorId());
            row.add(rolesText(log.getRoles()));
            OrgUnit unit = log.getUnitId() == null ? null : unitMap.get(log.getUnitId());
            row.add(unit == null ? "" : unit.getName());
            row.add(log.getAction());
            row.add(log.getResourceType() == null ? "" : log.getResourceType());
            row.add(log.getResourceId() == null ? "" : log.getResourceId());
            row.add(log.getResourceCode() == null ? "" : log.getResourceCode());
            row.add(log.getBeforeJson() == null ? "" : log.getBeforeJson());
            row.add(log.getAfterJson() == null ? "" : log.getAfterJson());
            row.add(log.getReason() == null ? "" : log.getReason());
            row.add(log.getClientType() == null ? "" : log.getClientType());
            row.add(log.getIp() == null ? "" : log.getIp());
            row.add(log.getRequestId() == null ? "" : log.getRequestId());
            return row;
        }).collect(Collectors.toList());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> head = List.of("时间", "操作人", "操作人ID", "角色", "所属单元", "动作",
                "资源类型", "资源ID", "资源编号", "变更前", "变更后", "原因", "客户端", "IP", "请求ID");
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            List<List<String>> headRows = head.stream().map(List::of).collect(Collectors.toList());
            writer.write(rows, EasyExcel.writerSheet("操作日志").head(headRows).build());
        }
        return out.toByteArray();
    }

    // ---------------- 内部 ----------------

    private LambdaQueryWrapper<AuditLog> buildWrapper(LocalDateTime from, LocalDateTime to,
                                                      Long unitId, String action, Long operatorId,
                                                      String resourceType, Long resourceId) {
        List<String> actions = parseActions(action);
        return new LambdaQueryWrapper<AuditLog>()
                .ge(from != null, AuditLog::getOccurredAt, from)
                .le(to != null, AuditLog::getOccurredAt, to)
                .eq(unitId != null, AuditLog::getUnitId, unitId)
                .in(!actions.isEmpty(), AuditLog::getAction, actions)
                .eq(operatorId != null, AuditLog::getOperatorId, operatorId)
                .eq(StringUtils.hasText(resourceType), AuditLog::getResourceType, resourceType)
                .eq(resourceId != null, AuditLog::getResourceId, resourceId);
    }

    /** action 过滤：逗号分隔多值（API 16.6）；非法空白项忽略 */
    private List<String> parseActions(String action) {
        if (!StringUtils.hasText(action)) {
            return List.of();
        }
        return Arrays.stream(action.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private LocalDateTime parseDateTime(String raw, String field) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "字段 " + field + " 应为 ISO 8601 格式（如 2026-01-01T00:00:00+08:00）");
        }
    }

    private AuditLogDtos.LogItem toItem(AuditLog log, Map<Long, OrgUnit> unitMap) {
        OrgUnit unit = log.getUnitId() == null ? null : unitMap.get(log.getUnitId());
        return new AuditLogDtos.LogItem(
                log.getId(), log.getOccurredAt(),
                log.getOperatorId() == null ? null : new UserRef(log.getOperatorId(), log.getOperatorName()),
                log.getRoles(),
                log.getUnitId() == null ? null
                        : new AuditLogDtos.UnitRef(log.getUnitId(), unit == null ? null : unit.getName()),
                log.getAction(), log.getResourceType(), log.getResourceId(), log.getResourceCode(),
                log.getBeforeJson(), log.getAfterJson(), log.getReason(),
                log.getClientType(), log.getIp(), log.getRequestId());
    }

    private Map<Long, OrgUnit> unitMapOf(List<AuditLog> logs) {
        List<Long> unitIds = logs.stream().map(AuditLog::getUnitId)
                .filter(Objects::nonNull).distinct().toList();
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, unitIds)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    }

    /** 角色 JSON 数组 → 逗号分隔文本（导出可读性） */
    private String rolesText(String rolesJson) {
        if (!StringUtils.hasText(rolesJson)) {
            return "";
        }
        return rolesJson.replace("[", "").replace("]", "").replace("\"", "").replace(',', '、');
    }

    private static String asString(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(raw);
    }

    private static Long asLong(Object raw) {
        if (raw == null || "null".equals(String.valueOf(raw))) {
            return null;
        }
        try {
            return raw instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "导出参数中的 ID 格式不正确");
        }
    }
}
