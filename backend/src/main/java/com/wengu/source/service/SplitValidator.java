package com.wengu.source.service;

import com.wengu.source.dto.SplitQuestion;
import com.wengu.source.dto.SplitResult;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 检查模型的切分结果：每道题和每条追问都必须出现在原文中，防止模型改写或编造题目。纯函数，不依赖 Spring。
 * <p>
 * 比对前双方都做 {@link #normalize} 规范化，模型去掉加粗、反引号，或者把全角标点写成半角，都不算失败。
 * 漏题检查不了，靠用户在预览时发现。
 */
public final class SplitValidator {

    /** 空白字符和常见的 Markdown 符号。NFKC 之后全角空格已变成普通空格，\p{Z} 再兜住其他 Unicode 空白。 */
    private static final Pattern IGNORED = Pattern.compile("[\\s\\p{Z}*`#_>]");

    private SplitValidator() {
    }

    /** 返回发现的问题，为空表示通过。 */
    public static List<String> validate(String content, SplitResult result) {
        if (result == null || result.questions() == null) {
            return List.of("缺少 questions");
        }
        String normalizedContent = normalize(content);
        List<String> problems = new ArrayList<>();
        List<SplitQuestion> questions = result.questions();
        for (int i = 0; i < questions.size(); i++) {
            SplitQuestion q = questions.get(i);
            int no = i + 1;
            if (q == null) {
                problems.add("第 " + no + " 题为空");
                continue;
            }
            checkText(normalizedContent, q.text(), "第 " + no + " 题", problems);
            List<String> followUps = q.followUps() == null ? List.of() : q.followUps();
            for (int j = 0; j < followUps.size(); j++) {
                checkText(normalizedContent, followUps.get(j), "第 " + no + " 题的第 " + (j + 1) + " 条追问", problems);
            }
        }
        return problems;
    }

    private static void checkText(String normalizedContent, String text, String label, List<String> problems) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            problems.add(label + "为空");
        } else if (!normalizedContent.contains(normalized)) {
            problems.add(label + "不在原文中：" + text);
        }
    }

    /**
     * 去掉完全相同的重复题（主问题和追问规范化后都相同），保留第一次出现的。用户原文里本来就可能有重复；
     * 问法不同的题不在这里处理，交给抽取去合并。
     * <p>
     * 应在 {@link #validate} 通过后调用。返回的题目去掉首尾空白，followUps 不为 null。
     */
    public static List<SplitQuestion> deduplicate(List<SplitQuestion> questions) {
        Map<String, SplitQuestion> distinct = new LinkedHashMap<>();
        for (SplitQuestion q : questions) {
            List<String> followUps = q.followUps() == null ? List.of()
                    : q.followUps().stream().map(String::strip).toList();
            String key = normalize(q.text()) + "\n" + String.join("\n", followUps.stream().map(SplitValidator::normalize).toList());
            distinct.putIfAbsent(key, new SplitQuestion(q.text().strip(), followUps));
        }
        return List.copyOf(distinct.values());
    }

    /** NFKC 统一全半角和兼容字符，再删掉空白和 Markdown 符号。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return IGNORED.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
    }
}
