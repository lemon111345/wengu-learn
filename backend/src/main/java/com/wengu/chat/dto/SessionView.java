package com.wengu.chat.dto;

import com.wengu.chat.entity.SessionType;

import java.util.List;

/**
 * 学习会话和全部消息，用于开始学习和刷新后恢复。发给模型的知识点信息不在消息中。
 *
 * @param kpTitle            知识点标题，学习页的标题区用
 * @param ended              学习对话是否已经结束（模型输出了【学习结束】，或者回答达到上限）
 * @param skippedExplanation 是否点过“这个我会，直接完整作答”
 * @param generating         老师是否正在回复；刷新时为 true 说明上一条回复还在后台生成，稍后再取
 * @param messages           按时间排列
 */
public record SessionView(long id, SessionType type, Long kpId, String kpTitle, boolean ended,
                          boolean skippedExplanation, boolean generating, List<MessageView> messages) {
}
