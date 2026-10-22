package com.wengu.knowledge.experiment;

import com.wengu.knowledge.dto.ExtractionQuestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析样本题 Markdown：每题一个“## 题号. 题目”标题，标题下的内容（参考答案等）目前不使用。
 */
final class SampleQuestions {

    private static final Pattern HEADING = Pattern.compile("^##\\s+(\\d+)\\.\\s+(.+?)\\s*$");

    private SampleQuestions() {
    }

    static List<ExtractionQuestion> parse(String markdown) {
        List<ExtractionQuestion> questions = new ArrayList<>();
        for (String line : markdown.lines().toList()) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                questions.add(new ExtractionQuestion(Integer.parseInt(m.group(1)), m.group(2)));
            }
        }
        return questions;
    }
}
