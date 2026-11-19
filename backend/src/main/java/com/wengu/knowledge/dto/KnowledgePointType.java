package com.wengu.knowledge.dto;

import java.util.Arrays;
import java.util.List;

/**
 * 知识点类型，决定学习和复习时使用哪种对话方式。定义见技术方案“知识点粒度与类型”。
 */
public enum KnowledgePointType {

    /** 原子概念：单一概念，对话讲解，复习时换角度提问。 */
    CONCEPT("原子概念"),
    /** 对比：追问差异和各自适用场景。 */
    COMPARISON("对比"),
    /** 原理机制：流程或原理，深度对话，按顺序学习。 */
    MECHANISM("原理机制"),
    /** 列举：偏记忆，快问快答。 */
    ENUMERATION("列举"),
    /** 排查实战：情景模拟，考察步骤和工具。 */
    TROUBLESHOOTING("排查实战"),
    /** 系统设计：综合多个知识点；M1 只标记、不进入每日计划。 */
    SYSTEM_DESIGN("系统设计"),
    /** 个人经历：必须结合用户源码追问。 */
    EXPERIENCE("个人经历");

    private final String label;

    KnowledgePointType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 这个类型本身是否参与调度（进入每日计划）。不参与的类型不初始化 card_state，由 knowledge 在创建知识点时判断（决策 007）：
     * SYSTEM_DESIGN 的学法在 M3 定；EXPERIENCE 要结合用户源码追问，带源码的另外算，见 {@link #isScheduled(boolean)}。
     */
    public boolean isScheduled() {
        return this != SYSTEM_DESIGN && this != EXPERIENCE;
    }

    /**
     * 一个知识点是否参与调度（决策 051）：类型本身参与调度，或者是带源码（有 kp_code）的个人经历题。
     * 题库抽出的个人经历题没有源码，老师没有事实依据，仍然只收录。
     *
     * @param hasCode 这个知识点有没有源码数据（kp_code）
     */
    public boolean isScheduled(boolean hasCode) {
        return isScheduled() || (this == EXPERIENCE && hasCode);
    }

    /** 类型本身参与调度的全部类型，每日计划用它排除只收录的（决策 029）；带源码的个人经历题另外加上（决策 051） */
    public static List<KnowledgePointType> scheduledTypes() {
        return Arrays.stream(values()).filter(KnowledgePointType::isScheduled).toList();
    }
}
