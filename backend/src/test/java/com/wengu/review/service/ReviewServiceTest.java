package com.wengu.review.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.review.dto.CardProgress;
import com.wengu.review.dto.ReviewRecord;
import com.wengu.review.dto.ReviewResult;
import com.wengu.review.entity.CardState;
import com.wengu.review.entity.ReviewLog;
import com.wengu.review.fsrs.Card;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.fsrs.Scheduler;
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
import java.util.Map;
import java.util.Objects;
import java.util.Random;

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
    void 知识库的复习进度只返回该用户有调度状态的知识点() {
        reviewedCard(401, 1, NOW.plus(Duration.ofDays(5)));
        reviewService.initCards(1, List.of(402L));
        reviewedCard(403, 2, NOW);

        Map<Long, CardProgress> progress = reviewService.progressOf(1, List.of(401L, 402L, 403L, 404L));

        assertThat(progress).containsOnlyKeys(401L, 402L);
        assertThat(progress.get(401L).due()).isEqualTo(NOW.plus(Duration.ofDays(5)));
        assertThat(progress.get(401L).lastReview()).isNotNull();
        assertThat(progress.get(402L).lastReview()).isNull();
        assertThat(progress.get(402L).stability()).isNull();
        assertThat(reviewService.progressOf(1, List.of())).isEmpty();
    }

    @Test
    void 学习记录按时间倒序并带上AI建议() {
        log(501, 1, true, NOW.minus(Duration.ofDays(3)));
        log(502, 1, true, NOW.minus(Duration.ofDays(2)));
        ReviewLog withAi = new ReviewLog();
        withAi.setUserId(1L);
        withAi.setKpId(501L);
        withAi.setRatingUser(Rating.HARD);
        withAi.setRatingAi(Rating.AGAIN);
        withAi.setStateBefore(State.REVIEW);
        withAi.setFirstReview(false);
        withAi.setReviewedAt(NOW);
        withAi.setSessionId(9501L);
        reviewLogMapper.insert(withAi);
        log(503, 1, true, NOW);
        log(501, 2, true, NOW);

        List<ReviewRecord> records = reviewService.reviewsOf(1, List.of(501L, 502L));

        assertThat(records).extracting(ReviewRecord::kpId).containsExactly(501L, 502L, 501L);
        assertThat(records.getFirst()).isEqualTo(new ReviewRecord(501, Rating.HARD, Rating.AGAIN, false, NOW, 9501L));
        assertThat(records.get(2).ratingAi()).isNull();
        assertThat(reviewService.reviewsOf(1, List.of())).isEmpty();
    }

    @Test
    void 重复初始化同一个知识点时整体失败() {
        reviewService.initCards(1, List.of(101L));

        assertThatThrownBy(() -> reviewService.initCards(1, List.of(102L, 101L)))
                .isInstanceOf(DuplicateKeyException.class);
        // 单独调用时自己开事务：102 也没有写进去
        assertThat(cardStateMapper.selectById(102L)).isNull();
    }

    @Test
    void 改成参与调度的类型时只给还没有调度状态的初始化() {
        reviewService.initCards(1, List.of(101L));
        reviewService.rate(1, 101L, null, Rating.GOOD, null);
        CardState reviewed = cardStateMapper.selectById(101L);

        reviewService.ensureCard(1, 101L);
        reviewService.ensureCard(1, 102L);

        // 已经有的不动，复习进度接着用
        assertThat(cardStateMapper.selectById(101L).getLastReview()).isEqualTo(reviewed.getLastReview());
        assertThat(cardStateMapper.selectById(101L).getDue()).isEqualTo(reviewed.getDue());
        // 没有的初始化成新卡
        assertThat(cardStateMapper.selectById(102L).getLastReview()).isNull();
    }

    /** 测试中直接调用 Scheduler 的结果：随机数的种子和 ReviewService 一样，由知识点 id 和上次复习时间决定（决策 026） */
    private static Card expected(long kpId, Card before, Rating rating) {
        Random random = new Random(Objects.hash(kpId, before.lastReview()));
        return Scheduler.projectDefault(random::nextDouble).review(before, rating, NOW);
    }

    @Test
    void 新卡第一次评分时更新调度状态并写流水() {
        reviewService.initCards(1, List.of(401L));
        Card before = cardStateMapper.selectById(401L).toCard();

        ReviewResult result = reviewService.rate(1, 401L, 9001L, Rating.GOOD, Rating.HARD);

        Card after = cardStateMapper.selectById(401L).toCard();
        // 调度结果和直接调用 Scheduler 一致
        assertThat(after).isEqualTo(expected(401L, before, Rating.GOOD));
        assertThat(result).isEqualTo(new ReviewResult(Rating.GOOD, after.due(), true));
        ReviewLog log = reviewLogMapper.selectOne(null);
        assertThat(log.getSessionId()).isEqualTo(9001L);
        assertThat(log.getRatingUser()).isEqualTo(Rating.GOOD);
        assertThat(log.getRatingAi()).isEqualTo(Rating.HARD);
        assertThat(log.getStateBefore()).isEqualTo(State.LEARNING);
        assertThat(log.getFirstReview()).isTrue();
        assertThat(log.getDueAfter()).isEqualTo(after.due());
        assertThat(log.getReviewedAt()).isEqualTo(NOW);
    }

    @Test
    void 复习时不是第一次学习() {
        Card reviewed = new Card(State.REVIEW, null, 5.0, 5.0, NOW, NOW.minus(Duration.ofDays(5)));
        cardStateMapper.insert(CardState.of(402L, 1, reviewed));

        ReviewResult result = reviewService.rate(1, 402L, 9002L, Rating.AGAIN, null);

        assertThat(result.firstReview()).isFalse();
        assertThat(cardStateMapper.selectById(402L).toCard()).isEqualTo(expected(402L, reviewed, Rating.AGAIN));
        ReviewLog log = reviewLogMapper.selectOne(null);
        assertThat(log.getStateBefore()).isEqualTo(State.REVIEW);
        assertThat(log.getRatingAi()).isNull();
    }

    @Test
    void 预览四档评分且和实际评分的结果一致() {
        reviewService.initCards(1, List.of(501L, 502L, 503L, 504L));
        Map<Rating, Instant> preview = reviewService.preview(1, 501L);
        assertThat(preview).containsOnlyKeys(Rating.values());
        // 评分越高，间隔越长
        assertThat(preview.get(Rating.AGAIN)).isBefore(preview.get(Rating.EASY));

        // 每张卡各取一种评分，实际结果和它自己的预览一致；预览不保存
        long kpId = 501L;
        for (Rating rating : Rating.values()) {
            Instant expectedDue = reviewService.preview(1, kpId).get(rating);
            assertThat(cardStateMapper.selectById(kpId).getLastReview()).isNull();
            assertThat(reviewService.rate(1, kpId, 9100L + kpId, rating, null).due()).isEqualTo(expectedDue);
            kpId++;
        }
    }

    @Test
    void 同一个会话不能评两次分() {
        reviewService.initCards(1, List.of(601L));
        reviewService.rate(1, 601L, 9003L, Rating.GOOD, null);
        CardState afterFirst = cardStateMapper.selectById(601L);

        assertThatThrownBy(() -> reviewService.rate(1, 601L, 9003L, Rating.EASY, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(reviewService.isRated(9003L)).isTrue();
        assertThat(cardStateMapper.selectById(601L).getDue()).isEqualTo(afterFirst.getDue());
        assertThat(reviewLogMapper.selectCount(null)).isEqualTo(1);
    }

    /** 四档两两组合：先评 first 再改成 second，和直接评 second 的结果完全相同（决策 039）。 */
    private void assertChangeSameAsDirect(Card before) {
        long kpId = 1000L;
        for (Rating first : Rating.values()) {
            for (Rating second : Rating.values()) {
                kpId++;
                // fuzz 的随机数和 kpId 有关，两条路径要用同一个 kpId：先直接评，记下结果后清掉，再走“评 + 改”
                cardStateMapper.insert(CardState.of(kpId, 1, before));
                ReviewResult direct = reviewService.rate(1, kpId, kpId, second, null);
                Card directCard = cardStateMapper.selectById(kpId).toCard();
                cardStateMapper.deleteById(kpId);
                reviewLogMapper.delete(null);

                cardStateMapper.insert(CardState.of(kpId, 1, before));
                reviewService.rate(1, kpId, kpId, first, Rating.HARD);
                Instant previewed = reviewService.previewChange(1, kpId).get(second);
                ReviewResult changed = reviewService.changeRating(1, kpId, second);

                assertThat(cardStateMapper.selectById(kpId).toCard()).as("%s -> %s", first, second).isEqualTo(directCard);
                assertThat(changed).isEqualTo(direct);
                assertThat(previewed).isEqualTo(direct.due());
                ReviewLog log = reviewLogMapper.selectOne(null);
                assertThat(log.getRatingUser()).isEqualTo(second);
                assertThat(log.getDueAfter()).isEqualTo(direct.due());
                // 其他字段保持第一次评分时的值
                assertThat(log.getRatingAi()).isEqualTo(Rating.HARD);
                assertThat(log.getReviewedAt()).isEqualTo(NOW);
                assertThat(log.cardBefore()).isEqualTo(before);
                reviewLogMapper.delete(null);
            }
        }
    }

    @Test
    void 新卡改分和第一次就选这一档完全相同() {
        assertChangeSameAsDirect(Card.newCard(NOW.minus(Duration.ofDays(1))));
    }

    @Test
    void 复习中的卡改分和第一次就选这一档完全相同() {
        assertChangeSameAsDirect(new Card(State.REVIEW, null, 5.0, 5.0, NOW, NOW.minus(Duration.ofDays(5))));
    }

    @Test
    void 重学中的卡改分和第一次就选这一档完全相同() {
        assertChangeSameAsDirect(new Card(State.RELEARNING, 0, 2.0, 7.0, NOW, NOW.minus(Duration.ofHours(1))));
    }

    @Test
    void 改了又改回去等于没改() {
        reviewService.initCards(1, List.of(801L));
        reviewService.rate(1, 801L, 9801L, Rating.GOOD, null);
        Card original = cardStateMapper.selectById(801L).toCard();

        reviewService.changeRating(1, 9801L, Rating.AGAIN);
        reviewService.changeRating(1, 9801L, Rating.EASY);
        reviewService.changeRating(1, 9801L, Rating.GOOD);

        assertThat(cardStateMapper.selectById(801L).toCard()).isEqualTo(original);
        assertThat(reviewLogMapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void 改分后仍然算第一次学习() {
        reviewService.initCards(1, List.of(802L));
        reviewService.rate(1, 802L, 9802L, Rating.GOOD, null);

        assertThat(reviewService.changeRating(1, 9802L, Rating.EASY).firstReview()).isTrue();
        assertThat(reviewService.countFirstReviews(1, NOW, NOW.plusMillis(1))).isEqualTo(1);
    }

    @Test
    void 之后又复习过的评分不能再改最新的可以改() {
        reviewService.initCards(1, List.of(803L));
        reviewService.rate(1, 803L, 9803L, Rating.GOOD, null);
        reviewService.rate(1, 803L, 9804L, Rating.GOOD, null);
        Card afterLatest = cardStateMapper.selectById(803L).toCard();

        assertThat(reviewService.isRatingChangeable(9803L)).isFalse();
        assertThat(reviewService.isRatingChangeable(9804L)).isTrue();
        assertThatThrownBy(() -> reviewService.changeRating(1, 9803L, Rating.EASY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> reviewService.previewChange(1, 9803L)).isInstanceOf(BusinessException.class);
        assertThat(cardStateMapper.selectById(803L).toCard()).isEqualTo(afterLatest);

        reviewService.changeRating(1, 9804L, Rating.EASY);
        assertThat(cardStateMapper.selectById(803L).toCard()).isNotEqualTo(afterLatest);
    }

    @Test
    void 没有快照的旧记录不能改() {
        reviewService.initCards(1, List.of(804L));
        ReviewLog old = new ReviewLog();
        old.setUserId(1L);
        old.setKpId(804L);
        old.setSessionId(9805L);
        old.setRatingUser(Rating.GOOD);
        old.setStateBefore(State.LEARNING);
        old.setFirstReview(true);
        old.setReviewedAt(NOW);
        reviewLogMapper.insert(old);

        assertThat(reviewService.isRatingChangeable(9805L)).isFalse();
        assertThatThrownBy(() -> reviewService.changeRating(1, 9805L, Rating.EASY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void 没有评分和其他用户的会话不能改() {
        reviewService.initCards(2, List.of(805L));
        reviewService.rate(2, 805L, 9806L, Rating.GOOD, null);
        Card before = cardStateMapper.selectById(805L).toCard();

        assertThat(reviewService.isRatingChangeable(9999L)).isFalse();
        assertThatThrownBy(() -> reviewService.changeRating(1, 9806L, Rating.EASY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> reviewService.changeRating(1, 9999L, Rating.EASY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(cardStateMapper.selectById(805L).toCard()).isEqualTo(before);
    }

    @Test
    void 其他用户的知识点不能评分也不能预览() {
        reviewService.initCards(2, List.of(701L));

        assertThatThrownBy(() -> reviewService.rate(1, 701L, 9004L, Rating.GOOD, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> reviewService.preview(1, 701L)).isInstanceOf(BusinessException.class);
        assertThat(reviewLogMapper.selectCount(null)).isZero();
    }
}
