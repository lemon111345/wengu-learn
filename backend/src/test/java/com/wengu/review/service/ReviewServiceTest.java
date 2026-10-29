package com.wengu.review.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.review.entity.CardState;
import com.wengu.review.fsrs.State;
import com.wengu.review.mapper.CardStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
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

    @BeforeEach
    void clean() {
        cardStateMapper.delete(null);
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

    @Test
    void 重复初始化同一个知识点时整体失败() {
        reviewService.initCards(1, List.of(101L));

        assertThatThrownBy(() -> reviewService.initCards(1, List.of(102L, 101L)))
                .isInstanceOf(DuplicateKeyException.class);
        // 单独调用时自己开事务：102 也没有写进去
        assertThat(cardStateMapper.selectById(102L)).isNull();
    }
}
