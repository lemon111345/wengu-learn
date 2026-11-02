package com.wengu.chat.service;

import com.wengu.chat.entity.Message;
import com.wengu.knowledge.dto.StudyMaterial;

import java.util.List;

/** AI 评分建议的 prompt 和输入（决策 008、026）。输入格式和 #14 实验一致，离线重评的结论才能沿用。 */
final class RatingPrompt {

    static final String VERSION = "v3";

    /** 评分要稳定：同一段对话多评几次结果应该一样，和 #14 实验一致 */
    static final double TEMPERATURE = 0.2;

    private static final String PROMPT_PATH = "prompts/rating-suggestion-" + VERSION + ".md";

    private RatingPrompt() {
    }

    static String system() {
        return LearningChatPrompt.load(PROMPT_PATH);
    }

    /** 知识点、要点和整段学习对话。要点是评分标准；对话不含第一条知识点信息 */
    static String input(StudyMaterial material, List<Message> messages) {
        return """
                知识点：%s
                类型：%s（%s）
                要点：%s

                对话记录：

                %s""".formatted(material.title(), material.type().name(), material.type().label(), material.summary(),
                LearningChatPrompt.transcript(messages));
    }
}
