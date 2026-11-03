package com.wengu.chat.service;

import com.wengu.MutableClock;
import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.ChatEvents;
import com.wengu.chat.dto.MessageView;
import com.wengu.chat.dto.RateRequest;
import com.wengu.chat.dto.SendMessageRequest;
import com.wengu.chat.dto.SessionView;
import com.wengu.chat.dto.StartSessionRequest;
import com.wengu.chat.entity.Marker;
import com.wengu.chat.entity.MessageRole;
import com.wengu.chat.entity.RatingSuggestion;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.RatingSuggestionMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.service.LlmException;
import com.wengu.llm.service.LlmService;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import com.wengu.review.service.ReviewService;
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
import reactor.core.publisher.Sinks;

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
 * 学习对话（决策 025）：多轮对话、标记、轮数上限、跳过讲解、断开、出错、并发、计时。
 * 真实 MySQL，模型换成模拟对象，按测试需要返回不同的流。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatServiceTest {

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

    private long sessionId;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).set(NOW);
        messageMapper.delete(null);
        sessionMapper.delete(null);
        knowledgePointMapper.delete(null);
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(USER_ID);
        kp.setType(KnowledgePointType.MECHANISM);
        kp.setTitle("HashMap 的扩容机制是怎样的？");
        kp.setSummary("负载因子 0.75；容量翻倍；rehash");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(NOW);
        kp.setUpdatedAt(NOW);
        knowledgePointMapper.insert(kp);
        sessionId = sessionService.start(USER_ID, new StartSessionRequest(kp.getId())).id();
    }

    /** 模型下一次（以及之后每次）返回这些段 */
    private void modelReplies(String... chunks) {
        when(llmService.stream(anyString(), anyList(), any())).thenReturn(Flux.just(chunks));
    }

    private static List<ServerSentEvent<Object>> collect(Flux<ServerSentEvent<Object>> events) {
        return events.collectList().block(Duration.ofSeconds(10));
    }

    private static String text(List<ServerSentEvent<Object>> events) {
        StringBuilder out = new StringBuilder();
        events.stream().filter(e -> "delta".equals(e.event()))
                .forEach(e -> out.append(((ChatEvents.Delta) e.data()).text()));
        return out.toString();
    }

    private static ChatEvents.Done done(List<ServerSentEvent<Object>> events) {
        assertThat(events.getLast().event()).isEqualTo("done");
        return (ChatEvents.Done) events.getLast().data();
    }

    /** 最近一次发给模型的历史 */
    @SuppressWarnings("unchecked")
    private List<ChatTurn> lastHistory() {
        ArgumentCaptor<List<ChatTurn>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmService, atLeastOnce()).stream(anyString(), captor.capture(), any());
        return captor.getValue();
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 多轮对话后刷新能恢复全部历史() {
        modelReplies("你知道", "负载因子吗？");
        List<ServerSentEvent<Object>> first = collect(chatService.reply(USER_ID, sessionId));

        assertThat(text(first)).isEqualTo("你知道负载因子吗？");
        assertThat(done(first).marker()).isNull();
        assertThat(done(first).ended()).isFalse();

        modelReplies("对，", "那容量怎么变？");
        collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("  是 0.75  ")));

        SessionView view = sessionService.get(USER_ID, sessionId);
        assertThat(view.messages()).extracting(MessageView::role, MessageView::content).containsExactly(
                tuple(MessageRole.ASSISTANT, "你知道负载因子吗？"),
                tuple(MessageRole.USER, "是 0.75"),
                tuple(MessageRole.ASSISTANT, "对，那容量怎么变？"));
        assertThat(view.generating()).isFalse();
        // 第二次发给模型的历史：知识点信息 + 老师的第一句 + 学生的回答
        List<ChatTurn> history = lastHistory();
        assertThat(history).hasSize(3);
        assertThat(history.getFirst().content()).startsWith("知识点标题：HashMap 的扩容机制是怎样的？");
        assertThat(history.getLast()).isEqualTo(ChatTurn.user("是 0.75"));
    }

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RatingService ratingService;

    @Autowired
    private RatingSuggestionMapper suggestionMapper;

    @Autowired
    private ReviewLogMapper reviewLogMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 复习时第一条消息带上以前问过的问题和没掌握的点() {
        // 第一次学习：第一条消息里没有复习的内容
        modelReplies("你知道负载因子吗？");
        collect(chatService.reply(USER_ID, sessionId));
        assertThat(lastHistory().getFirst().content()).doesNotContain("这是复习");
        modelReplies("对。**那扩容时元素怎么迁移？**");
        collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("0.75")));
        modelReplies("请完整回答原题。\n【完整作答】");
        collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("重新计算下标")));
        modelReplies("还漏了高低位拆分。\n【学习结束】");
        collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("容量翻倍，重新计算下标")));

        // 评分：评分建议存好，用户评“困难”
        long kpId = sessionMapper.selectById(sessionId).getKpId();
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        cardStateMapper.delete(null);
        reviewLogMapper.delete(null);
        suggestionMapper.delete(null);
        reviewService.initCards(USER_ID, List.of(kpId));
        RatingSuggestion suggestion = new RatingSuggestion();
        suggestion.setSessionId(sessionId);
        suggestion.setUserId(USER_ID);
        suggestion.setRating(Rating.HARD);
        suggestion.setAnalysis("依据");
        suggestion.setWeakPoints("[\"迁移时的高低位拆分\"]");
        suggestion.setOutOfScopeGaps("[]");
        suggestion.setPromptVersion("v3");
        suggestion.setCreatedAt(NOW);
        suggestionMapper.insert(suggestion);
        ratingService.rate(USER_ID, sessionId, new RateRequest(Rating.HARD));

        // 复习：开一个新会话
        long reviewId = sessionService.start(USER_ID, new StartSessionRequest(kpId)).id();
        assertThat(reviewId).isNotEqualTo(sessionId);
        modelReplies("换个角度的问题？");
        collect(chatService.reply(USER_ID, reviewId));

        String kickoff = lastHistory().getFirst().content();
        assertThat(kickoff).contains(
                "这是复习：这个知识点以前学过 1 次，上次的评分是“困难”。",
                "上次没掌握的（开场优先围绕其中一点提问）：\n- 迁移时的高低位拆分",
                "以前问过的问题（换一个角度，不要重复，也不要只是换个说法）：\n- 你知道负载因子吗？\n- 那扩容时元素怎么迁移？");
        // 要求完整作答和收尾的回复不算问过的问题
        assertThat(kickoff).doesNotContain("请完整回答原题", "还漏了高低位拆分");

        // 后面每一轮发给模型的第一条消息都一样
        modelReplies("继续追问？");
        collect(chatService.send(USER_ID, reviewId, new SendMessageRequest("回答")));
        assertThat(lastHistory().getFirst().content()).isEqualTo(kickoff);
    }

    @Test
    void 标记不推给前端而是记在消息上() {
        modelReplies("开场");
        collect(chatService.reply(USER_ID, sessionId));
        modelReplies("请完整回答这个问题。\n【完", "整作答】");

        List<ServerSentEvent<Object>> events = collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("懂了")));

        assertThat(text(events)).doesNotContain("【").startsWith("请完整回答这个问题。");
        assertThat(done(events).marker()).isEqualTo(Marker.RESTATE);
        MessageView last = sessionService.get(USER_ID, sessionId).messages().getLast();
        assertThat(last.content()).isEqualTo("请完整回答这个问题。");
        assertThat(last.marker()).isEqualTo(Marker.RESTATE);
    }

    @Test
    void 学习结束后会话结束不能再回答() {
        modelReplies("开场");
        collect(chatService.reply(USER_ID, sessionId));
        modelReplies("参考答法……\n【学习结束】");

        List<ServerSentEvent<Object>> events = collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("完整作答")));

        assertThat(done(events).ended()).isTrue();
        assertThat(sessionMapper.selectById(sessionId).getEndedAt()).isEqualTo(NOW);
        assertThat(sessionService.get(USER_ID, sessionId).ended()).isTrue();
        assertError(() -> chatService.send(USER_ID, sessionId, new SendMessageRequest("再问一句")), ErrorCode.CONFLICT);
    }

    @Test
    void 第7次回答要求完整作答第8次要求收尾之后会话结束() {
        modelReplies("继续");
        collect(chatService.reply(USER_ID, sessionId));
        for (int i = 1; i <= 6; i++) {
            collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("回答 " + i)));
            assertThat(lastHistory().getLast().content()).isEqualTo("回答 " + i);
        }

        collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("回答 7")));
        assertThat(lastHistory().getLast().content()).isEqualTo("回答 7\n\n" + LearningChatPrompt.RESTATE_NOTE);
        assertThat(sessionService.get(USER_ID, sessionId).ended()).isFalse();

        // 第 8 次后模型没有输出【学习结束】，会话也结束
        List<ServerSentEvent<Object>> last = collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("回答 8")));
        assertThat(lastHistory().getLast().content()).isEqualTo("回答 8\n\n" + LearningChatPrompt.MAX_ANSWERS_NOTE);
        assertThat(done(last).ended()).isTrue();
    }

    @Test
    void 跳过讲解时记在会话上并直接要求完整作答() {
        modelReplies("你知道负载因子吗？");
        collect(chatService.reply(USER_ID, sessionId));
        modelReplies("好，请完整回答。\n【完整作答】");

        List<ServerSentEvent<Object>> events = collect(chatService.skipExplanation(USER_ID, sessionId));

        assertThat(done(events).marker()).isEqualTo(Marker.RESTATE);
        assertThat(lastHistory().getLast().content())
                .isEqualTo(LearningChatPrompt.SKIP_MESSAGE + "\n\n" + LearningChatPrompt.SKIP_NOTE);
        SessionView view = sessionService.get(USER_ID, sessionId);
        assertThat(view.skippedExplanation()).isTrue();
        assertThat(view.messages().get(1).content()).isEqualTo(LearningChatPrompt.SKIP_MESSAGE);
        // 已经在完整作答了，不能再跳过
        assertError(() -> chatService.skipExplanation(USER_ID, sessionId), ErrorCode.CONFLICT);
    }

    @Test
    void 浏览器中途断开后回复照样生成完并存库() {
        when(llmService.stream(anyString(), anyList(), any()))
                .thenReturn(Flux.just("第一段", "第二段", "第三段").delayElements(Duration.ofMillis(200)));

        // 只收到第一段就断开：take(1) 之后取消订阅
        List<ServerSentEvent<Object>> received = chatService.reply(USER_ID, sessionId).take(1)
                .collectList().block(Duration.ofSeconds(10));
        assertThat(text(received)).isEqualTo("第一段");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(sessionService.get(USER_ID, sessionId).messages())
                        .singleElement()
                        .satisfies(m -> assertThat(m.content()).isEqualTo("第一段第二段第三段")));
        assertThat(sessionService.get(USER_ID, sessionId).generating()).isFalse();
    }

    @Test
    void 模型调用失败时推送分好类的提示() {
        when(llmService.stream(anyString(), anyList(), any()))
                .thenReturn(Flux.error(new LlmException(ErrorCode.LLM_TIMEOUT)));

        List<ServerSentEvent<Object>> failed = collect(chatService.reply(USER_ID, sessionId));

        assertThat(failed).singleElement().satisfies(e ->
                assertThat(((ChatEvents.Error) e.data()).message()).isEqualTo(ErrorCode.LLM_TIMEOUT.getMessage()));
        assertThat(sessionService.get(USER_ID, sessionId).messages()).isEmpty();
    }

    @Test
    void 去掉标记后没有正文时按空正文提示() {
        modelReplies("【学习结束】");

        List<ServerSentEvent<Object>> failed = collect(chatService.reply(USER_ID, sessionId));

        assertThat(failed).last().satisfies(e -> {
            assertThat(e.event()).isEqualTo("error");
            assertThat(((ChatEvents.Error) e.data()).message()).isEqualTo(ErrorCode.LLM_EMPTY_RESPONSE.getMessage());
        });
        assertThat(sessionService.get(USER_ID, sessionId).messages()).isEmpty();
    }

    @Test
    void 模型出错时推送error不存回复可以重试() {
        when(llmService.stream(anyString(), anyList(), any())).thenReturn(Flux.error(new RuntimeException("timeout")));

        List<ServerSentEvent<Object>> failed = collect(chatService.reply(USER_ID, sessionId));

        assertThat(failed).singleElement().satisfies(e -> {
            assertThat(e.event()).isEqualTo("error");
            assertThat(((ChatEvents.Error) e.data()).message()).isEqualTo(ChatService.ERROR_MESSAGE);
        });
        assertThat(sessionService.get(USER_ID, sessionId).messages()).isEmpty();

        // 重试成功
        modelReplies("你知道负载因子吗？");
        assertThat(text(collect(chatService.reply(USER_ID, sessionId)))).isEqualTo("你知道负载因子吗？");
    }

    @Test
    void 空回复当作出错() {
        modelReplies("  ", "\n");

        List<ServerSentEvent<Object>> events = collect(chatService.reply(USER_ID, sessionId));

        assertThat(events.getLast().event()).isEqualTo("error");
        assertThat(sessionService.get(USER_ID, sessionId).messages()).isEmpty();
    }

    @Test
    void 正在回复时不能再发也不能重复生成() {
        Sinks.Many<String> slow = Sinks.many().unicast().onBackpressureBuffer();
        when(llmService.stream(anyString(), anyList(), any())).thenReturn(slow.asFlux());
        Flux<ServerSentEvent<Object>> first = chatService.reply(USER_ID, sessionId);

        assertThat(sessionService.get(USER_ID, sessionId).generating()).isTrue();
        assertError(() -> chatService.reply(USER_ID, sessionId), ErrorCode.CONFLICT);

        slow.tryEmitNext("你知道负载因子吗？");
        slow.tryEmitComplete();
        assertThat(text(collect(first))).isEqualTo("你知道负载因子吗？");
        await().atMost(Duration.ofSeconds(5)).until(() -> !sessionService.get(USER_ID, sessionId).generating());
        // 老师说完了，再生成一条回复也不行：轮到学生回答
        assertError(() -> chatService.reply(USER_ID, sessionId), ErrorCode.CONFLICT);
    }

    @Test
    void 不合法的回答和其他用户的会话() {
        modelReplies("开场");
        collect(chatService.reply(USER_ID, sessionId));

        assertError(() -> chatService.send(USER_ID, sessionId, new SendMessageRequest("   ")), ErrorCode.BAD_REQUEST);
        assertError(() -> chatService.send(USER_ID, sessionId, new SendMessageRequest("字".repeat(5001))),
                ErrorCode.BAD_REQUEST);
        assertError(() -> chatService.send(2, sessionId, new SendMessageRequest("你好")), ErrorCode.NOT_FOUND);
        assertError(() -> chatService.reply(2, sessionId), ErrorCode.NOT_FOUND);
    }

    @Test
    void 有效时长随学生回答和老师回复累加() {
        modelReplies("开场");
        collect(chatService.reply(USER_ID, sessionId));
        // 3 分钟后回答：+180；回复在同一时刻完成：+0
        ((MutableClock) clock).set(NOW.plus(Duration.ofMinutes(3)));
        collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("回答 1")));
        // 离开 40 分钟后回答：只加 600
        ((MutableClock) clock).set(NOW.plus(Duration.ofMinutes(43)));
        collect(chatService.send(USER_ID, sessionId, new SendMessageRequest("回答 2")));

        assertThat(sessionMapper.selectById(sessionId).getActiveSeconds()).isEqualTo(180 + 600);
    }
}
