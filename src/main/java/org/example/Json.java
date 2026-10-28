package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖 JSON 工具（Java 8 版）：写值 + 解析，移植自 DecisionModelClient 的 jsonStr/writeValue/JsonParser。
 * 解析返回 LinkedHashMap / ArrayList / String / Long / Double / Boolean / null。
 */
public final class Json {

    private Json() { }

    // ---------- 写 ----------

    /** 对象序列化为 JSON 文本（中文原样输出）。 */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { sb.append(str((String) v)); return; }
        if (v instanceof Boolean) { sb.append(v.toString()); return; }
        if (v instanceof Number) { sb.append(v.toString()); return; }
        if (v instanceof Map) {
            sb.append('{');
            boolean f = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!f) sb.append(',');
                f = false;
                sb.append(str(String.valueOf(e.getKey()))).append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable) {
            sb.append('[');
            boolean f = true;
            for (Object o : (Iterable<?>) v) {
                if (!f) sb.append(',');
                f = false;
                writeValue(sb, o);
            }
            sb.append(']');
            return;
        }
        sb.append(str(String.valueOf(v)));
    }

    /** JSON 字符串转义（引号、反斜杠、控制字符）。 */
    public static String str(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ---------- 读 ----------

    /** 解析 JSON 文本为 Map / List / String / Long / Double / Boolean / null。 */
    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw p.err("多余字符");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object v = parse(s);
        if (!(v instanceof Map)) throw new IllegalArgumentException("期望 JSON 对象: " + abbrev(s));
        return (Map<String, Object>) v;
    }

    private static String abbrev(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static final class P {
        private final String s;
        private int i;

        P(String s) { this.s = s; }

        Object value() {
            if (i >= s.length()) throw err("意外结束");
            char c = s.charAt(i);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': lit("true"); return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null"); return null;
                default: return number();
            }
        }

        Map<String, Object> obj() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (peek() == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        List<Object> arr() {
            expect('[');
            List<Object> l = new ArrayList<Object>();
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (peek() == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("字符串未闭合");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw err("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            if (t.isEmpty()) throw err("非法数字");
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                return Long.parseLong(t);
            }
            return Double.parseDouble(t);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            if (i >= s.length()) throw err("意外结束");
            return s.charAt(i);
        }

        void lit(String t) {
            if (!s.startsWith(t, i)) throw err("应为 " + t);
            i += t.length();
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw err("应为 '" + c + "'");
            i++;
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("JSON 解析错误 @" + i + ": " + msg);
        }
    }
}
