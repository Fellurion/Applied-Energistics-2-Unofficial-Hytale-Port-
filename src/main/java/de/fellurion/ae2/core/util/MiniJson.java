/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer/parser used to serialize core state (cells, machines) into compact canonical
 * strings that the integration layer can store in Hytale item/block metadata.
 * <p>
 * Supported values: {@link Map} (written in iteration order), {@link List}, {@link String}, {@link Long}/{@link Integer}
 * (written as integers), {@link Double}/{@link Float}, {@link Boolean} and {@code null}. Parsing returns
 * {@link LinkedHashMap}, {@link ArrayList}, {@link String}, {@link Long} (integers), {@link Double} (fractions),
 * {@link Boolean} and {@code null}.
 * <p>
 * Not derived from AE2 (AE2 uses Minecraft's NBT/codecs for this purpose).
 */
public final class MiniJson {
    private MiniJson() {
    }

    public static String write(Object value) {
        var sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b);
            case Integer i -> sb.append(i);
            case Long l -> sb.append(l);
            case Short s -> sb.append(s);
            case Byte b -> sb.append(b);
            case Double d -> writeDouble(sb, d);
            case Float f -> writeDouble(sb, f.doubleValue());
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> it -> {
                sb.append('[');
                boolean first = true;
                for (var o : it) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, o);
                }
                sb.append(']');
            }
            case Enum<?> e -> writeString(sb, e.name());
            default -> throw new IllegalArgumentException("Unsupported JSON value: " + v.getClass());
        }
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("Cannot write non-finite double: " + d);
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            // keep a fraction marker so it is parsed back as a double
            sb.append((long) d).append(".0");
        } else {
            sb.append(d);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String json) {
        var p = new Parser(json);
        p.skipWs();
        var v = p.value();
        p.skipWs();
        if (p.pos != json.length()) {
            throw p.error("Trailing characters");
        }
        return v;
    }

    /** Parses a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        var v = parse(json);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos + " in JSON");
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("Unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String lit, Object value) {
            if (!s.startsWith(lit, pos)) {
                throw error("Unexpected token");
            }
            pos += lit.length();
            return value;
        }

        Map<String, Object> object() {
            var map = new LinkedHashMap<String, Object>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw error("Expected key");
                }
                var key = string();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, value());
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("Expected , or }");
                }
            }
        }

        List<Object> array() {
            var list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("Expected , or ]");
                }
            }
        }

        String string() {
            expect('"');
            var sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("Bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            boolean fraction = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') {
                    fraction = true;
                } else if (!(c == '-' || c == '+' || Character.isDigit(c))) {
                    break;
                }
                pos++;
            }
            var str = s.substring(start, pos);
            if (str.isEmpty()) {
                throw error("Unexpected character");
            }
            try {
                return fraction ? (Object) Double.parseDouble(str) : (Object) Long.parseLong(str);
            } catch (NumberFormatException e) {
                throw error("Bad number '" + str + "'");
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("Unexpected end");
            }
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) {
            if (next() != c) {
                throw error("Expected '" + c + "'");
            }
        }
    }

    // --- small typed accessors for parsed values ---

    public static long getLong(Map<?, ?> map, String key, long def) {
        var v = map.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    public static int getInt(Map<?, ?> map, String key, int def) {
        var v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public static double getDouble(Map<?, ?> map, String key, double def) {
        var v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public static boolean getBool(Map<?, ?> map, String key, boolean def) {
        var v = map.get(key);
        return v instanceof Boolean b ? b : def;
    }

    public static String getString(Map<?, ?> map, String key, String def) {
        var v = map.get(key);
        return v instanceof String s ? s : def;
    }

    public static List<?> getList(Map<?, ?> map, String key) {
        var v = map.get(key);
        return v instanceof List<?> l ? l : List.of();
    }

    public static Map<?, ?> getMap(Map<?, ?> map, String key) {
        var v = map.get(key);
        return v instanceof Map<?, ?> m ? m : Map.of();
    }

    public static <E extends Enum<E>> E getEnum(Map<?, ?> map, String key, Class<E> type, E def) {
        var v = map.get(key);
        if (v instanceof String s) {
            try {
                return Enum.valueOf(type, s);
            } catch (IllegalArgumentException ignored) {
                return def;
            }
        }
        return def;
    }
}
