package com.wengu.chat.entity;

/**
 * 学习对话 prompt 约定的标记（决策 008、018）。模型把标记写在回复里，后端识别后从内容中去掉，单独记在消息上。
 */
public enum Marker {
    /** 要求学生完整作答：前端换输入框的标签，并折叠上面的对话 */
    RESTATE("【完整作答】"),
    /** 学习结束：会话结束，进入评分 */
    END("【学习结束】");

    private final String text;

    Marker(String text) {
        this.text = text;
    }

    /** 模型输出的原文 */
    public String text() {
        return text;
    }
}
