package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 学习一个知识点时发给模型的材料，学习对话的第一条消息由它拼成（决策 008）。
 *
 * @param kpId            知识点 id
 * @param title           写成一个问题的标题
 * @param type            知识点类型，决定讲解方式
 * @param summary         要点，是给老师的参考，也是评分标准
 * @param sourceQuestions 来源面试题，带上追问；不是从草稿来的知识点、或者材料已经不在时为空
 * @param code            源码知识点（#90）的细节、代码依据的文件等，学习对话和评分建议改用源码的 prompt；其他知识点为 null
 */
public record StudyMaterial(long kpId, String title, KnowledgePointType type, String summary,
                            List<String> sourceQuestions, CodeMaterial code) {

    /** 没有源码的知识点 */
    public StudyMaterial(long kpId, String title, KnowledgePointType type, String summary, List<String> sourceQuestions) {
        this(kpId, title, type, summary, sourceQuestions, null);
    }
}
