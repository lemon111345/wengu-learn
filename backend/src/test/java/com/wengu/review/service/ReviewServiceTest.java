package com.wengu.review.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.review.entity.CardState;
import com.wengu.review.entity.ReviewLog;
import com.wengu.review.fsrs.Card;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.fsrs.State;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00.123Z");

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private ReviewLogMapper reviewLogMapper;

    @BeforeEach
    void clean() {
        cardStateMapper.delete(null);
        reviewLogMapper.delete(null);
    }

    @Test
    void 初始化为立即到期的新卡() {
        reviewService.initCards(1, List.of(101L, 102L));

        List<CardState> cards = cardStateMapper.selectList(null);
        assertThat(cards).extracting(CardState::getKpId).containsExactlyInAnyOrder(101L, 102L);
        CardState card = cardStateMapper.selectById(101L);
        assertThat(card.getUserId()).isEqualTo(1L);
        assertThat(card.getState()).isEqualTo(State.LEARNING);
        assertThat(card.getStep()).isZero();
        assertThat(card.getStability()).isNull();
        assertThat(card.getDifficulty()).isNull();
        assertThat(card.getDue()).isEqualTo(NOW);
        assertThat(card.getLastReview()).isNull();
    }

    /** 插入一张复习过的卡片。 */
    private void reviewedCard(long kpId, long userId, Instant due) {
        CardState card = CardState.of(kpId, userId, Card.newCard(NOW));
        card.setState(State.REVIEW);
        card.setStep(null);
        card.setDue(due);
        card.setLastReview(NOW.minus(Duration.ofDays(3)));
        cardStateMapper.insert(card);
    }

    private void log(long kpId, long userId, boolean firstReview, Instant reviewedAt) {
        ReviewLog log = new ReviewLog();
        log.setUserId(userId);
        log.setKpId(kpId);
        log.setRatingUser(Rating.GOOD);
        log.setStateBefore(State.LEARNING);
        log.setFirstReview(firstReview);
        log.setReviewedAt(reviewedAt);
        reviewLogMapper.insert(log);
    }

    @Test
    void 到期复习不包括新卡和之后才到期的卡() {
        Instant endOfToday = NOW.plus(Duration.ofHours(10));
        reviewedCard(201, 1, NOW.minus(Duration.ofDays(1)));
        reviewedCard(202, 1, endOfToday.minusMillis(1));
        reviewedCard(203, 1, endOfToday);
        reviewedCard(204, 2, NOW.minus(Duration.ofDays(1)));
        reviewService.initCards(1, List.of(205L));

        assertThat(reviewService.dueKpIds(1, endOfToday)).containsExactlyInAnyOrder(201L, 202L);
        assertThat(reviewService.newKpIds(1)).containsExactly(205L);
    }

    @Test
    void 只统计时间范围内的第一次学习() {
        Instant start = NOW.minus(Duration.ofHours(4));
        Instant end = start.plus(Duration.ofDays(1));
        log(301, 1, true, start);
        log(302, 1, true, end.minusMillis(1));
        log(303, 1, true, start.minusMillis(1));
        log(304, 1, true, end);
        log(305, 1, false, NOW);
        log(306, 2, true, NOW);

        assertThat(reviewService.countFirstReviews(1, start, end)).isEqualTo(2);
    }

    @Test
    void 重复初始化同一个知识点时整体失败() {
        reviewService.initCards(1, List.of(101L));

        assertThatThrownBy(() -> reviewService.initCards(1, List.of(102L, 101L)))
                .isInstanceOf(DuplicateKeyException.class);
        // 单独调用时自己开事务：102 也没有写进去
        assertThat(cardStateMapper.selectById(102L)).isNull();
    }
}
