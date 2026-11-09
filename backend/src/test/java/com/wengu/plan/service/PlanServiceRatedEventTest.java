package com.wengu.plan.service;

import com.wengu.chat.dto.StudyRatedEvent;
import com.wengu.user.service.UserSettingsService;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 评分事件的监听器（决策 041）。评分时另一半在 RatingServiceTest。 */
class PlanServiceRatedEventTest {

    @Test
    void 检查当天完成失败时不把异常传回评分() {
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.get(1L)).thenThrow(new IllegalStateException("数据库连不上"));
        PlanService service = new PlanService(settings, null, null, null, null, Clock.systemUTC());

        assertThatCode(() -> service.onStudyRated(new StudyRatedEvent(1L))).doesNotThrowAnyException();
    }
}
