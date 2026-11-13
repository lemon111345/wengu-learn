package com.wengu.chat.dto;

import com.wengu.chat.entity.SessionType;

import java.util.List;

/**
 * 学习会话和全部消息，用于开始学习和刷新后恢复。发给模型的知识点信息不在消息中。
 *
 * @param parentSessionId    接着问的会话指向原来的学习会话；其他类型为 null
 * @param kpTitle            知识点标题，学习页的标题区用
 * @param ended              学习对话是否已经结束（模型输出了【学习结束】，或者回答达到上限）
 * @param skippedExplanation 是否点过“这个我会，直接完整作答”
 * @param generating         老师是否正在回复；刷新时为 true 说明上一条回复还在后台生成，稍后再取
 * @param activeSeconds      有效时长（秒），学习页顶栏的“本次 X 分钟”
 * @param rating             学习会话已经评过分时，评分和下次复习的学习日；没评分、接着问时为 null
 * @param ratingEditable     已经评过分时，这次评分还能不能修改；没评分、接着问、知识点已合并或删除时为 false（决策 039）
 * @param followUpSessionId  学习会话的接着问会话，回顾时用；没有接着问时为 null（#82）
 * @param messages           按时间排列
 * @param teacherName        用户现在设置的老师称呼，界面显示用；历史对话也显示现在的称呼（决策 042）
 * @param referenceAnswer    学习会话结束后，从收尾中切出的面试参考答法；没结束、不是学习会话、收尾里没有找到时为 null（决策 047）
 */
public record SessionView(long id, SessionType type, Long kpId, Long parentSessionId, String kpTitle, boolean ended,
                          boolean skippedExplanation, boolean generating, int activeSeconds, RatingOption rating,
                          boolean ratingEditable, Long followUpSessionId, List<MessageView> messages,
                          String teacherName, String referenceAnswer) {
}
