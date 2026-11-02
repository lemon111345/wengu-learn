package com.wengu.chat.dto;

import com.wengu.review.fsrs.Rating;

import java.time.LocalDate;

/**
 * 一种评分对应的下次复习日期：预览时四种各一个，确认评分后返回选中的那个。
 *
 * @param date 下次复习的学习日，按用户的时区和 day_start_hour 换算，前端显示“X月X日再见”
 * @param days 距今天几个学习日，前端显示“明天 / 3 天后”
 */
public record RatingOption(Rating rating, LocalDate date, long days) {
}
