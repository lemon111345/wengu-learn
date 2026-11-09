package com.wengu.chat.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.ChatEvents;
import com.wengu.chat.dto.MessageView;
import com.wengu.chat.dto.SendMessageRequest;
import com.wengu.chat.dto.SessionView;
import com.wengu.chat.dto.StartSessionRequest;
import com.wengu.chat.entity.MessageRole;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.service.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接着问（决策 025）：挂在学完的学习会话下面，学生先问，上下文带着学习对话，上限 30 次提问。
 * 真实 MySQL，模型换成模拟对象。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FollowUpTest {

    private static final long USER_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-11-02T02:00:00Z");

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return new MutableClock(NOW);
    }

    @MockitoBean
    private LlmService llmService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private StudySessionService sessionService;

    @Autowired
    private StudySessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long studyId;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).set(NOW);
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        jdbc.update("UPDATE user_settings SET teacher_name = '老师' WHERE user_id = 1");
        messageMapper.delete(null);
        sessionMapper.delete(null);
        knowledgePointMapper.delete(null);
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(USER_ID);
        kp.setType(KnowledgePointType.MECHANISM);
        kp.setTitle("HashMap 的扩容机制是怎样的？");
        kp.setSummary("负载因子 0.75；容量翻倍");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(NOW);
        kp.setUpdatedAt(NOW);
        knowledgePointMapper.insert(kp);
        studyId = sessionService.start(USER_ID, new StartSessionRequest(kp.getId())).id();
    }

    private void modelReplies(String... chunks) {
        when(llmService.stream(anyString(), anyList(), any())).thenReturn(Flux.just(chunks));
    }

    private static List<ServerSentEvent<Object>> collect(Flux<ServerSentEvent<Object>> events) {
        return events.collectList().block(Duration.ofSeconds(10));
    }

    /** 把学习对话走完：开场、一次回答、收尾 */
    private void finishStudy() {
        modelReplies("你知道负载因子吗？");
        collect(chatService.reply(USER_ID, studyId));
        modelReplies("参考答法……\n【学习结束】");
        collect(chatService.send(USER_ID, studyId, new SendMessageRequest("是 0.75")));
        assertThat(sessionService.get(USER_ID, studyId).ended()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private List<ChatTurn> lastHistory() {
        ArgumentCaptor<List<ChatTurn>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmService, atLeastOnce()).stream(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void 接着问也用新称呼() {
        finishStudy();
        jdbc.update("UPDATE user_settings SET teacher_name = '小明' WHERE user_id = 1");
        long followUpId = sessionService.startFollowUp(USER_ID, studyId).id();
        modelReplies("负载因子是 0.75。");

        collect(chatService.send(USER_ID, followUpId, new SendMessageRequest("为什么是 0.75？")));

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(llmService, atLeastOnce()).stream(system.capture(), anyList(), any());
        assertThat(system.getValue()).startsWith(FollowUpPrompt.system()).contains("学生称呼你为“小明”");
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 回顾时查得到接着问的会话且读取不会新建() {
        finishStudy();

        assertThat(sessionService.get(USER_ID, studyId).followUpSessionId()).isNull();
        assertThat(sessionMapper.selectCount(null)).isEqualTo(1);

        long followUpId = sessionService.startFollowUp(USER_ID, studyId).id();

        assertThat(sessionService.get(USER_ID, studyId).followUpSessionId()).isEqualTo(followUpId);
        assertThat(sessionService.get(USER_ID, followUpId).followUpSessionId()).isNull();
    }

    @Test
    void 学完之后才能接着问且再次进入是同一个会话() {
        assertError(() -> sessionService.startFollowUp(USER_ID, studyId), ErrorCode.CONFLICT);
        finishStudy();

        SessionView followUp = sessionService.startFollowUp(USER_ID, studyId);

        assertThat(followUp.type()).isEqualTo(SessionType.FOLLOW_UP);
        assertThat(followUp.parentSessionId()).isEqualTo(studyId);
        assertThat(followUp.kpTitle()).isEqualTo("HashMap 的扩容机制是怎样的？");
        assertThat(followUp.messages()).isEmpty();
        assertThat(sessionMapper.selectById(followUp.id()).getPromptVersion()).isEqualTo(FollowUpPrompt.VERSION);
        assertThat(sessionService.startFollowUp(USER_ID, studyId).id()).isEqualTo(followUp.id());
        // 接着问的会话不能再接着问；其他用户的会话不存在
        assertError(() -> sessionService.startFollowUp(USER_ID, followUp.id()), ErrorCode.BAD_REQUEST);
        assertError(() -> sessionService.startFollowUp(2, studyId), ErrorCode.NOT_FOUND);
    }

    @Test
    void 学生先问且第一个问题前面带着学习对话() {
        finishStudy();
        long followUpId = sessionService.startFollowUp(USER_ID, studyId).id();
        // 没有开场白，也不能跳过讲解
        assertError(() -> chatService.reply(USER_ID, followUpId), ErrorCode.CONFLICT);
        assertError(() -> chatService.skipExplanation(USER_ID, followUpId), ErrorCode.CONFLICT);

        modelReplies("这是时间和空间的折中……");
        List<ServerSentEvent<Object>> events = collect(chatService.send(USER_ID, followUpId,
                new SendMessageRequest("为什么是 0.75？")));

        assertThat(events.getLast().event()).isEqualTo("done");
        assertThat(((ChatEvents.Done) events.getLast().data()).ended()).isFalse();
        List<ChatTurn> history = lastHistory();
        assertThat(history).singleElement().satisfies(turn -> assertThat(turn.content())
                .startsWith("知识点标题：HashMap 的扩容机制是怎样的？")
                .contains("【老师】\n你知道负载因子吗？", "【学生】\n是 0.75", "【老师】\n参考答法……")
                .doesNotContain("【学习结束】")
                .endsWith("学生的问题：\n为什么是 0.75？"));
        assertThat(sessionService.get(USER_ID, followUpId).messages())
                .extracting(MessageView::role, MessageView::content)
                .containsExactly(tuple(MessageRole.USER, "为什么是 0.75？"),
                        tuple(MessageRole.ASSISTANT, "这是时间和空间的折中……"));
        // 学习会话的消息不受影响
        assertThat(sessionService.get(USER_ID, studyId).messages()).hasSize(3);
    }

    @Test
    void 第26次提问时提醒第30次后结束() {
        finishStudy();
        long followUpId = sessionService.startFollowUp(USER_ID, studyId).id();
        modelReplies("回答");
        for (int i = 1; i <= FollowUpPrompt.MAX_QUESTIONS; i++) {
            List<ServerSentEvent<Object>> events = collect(chatService.send(USER_ID, followUpId,
                    new SendMessageRequest("问题 " + i)));
            String sent = lastHistory().getLast().content();
            if (i == FollowUpPrompt.REMIND_AT) {
                assertThat(sent).isEqualTo("问题 " + i + "\n\n" + FollowUpPrompt.REMIND_NOTE);
            } else {
                assertThat(sent).endsWith("问题 " + i);
            }
            assertThat(((ChatEvents.Done) events.getLast().data()).ended()).isEqualTo(i == FollowUpPrompt.MAX_QUESTIONS);
        }

        assertThatThrownBy(() -> chatService.send(USER_ID, followUpId, new SendMessageRequest("问题 31")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).startsWith("这次聊得比较多了");
                });
        // 到上限结束了，再进入还是这个会话，前端据此提示
        SessionView again = sessionService.startFollowUp(USER_ID, studyId);
        assertThat(again.id()).isEqualTo(followUpId);
        assertThat(again.ended()).isTrue();
    }

    @Test
    void 出错后可以重试且断开后回复照样存库() {
        finishStudy();
        long followUpId = sessionService.startFollowUp(USER_ID, studyId).id();
        when(llmService.stream(anyString(), anyList(), any())).thenReturn(Flux.error(new RuntimeException("timeout")));
        assertThat(collect(chatService.send(USER_ID, followUpId, new SendMessageRequest("为什么是 0.75？")))
                .getLast().event()).isEqualTo("error");

        // 重试时只收到第一段就断开
        when(llmService.stream(anyString(), anyList(), any()))
                .thenReturn(Flux.just("第一段", "第二段").delayElements(Duration.ofMillis(200)));
        chatService.reply(USER_ID, followUpId).take(1).collectList().block(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(sessionService.get(USER_ID, followUpId).messages().getLast().content())
                        .isEqualTo("第一段第二段"));
    }

    @Test
    void 接着问的时长算进今天的学习时长() {
        finishStudy();
        long followUpId = sessionService.startFollowUp(USER_ID, studyId).id();
        modelReplies("回答");
        ((MutableClock) clock).set(NOW.plus(Duration.ofMinutes(4)));
        collect(chatService.send(USER_ID, followUpId, new SendMessageRequest("为什么是 0.75？")));

        assertThat(sessionMapper.selectById(followUpId).getActiveSeconds()).isEqualTo(240);
        assertThat(sessionService.activeSecondsBetween(USER_ID, NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofHours(1)))).isEqualTo(240);
    }
}
