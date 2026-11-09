package com.wengu.source.experiment;

import com.wengu.source.experiment.CodeExtractionResult.Evidence;
import com.wengu.source.experiment.CodeExtractionResult.KnowledgePoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 源码抽取实验的纯函数：去注释、拼输入、校验代码依据、生成标注表。不调用模型，有单元测试。
 */
final class CodeExtractionSupport {

    private static final String BOM = "﻿";

    private static final List<String> HEADER = List.of("组", "第几次", "序号", "角度", "标题", "要点", "代码依据",
            "代码证明不了的问题", "依据校验", "值得学（Y/N）", "事实错误（Y/N）", "编造动机（Y/N）", "粒度合适（Y/N）", "备注");

    private CodeExtractionSupport() {
    }

    /**
     * 去掉 Java 注释，用来模拟没有详细注释的普通项目。
     * <p>
     * 不用正则：字符串里可能出现 // 和 /*，比如 URL，必须跟踪当前是否在字符串里。
     */
    static String stripComments(String source) {
        String code = source.replace("\r\n", "\n");
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            if (code.startsWith("\"\"\"", i)) {
                int end = endOfTextBlock(code, i);
                out.append(code, i, end);
                i = end;
            } else if (code.charAt(i) == '"' || code.charAt(i) == '\'') {
                int end = endOfLiteral(code, i);
                out.append(code, i, end);
                i = end;
            } else if (code.startsWith("//", i)) {
                int newline = code.indexOf('\n', i);
                i = newline < 0 ? code.length() : newline;
            } else if (code.startsWith("/*", i)) {
                int close = code.indexOf("*/", i + 2);
                i = close < 0 ? code.length() : close + 2;
            } else {
                out.append(code.charAt(i));
                i++;
            }
        }
        return out.toString()
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip() + "\n";
    }

    private static int endOfTextBlock(String code, int start) {
        int i = start + 3;
        while (i < code.length()) {
            if (code.charAt(i) == '\\') {
                i += 2;
            } else if (code.startsWith("\"\"\"", i)) {
                return i + 3;
            } else {
                i++;
            }
        }
        return code.length();
    }

    private static int endOfLiteral(String code, int start) {
        char quote = code.charAt(start);
        int i = start + 1;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote || c == '\n') {
                return i + 1;
            } else {
                i++;
            }
        }
        return code.length();
    }

    /** 拼成发给模型的输入，每个文件前面带路径，模型才能在 evidence 里引用。 */
    static String renderInput(Map<String, String> files) {
        StringBuilder input = new StringBuilder("以下是学生项目中的 " + files.size() + " 个文件。\n");
        files.forEach((path, content) -> input.append("\n===== 文件：").append(path).append(" =====\n").append(content)
                .append(content.endsWith("\n") ? "" : "\n"));
        return input.toString();
    }

    /** 校验代码依据：文件必须在输入中，symbol 的类名和方法名必须在这个文件里真实出现。 */
    static List<String> checkEvidence(Map<String, String> files, CodeExtractionResult result) {
        List<String> problems = new ArrayList<>();
        for (KnowledgePoint kp : result.knowledgePoints()) {
            problems.addAll(checkEvidence(files, kp));
        }
        return problems;
    }

    static List<String> checkEvidence(Map<String, String> files, KnowledgePoint kp) {
        String label = "第 " + kp.order() + " 个知识点：";
        if (kp.evidence() == null || kp.evidence().isEmpty()) {
            return List.of(label + "没有代码依据");
        }
        List<String> problems = new ArrayList<>();
        for (Evidence evidence : kp.evidence()) {
            String content = files.get(evidence.file());
            if (content == null) {
                problems.add(label + "文件不在输入中：" + evidence.file());
                continue;
            }
            for (String name : evidence.symbol() == null ? new String[0] : evidence.symbol().split("[#.]")) {
                String identifier = name.replace("()", "").strip();
                if (!identifier.isEmpty()
                        && !Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(content).find()) {
                    problems.add(label + evidence.file() + " 中没有 " + identifier);
                }
            }
        }
        return problems;
    }

    static int evidenceCount(CodeExtractionResult result) {
        return result.knowledgePoints().stream()
                .mapToInt(kp -> kp.evidence() == null ? 0 : kp.evidence().size())
                .sum();
    }

    /** 给作者标注的表：一行一个知识点，最后五列留空。带 BOM 和 \r\n，Excel 打开不乱码。 */
    static String annotationCsv(String setName, int run, Map<String, String> files, CodeExtractionResult result) {
        Stream<List<String>> rows = result.knowledgePoints().stream()
                .sorted(Comparator.comparingInt(KnowledgePoint::order))
                .map(kp -> List.of(setName, String.valueOf(run), String.valueOf(kp.order()), nullToEmpty(kp.angle()),
                        nullToEmpty(kp.title()), nullToEmpty(kp.summary()), evidenceText(kp),
                        kp.unverifiable() == null ? "" : String.join("\n", kp.unverifiable()),
                        String.join("\n", checkEvidence(files, kp)), "", "", "", "", ""));
        return BOM + Stream.concat(Stream.of(HEADER), rows)
                .map(CodeExtractionSupport::csvLine)
                .collect(Collectors.joining("\r\n")) + "\r\n";
    }

    private static String evidenceText(KnowledgePoint kp) {
        if (kp.evidence() == null) {
            return "";
        }
        return kp.evidence().stream()
                .map(e -> e.file() + "  " + e.symbol())
                .collect(Collectors.joining("\n"));
    }

    private static String csvLine(List<String> fields) {
        return fields.stream()
                .map(f -> "\"" + f.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
