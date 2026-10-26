package com.wengu.knowledge.dto;

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
}
