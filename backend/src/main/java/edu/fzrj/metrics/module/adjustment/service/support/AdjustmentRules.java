package edu.fzrj.metrics.module.adjustment.service.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标调整纯逻辑规则（PRD 5.6、API 约定 15.6 / 第 9 章，错误码 70001-70006）。
 * 不依赖 Spring，便于单元测试。
 */
public final class AdjustmentRules {

    public static final String POSTPONE = "POSTPONE";
    public static final String TRANSFER = "TRANSFER";
    public static final String POSTPONE_TRANSFER = "POSTPONE_TRANSFER";
    public static final String TARGET_CHANGE = "TARGET_CHANGE";

    public static final String OWNER_REVIEW = "OWNER_REVIEW";
    public static final String RECEIVER_CONFIRM = "RECEIVER_CONFIRM";
    public static final String LEADER_APPROVAL = "LEADER_APPROVAL";

    public static final String PENDING_OWNER_REVIEW = "PENDING_OWNER_REVIEW";
    public static final String PENDING_RECEIVER_CONFIRM = "PENDING_RECEIVER_CONFIRM";
    public static final String PENDING_LEADER_APPROVAL = "PENDING_LEADER_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";

    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";

    private static final Set<String> TYPES = Set.of(POSTPONE, TRANSFER, POSTPONE_TRANSFER, TARGET_CHANGE);
    private static final Set<String> PENDING_STATUSES = Set.of(
            PENDING_OWNER_REVIEW, PENDING_RECEIVER_CONFIRM, PENDING_LEADER_APPROVAL);

    private AdjustmentRules() {
    }

    public static boolean isKnownType(String type) {
        return TYPES.contains(type);
    }

    public static boolean isTransfer(String type) {
        return TRANSFER.equals(type) || POSTPONE_TRANSFER.equals(type);
    }

    public static boolean isPostpone(String type) {
        return POSTPONE.equals(type) || POSTPONE_TRANSFER.equals(type);
    }

    /**
     * 70006 调整类型与统计口径的适用关系（API 约定第 9 章）：
     * 延期只适用 INCREMENT / BOOLEAN；转移类只适用 INCREMENT；目标调整适用全部口径。
     */
    public static boolean supports(String caliber, String type) {
        return switch (type) {
            case POSTPONE -> "INCREMENT".equals(caliber) || "BOOLEAN".equals(caliber);
            case TRANSFER, POSTPONE_TRANSFER -> "INCREMENT".equals(caliber);
            case TARGET_CHANGE -> true;
            default -> false;
        };
    }

    /**
     * 审批链（PRD 5.6.2）：校级负责人初审 →（转移类且计划开启接收确认时）接收方确认 → 校领导审批。
     * 目标调整仅管理员发起，只需校领导审批（PRD 5.6.1【P1】）。
     */
    public static List<String> nodesOf(String type, boolean receiverConfirmRequired) {
        if (TARGET_CHANGE.equals(type)) {
            return List.of(LEADER_APPROVAL);
        }
        List<String> nodes = new ArrayList<>();
        nodes.add(OWNER_REVIEW);
        if (isTransfer(type) && receiverConfirmRequired) {
            nodes.add(RECEIVER_CONFIRM);
        }
        nodes.add(LEADER_APPROVAL);
        return nodes;
    }

    /** 节点 → 进行中的调整状态 */
    public static String statusOfNode(String node) {
        return switch (node) {
            case OWNER_REVIEW -> PENDING_OWNER_REVIEW;
            case RECEIVER_CONFIRM -> PENDING_RECEIVER_CONFIRM;
            case LEADER_APPROVAL -> PENDING_LEADER_APPROVAL;
            default -> null;
        };
    }

    /** 进行中的调整状态 → 节点；非进行中返回 null */
    public static String nodeOfStatus(String status) {
        return switch (status) {
            case PENDING_OWNER_REVIEW -> OWNER_REVIEW;
            case PENDING_RECEIVER_CONFIRM -> RECEIVER_CONFIRM;
            case PENDING_LEADER_APPROVAL -> LEADER_APPROVAL;
            default -> null;
        };
    }

