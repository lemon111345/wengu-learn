package com.wengu.plan.dto;

import java.util.List;

/**
 * 把知识点加入今天（#128，决策 052）。
 *
 * @param kpIds 要加入的知识点，可以混着学过的、暂停的，只加入其中待学的
 */
public record PickRequest(List<Long> kpIds) {
}
