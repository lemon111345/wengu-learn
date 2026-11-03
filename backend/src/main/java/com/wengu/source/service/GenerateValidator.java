package com.wengu.source.service;

import com.wengu.source.dto.GenerateResult;
import com.wengu.source.dto.GeneratedQuestion;
import com.wengu.source.dto.SplitQuestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检查模型根据讲解资料整理出的题目（#26，决策 037）。纯函数，不依赖 Spring。
 * <p>
 * 题目是模型自己写的，没法像切分那样逐字比对；能核对的是出处：每道题注明的小节标题必须出现在资料中
 * （比对方式同 {@link SplitValidator#normalize}），防止模型脱离资料出题。资料没有标题时模型写“无”，不核对。
 */
public final class GenerateValidator {

    /** 资料没有小节标题时，模型在 section 中写的值 */
    static final String NO_SECTION = "无";

    private GenerateValidator() {
    }

    /** 返回发现的问题，为空表示通过。 */
    public static List<String> validate(String content, GenerateResult result) {
        if (result == null || result.questions() == null) {
            return List.of("缺少 questions");
        }
        String normalizedContent = SplitValidator.normalize(content);
        List<String> problems = new ArrayList<>();
        List<GeneratedQuestion> questions = result.questions();
        for (int i = 0; i < questions.size(); i++) {
            GeneratedQuestion q = questions.get(i);
            String label = "第 " + (i + 1) + " 题";
            if (q == null) {
                problems.add(label + "为空");
                continue;
            }
            if (SplitValidator.normalize(q.text()).isEmpty()) {
                problems.add(label + "为空");
            }
            String section = SplitValidator.normalize(q.section());
            if (section.isEmpty()) {
                problems.add(label + "没有写出处");
            } else if (!section.equals(NO_SECTION) && !normalizedContent.contains(section)) {
                problems.add(label + "的出处不在资料中：" + q.section());
            }
            List<String> followUps = q.followUps() == null ? List.of() : q.followUps();
            for (int j = 0; j < followUps.size(); j++) {
                if (SplitValidator.normalize(followUps.get(j)).isEmpty()) {
                    problems.add(label + "的第 " + (j + 1) + " 条追问为空");
                }
            }
        }
        return problems;
    }

    /**
     * 转成和切分结果一样的题目（出处只用于核对，不保存），去掉主问题相同的重复题，保留第一次出现的。
     * 应在 {@link #validate} 通过后调用。返回的题目去掉首尾空白，followUps 不为 null。
     */
    public static List<SplitQuestion> toQuestions(List<GeneratedQuestion> questions) {
        Map<String, SplitQuestion> distinct = new LinkedHashMap<>();
        for (GeneratedQuestion q : questions) {
            List<String> followUps = q.followUps() == null ? List.of()
                    : q.followUps().stream().map(String::strip).toList();
            distinct.putIfAbsent(SplitValidator.normalize(q.text()), new SplitQuestion(q.text().strip(), followUps));
        }
        return List.copyOf(distinct.values());
    }
}
