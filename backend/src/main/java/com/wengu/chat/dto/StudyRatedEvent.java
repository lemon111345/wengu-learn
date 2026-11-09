package com.wengu.chat.dto;

/**
 * 一次学习评完了分（评分和修改评分都发布）。发布时评分已经写进库。
 * plan 监听它，检查今天是否学完（决策 041）；chat 不知道谁在监听，所以不用依赖 plan。
 *
 * @param userId 评分的用户
 */
public record StudyRatedEvent(long userId) {
}