    public static boolean isPending(String status) {
        return PENDING_STATUSES.contains(status);
    }

    /** 生效前仅申请人可撤回：仅进行中状态可撤回 */
    public static boolean canWithdraw(String status) {
        return isPending(status);
    }

    /**
     * 70005 当前环节处理人判定：
     * 初审 = 指标有效负责人（期望处理人为空时仅管理员）；接收确认 = 接收单元的学院负责人；终审 = 校领导。
     */
    public static boolean canDecide(String node, Long expectedApproverId, Long expectedUnitId,
                                    Long userId, Long userUnitId, List<String> roles) {
        return switch (node) {
            case OWNER_REVIEW -> expectedApproverId != null
                    ? expectedApproverId.equals(userId)
                    : roles.contains("ADMIN");
            case RECEIVER_CONFIRM -> roles.contains("UNIT_OWNER")
                    && expectedUnitId != null && expectedUnitId.equals(userUnitId);
            case LEADER_APPROVAL -> roles.contains("LEADER");
            default -> false;
        };
    }

    /**
     * 审批推进（PRD 5.6.2：任一环节驳回，整个申请结束）。
     *
     * @return 驳回 → REJECTED；同意且为最后环节 → APPROVED；否则为下一环节对应的进行中状态
     */
    public static String statusOnDecision(String type, String currentStatus, String decision,
                                          boolean receiverConfirmRequired) {
        String currentNode = nodeOfStatus(currentStatus);
        if (currentNode == null) {
            return null;
        }
        if (REJECT.equals(decision)) {
            return REJECTED;
        }
        List<String> nodes = nodesOf(type, receiverConfirmRequired);
        int index = nodes.indexOf(currentNode);
        if (index < 0) {
            return null;
        }
        if (index == nodes.size() - 1) {
            return APPROVED;
        }
        return statusOfNode(nodes.get(index + 1));
    }

    /** 尚未完成的量 = 目标 − 已完成（认定值口径）− 已冻结量；空值按 0 参与 */
    public static BigDecimal remaining(BigDecimal target, BigDecimal actual, BigDecimal frozen) {
        return nvl(target).subtract(nvl(actual)).subtract(nvl(frozen));
    }

    /**
     * 70003 目标年份校验：超出规划期或早于原年份不合法；含延期的类型目标年份必须晚于原年份。
     *
     * @return 不合法时的中文说明；合法返回 null
     */
    public static String yearError(String type, int fromYear, int toYear, int startYear, int endYear) {
        if (toYear < startYear || toYear > endYear) {
            return "目标年份 " + toYear + " 超出规划期（" + startYear + "–" + endYear + "）";
        }
        if (toYear < fromYear) {
            return "目标年份 " + toYear + " 早于原年份 " + fromYear;
        }
        if (isPostpone(type) && toYear == fromYear) {
            return "延期的目标年份必须晚于原年份 " + fromYear;
        }
        return null;
    }

    // ---------------- 试算 ----------------

    /** 年度目标快照条目：taskCode / indicatorCode 二选一（TARGET_CHANGE 用 indicatorCode；year 为 null 表示五年总量） */
    public record TargetEntry(String taskCode, String indicatorCode, Integer year, BigDecimal target) {

        public String key() {
            return (taskCode != null ? "T:" + taskCode : "I:" + indicatorCode) + "@" + year;
        }
    }

    /** 数量移动项（延期 / 转移） */
    public record MoveItem(String fromCode, Integer fromYear, String toCode, Integer toYear, BigDecimal amount) {
    }

    /** 目标设定项（TARGET_CHANGE） */
    public record SetItem(String code, Integer year, BigDecimal newTarget) {
    }

