package edu.fzrj.metrics.module.submission.service.support;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 填报值 / 认定值校验（纯逻辑，API 约定 3.5 与错误码 60002 / 60005 / 60006）：
 * MetricValue 只能填对应统计口径的字段，其余字段必须为 null。
 */
public final class SubmissionValueRules {

    private SubmissionValueRules() {
    }

    /**
     * 60006 口径字段校验：对应口径的字段必填，其余字段必须为 null。
     *
     * @return 不合法时的中文说明；合法返回 null
     */
    public static String caliberError(String caliber, BigDecimal amount, BigDecimal total,
                                      BigDecimal numerator, BigDecimal denominator,
                                      BigDecimal percent, Boolean achieved) {
        return switch (caliber) {
            case "INCREMENT" -> requireOnly(amount != null, "amount（本次新增数量）必填",
                    total, numerator, denominator, percent, achieved);
            case "STOCK", "GROWTH" -> requireOnly(total != null, "total（当前总量）必填",
                    amount, numerator, denominator, percent, achieved);
            case "RATIO" -> {
                String error = requireOnly(numerator != null && denominator != null,
                        "numerator（分子）与 denominator（分母）必填", amount, total, percent, achieved);
                if (error == null && denominator.compareTo(BigDecimal.ZERO) <= 0) {
                    error = "denominator（分母）必须大于 0";
                }
                yield error;
            }
            case "DIRECT_PERCENT" -> requireOnly(percent != null, "percent（百分比，小数）必填",
                    amount, total, numerator, denominator, achieved);
            case "BOOLEAN" -> requireOnly(achieved != null, "achieved（是否完成）必填",
                    amount, total, numerator, denominator, percent);
            default -> "未知统计口径：" + caliber;
        };
    }

    /**
     * 60005：认定值不能大于填报值（按口径比较对应字段；BOOLEAN 不允许把「未完成」认定为「完成」）。
     */
    public static boolean exceedsSubmitted(String caliber,
                                           BigDecimal amount, BigDecimal total, BigDecimal numerator,
                                           BigDecimal denominator, BigDecimal percent, Boolean achieved,
                                           BigDecimal aAmount, BigDecimal aTotal, BigDecimal aNumerator,
                                           BigDecimal aDenominator, BigDecimal aPercent, Boolean aAchieved) {
        return switch (caliber) {
            case "INCREMENT" -> greater(aAmount, amount);
            case "STOCK", "GROWTH" -> greater(aTotal, total);
            case "RATIO" -> greater(aNumerator, numerator) || greater(aDenominator, denominator);
            case "DIRECT_PERCENT" -> greater(aPercent, percent);
            case "BOOLEAN" -> Boolean.FALSE.equals(achieved) && Boolean.TRUE.equals(aAchieved);
            default -> false;
        };
    }

    /**
     * 60002：佐证材料数量不少于指标要求；直填百分比型必须附数据来源说明。
     *
     * @return 不合法时的中文说明；合法返回 null
     */
    public static String attachmentError(String caliber, int minFileCount, int fileCount, String sourceNote) {
        if (fileCount < minFileCount) {
            return "缺少必须上传的佐证材料：至少 " + minFileCount + " 个，当前 " + fileCount + " 个";
        }
        if ("DIRECT_PERCENT".equals(caliber) && !StringUtils.hasText(sourceNote)) {
            return "直填百分比型必须填写数据来源说明";
        }
        return null;
    }

    // ---------------- 内部 ----------------

    private static String requireOnly(boolean requiredPresent, String requiredHint, Object... mustBeNull) {
        if (!requiredPresent) {
            return requiredHint;
        }
        for (Object value : mustBeNull) {
            if (value != null) {
                return "只能填写对应统计口径的字段，其余字段必须为 null";
            }
        }
        return null;
    }

    private static boolean greater(BigDecimal approved, BigDecimal submitted) {
        return approved != null && submitted != null && approved.compareTo(submitted) > 0;
    }
}
