package edu.fzrj.metrics.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.DictItem;
import edu.fzrj.metrics.module.system.mapper.DictItemMapper;
import edu.fzrj.metrics.module.system.service.DictAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典接口：GET /dicts/enums 全量枚举中文名（API 约定第 9 章）；
 * GET /dicts/{dictType} 已登录；PUT /dicts/{dictType} 整体替换（system:manage）
 */
@Tag(name = "字典")
@RestController
@RequestMapping("/api/v1/dicts")
public class DictController {

    private final DictItemMapper dictItemMapper;
    private final DictAdminService dictAdminService;

    public DictController(DictItemMapper dictItemMapper, DictAdminService dictAdminService) {
        this.dictItemMapper = dictItemMapper;
        this.dictAdminService = dictAdminService;
    }

    @Operation(summary = "全部枚举字典（前端不要写死枚举中文名）；响应结构：枚举名 → (枚举值 → 中文名)")
    @GetMapping("/enums")
    public Map<String, Map<String, String>> enums() {
        return EnumDict.ALL;
    }

    @Operation(summary = "自定义字典项（dict_item 表）")
    @GetMapping("/{dictType}")
    public List<DictItem> dictItems(@PathVariable String dictType) {
        return dictItemMapper.selectList(new LambdaQueryWrapper<DictItem>()
                .eq(DictItem::getDictType, dictType)
                .eq(DictItem::getIsEnabled, true)
                .eq(DictItem::getDeleted, 0L)
                .orderByAsc(DictItem::getSort));
    }

