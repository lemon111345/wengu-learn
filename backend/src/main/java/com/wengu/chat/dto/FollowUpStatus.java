package com.wengu.chat.dto;

/**
 * 一次学习的接着问状态，问答记录打开一条对话时用（决策 043）。只查不建。
 *
 * @param studySessionId    学习会话
 * @param followUpSessionId 接着问的会话；还没有接着问时为 null，第一次发送时才创建
 * @param kpTitle           知识点标题，已合并或删除的也有
 * @param canContinue       还能不能继续问
 * @param readOnlyReason    不能继续问的原因，给用户看；能继续问时为 null
 * @param dueToday          知识点今天要复习、还没复习；暂停的不算
 * @param teacherName       用户现在设置的老师称呼（决策 042）
 */
public record FollowUpStatus(long studySessionId, Long followUpSessionId, String kpTitle, boolean canContinue,
                             String readOnlyReason, boolean dueToday, String teacherName) {
}
