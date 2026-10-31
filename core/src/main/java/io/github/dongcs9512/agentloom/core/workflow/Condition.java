package io.github.dongcs9512.agentloom.core.workflow;

import java.util.Map;

/**
 * 工作流里的条件表达式，语法刻意做得极简，避免引入脚本引擎：
 *
 * <pre>
 *   slots.amount &gt; 10000
 *   slots.amount &gt; params.handoff-threshold     // 右侧可以引用工作流参数
 *   slots.incident contains 辞退
 *   slots.city == "杭州"
 * </pre>
 *
 * 支持的操作符：== != &gt; &lt; &gt;= &lt;= contains
 */
public final class Condition {

    public enum Operator {
        EQ, NE, GT, LT, GE, LE, CONTAINS
    }

    private static final String PARAM_PREFIX = "params.";

    private final String slotName;
    private final Operator operator;
    /** 字面量；引用参数时为 null。 */
    private final String expected;
    /** 引用的工作流参数名；字面量时为 null。 */
    private final String paramName;

    private Condition(String slotName, Operator operator, String expected, String paramName) {
        this.slotName = slotName;
        this.operator = operator;
        this.expected = expected;
        this.paramName = paramName;
    }

    public static Condition parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("条件表达式不能为空");
        }
        String text = expression.trim();

        // 双字符操作符要先匹配，否则会被单字符的 > 抢掉
        for (String op : new String[]{">=", "<=", "!=", "=="}) {
            int idx = text.indexOf(op);
            if (idx > 0) {
                return build(text, idx, op, operatorOf(op));
            }
        }
        for (String op : new String[]{">", "<"}) {
            int idx = text.indexOf(op);
            if (idx > 0) {
                return build(text, idx, op, operatorOf(op));
            }
        }
        int containsIdx = text.indexOf(" contains ");
        if (containsIdx > 0) {
            return build(text, containsIdx, " contains ", Operator.CONTAINS);
        }
        throw new IllegalArgumentException("无法解析的条件表达式: " + expression
                + "，支持 == != > < >= <= contains");
    }

    private static Condition build(String text, int idx, String token, Operator operator) {
        String left = text.substring(0, idx).trim();
        String right = text.substring(idx + token.length()).trim();
        if (!left.startsWith("slots.")) {
            throw new IllegalArgumentException("条件左侧必须是 slots.<字段名>: " + text);
        }
        String slotName = left.substring("slots.".length()).trim();
        if (slotName.isEmpty()) {
            throw new IllegalArgumentException("条件缺少槽位名: " + text);
        }

        String expected = unquote(right);
        if (expected.startsWith(PARAM_PREFIX)) {
            String paramName = expected.substring(PARAM_PREFIX.length()).trim();
            if (paramName.isEmpty()) {
                throw new IllegalArgumentException("条件引用了空参数名: " + text);
            }
            // 具体数值在求值时从工作流参数里取
            return new Condition(slotName, operator, null, paramName);
        }
        if (isNumericOperator(operator)) {
            // 加载时就把配错的阈值挡住，别等线上真跑到这一步才炸
            try {
                Double.parseDouble(expected.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("数值比较的右侧必须是数字: " + text);
            }
        }
        return new Condition(slotName, operator, expected, null);
    }

    private static boolean isNumericOperator(Operator operator) {
        return operator == Operator.GT || operator == Operator.LT
                || operator == Operator.GE || operator == Operator.LE;
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2
                && ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static Operator operatorOf(String token) {
        return switch (token.trim()) {
            case "==" -> Operator.EQ;
            case "!=" -> Operator.NE;
            case ">" -> Operator.GT;
            case "<" -> Operator.LT;
            case ">=" -> Operator.GE;
            case "<=" -> Operator.LE;
            case "contains" -> Operator.CONTAINS;
            default -> throw new IllegalArgumentException("未知操作符: " + token);
        };
    }

    /** 没有工作流参数的场景。 */
    public boolean evaluate(Map<String, Object> slots) {
        return evaluate(slots, Map.of());
    }

    public boolean evaluate(Map<String, Object> slots, Map<String, String> params) {
        Object value = slots == null ? null : slots.get(slotName);
        String actual = value == null ? "" : String.valueOf(value);
        String right = paramName == null
                ? expected
                : (params == null ? "" : params.getOrDefault(paramName, ""));

        return switch (operator) {
            case EQ -> actual.equals(right);
            case NE -> !actual.equals(right);
            case CONTAINS -> right != null && actual.contains(right);
            case GT, LT, GE, LE -> compareNumbers(actual, right);
        };
    }

    /**
     * 槽位没填、或值是「两万」这种非数字时返回 false（走 otherwise 分支）。
     * 条件判断不该因为用户说话方式把对话打断。
     */
    private boolean compareNumbers(String actual, String right) {
        double left;
        double expectedValue;
        try {
            left = Double.parseDouble(actual.trim());
            expectedValue = Double.parseDouble(right == null ? "" : right.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return switch (operator) {
            case GT -> left > expectedValue;
            case LT -> left < expectedValue;
            case GE -> left >= expectedValue;
            case LE -> left <= expectedValue;
            default -> false;
        };
    }

    public String slotName() {
        return slotName;
    }

    public Operator operator() {
        return operator;
    }

    public String expected() {
        return expected;
    }

    /** 右侧引用的工作流参数名；没有则为 null。 */
    public String paramName() {
        return paramName;
    }

    public boolean usesParam() {
        return paramName != null;
    }

    public boolean isNumericComparison() {
        return isNumericOperator(operator);
    }
}
