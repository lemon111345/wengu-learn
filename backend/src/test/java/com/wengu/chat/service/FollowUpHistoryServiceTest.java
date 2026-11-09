package com.wengu.chat.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.FollowUpStatus;
import com.wengu.chat.dto.FollowUpSummary;
import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.review.entity.CardState;
import com.wengu.review.fsrs.Card;
import com.wengu.review.fsrs.State;
import com.wengu.review.mapper.CardStateMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 问答记录（决策 043）。真实 MySQL，时钟可以拨动。
 * 用户设置：Asia/Shanghai、凌晨 4 点分界；默认时刻是北京时间 10 月 29 日 10:00。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FollowUpHistoryServiceTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER = 2L;
    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return new MutableClock(NOW);
    }

    @Autowired
    private FollowUpHistoryService service;

    @Autowired
    private StudySessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).set(NOW);
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        jdbc.update("UPDATE user_settings SET timezone = 'Asia/Shanghai', day_start_hour = 4, teacher_name = '老师'"
                + " WHERE user_id = 1");
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

    private StudySession study(KnowledgePoint kp, boolean ended) {
        StudySession s = new StudySession();
        s.setUserId(kp.getUserId());
        s.setType(SessionType.STUDY);
        s.setKpId(kp.getId());
        s.setStartedAt(NOW);
        s.setEndedAt(ended ? NOW : null);
        s.setSkippedExplanation(false);
        s.setActiveSeconds(0);
        sessionMapper.insert(s);
        return s;
    }

    private StudySession followUp(StudySession study, Instant lastActive, boolean ended) {
        StudySession s = new StudySession();
        s.setUserId(study.getUserId());
        s.setType(SessionType.FOLLOW_UP);
        s.setKpId(study.getKpId());
        s.setParentSessionId(study.getId());
        s.setStartedAt(lastActive);
        s.setLastActiveAt(lastActive);
        s.setEndedAt(ended ? lastActive : null);
        s.setSkippedExplanation(false);
        s.setActiveSeconds(0);
        sessionMapper.insert(s);
        return s;
    }

    private void message(StudySession session, MessageRole role, String content, Instant at) {
        Message m = new Message();
        m.setUserId(session.getUserId());
        m.setSessionId(session.getId());
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(at);
        messageMapper.insert(m);
    }

    /** 一场问过一个问题的接着问 */
    private StudySession asked(String title, String question, String answer, Instant lastActive) {
        StudySession followUp = followUp(study(kp(title), true), lastActive, false);
        message(followUp, MessageRole.USER, question, lastActive);
        message(followUp, MessageRole.ASSISTANT, answer, lastActive);
        return followUp;
    }

    private void cardDue(KnowledgePoint kp, Instant due) {
        cardStateMapper.insert(CardState.of(kp.getId(), kp.getUserId(),
                new Card(State.REVIEW, null, 5.0, 5.0, due, due.minus(Duration.ofDays(5)))));
    }

    private static List<String> titles(List<FollowUpSummary> items) {
        return items.stream().map(FollowUpSummary::kpTitle).toList();
    }

    @Test
    void 最近活动的在前_显示最后一问和提问数() {
        asked("较早的", "问题", "回答", NOW.minus(Duration.ofDays(2)));
        StudySession recent = asked("最近的", "第一问", "回答一", NOW.minus(Duration.ofHours(2)));
        message(recent, MessageRole.USER, "第二问", NOW.minus(Duration.ofHours(1)));

        List<FollowUpSummary> items = service.list(USER_ID, null);

        assertThat(titles(items)).containsExactly("最近的", "较早的");
        assertThat(items.getFirst().lastQuestion()).isEqualTo("第二问");
        assertThat(items.getFirst().questionCount()).isEqualTo(2);
        assertThat(items.getFirst().followUpSessionId()).isEqualTo(recent.getId());
        assertThat(items.getFirst().studySessionId()).isEqualTo(recent.getParentSessionId());
    }

    @Test
    void 没有提问的空对话不显示() {
        followUp(study(kp("进入过但没问"), true), NOW, false);

        assertThat(service.list(USER_ID, null)).isEmpty();
    }

    @Test
    void 其他用户的对话不显示() {
        StudySession others = followUp(study(kp(OTHER_USER, "别人的", KnowledgePointStatus.ACTIVE), true), NOW, false);
        message(others, MessageRole.USER, "别人的问题", NOW);

        assertThat(service.list(USER_ID, null)).isEmpty();
    }

    @Test
    void 最后活动日按学习日算_凌晨分界之前算前一天() {
        // 北京时间 10 月 29 日 03:30，学习日还是 10 月 28 日
        asked("凌晨问的", "问题", "回答", Instant.parse("2026-10-28T19:30:00Z"));

        assertThat(service.list(USER_ID, null).getFirst().lastActiveDate()).isEqualTo(LocalDate.of(2026, 10, 28));
    }

    @Test
    void 搜索匹配标题问题和回答_不区分大小写() {
        asked("HashMap 的扩容", "为什么是两倍", "因为要用位运算", NOW.minus(Duration.ofHours(3)));
        asked("volatile 的作用", "可见性是什么", "涉及内存屏障", NOW.minus(Duration.ofHours(2)));

        assertThat(titles(service.list(USER_ID, "hashmap"))).containsExactly("HashMap 的扩容");
        assertThat(titles(service.list(USER_ID, "可见性"))).containsExactly("volatile 的作用");
        assertThat(titles(service.list(USER_ID, "内存屏障"))).containsExactly("volatile 的作用");
        assertThat(titles(service.list(USER_ID, "  位运算  "))).containsExactly("HashMap 的扩容");
        assertThat(service.list(USER_ID, "不存在的词")).isEmpty();
        assertThat(service.list(USER_ID, "   ")).hasSize(2);
    }

    @Test
    void 知识点已删除时标题照常显示_不能继续问() {
        StudySession followUp = asked("删掉的知识点", "问题", "回答", NOW);
        jdbc.update("UPDATE knowledge_point SET deleted_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW), followUp.getKpId());

        FollowUpSummary item = service.list(USER_ID, null).getFirst();
        assertThat(item.kpTitle()).isEqualTo("删掉的知识点");
        assertThat(item.canContinue()).isFalse();

        FollowUpStatus status = service.status(USER_ID, followUp.getParentSessionId());
        assertThat(status.kpTitle()).isEqualTo("删掉的知识点");
        assertThat(status.canContinue()).isFalse();
        assertThat(status.readOnlyReason()).isEqualTo(FollowUpHistoryService.KNOWLEDGE_POINT_GONE);
    }

    @Test
    void 问满上限后不能继续问() {
        StudySession study = study(kp("问满了"), true);
        StudySession followUp = followUp(study, NOW, true);
        message(followUp, MessageRole.USER, "问题", NOW);

        assertThat(service.list(USER_ID, null).getFirst().canContinue()).isFalse();
        assertThat(service.status(USER_ID, study.getId()).readOnlyReason())
                .isEqualTo(FollowUpHistoryService.LIMIT_REACHED);
    }

    @Test
    void 今天要复习的才标记_今天结束前到期也算() {
        KnowledgePoint today = kp("今天晚些时候到期");
        KnowledgePoint later = kp("以后才到期");
        cardDue(today, Instant.parse("2026-10-29T19:00:00Z"));
        cardDue(later, Instant.parse("2026-10-29T21:00:00Z"));
        StudySession todayStudy = study(today, true);
        StudySession laterStudy = study(later, true);

        assertThat(service.status(USER_ID, todayStudy.getId()).dueToday()).isTrue();
        assertThat(service.status(USER_ID, laterStudy.getId()).dueToday()).isFalse();

        message(followUp(todayStudy, NOW, false), MessageRole.USER, "问题", NOW);
        assertThat(service.list(USER_ID, null).getFirst().dueToday()).isTrue();
    }

    @Test
    void 暂停的知识点不算今天要复习() {
        KnowledgePoint paused = kp(USER_ID, "暂停了", KnowledgePointStatus.SUSPENDED);
        cardDue(paused, NOW);

        assertThat(service.status(USER_ID, study(paused, true).getId()).dueToday()).isFalse();
    }

    @Test
    void 还没接着问时只查不建_可以继续问() {
        StudySession study = study(kp("学完没问"), true);

        FollowUpStatus status = service.status(USER_ID, study.getId());

        assertThat(status.followUpSessionId()).isNull();
        assertThat(status.canContinue()).isTrue();
        assertThat(status.readOnlyReason()).isNull();
        assertThat(status.teacherName()).isEqualTo("老师");
        assertThat(sessionMapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void 学习还没结束时不能接着问() {
        FollowUpStatus status = service.status(USER_ID, study(kp("学到一半"), false).getId());

        assertThat(status.canContinue()).isFalse();
        assertThat(status.readOnlyReason()).isEqualTo(FollowUpHistoryService.STUDY_NOT_ENDED);
    }

    @Test
    void 状态接口只接受自己的学习会话() {
        StudySession followUp = followUp(study(kp("知识点"), true), NOW, false);
        StudySession others = study(kp(OTHER_USER, "别人的", KnowledgePointStatus.ACTIVE), true);

        assertThatThrownBy(() -> service.status(USER_ID, followUp.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.status(USER_ID, others.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
