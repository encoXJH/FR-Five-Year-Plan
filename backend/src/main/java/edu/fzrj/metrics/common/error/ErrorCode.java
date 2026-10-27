package edu.fzrj.metrics.common.error;

import lombok.Getter;

/**
 * 错误码（API 约定 7.2，含义发布后不改变、不复用）
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, 200, "成功"),

    // 1xxxx 通用
    PARAM_INVALID(10001, 400, "参数校验失败"),
    BODY_INVALID(10002, 400, "请求体格式错误"),
    NOT_FOUND(10003, 404, "资源不存在"),
    IDEMPOTENT_CONFLICT(10004, 409, "幂等键冲突（相同 Key、不同请求体）"),
    VERSION_CONFLICT(10005, 409, "数据已被他人修改，请刷新后重试"),
    RATE_LIMITED(10006, 429, "请求过于频繁"),
    INTERNAL_ERROR(10099, 500, "系统内部错误"),

    // 2xxxx 认证权限
    UNAUTHORIZED(20001, 401, "未登录或缺少 Token"),
    TOKEN_EXPIRED(20002, 401, "Token 已过期"),
    TOKEN_INVALID(20003, 401, "Token 无效"),
    FORBIDDEN(20004, 403, "没有操作权限"),
    DATA_SCOPE_DENIED(20005, 403, "没有数据权限（超出本人负责范围或本单元）"),
    FEISHU_LOGIN_FAILED(20006, 401, "飞书免登失败"),
    ACCOUNT_DISABLED(20007, 403, "账号已停用，或飞书账号未绑定平台用户"),
    BAD_CREDENTIALS(20008, 401, "用户名或密码错误"),
    ACCOUNT_LOCKED(20009, 403, "登录失败次数过多，账号暂时锁定"),

    // 3xxxx 计划模板
    PLAN_STATUS_INVALID(30001, 409, "计划当前状态不允许此操作"),
    PLAN_ARCHIVED(30002, 409, "计划已归档，只能查看"),
    TEMPLATE_NOT_FOUND(30003, 404, "模板不存在或已停用"),
    PLAN_YEARS_INVALID(30004, 422, "计划起止年份不合法"),

    // 4xxxx 指标
    EXCEL_IMPORT_INVALID(40001, 422, "Excel 导入校验未通过"),
    YEAR_TARGETS_SUM_INVALID(40002, 422, "各年度目标之和不等于五年总量"),
    A_PLUS_SUM_INVALID(40003, 422, "A+ 目标之和不等于 A 目标"),
    INDICATOR_DISTRIBUTED(40004, 409, "指标已下发，不能删除"),
    INDICATOR_FINALIZED(40005, 409, "指标体系已定稿，修改目标需要发起目标调整"),
    LEVEL_INVALID(40006, 422, "层级不合法"),
    CALIBER_MISMATCH(40007, 422, "计量类型与统计口径不匹配"),
    HAS_CHILDREN(40008, 409, "存在下级数据，不能删除"),

    // 5xxxx 分解下发
    ALLOCATION_SUM_INVALID(50001, 422, "B 分解合计与 A+ 目标不一致"),
    TASK_OWNER_MISSING(50002, 422, "B 任务没有指定学院项目负责人"),
    TASK_DISTRIBUTED(50003, 409, "任务已下发"),
    YEAR_NOT_ACTIVATED(50004, 409, "该年度尚未激活，不能填报"),
    INDICATOR_NOT_FINALIZED(50005, 409, "指标体系尚未定稿，不能下发"),
    TASK_HISTORICAL_READ_ONLY(50006, 409, "历史任务只读，不能执行写操作"),

    // 6xxxx 填报审核
    SUBMISSION_STATUS_INVALID(60001, 409, "当前状态不允许此操作"),
    ATTACHMENT_REQUIRED(60002, 422, "缺少必须上传的佐证材料"),
    YEAR_SETTLED(60003, 409, "该年度已核算锁定"),
    REVIEW_COMMENT_REQUIRED(60004, 422, "驳回时必须填写审核意见"),
    CONFIRMED_EXCEEDS_SUBMITTED(60005, 422, "认定值不能大于填报值"),
    VALUE_CALIBER_MISMATCH(60006, 422, "填报值与统计口径不匹配"),

    // 7xxxx 调整审批
    ADJUST_AMOUNT_EXCEEDED(70001, 422, "调整数量超过尚未完成的量"),
    CONSERVATION_VIOLATED(70002, 422, "违反守恒规则"),
    ADJUST_YEAR_INVALID(70003, 422, "目标年份超出规划期，或早于原年份"),
    ADJUST_IN_PROGRESS(70004, 409, "该额度已有进行中的调整申请"),
    NOT_CURRENT_APPROVER(70005, 403, "当前登录人不是这一环节的审批人"),
    ADJUST_TYPE_UNSUPPORTED(70006, 422, "该统计口径不支持此调整类型"),

    // 8xxxx 文件
    FILE_TYPE_UNSUPPORTED(80001, 422, "文件格式不支持"),
    FILE_TOO_LARGE(80002, 413, "文件超过大小限制"),
    PART_INVALID(80003, 422, "分片缺失或校验失败"),
    FILE_NOT_FOUND(80004, 404, "文件不存在，或签名地址已过期"),
    PREVIEW_FAILED(80005, 502, "文件预览转换失败"),

    // 9xxxx 集成
    FEISHU_API_FAILED(90001, 502, "飞书接口调用失败"),
    FEISHU_SIGNATURE_INVALID(90002, 401, "飞书回调签名校验失败"),
    MIDDLE_PLATFORM_UNAVAILABLE(90003, 503, "中台接口不可用");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
