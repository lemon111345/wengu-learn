package com.wengu.plan.dto;

/**
 * 加入今天的结果（#128，决策 052），前端按这三个数拼提示。
 *
 * @param added          这次加入的
 * @param alreadyInToday 本来就在今天的新学列表里的（学到一半的、今天加入过的、计划派发的）
 * @param skipped        不是待学的，没有加入：学过的、暂停的、只收录的、删除的、不存在或不属于当前用户的
 */
public record PickResult(int added, int alreadyInToday, int skipped) {
}
