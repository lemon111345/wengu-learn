package com.wengu.knowledge.dto;

import java.time.LocalDate;

/**
 * 下次复习，按用户的学习日表示（技术方案“前端设计带来的需求”：前端显示的日期一律用接口返回的学习日）。
 *
 * @param date 下次复习的学习日
 * @param days 距今天几个学习日：1 是明天（ui.md 用 --wg-warn 标出）；0 或负数表示已经到期，在今天的计划里
 */
public record NextReview(LocalDate date, long days) {
}
