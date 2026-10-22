package com.wengu.knowledge.experiment;

import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 生成给作者人工标注的 CSV：一行一个知识点，最后两列留空填写。
 * <p>
 * 开头带 UTF-8 BOM、行尾用 \r\n，Excel 直接双击打开不会出现中文乱码。
 */
final class AnnotationCsv {

    static final String BOM = "﻿";

    private static final List<String> HEADER = List.of(
            "学习顺序", "标题", "摘要", "类型", "来源题号", "来源题目", "标注（合格/需修改/应删除/遗漏）", "修改意见");

    private AnnotationCsv() {
    }

    static String render(List<ExtractionQuestion> questions, ExtractionResult result) {
        Map<Integer, String> questionText = questions.stream()
                .collect(Collectors.toMap(ExtractionQuestion::id, ExtractionQuestion::text));
        Function<Integer, String> describe = id -> "[" + id + "] " + questionText.getOrDefault(id, "（不存在的题号）");

        Stream<List<String>> rows = result.knowledgePoints().stream()
                .sorted(Comparator.comparingInt(ExtractedKnowledgePoint::order))
                .map(kp -> List.of(
                        String.valueOf(kp.order()),
                        nullToEmpty(kp.title()),
                        nullToEmpty(kp.summary()),
                        kp.type() == null ? "" : kp.type().name() + "（" + kp.type().label() + "）",
                        ids(kp).stream().map(String::valueOf).collect(Collectors.joining(", ")),
                        ids(kp).stream().map(describe).collect(Collectors.joining("\n")),
                        "",
                        ""));

        return BOM + Stream.concat(Stream.of(HEADER), rows)
                .map(AnnotationCsv::line)
                .collect(Collectors.joining("\r\n")) + "\r\n";
    }

    private static List<Integer> ids(ExtractedKnowledgePoint kp) {
        return kp.sourceQuestionIds() == null ? List.of() : kp.sourceQuestionIds();
    }

    /** 每个字段都加双引号，字段内的双引号写成两个，换行和逗号因此可以原样保留。 */
    private static String line(List<String> fields) {
        return fields.stream()
                .map(f -> "\"" + f.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
