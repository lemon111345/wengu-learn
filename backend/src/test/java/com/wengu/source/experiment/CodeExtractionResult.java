package com.wengu.source.experiment;

import java.util.List;

/**
 * 源码抽取实验（issue #89）的模型输出。只用于实验，正式的结构等 #90 根据结论再定。
 * <p>
 * Spring AI 生成的 JSON Schema 按字段名字母顺序排列，模型也按这个顺序输出（决策 008），
 * 所以 evidence 排在 summary 前面：模型先找到代码位置，再写要点；summaryDetails 排在 summary 后面，先核心后细节。
 */
record CodeExtractionResult(List<KnowledgePoint> knowledgePoints) {

    /**
     * @param angle          CHAIN（实现链路）、TRADEOFF（设计取舍）、FAILURE（边界与故障）
     * @param evidence       支撑要点的代码位置
     * @param order          学习顺序
     * @param prerequisites  学之前要先懂的通用概念（v2 起有，v1 的结果中为 null）
     * @param summary        代码能证明的要点，是之后评分的标准；v2 起只写核心要点
     * @param summaryDetails 漏了也不影响掌握的细节（v2 起有）
     * @param title          面试官能直接问出口的问题
     * @param unverifiable   代码证明不了、只有学生能回答的问题
     */
    record KnowledgePoint(String angle, List<Evidence> evidence, int order, List<Prerequisite> prerequisites,
                          String summary, String summaryDetails, String title, List<String> unverifiable) {
    }

    /**
     * @param file   输入中的文件路径
     * @param symbol 类名#方法名，涉及整个类时只写类名
     */
    record Evidence(String file, String symbol) {
    }

    /**
     * @param concept     概念名
     * @param explanation 150 字以内的通俗解释
     */
    record Prerequisite(String concept, String explanation) {
    }
}