    /**
     * 试算移动类调整后的年度目标：from 条目必须存在，to 条目不存在时按 0 新建（接收方自动新建任务的试算）。
     * 返回按 code + year 排序的新列表；输入列表不被修改。
     */
    public static List<TargetEntry> applyMoves(List<TargetEntry> before, List<MoveItem> items) {
        Map<String, TargetEntry> map = new LinkedHashMap<>();
        before.forEach(e -> map.put(e.key(), e));
        for (MoveItem item : items) {
            String fromKey = "T:" + item.fromCode() + "@" + item.fromYear();
            TargetEntry from = map.get(fromKey);
            if (from == null) {
                throw new IllegalArgumentException("转出年度条目不存在：" + item.fromCode() + " " + item.fromYear());
            }
            map.put(fromKey, new TargetEntry(from.taskCode(), null, from.year(),
                    nvl(from.target()).subtract(item.amount())));
            String toKey = "T:" + item.toCode() + "@" + item.toYear();
            TargetEntry to = map.get(toKey);
            map.put(toKey, new TargetEntry(item.toCode(), null, item.toYear(),
                    nvl(to == null ? null : to.target()).add(item.amount())));
        }
        return map.values().stream()
                .sorted(Comparator.comparing((TargetEntry e) -> e.taskCode() == null ? "" : e.taskCode())
                        .thenComparing(e -> e.year() == null ? 0 : e.year()))
                .toList();
    }

    /** 试算目标调整：按 code + year 覆盖目标值（year 为 null 表示五年总量）；条目不存在时新增 */
    public static List<TargetEntry> applySetItems(List<TargetEntry> before, List<SetItem> items) {
        Map<String, TargetEntry> map = new LinkedHashMap<>();
        before.forEach(e -> map.put(e.key(), e));
        for (SetItem item : items) {
            String key = "I:" + item.code() + "@" + item.year();
            map.put(key, new TargetEntry(null, item.code(), item.year(), item.newTarget()));
        }
        return map.values().stream()
                .sorted(Comparator.comparing((TargetEntry e) -> e.indicatorCode() == null ? "" : e.indicatorCode())
                        .thenComparing(e -> e.year() == null ? Integer.MAX_VALUE : e.year()))
                .toList();
    }

    /**
     * 70002 守恒校验（PRD 4.5）：逐年比对「下级合计」与「上级目标」。
     * 只对求和口径（INCREMENT / STOCK / BOOLEAN）校验；比率类直接通过。
     *
     * @return 逐条中文错误说明；守恒返回空列表
     */
    public static List<String> conservationErrors(String caliber, String parentLabel,
                                                  Map<Integer, BigDecimal> childSumByYear,
                                                  Map<Integer, BigDecimal> parentTargetByYear) {
        List<String> errors = new ArrayList<>();
        if (!"INCREMENT".equals(caliber) && !"STOCK".equals(caliber) && !"BOOLEAN".equals(caliber)) {
            return errors;
        }
        Set<Integer> years = new java.util.TreeSet<>();
        years.addAll(childSumByYear.keySet());
        years.addAll(parentTargetByYear.keySet());
        for (Integer year : years) {
            BigDecimal child = nvl(childSumByYear.get(year));
            BigDecimal parent = nvl(parentTargetByYear.get(year));
            if (child.compareTo(parent) != 0) {
                errors.add(year + " 年：下级合计 " + child.stripTrailingZeros().toPlainString()
                        + " ≠ " + parentLabel + " " + parent.stripTrailingZeros().toPlainString());
            }
        }
        return errors;
    }

    /** 40002 语义（TARGET_CHANGE 复用）：INCREMENT / BOOLEAN 各年度目标之和应等于五年总量；其余口径不校验 */
    public static boolean yearSumMatchesTotal(String caliber, Map<Integer, BigDecimal> yearTargets,
                                              BigDecimal totalTarget) {
        if (!"INCREMENT".equals(caliber) && !"BOOLEAN".equals(caliber)) {
            return true;
        }
        if (totalTarget == null) {
            return false;
        }
        BigDecimal sum = yearTargets.values().stream().reduce(BigDecimal.ZERO,
                (acc, v) -> acc.add(nvl(v)), BigDecimal::add);
        return sum.compareTo(totalTarget) == 0;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
