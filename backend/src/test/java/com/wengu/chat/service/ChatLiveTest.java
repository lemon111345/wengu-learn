package com.wengu.chat.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.ChatEvents;
import com.wengu.chat.dto.SendMessageRequest;
import com.wengu.chat.dto.StartSessionRequest;
import com.wengu.chat.entity.Marker;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实连测试：用真实模型走一遍学习对话，检查 prompt v3 的两个标记模型是否照做。会产生少量费用，默认跳过：
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
    void 开场然后跳过讲解完整作答后收尾() {
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
    }
}
