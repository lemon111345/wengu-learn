package com.wengu.chat.dto;

import java.time.LocalDate;

/**
 * 问答记录列表的一行：一场有提问的接着问（决策 043）。
 *
 * @param studySessionId   原来的学习会话，问答记录的地址用它
 * @param followUpSessionId 接着问的会话
 * @param kpTitle          知识点标题，已合并或删除的也有
 * @param lastQuestion     最后一问的原话
 * @param questionCount    问了几次
 * @param lastActiveDate   最后一次活动的学习日
 * @param canContinue      还能不能继续问：知识点已合并或删除、问满上限时为 false
 * @param dueToday         知识点今天要复习、还没复习；暂停的不算
 */
public record FollowUpSummary(long studySessionId, long followUpSessionId, String kpTitle, String lastQuestion,
                              int questionCount, LocalDate lastActiveDate, boolean canContinue, boolean dueToday) {
}
