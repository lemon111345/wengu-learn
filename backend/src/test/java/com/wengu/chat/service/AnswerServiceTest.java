package com.wengu.chat.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.AnswerSummary;
import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.review.entity.CardState;
import com.wengu.review.fsrs.Card;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.fsrs.State;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import com.wengu.review.service.ReviewService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 答法速查（#112）。真实 MySQL，时钟固定。
 * 用户设置：Asia/Shanghai、凌晨 4 点分界；现在是北京时间 10 月 29 日 10:00。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AnswerServiceTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER = 2L;
    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return new MutableClock(NOW);
    }

    @Autowired
    private AnswerService service;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private StudySessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private ReviewLogMapper reviewLogMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        jdbc.update("UPDATE user_settings SET timezone = 'Asia/Shanghai', day_start_hour = 4 WHERE user_id = 1");
        reviewLogMapper.delete(null);
        messageMapper.delete(null);
        sessionMapper.delete(null);
        cardStateMapper.delete(null);
        knowledgePointMapper.delete(null);
    }

    private KnowledgePoint kp(long userId, String title, KnowledgePointStatus status) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(userId);
        kp.setType(KnowledgePointType.MECHANISM);
        kp.setTitle(title);
        kp.setSummary("要点");
        kp.setStatus(status);
        kp.setSortOrder(1);
        kp.setCreatedAt(NOW);
        kp.setUpdatedAt(NOW);
        knowledgePointMapper.insert(kp);
        return kp;
    }

    private KnowledgePoint kp(String title) {
        return kp(USER_ID, title, KnowledgePointStatus.ACTIVE);
    }

    /** 一次学习：endedAt 为 null 时还没结束；closing 是最后一条老师回复，为 null 时没有消息 */
    private StudySession study(KnowledgePoint kp, SessionType type, Instant endedAt, String closing) {
        StudySession s = new StudySession();
        s.setUserId(kp.getUserId());
        s.setType(type);
        s.setKpId(kp.getId());
        s.setStartedAt(NOW.minus(Duration.ofDays(10)));
        s.setEndedAt(endedAt);
        s.setSkippedExplanation(false);
        s.setActiveSeconds(0);
        sessionMapper.insert(s);
        if (closing != null) {
            message(s, MessageRole.ASSISTANT, "开场问题？");
            message(s, MessageRole.USER, "完整作答");
            message(s, MessageRole.ASSISTANT, closing);
        }
        return s;
    }

    private StudySession studied(KnowledgePoint kp, Instant endedAt, String answer) {
        return study(kp, SessionType.STUDY, endedAt, "点评。\n\n**参考答法**：" + answer);
    }

    private void message(StudySession session, MessageRole role, String content) {
        Message m = new Message();
        m.setUserId(session.getUserId());
        m.setSessionId(session.getId());
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(NOW);
        messageMapper.insert(m);
    }

    private void card(KnowledgePoint kp, Instant due) {
        cardStateMapper.insert(CardState.of(kp.getId(), kp.getUserId(),
                new Card(State.REVIEW, null, 5.0, 5.0, due, due.minus(Duration.ofDays(5)))));
    }

    private static List<String> titles(List<AnswerSummary> items) {
        return items.stream().map(AnswerSummary::kpTitle).toList();
    }

    @Test
    void 每个知识点一条_用最近一次学习的答法_最近学的在前() {
        KnowledgePoint hashMap = kp("HashMap");
        KnowledgePoint mvcc = kp("MVCC");
        studied(hashMap, NOW.minus(Duration.ofDays(5)), "旧的答法");
        StudySession latest = studied(hashMap, NOW.minus(Duration.ofHours(1)), "新的答法");
        studied(mvcc, NOW.minus(Duration.ofDays(2)), "MVCC 的答法");

        List<AnswerSummary> items = service.list(USER_ID);

        assertThat(titles(items)).containsExactly("HashMap", "MVCC");
        assertThat(items.getFirst().referenceAnswer()).isEqualTo("新的答法");
        assertThat(items.getFirst().sessionId()).isEqualTo(latest.getId());
    }

    @Test
    void 没学完的_接着问_别人的_已删除的都不列() {
        study(kp("没学完"), SessionType.STUDY, null, null);
        KnowledgePoint followed = kp("只有接着问");
        study(followed, SessionType.FOLLOW_UP, NOW, "参考答法：不该出现");
        studied(kp(OTHER_USER, "别人的", KnowledgePointStatus.ACTIVE), NOW, "答法");
        KnowledgePoint deleted = kp("删掉的");
        studied(deleted, NOW, "答法");
        deleted.setDeletedAt(NOW);
        knowledgePointMapper.updateById(deleted);
        KnowledgePoint paused = kp(USER_ID, "暂停的", KnowledgePointStatus.SUSPENDED);
        studied(paused, NOW, "答法");

        assertThat(titles(service.list(USER_ID))).containsExactly("暂停的");
    }

    @Test
    void 收尾里没有参考答法时也列出_答法为null() {
        study(kp("没写答法"), SessionType.STUDY, NOW, "讲得不错。");

        AnswerSummary item = service.list(USER_ID).getFirst();

        assertThat(item.referenceAnswer()).isNull();
    }

    @Test
    void 带这次学习的评分_没评分时为null() {
        KnowledgePoint rated = kp("评过分的");
        card(rated, NOW.plus(Duration.ofDays(3)));
        StudySession session = studied(rated, NOW, "答法");
        reviewService.rate(USER_ID, rated.getId(), session.getId(), Rating.HARD, null);
        studied(kp("没评分的"), NOW.minus(Duration.ofHours(1)), "答法");

        List<AnswerSummary> items = service.list(USER_ID);

        assertThat(items).extracting(AnswerSummary::rating).containsExactly(Rating.HARD, null);
    }

    @Test
    void 学习日按用户的分界时刻换算() {
        // 北京时间 10 月 29 日 03:00，在凌晨 4 点之前，算 10 月 28 日
        studied(kp("凌晨学的"), Instant.parse("2026-10-28T19:00:00Z"), "答法");

        assertThat(service.list(USER_ID).getFirst().studiedDate()).isEqualTo(LocalDate.of(2026, 10, 28));
    }

    @Test
    void 今天要复习还没复习的标记出来_暂停的不算() {
        KnowledgePoint today = kp("今天到期");
        card(today, NOW.minus(Duration.ofHours(1)));
        studied(today, NOW.minus(Duration.ofDays(3)), "答法");
        KnowledgePoint later = kp("以后到期");
        card(later, NOW.plus(Duration.ofDays(3)));
        studied(later, NOW.minus(Duration.ofDays(4)), "答法");
        KnowledgePoint paused = kp(USER_ID, "暂停的", KnowledgePointStatus.SUSPENDED);
        card(paused, NOW.minus(Duration.ofHours(1)));
        studied(paused, NOW.minus(Duration.ofDays(5)), "答法");

        assertThat(service.list(USER_ID)).extracting(AnswerSummary::dueToday).containsExactly(true, false, false);
    }
}
