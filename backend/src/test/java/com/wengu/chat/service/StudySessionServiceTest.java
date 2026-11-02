package com.wengu.chat.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.SessionView;
import com.wengu.chat.dto.StartSessionRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 学习会话：开始或恢复、读取、自动计时（决策 025）。真实 MySQL，时钟可以拨动。 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StudySessionServiceTest {

    private static final long USER_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-11-02T02:00:00Z");

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return new MutableClock(NOW);
    }

    @Autowired
    private StudySessionService sessionService;

    @Autowired
    private StudySessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).set(NOW);
        messageMapper.delete(null);
        sessionMapper.delete(null);
        knowledgePointMapper.delete(null);
    }

    private long kp(long userId, String title, KnowledgePointType type) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(userId);
        kp.setType(type);
        kp.setTitle(title);
        kp.setSummary("要点");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(NOW);
        kp.setUpdatedAt(NOW);
        knowledgePointMapper.insert(kp);
        return kp.getId();
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 开始学习时创建会话再次开始时接着上次的会话() {
        long kpId = kp(USER_ID, "HashMap 的扩容机制是怎样的？", KnowledgePointType.MECHANISM);

        SessionView first = sessionService.start(USER_ID, new StartSessionRequest(kpId));

        assertThat(first.type()).isEqualTo(SessionType.STUDY);
        assertThat(first.kpTitle()).isEqualTo("HashMap 的扩容机制是怎样的？");
        assertThat(first.ended()).isFalse();
        assertThat(first.messages()).isEmpty();
        StudySession saved = sessionMapper.selectById(first.id());
        assertThat(saved.getStartedAt()).isEqualTo(NOW);
        assertThat(saved.getLastActiveAt()).isEqualTo(NOW);
        assertThat(saved.getActiveSeconds()).isZero();
        assertThat(saved.getPromptVersion()).isEqualTo(LearningChatPrompt.VERSION);

        // 没结束时再开始，还是同一个会话
        assertThat(sessionService.start(USER_ID, new StartSessionRequest(kpId)).id()).isEqualTo(first.id());

        // 结束以后再开始，是新的会话
        saved.setEndedAt(NOW);
        sessionMapper.updateById(saved);
        assertThat(sessionService.start(USER_ID, new StartSessionRequest(kpId)).id()).isNotEqualTo(first.id());
    }

    @Test
    void 只收录的类型和其他用户的知识点不能学习() {
        long design = kp(USER_ID, "如何设计一个秒杀系统？", KnowledgePointType.SYSTEM_DESIGN);
        long others = kp(2, "其他用户的？", KnowledgePointType.CONCEPT);

        assertError(() -> sessionService.start(USER_ID, new StartSessionRequest(design)), ErrorCode.BAD_REQUEST);
        assertError(() -> sessionService.start(USER_ID, new StartSessionRequest(others)), ErrorCode.NOT_FOUND);
        assertError(() -> sessionService.start(USER_ID, new StartSessionRequest(null)), ErrorCode.BAD_REQUEST);
        assertThat(sessionMapper.selectCount(null)).isZero();
    }

    @Test
    void 读取会话时返回消息但不返回只给模型看的提示() {
        long kpId = kp(USER_ID, "什么是 MVCC？", KnowledgePointType.CONCEPT);
        long sessionId = sessionService.start(USER_ID, new StartSessionRequest(kpId)).id();
        Message message = new Message();
        message.setUserId(USER_ID);
        message.setSessionId(sessionId);
        message.setRole(MessageRole.USER);
        message.setContent("我的回答");
        message.setModelNote(LearningChatPrompt.RESTATE_NOTE);
        message.setCreatedAt(NOW);
        messageMapper.insert(message);

        SessionView view = sessionService.get(USER_ID, sessionId);

        assertThat(view.kpTitle()).isEqualTo("什么是 MVCC？");
        assertThat(view.messages()).singleElement().satisfies(m -> {
            assertThat(m.role()).isEqualTo(MessageRole.USER);
            assertThat(m.content()).isEqualTo("我的回答");
        });
        assertError(() -> sessionService.get(2, sessionId), ErrorCode.NOT_FOUND);
    }

    @Test
    void 有效时长两次活动之间最多算10分钟() {
        assertThat(StudySessionService.activeIncrement(null, NOW)).isZero();
        assertThat(StudySessionService.activeIncrement(NOW, NOW.plus(Duration.ofMinutes(3)))).isEqualTo(180);
        assertThat(StudySessionService.activeIncrement(NOW, NOW.plus(Duration.ofMinutes(30)))).isEqualTo(600);
        assertThat(StudySessionService.activeIncrement(NOW, NOW.minusSeconds(5))).isZero();

        long kpId = kp(USER_ID, "什么是 MVCC？", KnowledgePointType.CONCEPT);
        long sessionId = sessionService.start(USER_ID, new StartSessionRequest(kpId)).id();
        sessionService.recordActivity(sessionId, NOW.plus(Duration.ofMinutes(3)));
        // 中途离开半小时，只多算 10 分钟
        sessionService.recordActivity(sessionId, NOW.plus(Duration.ofMinutes(33)));

        StudySession saved = sessionMapper.selectById(sessionId);
        assertThat(saved.getActiveSeconds()).isEqualTo(180 + 600);
        assertThat(saved.getLastActiveAt()).isEqualTo(NOW.plus(Duration.ofMinutes(33)));
    }
}
