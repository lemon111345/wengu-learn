package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 源码抽取 prompt（code-extraction-v3）的输出（#90，决策 050）。
 * <p>
 * Spring AI 生成的 JSON Schema 按字段名字母顺序排列，模型也按这个顺序输出（决策 008），
 * 所以 evidence 排在 summary 前面：模型先找到代码位置，再写要点。
 */
public record CodeExtractionResult(List<KnowledgePoint> knowledgePoints) {

    /**
     * @param angle          CHAIN（实现链路）、TRADEOFF（设计取舍）、FAILURE（边界与故障）。用来让模型三种角度都覆盖到，不存库
     * @param evidence       支撑要点的代码位置，1～4 处
     * @param order          学习顺序，从 1 开始连续编号
     * @param prerequisites  学之前要先懂的通用概念
     * @param summary        核心要点，每条下面 2～3 个以“- ”开头的得分点，是之后评分的标准
     * @param summaryDetails 漏了也不影响掌握的细节，没有时为空字符串
     * @param title          面试官能直接问出口的问题
     * @param unverifiable   代码证明不了、只有学生能回答的问题
     */
    public record KnowledgePoint(String angle, List<CodeEvidence> evidence, int order,
                                 List<CodePrerequisite> prerequisites, String summary, String summaryDetails,
                                 String title, List<String> unverifiable) {
    }
}
