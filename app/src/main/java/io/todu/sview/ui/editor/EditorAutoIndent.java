package io.todu.sview.ui.editor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.List;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只在单次回车时计算缩进，不修改粘贴内容或对整篇代码重新排版。 */
public final class EditorAutoIndent {
    public static final String DEFAULT_INDENT = "    ";
    private static final List<String> VOID_TAGS = Arrays.asList("area", "base", "br", "col", "embed", "hr",
            "img", "input", "link", "meta", "param", "source", "track", "wbr");
    private static final Pattern TAG = Pattern.compile(
            "<(/?)([A-Za-z][\\w:-]*)(?:[^<>\"']|\"[^\"]*\"|'[^']*')*?>");

    private EditorAutoIndent() { }

    public static final class Edit {
        public final String text;
        public final int cursor;

        private Edit(String text, int cursor) {
            this.text = text;
            this.cursor = cursor;
        }
    }

    /** 替换选区并插入换行；括号或标签成对时将结束符留在下一行。 */
    public static Edit newline(String text, int start, int end, String language) {
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        String before = text.substring(lineStart, start);
        String indent = leadingSpace(before);
        String closer = closingToken(text, start, lineStart, language);
        String innerIndent = indent + (closer.isEmpty() ? "" : indentUnit(text, lineStart, indent));
        int tail = end;
        while (tail < text.length() && (text.charAt(tail) == ' ' || text.charAt(tail) == '\t')) tail++;
        boolean paired = !closer.isEmpty() && text.startsWith(closer, tail);
        String insertion = "\n" + innerIndent + (paired ? "\n" + indent : "");
        return new Edit(text.substring(0, start) + insertion + text.substring(paired ? tail : end),
                start + 1 + innerIndent.length());
    }

    /** 保留原有 Tab；空格优先采用附近代码已使用的缩进宽度。 */
    private static String indentUnit(String text, int lineStart, String current) {
        if (current.indexOf('\t') >= 0) return "\t";
        int width = current.isEmpty() ? 0 : current.length();
        int end = lineStart;
        for (int count = 0; end > 0 && count < 80; count++) {
            int start = text.lastIndexOf('\n', end - 2) + 1;
            String line = text.substring(start, end - 1);
            String space = leadingSpace(line);
            if (!line.trim().isEmpty() && !space.isEmpty()) {
                if (space.indexOf('\t') >= 0) return "\t";
                width = width == 0 ? space.length() : Math.min(width, space.length());
            }
            end = start;
        }
        if (width <= 0 || width > 8) return DEFAULT_INDENT;
        char[] spaces = new char[width];
        Arrays.fill(spaces, ' ');
        return new String(spaces);
    }

    private static String leadingSpace(String text) {
        int end = 0;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        return text.substring(0, end);
    }

    /** 扫描到光标，忽略字符串与注释内的括号，只处理当前行未闭合的结构。 */
    private static String closingToken(String text, int cursor, int lineStart, String language) {
        if ("markup".equals(language)) return closingTag(text, cursor, lineStart);
        if (!"javascript".equals(language) && !"css".equals(language) && !"json".equals(language)) return "";
        Deque<Character> stack = new ArrayDeque<>();
        char quote = 0;
        boolean regexClass = false;
        boolean blockComment = false;
        boolean lineComment = false;
        for (int i = 0; i < cursor; i++) {
            char c = text.charAt(i);
            char next = i + 1 < cursor ? text.charAt(i + 1) : 0;
            if (i == lineStart) stack.clear();
            if (lineComment) {
                if (c == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++; }
                continue;
            }
            if (quote != 0) {
                if (c == '\\') i++;
                else if (quote == '/' && c == '[') regexClass = true;
                else if (quote == '/' && c == ']') regexClass = false;
                else if (c == quote && !regexClass) quote = 0;
                continue;
            }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if ("javascript".equals(language) && c == '/' && next == '/') { lineComment = true; i++; continue; }
            if ("javascript".equals(language) && c == '/' && startsRegex(text, i)) { quote = '/'; continue; }
            if (c == '\'' || c == '"' || c == '`') { quote = c; continue; }
            if (c == '{') stack.push('}');
            else if (c == '[') stack.push(']');
            else if (c == '(') stack.push(')');
            else if (!stack.isEmpty() && stack.peek() == c) stack.pop();
        }
        return quote != 0 || blockComment || stack.isEmpty() ? "" : String.valueOf(stack.peek());
    }

    /** 表达式起始位置的斜线按正则处理，避免正则中的括号影响缩进。 */
    private static boolean startsRegex(String text, int slash) {
        int index = slash - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) index--;
        if (index < 0 || "=(:,[!&|?;{}".indexOf(text.charAt(index)) >= 0) return true;
        int end = index + 1;
        while (index >= 0 && Character.isJavaIdentifierPart(text.charAt(index))) index--;
        String word = text.substring(index + 1, end);
        return word.equals("return") || word.equals("throw") || word.equals("yield");
    }

    /** HTML/XML 的普通开始标签增加一级，自闭合标签与 HTML 空元素不增加。 */
    private static String closingTag(String text, int cursor, int lineStart) {
        String prefix = text.substring(0, cursor);
        if (prefix.lastIndexOf("<!--") > prefix.lastIndexOf("-->")) return "";
        String line = text.substring(lineStart, cursor).replaceAll("<!--.*?-->", "");
        Deque<String> tags = new ArrayDeque<>();
        Matcher matcher = TAG.matcher(line);
        while (matcher.find()) {
            String name = matcher.group(2);
            if (!matcher.group(1).isEmpty()) {
                if (!tags.isEmpty() && tags.peek().equalsIgnoreCase(name)) tags.pop();
            } else if (!matcher.group().endsWith("/>") && !VOID_TAGS.contains(name.toLowerCase(Locale.ROOT))) {
                tags.push(name);
            }
        }
        return tags.isEmpty() ? "" : "</" + tags.peek() + ">";
    }
}
