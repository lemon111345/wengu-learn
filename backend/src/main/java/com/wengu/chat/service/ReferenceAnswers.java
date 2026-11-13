package com.wengu.chat.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从收尾消息中切出面试参考答法（决策 047）。提示词要求收尾时给出参考答法，但没有规定写法，
 * 实际有“**参考答法**：”“面试参考答法：”“【参考答法】”“这道题的参考答法：”等几种，所以按关键词切，不靠固定格式。
 * 读取时现切，不单独存储：旧的学习记录也能直接用。
 */
final class ReferenceAnswers {

    private static final String KEYWORD = "参考答法";

    /** 关键词后面的分隔：可能有加粗的 **、“如下”“是”，再是冒号或右方括号 */
    private static final Pattern SEPARATOR = Pattern.compile("^[*\\s]*(?:如下|是)?[*\\s]*[：:】]\\s*");

    private ReferenceAnswers() {
    }

    /**
     * 取最后一次出现“参考答法”之后、到消息结尾的文字。点评里也可能提到这几个字，参考答法在收尾的最后，所以取最后一次。
     *
     * @return 没有找到或切出来是空的时为 null
     */
    static String extract(String closing) {
        if (closing == null) {
            return null;
        }
        int index = closing.lastIndexOf(KEYWORD);
        if (index < 0) {
            return null;
        }
        String rest = closing.substring(index + KEYWORD.length());
        Matcher separator = SEPARATOR.matcher(rest);
        // 有冒号时，冒号前的 ** 是标题的加粗，去掉；没有冒号时只去掉空白，免得把答法开头的加粗去掉一半
        String answer = separator.find() ? rest.substring(separator.end()) : rest;
        answer = answer.strip();
        return answer.isEmpty() ? null : answer;
    }
}
