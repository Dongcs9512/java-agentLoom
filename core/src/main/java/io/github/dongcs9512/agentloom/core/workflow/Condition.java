package io.github.dongcs9512.agentloom.core.workflow;

/**
 * 工作流里的条件表达式，语法刻意做得极简，避免引入脚本引擎：
 *
 * <pre>
 *   slots.amount &gt; 10000
 *   slots.incident contains 辞退
 *   slots.city == "杭州"
 *   slots.urgent != true
 * </pre>
 *
 * 支持的操作符：== != &gt; &lt; &gt;= &lt;= contains
 */
public final class Condition {

    public enum Operator {
        EQ, NE, GT, LT, GE, LE, CONTAINS
    }

    private final String slotName;
    private final Operator operator;
    private final String expected;

    private Condition(String slotName, Operator operator, String expected) {
        this.slotName = slotName;
        this.operator = operator;
        this.expected = expected;
    }

    public static Condition parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("条件表达式不能为空");
        }
        String text = expression.trim();

        // 双字符操作符要先匹配，否则会被单字符的 > 抢掉
        String[] twoChar = {">=", "<=", "!=", "=="};
        for (String op : twoChar) {
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
        return new Condition(slotName, operator, unquote(right));
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2
                && ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static Operator operatorOf(String token) {
        return switch (token) {
            case "==" -> Operator.EQ;
            case "!=" -> Operator.NE;
            case ">" -> Operator.GT;
            case "<" -> Operator.LT;
            case ">=" -> Operator.GE;
            case "<=" -> Operator.LE;
            default -> throw new IllegalArgumentException("未知操作符: " + token);
        };
    }

    public boolean evaluate(java.util.Map<String, Object> slots) {
        Object value = slots == null ? null : slots.get(slotName);
        String actual = value == null ? "" : String.valueOf(value);
        return switch (operator) {
            case EQ -> actual.equals(expected);
            case NE -> !actual.equals(expected);
            case CONTAINS -> actual.contains(expected);
            case GT, LT, GE, LE -> compareNumbers(actual);
        };
    }

    private boolean compareNumbers(String actual) {
        double left = parseNumber(actual);
        double right = parseNumber(expected);
        return switch (operator) {
            case GT -> left > right;
            case LT -> left < right;
            case GE -> left >= right;
            case LE -> left <= right;
            default -> false;
        };
    }

    private static double parseNumber(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("条件比较需要数字，实际是: " + raw);
        }
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
}
