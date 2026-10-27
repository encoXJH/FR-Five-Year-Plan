package edu.fzrj.metrics.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志标记：写操作自动记录（API 约定第 10 章）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 动作：LOGIN / CREATE / UPDATE / DELETE / IMPORT / EXPORT / FINALIZE / DISTRIBUTE / ACCEPT / SUBMIT / WITHDRAW / REVIEW / ADJUST_APPLY / ADJUST_DECIDE / SETTLE / UNLOCK / PERMISSION_CHANGE / FILE_DOWNLOAD */
    String action();

    /** 资源类型，如 plan / indicator / task / submission / adjustment / file */
    String resourceType();

    /** 资源 id 的 SpEL 表达式（基于方法参数），如 #planId */
    String resourceId() default "";

    /** 操作原因的 SpEL 表达式（敏感操作必填 reason） */
    String reason() default "";

    /** 变更前数据的 SpEL 表达式（可选；对象会被序列化为 JSON，如 #result.auditBefore） */
    String before() default "";

    /** 变更后数据的 SpEL 表达式（可选；对象会被序列化为 JSON，如 #result.auditAfter） */
    String after() default "";
}
