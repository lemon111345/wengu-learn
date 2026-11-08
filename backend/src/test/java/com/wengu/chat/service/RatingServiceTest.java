package com.wengu.chat.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.RateRequest;
import com.wengu.chat.dto.RatingOption;
import com.wengu.chat.entity.RatingSuggestion;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.RatingSuggestionMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.review.entity.ReviewLog;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import com.wengu.review.service.ReviewService;
import com.wengu.user.dto.UpdateSettingsRequest;
import com.wengu.user.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评分预览和确认评分（决策 026）。真实 MySQL，时钟可以拨动。
 * 用户设置：Asia/Shanghai、凌晨 4 点分界。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RatingServiceTest {

    private static final long USER_ID = 1L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 北京时间 11 月 2 日 10:00，学习日是 11 月 2 日 */
    private static final Instant NOW = Instant.parse("2026-11-02T02:00:00Z");

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return new MutableClock(NOW);
    }

    @Autowired
    private RatingService ratingService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserSettingsService settingsService;

    @Autowired
    private StudySessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private RatingSuggestionMapper suggestionMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private ReviewLogMapper reviewLogMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long kpId;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).set(NOW);
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        settingsService.update(USER_ID, new UpdateSettingsRequest("Asia/Shanghai", 4, 3));
        suggestionMapper.delete(null);
        messageMapper.delete(null);
        sessionMapper.delete(null);
        reviewLogMapper.delete(null);
        cardStateMapper.delete(null);
        knowledgePointMapper.delete(null);
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(USER_ID);
        kp.setType(KnowledgePointType.CONCEPT);
        kp.setTitle("什么是 volatile？");
        kp.setSummary("要点");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(NOW);
        kp.setUpdatedAt(NOW);
        knowledgePointMapper.insert(kp);
        kpId = kp.getId();
        reviewService.initCards(USER_ID, List.of(kpId));
    }

    private long session(SessionType type, boolean ended) {
        StudySession s = new StudySession();
        s.setUserId(USER_ID);
        s.setType(type);
        s.setKpId(kpId);
        s.setStartedAt(NOW);
        s.setEndedAt(ended ? NOW : null);
        s.setSkippedExplanation(false);
        s.setActiveSeconds(0);
        sessionMapper.insert(s);
        return s.getId();
    }

    private void suggestion(long sessionId, Rating rating) {
        RatingSuggestion s = new RatingSuggestion();
        s.setSessionId(sessionId);
        s.setUserId(USER_ID);
        s.setRating(rating);
        s.setAnalysis("依据");
        s.setWeakPoints("[]");
        s.setOutOfScopeGaps("[]");
        s.setPromptVersion("v3");
        s.setCreatedAt(NOW);
        suggestionMapper.insert(s);
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    /** 某个时刻属于北京时间凌晨 4 点分界的哪个学习日 */
    private static LocalDate studyDate(Instant instant) {
        return LocalDate.ofInstant(instant.minus(Duration.ofHours(4)), SHANGHAI);
    }

    @Test
    void 预览四档评分的下次复习日期() {
        long sessionId = session(SessionType.STUDY, true);

        List<RatingOption> options = ratingService.preview(USER_ID, sessionId);

        assertThat(options).extracting(RatingOption::rating).containsExactly(Rating.values());
        Map<Rating, Instant> dues = reviewService.preview(USER_ID, kpId);
        for (RatingOption option : options) {
            assertThat(option.date()).isEqualTo(studyDate(dues.get(option.rating())));
            assertThat(option.days()).isEqualTo(ChronoUnit.DAYS.between(LocalDate.of(2026, 11, 2), option.date()));
            assertThat(option.days()).isPositive();
        }
        // 预览不保存
        assertThat(reviewLogMapper.selectCount(null)).isZero();
    }

    @Test
    void 凌晨分界之前预览时今天还是前一天() {
        // 北京时间 11 月 2 日 03:30，学习日还是 11 月 1 日；UTC 是 11 月 1 日 19:30
        ((MutableClock) clock).set(Instant.parse("2026-11-01T19:30:00Z"));
        long sessionId = session(SessionType.STUDY, true);

        RatingOption again = ratingService.preview(USER_ID, sessionId).getFirst();

        Instant due = reviewService.preview(USER_ID, kpId).get(Rating.AGAIN);
        assertThat(again.date()).isEqualTo(studyDate(due));
        assertThat(again.days()).isEqualTo(ChronoUnit.DAYS.between(LocalDate.of(2026, 11, 1), again.date()));
    }

    @Test
    void 确认评分和预览一致并记下AI建议() {
        long sessionId = session(SessionType.STUDY, true);
        suggestion(sessionId, Rating.HARD);
        RatingOption previewed = ratingService.preview(USER_ID, sessionId).get(2);

        RatingOption rated = ratingService.rate(USER_ID, sessionId, new RateRequest(Rating.GOOD));

        assertThat(rated).isEqualTo(previewed);
        ReviewLog log = reviewLogMapper.selectOne(null);
        assertThat(log.getSessionId()).isEqualTo(sessionId);
        assertThat(log.getRatingUser()).isEqualTo(Rating.GOOD);
        assertThat(log.getRatingAi()).isEqualTo(Rating.HARD);
        assertThat(log.getFirstReview()).isTrue();
    }

    @Test
    void 评过分后再次提交就是修改且和预览一致() {
        long sessionId = session(SessionType.STUDY, true);
        suggestion(sessionId, Rating.HARD);
        ratingService.rate(USER_ID, sessionId, new RateRequest(Rating.GOOD));
        RatingOption previewed = ratingService.preview(USER_ID, sessionId).get(3);

        RatingOption changed = ratingService.rate(USER_ID, sessionId, new RateRequest(Rating.EASY));

        assertThat(changed).isEqualTo(previewed);
        assertThat(changed.rating()).isEqualTo(Rating.EASY);
        assertThat(reviewLogMapper.selectCount(null)).isEqualTo(1);
        ReviewLog log = reviewLogMapper.selectOne(null);
        assertThat(log.getRatingUser()).isEqualTo(Rating.EASY);
        assertThat(log.getRatingAi()).isEqualTo(Rating.HARD);
    }

    @Test
    void 隔了几天改成忘了时距今天是负数() {
        long sessionId = session(SessionType.STUDY, true);
        ratingService.rate(USER_ID, sessionId, new RateRequest(Rating.EASY));

        ((MutableClock) clock).set(NOW.plus(Duration.ofDays(3)));
        RatingOption changed = ratingService.rate(USER_ID, sessionId, new RateRequest(Rating.AGAIN));

        // 按原来的评分时间计算：“忘了”至少隔一天（决策 003），是 11 月 3 日；今天已经是 11 月 5 日，前端显示“今天”
        assertThat(changed.date()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(changed.days()).isEqualTo(-2);
        assertThat(reviewLogMapper.selectOne(null).getReviewedAt()).isEqualTo(NOW);
    }

    @Test
    void 之后又复习过的会话不能预览也不能修改() {
        long first = session(SessionType.STUDY, true);
        ratingService.rate(USER_ID, first, new RateRequest(Rating.GOOD));
        long second = session(SessionType.STUDY, true);
        ratingService.rate(USER_ID, second, new RateRequest(Rating.GOOD));

        assertError(() -> ratingService.preview(USER_ID, first), ErrorCode.CONFLICT);
        assertError(() -> ratingService.rate(USER_ID, first, new RateRequest(Rating.EASY)), ErrorCode.CONFLICT);
    }

    @Test
    void 先评分后出建议时不记AI建议() {
        long sessionId = session(SessionType.STUDY, true);

        ratingService.rate(USER_ID, sessionId, new RateRequest(Rating.AGAIN));

        assertThat(reviewLogMapper.selectOne(null).getRatingAi()).isNull();
    }

    @Test
    void 没学完的接着问的其他用户的会话和空评分() {
        long unfinished = session(SessionType.STUDY, false);
        long followUp = session(SessionType.FOLLOW_UP, true);
        long finished = session(SessionType.STUDY, true);

        assertError(() -> ratingService.rate(USER_ID, unfinished, new RateRequest(Rating.GOOD)), ErrorCode.CONFLICT);
        assertError(() -> ratingService.rate(USER_ID, followUp, new RateRequest(Rating.GOOD)), ErrorCode.BAD_REQUEST);
        assertError(() -> ratingService.rate(2, finished, new RateRequest(Rating.GOOD)), ErrorCode.NOT_FOUND);
        assertError(() -> ratingService.rate(USER_ID, finished, new RateRequest(null)), ErrorCode.BAD_REQUEST);
        assertThat(reviewLogMapper.selectCount(null)).isZero();
    }
}
