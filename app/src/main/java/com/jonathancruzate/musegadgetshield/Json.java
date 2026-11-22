package com.jonathancruzate.musegadgetshield;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer covering what the gadget protocols exchange:
 * objects (insertion-ordered), arrays, strings, numbers, booleans, null.
 *
 * Parsed numbers are {@link Long} when integral and {@link Double}
 * otherwise, mirroring how the Python SDK reads them (``int``/``float``).
 * Serialization is compact (no spaces), like ``json.dumps(separators=
 * (",", ":"))``.
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw new IllegalArgumentException("trailing data after JSON value");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("JSON value is not an object");
        return (Map<String, Object>) value;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("non-finite number is not JSON");
            }
            sb.append(Double.toString(d));
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else {
            throw new IllegalArgumentException("not JSON-serializable: " + value.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) { this.text = text; }

        boolean atEnd() { return pos >= text.length(); }

        void skipWs() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWs();
            if (atEnd()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = text.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw new IllegalArgumentException("invalid JSON literal at " + pos);
            }
            pos += literal.length();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (!atEnd() && text.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw new IllegalArgumentException("expected object key at " + pos);
                }
                String key = parseString();
                skipWs();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw new IllegalArgumentException("expected ':' at " + pos);
                }
                pos++;
                map.put(key, parseValue());
                skipWs();
                if (atEnd()) throw new IllegalArgumentException("unterminated object");
                char c = text.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw new IllegalArgumentException("expected ',' or '}' at " + pos);
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (!atEnd() && text.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (atEnd()) throw new IllegalArgumentException("unterminated array");
                char c = text.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw new IllegalArgumentException("expected ',' or ']' at " + pos);
            }
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (true) {
                if (atEnd()) throw new IllegalArgumentException("unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw new IllegalArgumentException("unterminated escape");
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > text.length()) {
                                throw new IllegalArgumentException("bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-') pos++;
            boolean floating = false;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if (c == '.' || c == 'e' || c == 'E') floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) throw new IllegalArgumentException("invalid JSON value at " + start);
            String num = text.substring(start, pos);
            if (!floating) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException overflow) {
                    return Double.parseDouble(num);
                }
            }
            return Double.parseDouble(num);
        }
    }
}
