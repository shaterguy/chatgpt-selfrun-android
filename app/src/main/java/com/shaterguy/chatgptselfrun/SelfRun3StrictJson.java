package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Strict RFC-8259-style JSON syntax gate placed in front of Android's intentionally lenient JSONTokener. */
final class SelfRun3StrictJson {
    static JSONObject parseObject(String raw) {
        if (raw == null) throw new IllegalArgumentException("JSON is null");
        Parser parser = new Parser(raw);
        parser.ws();
        parser.object();
        parser.ws();
        if (!parser.end()) throw parser.error("trailing data");
        try { return new JSONObject(raw); }
        catch (Exception error) { throw new IllegalArgumentException("JSON object rejected", error); }
    }

    static boolean isSyntacticallyValidObject(String raw) {
        try { parseObject(raw); return true; }
        catch (RuntimeException error) { return false; }
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) { this.text = text; }
        boolean end() { return at == text.length(); }
        void ws() { while (!end() && (text.charAt(at) == ' ' || text.charAt(at) == '\n' || text.charAt(at) == '\r' || text.charAt(at) == '\t')) at++; }
        IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at " + at); }
        char next() { if (end()) throw error("unexpected end"); return text.charAt(at++); }
        boolean take(char c) { if (!end() && text.charAt(at) == c) { at++; return true; } return false; }
        void expect(char c) { if (!take(c)) throw error("expected " + c); }

        void value() {
            ws();
            if (end()) throw error("value required");
            char c = text.charAt(at);
            if (c == '{') object();
            else if (c == '[') array();
            else if (c == '"') string();
            else if (c == 't') literal("true");
            else if (c == 'f') literal("false");
            else if (c == 'n') literal("null");
            else if (c == '-' || (c >= '0' && c <= '9')) number();
            else throw error("invalid value");
        }

        void object() {
            expect('{'); ws();
            if (take('}')) return;
            Set<String> keys = new HashSet<>();
            while (true) {
                ws();
                if (end() || text.charAt(at) != '"') throw error("object key must be a string");
                String key = string();
                if (!keys.add(key)) throw error("duplicate object key");
                ws(); expect(':'); value(); ws();
                if (take('}')) return;
                expect(','); ws();
                if (!end() && text.charAt(at) == '}') throw error("trailing object comma");
            }
        }

        void array() {
            expect('['); ws();
            if (take(']')) return;
            while (true) {
                value(); ws();
                if (take(']')) return;
                expect(','); ws();
                if (!end() && text.charAt(at) == ']') throw error("trailing array comma");
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = next();
                if (c == '"') return out.toString();
                if (c < 0x20) throw error("unescaped control character");
                if (c != '\\') { out.append(c); continue; }
                if (end()) throw error("truncated escape");
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw error("invalid escape");
                }
            }
            throw error("unterminated string");
        }

        char unicode() {
            if (at + 4 > text.length()) throw error("truncated unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = text.charAt(at++);
                int digit = Character.digit(c, 16);
                if (digit < 0) throw error("invalid unicode escape");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        void literal(String value) {
            if (!text.regionMatches(at, value, 0, value.length())) throw error("invalid literal");
            at += value.length();
        }

        void number() {
            if (take('-') && end()) throw error("truncated number");
            if (take('0')) {
                if (!end() && Character.isDigit(text.charAt(at))) throw error("leading zero");
            } else {
                if (end() || text.charAt(at) < '1' || text.charAt(at) > '9') throw error("invalid integer");
                while (!end() && Character.isDigit(text.charAt(at))) at++;
            }
            if (take('.')) {
                int start = at;
                while (!end() && Character.isDigit(text.charAt(at))) at++;
                if (at == start) throw error("fraction digit required");
            }
            if (!end() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (!end() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                int start = at;
                while (!end() && Character.isDigit(text.charAt(at))) at++;
                if (at == start) throw error("exponent digit required");
            }
        }
    }

    private SelfRun3StrictJson() {}
}
