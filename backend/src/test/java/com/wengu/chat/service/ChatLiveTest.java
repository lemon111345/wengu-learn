package com.wengu.chat.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.ChatEvents;
import com.wengu.chat.dto.RateRequest;
import com.wengu.chat.dto.RatingOption;
import com.wengu.chat.dto.RatingSuggestionView;
import com.wengu.chat.dto.SendMessageRequest;
import com.wengu.chat.dto.StartSessionRequest;
import com.wengu.chat.entity.Marker;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.RatingSuggestionMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import com.wengu.review.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实连测试：用真实模型走一遍学习对话、评分建议和接着问，检查学习对话 prompt v3 的两个标记模型是否照做，
 * 并打印接着问的回答供人工查看（是否只回答、不反问）。会产生少量费用，默认跳过：
 * <pre>
 * .\mvnw.cmd test -Dtest=ChatLiveTest -Dllm.live=true
 * </pre>
 * 运行前需设置环境变量 WENGU_LLM_API_KEY，见 README。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "llm.live", matches = "true")
class ChatLiveTest {

    private static final long USER_ID = 1L;
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

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
    private RatingSuggestionService ratingSuggestionService;

    @Autowired
    private RatingService ratingService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RatingSuggestionMapper suggestionMapper;

    @Autowired
    private ReviewLogMapper reviewLogMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 推送完的文字和最后的 done 事件；出错时 done 为 null */
    private record Reply(String text, ChatEvents.Done done) {
    }

    private static Reply run(String label, Flux<ServerSentEvent<Object>> events) {
        List<ServerSentEvent<Object>> all = events.collectList().block(TIMEOUT);
        StringBuilder text = new StringBuilder();
        ChatEvents.Done done = null;
        for (ServerSentEvent<Object> e : all) {
            switch (e.data()) {
                case ChatEvents.Delta d -> text.append(d.text());
                case ChatEvents.Done d -> done = d;
                case ChatEvents.Error err -> System.out.println("[" + label + "] 出错：" + err.message());
                default -> { }
            }
        }
        System.out.println("[" + label + "] " + (done == null ? "" : done.marker()) + "\n" + text + "\n");
        return new Reply(text.toString(), done);
    }

    @Test
    void 开场然后跳过讲解完整作答后收尾再接着问() {
        // 空库上 V7 删掉了默认用户和它的设置，评分换算学习日要用设置
        jdbc.update("INSERT IGNORE INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour)"
                + " VALUES (1, 3, 'Asia/Shanghai', 4)");
        suggestionMapper.delete(null);
        reviewLogMapper.delete(null);
        cardStateMapper.delete(null);
        messageMapper.delete(null);
        sessionMapper.delete(null);
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(USER_ID);
        kp.setType(KnowledgePointType.CONCEPT);
        kp.setTitle("什么是 Java 中的 volatile 关键字？");
        kp.setSummary("保证可见性：写后立即刷回主存，读时从主存读取；禁止指令重排序；不保证原子性，i++ 仍然不安全");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(Instant.now());
        kp.setUpdatedAt(Instant.now());
        knowledgePointMapper.insert(kp);
        reviewService.initCards(USER_ID, List.of(kp.getId()));
        long sessionId = sessionService.start(USER_ID, new StartSessionRequest(kp.getId())).id();

        Reply opening = run("开场", chatService.reply(USER_ID, sessionId));
        assertThat(opening.done()).isNotNull();
        assertThat(opening.done().marker()).isNull();

        // 跳过讲解：模型应当直接要求完整作答，并输出【完整作答】
        Reply restate = run("跳过讲解", chatService.skipExplanation(USER_ID, sessionId));
        assertThat(restate.done()).isNotNull();
        assertThat(restate.done().marker()).isEqualTo(Marker.RESTATE);
        assertThat(restate.text()).doesNotContain("【");

        // 完整作答之后，模型应当收尾并输出【学习结束】
        Reply end = run("收尾", chatService.send(USER_ID, sessionId, new SendMessageRequest(
                "volatile 保证可见性：一个线程写了 volatile 变量，会立刻刷回主存，其他线程读的时候从主存读，所以能看到最新值。"
                        + "它还会禁止指令重排序，比如双重检查锁的单例里 instance 要加 volatile。"
                        + "但它不保证原子性，i++ 是读、加、写三步，多线程下仍然会出错，要用 synchronized 或 AtomicInteger。")));
        assertThat(end.done()).isNotNull();
        assertThat(end.done().marker()).isEqualTo(Marker.END);
        assertThat(end.done().ended()).isTrue();
        assertThat(sessionService.get(USER_ID, sessionId).skippedExplanation()).isTrue();

        // AI 评分建议，然后按建议确认评分
        RatingSuggestionView suggestion = ratingSuggestionService.suggest(USER_ID, sessionId);
        System.out.println("[评分建议] " + suggestion.rating() + "\n依据：" + suggestion.analysis()
                + "\n没掌握的：" + suggestion.weakPoints() + "\n超出范围的：" + suggestion.outOfScopeGaps() + "\n");
        assertThat(suggestion.rating()).isNotNull();
        assertThat(suggestion.analysis()).isNotBlank();
        RatingOption rated = ratingService.rate(USER_ID, sessionId, new RateRequest(suggestion.rating()));
        System.out.println("[评分] " + rated + "\n");
        assertThat(rated.days()).isPositive();

        // 接着问：老师只回答，不输出标记，会话不结束。是否反问只能人工看打印的内容
        long followUpId = sessionService.startFollowUp(USER_ID, sessionId).id();
        Reply answer = run("接着问", chatService.send(USER_ID, followUpId, new SendMessageRequest(
                "为什么加了 volatile 的 i++ 还是线程不安全？能举个两个线程交错执行的例子吗？")));
        assertThat(answer.done()).isNotNull();
        assertThat(answer.done().marker()).isNull();
        assertThat(answer.done().ended()).isFalse();
        assertThat(answer.text()).isNotBlank().doesNotContain("【");
    }
}