    @Operation(summary = "整体替换某字典类型的条目（M12）",
            description = "权限：system:manage；按 code 匹配更新或新增，请求中未出现的既有编码做逻辑删除；"
                    + "dictType 只允许大写字母 / 数字 / 下划线")
    @PutMapping("/{dictType}")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "UPDATE", resourceType = "dict", resourceId = "#dictType")
    public List<DictItem> replace(@PathVariable String dictType,
                                  @Valid @RequestBody SystemDtos.DictPutRequest request) {
        return dictAdminService.replace(dictType, request);
    }

    /** 枚举常量表（与 API 约定第 9 章一致，新增枚举需同步）：枚举名 → (枚举值 → 中文名) */
    static final class EnumDict {

        static final Map<String, Map<String, String>> ALL = build();

        private static Map<String, Map<String, String>> build() {
            Map<String, Map<String, String>> m = new LinkedHashMap<>();
            m.put("PlanStatus", items("DRAFT:草稿", "ACTIVE:执行中", "ARCHIVED:已归档"));
            m.put("PlanSource", items("BLANK:空白", "TEMPLATE:从模板", "COPY:复制计划"));
            m.put("SubjectType", items("SCHOOL:校级", "MAJOR_GROUP:专业群"));
            m.put("IndicatorLevel", items("A:A 级（校级原始任务）", "A_PLUS:A+ 级（量化拆解任务）", "B:B 级（院级执行任务）"));
            m.put("IndicatorNature", items("CONSTRAINT:约束性", "DEVELOPMENT:发展性"));
            m.put("MeasureType", items("NUMBER:数值", "BOOLEAN:是否", "PERCENT_QUANTIFIABLE:百分比-可量化", "PERCENT_DIRECT:百分比-不可量化"));
            m.put("StatCaliber", items("INCREMENT:增量累计型", "STOCK:存量时点型", "GROWTH:增长率型", "RATIO:占比型", "DIRECT_PERCENT:直填百分比型", "BOOLEAN:是否型"));
            m.put("TargetDirection", items("HIGHER_BETTER:越大越好", "LOWER_BETTER:越小越好"));
            m.put("RollupMode", items("AUTO_SUM:自动汇总", "MANUAL:手工认定"));
            m.put("OrgUnitType", items("COLLEGE:二级学院", "INTERNATIONAL_COLLEGE:国际学院", "MARXISM_COLLEGE:马克思主义学院", "SCHOOL_OFFICE:校办公室"));
            m.put("Role", items("ADMIN:平台管理员", "LEADER:校领导", "SCHOOL_OWNER:校级项目负责人", "UNIT_OWNER:学院项目负责人"));
            m.put("TaskStatus", items("PENDING_DISTRIBUTION:待下发", "DISTRIBUTED:已下发", "IN_PROGRESS:执行中", "TRANSFERRED_OUT:已全部转出", "CLOSED:已结束"));
            m.put("TaskYearStatus", items("NOT_ACTIVATED:未激活", "IN_PROGRESS:进行中", "PENDING_REVIEW:待审核", "ADJUSTING:调整审批中", "ACHIEVED:已达标", "NOT_ACHIEVED:未达标", "MAKEUP_PENDING:待补做", "MADE_UP:已补做"));
            m.put("SubmissionStatus", items("DRAFT:草稿", "PENDING_REVIEW:待审核", "APPROVED:已通过", "REJECTED:已驳回", "WITHDRAWN:已撤回"));
            m.put("ReviewResult", items("APPROVED:通过", "REJECTED:驳回"));
            m.put("AdjustmentType", items("POSTPONE:延期", "TRANSFER:转移", "POSTPONE_TRANSFER:延期+转移", "TARGET_CHANGE:目标调整"));
            m.put("AdjustmentStatus", items("PENDING_OWNER_REVIEW:待初审", "PENDING_RECEIVER_CONFIRM:待接收方确认", "PENDING_LEADER_APPROVAL:待校领导审批", "APPROVED:已生效", "REJECTED:已驳回", "WITHDRAWN:已撤回"));
            m.put("ApprovalNode", items("OWNER_REVIEW:校级负责人初审", "RECEIVER_CONFIRM:接收方确认", "LEADER_APPROVAL:校领导审批"));
            m.put("Decision", items("APPROVE:同意", "REJECT:驳回（拒绝）"));
            m.put("MilestoneStatus", items("NOT_STARTED:未开始", "IN_PROGRESS:进行中", "COMPLETED:已完成", "OVERDUE:逾期", "POSTPONED:已延期"));
            m.put("FileBizType", items("SUBMISSION_ATTACHMENT:填报材料", "ADJUSTMENT_ATTACHMENT:调整附件", "MILESTONE_ATTACHMENT:里程碑附件", "IMPORT_FILE:导入文件"));
            m.put("FileCategory", items("DOCUMENT:文档", "PDF:PDF", "IMAGE:图片", "VIDEO:视频", "ARCHIVE:压缩包"));
            m.put("PreviewType", items("IMAGE:IMAGE", "PDF:PDF", "OFFICE:OFFICE", "VIDEO:VIDEO", "UNSUPPORTED:UNSUPPORTED"));
            m.put("ExportType", items("INDICATORS:指标体系", "TASKS:B 任务", "STATS_REPORT:统计报表", "SETTLEMENT:核算结果", "AUDIT_LOGS:操作日志"));
            m.put("JobStatus", items("PENDING:排队中", "RUNNING:执行中", "SUCCEEDED:成功", "FAILED:失败"));
            m.put("NotificationType", items("TASK_DISTRIBUTED:任务下发", "DEADLINE_REMINDER:截止提醒", "REVIEW_RESULT:审核结果", "ADJUSTMENT_TODO:调整待办", "ADJUSTMENT_RESULT:调整结果", "WARNING:预警"));
            m.put("AuditAction", items("LOGIN:LOGIN", "CREATE:CREATE", "UPDATE:UPDATE", "DELETE:DELETE", "IMPORT:IMPORT", "EXPORT:EXPORT", "FINALIZE:FINALIZE", "DISTRIBUTE:DISTRIBUTE", "ACCEPT:ACCEPT", "SUBMIT:SUBMIT", "WITHDRAW:WITHDRAW", "REVIEW:REVIEW", "ADJUST_APPLY:ADJUST_APPLY", "ADJUST_DECIDE:ADJUST_DECIDE", "SETTLE:SETTLE", "UNLOCK:UNLOCK", "PERMISSION_CHANGE:PERMISSION_CHANGE", "FILE_DOWNLOAD:FILE_DOWNLOAD", "FILE_PREVIEW:FILE_PREVIEW", "SYNC_ORG:SYNC_ORG"));
            m.put("ClientType", items("WEB:WEB", "FEISHU_PC:FEISHU_PC", "FEISHU_MOBILE:FEISHU_MOBILE", "SCREEN:SCREEN"));
            m.put("PlanYearStatus", items("NOT_ACTIVATED:未激活", "ACTIVE:已激活", "LOCKED:已核算锁定"));
            m.put("IndicatorStatus", items("DRAFT:草稿", "FINALIZED:已定稿", "DISTRIBUTED:已下发"));
            m.put("MakeupStatus", items("PENDING:待补做", "DONE:已补做", "CANCELLED:已取消"));
            m.put("FileStatus", items("TEMP:临时", "BOUND:已绑定"));
            m.put("StorageType", items("LOCAL:本地", "MINIO:对象存储"));
            m.put("PushStatus", items("PENDING:待推送", "SENT:已推送", "FAILED:推送失败", "SKIPPED:无需推送"));
            m.put("AchieveStatus", items("NOT_STARTED:未开始", "IN_PROGRESS:进行中", "ACHIEVED:已达成", "EXCEEDED:超额达成", "NOT_ACHIEVED:未达成", "LAGGING:进度滞后"));
            m.put("TranscodeStatus", items("PROCESSING:转码中", "SUCCEEDED:成功", "FAILED:失败"));
            m.put("UploadSessionStatus", items("UPLOADING:上传中", "COMPLETED:已完成", "EXPIRED:已过期"));
            m.put("FeishuEventStatus", items("RECEIVED:已接收", "PROCESSED:已处理", "FAILED:处理失败"));
            return m;
        }

        private static Map<String, String> items(String... pairs) {
            Map<String, String> map = new LinkedHashMap<>();
            for (String p : pairs) {
                int idx = p.indexOf(':');
                map.put(p.substring(0, idx), p.substring(idx + 1));
            }
            return map;
        }
    }
}
