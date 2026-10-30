package io.github.dongcs9512.agentloom.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolScanner {

    public List<Tool> scan(Object target) {
        List<Tool> tools = new ArrayList<>();
        for (Method method : target.getClass().getDeclaredMethods()) {
            ToolDef def = method.getAnnotation(ToolDef.class);
            if (def == null) {
                continue;
            }
            tools.add(new ReflectiveTool(target, method, def));
        }
        return tools;
    }

    private static final class ReflectiveTool implements Tool {
        private final Object target;
        private final Method method;
        private final ToolDef def;
        private final Map<String, Object> schema;
        private final List<String> required;
        private final String[] paramNames;
        private final Class<?>[] paramTypes;

        ReflectiveTool(Object target, Method method, ToolDef def) {
            this.target = target;
            this.method = method;
            this.def = def;

            Parameter[] params = method.getParameters();
            this.paramNames = new String[params.length];
            this.paramTypes = method.getParameterTypes();
            this.required = new ArrayList<>();

            Map<String, Object> properties = new LinkedHashMap<>();
            for (int i = 0; i < params.length; i++) {
                Parameter p = params[i];
                paramNames[i] = p.getName();
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", typeOf(p.getType()));
                ToolParam tp = p.getAnnotation(ToolParam.class);
                if (tp != null && !tp.description().isEmpty()) {
                    prop.put("description", tp.description());
                }
                if (tp != null && tp.required()) {
                    required.add(p.getName());
                }
                properties.put(p.getName(), prop);
            }

            this.schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
        }

        @Override
        public String name() {
            return def.name();
        }

        @Override
        public String description() {
            return def.description();
        }

        @Override
        public Map<String, Object> parameters() {
            return schema;
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            Object[] args = new Object[paramNames.length];
            for (int i = 0; i < paramNames.length; i++) {
                if (!invocation.arguments().containsKey(paramNames[i])) {
                    if (required.contains(paramNames[i])) {
                        return ToolResult.error("缺少必填参数: " + paramNames[i]);
                    }
                    args[i] = null;
                } else {
                    args[i] = coerce(invocation.arguments().get(paramNames[i]), paramTypes[i]);
                }
            }
            try {
                Object result = method.invoke(target, args);
                return ToolResult.ok(result == null ? "" : String.valueOf(result));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                return ToolResult.error(cause == null || cause.getMessage() == null ? "工具执行异常" : cause.getMessage());
            } catch (Exception e) {
                return ToolResult.error(e.getMessage() == null ? "工具执行异常" : e.getMessage());
            }
        }
    }

    private static String typeOf(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class
                || type == BigDecimal.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        return "string";
    }

    private static Object coerce(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return ((Number) value).intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return ((Number) value).longValue();
        }
        if (targetType == short.class || targetType == Short.class) {
            return ((Number) value).shortValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return ((Number) value).doubleValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return ((Number) value).floatValue();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return value instanceof Boolean ? value : Boolean.parseBoolean(String.valueOf(value));
        }
        return value;
    }
}
