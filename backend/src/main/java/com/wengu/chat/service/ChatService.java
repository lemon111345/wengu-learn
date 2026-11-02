package com.wengu.chat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.chat.dto.ChatEvents;
import com.wengu.chat.dto.SendMessageRequest;
import com.wengu.chat.entity.Marker;
import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.llm.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 学习对话：学生回答、老师流式回复（决策 025）。
 * <p>
 * 校验在返回 Flux 之前同步完成，不符合时直接抛出 BusinessException，由全局异常处理器返回统一格式的 JSON；
 * 通过校验后才开始推送 SSE。
 * <p>
 * 模型生成和 HTTP 连接分开：保存回复的订阅先于推给前端的订阅建立，浏览器断开只取消后者，
 * 模型照常生成完、存进库，刷新后能看到完整的回复。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final int MAX_CONTENT_LENGTH = 5000;

    /** 出错时给用户看的提示；具体原因只写日志（ui.md 第 7 节，友好提示由 #27 细化） */
    static final String ERROR_MESSAGE = "老师暂时没有回应，可以点重试";

    private final StudySessionService sessions;
    private final StudySessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final KnowledgePointService knowledgePointService;
    private final LlmService llmService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final String systemPrompt = LearningChatPrompt.system();

    public ChatService(StudySessionService sessions, StudySessionMapper sessionMapper, MessageMapper messageMapper,
                       KnowledgePointService knowledgePointService, LlmService llmService,
                       TransactionTemplate transactionTemplate, Clock clock) {
        this.sessions = sessions;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.knowledgePointService = knowledgePointService;
        this.llmService = llmService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 生成老师的下一条回复：会话刚开始时的第一句，或者上一条回复出错后重试。
     *
     * @throws BusinessException 已经轮到学生回答时为 CONFLICT
     */
    public Flux<ServerSentEvent<Object>> reply(long userId, long sessionId) {
        StudySession session = openSession(userId, sessionId);
        List<Message> messages = sessions.messages(sessionId);
        if (!messages.isEmpty() && messages.getLast().getRole() == MessageRole.ASSISTANT) {
            throw new BusinessException(ErrorCode.CONFLICT, "老师已经回复了，等你回答");
        }
        begin(sessionId);
        try {
            return stream(session, messages);
        } catch (RuntimeException e) {
            sessions.finishReply(sessionId);
            throw e;
        }
    }

    /** 学生回答，老师流式回复。第 7、8 次回答附上轮数提示（决策 008 第 3 条）。 */
    public Flux<ServerSentEvent<Object>> send(long userId, long sessionId, SendMessageRequest request) {
        String content = request == null || request.content() == null ? "" : request.content().strip();
        if (content.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回答不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回答太长了，最多 " + MAX_CONTENT_LENGTH + " 字");
        }
        StudySession session = openSession(userId, sessionId);
        List<Message> messages = sessions.messages(sessionId);
        requireStudentTurn(messages);
        int answers = (int) messages.stream().filter(m -> m.getRole() == MessageRole.USER).count() + 1;
        return answer(session, messages, content, LearningChatPrompt.turnNote(answers));
    }

    /**
     * “这个我会，直接完整作答”：跳过讲解，直接进入完整作答，照常评分（决策 018）。
     *
     * @throws BusinessException 已经在完整作答时为 CONFLICT
     */
    public Flux<ServerSentEvent<Object>> skipExplanation(long userId, long sessionId) {
        StudySession session = openSession(userId, sessionId);
        List<Message> messages = sessions.messages(sessionId);
        requireStudentTurn(messages);
        if (messages.getLast().getMarker() == Marker.RESTATE) {
            throw new BusinessException(ErrorCode.CONFLICT, "已经在完整作答了");
        }
        sessionMapper.update(new LambdaUpdateWrapper<StudySession>()
                .eq(StudySession::getId, sessionId)
                .set(StudySession::getSkippedExplanation, true));
        return answer(session, messages, LearningChatPrompt.SKIP_MESSAGE, LearningChatPrompt.SKIP_NOTE);
    }

    /** @throws BusinessException 会话不存在时为 NOT_FOUND，已经结束时为 CONFLICT */
    private StudySession openSession(long userId, long sessionId) {
        StudySession session = sessions.find(userId, sessionId);
        if (session.getEndedAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "这次学习已经结束");
        }
        return session;
    }

    /** 轮到学生回答：老师已经开口，并且最后一条是老师的回复。 */
    private static void requireStudentTurn(List<Message> messages) {
        if (messages.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "老师还没有开始");
        }
        if (messages.getLast().getRole() != MessageRole.ASSISTANT) {
            throw new BusinessException(ErrorCode.CONFLICT, "老师还没有回复上一条，请先重试");
        }
    }

    /** 同一个会话同时只生成一条回复：两个请求同时到达时，后到的直接拒绝。 */
    private void begin(long sessionId) {
        if (!sessions.tryStartReply(sessionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "老师还在回复，请稍等");
        }
    }

    /** 先存学生的消息、记一次活动，再生成回复。 */
    private Flux<ServerSentEvent<Object>> answer(StudySession session, List<Message> messages, String content,
                                                 String modelNote) {
        begin(session.getId());
        try {
            Instant now = Instant.now(clock);
            Message message = new Message();
            message.setUserId(session.getUserId());
            message.setSessionId(session.getId());
            message.setRole(MessageRole.USER);
            message.setContent(content);
            message.setModelNote(modelNote);
            message.setCreatedAt(now);
            messageMapper.insert(message);
            sessions.recordActivity(session.getId(), now);

            List<Message> history = new ArrayList<>(messages);
            history.add(message);
            return stream(session, history);
        } catch (RuntimeException e) {
            sessions.finishReply(session.getId());
            throw e;
        }
    }

    /** 调用模型生成回复：推给前端的是去掉标记的文字，生成完存库并推送 done；出错推送 error。 */
    private Flux<ServerSentEvent<Object>> stream(StudySession session, List<Message> messages) {
        long sessionId = session.getId();
        StudyMaterial material = knowledgePointService.studyMaterial(session.getUserId(), session.getKpId());
        int answers = (int) messages.stream().filter(m -> m.getRole() == MessageRole.USER).count();
        MarkerFilter filter = new MarkerFilter();

        // cache：模型只调用一次，保存和推送两个订阅共用；标记的过滤在 cache 之前，每段只处理一次
        Flux<String> visible = llmService.stream(systemPrompt, LearningChatPrompt.history(material, messages),
                        LearningChatPrompt.TEMPERATURE)
                .map(filter::push)
                .concatWith(Mono.fromCallable(filter::finish))
                .filter(text -> !text.isEmpty())
                .cache();
        // 存库是阻塞的 JDBC 调用，不能在模型客户端的网络线程上做
        Mono<Saved> saved = visible
                .then(Mono.fromCallable(() -> saveReply(session, filter, answers)).subscribeOn(Schedulers.boundedElastic()))
                .doFinally(signal -> sessions.finishReply(sessionId))
                .cache();
        // 先订阅保存，再把推送的 Flux 交给前端：浏览器断开只取消推送，不影响生成和保存
        saved.subscribe(s -> { }, e -> log.warn("会话 {} 的回复生成失败：{}", sessionId, e.toString()));

        return visible.map(ChatEvents::delta)
                .concatWith(saved.map(s -> ChatEvents.done(s.messageId(), s.marker(), s.ended())))
                .onErrorResume(e -> Flux.just(ChatEvents.error(ERROR_MESSAGE)));
    }

    private record Saved(long messageId, Marker marker, boolean ended) {
    }

    /**
     * 存下老师的回复，记一次活动；输出了【学习结束】、或者学生已回答到上限时结束会话。
     *
     * @throws IllegalStateException 模型返回了空回复（这时不存，学生可以重试）
     */
    private Saved saveReply(StudySession session, MarkerFilter filter, int answers) {
        String content = filter.content();
        Marker marker = filter.marker();
        if (content.isEmpty()) {
            throw new IllegalStateException("模型返回了空回复");
        }
        Instant now = Instant.now(clock);
        boolean ended = marker == Marker.END || answers >= LearningChatPrompt.MAX_ANSWERS;
        return transactionTemplate.execute(status -> {
            Message message = new Message();
            message.setUserId(session.getUserId());
            message.setSessionId(session.getId());
            message.setRole(MessageRole.ASSISTANT);
            message.setContent(content);
            message.setMarker(marker);
            message.setCreatedAt(now);
            messageMapper.insert(message);
            sessions.recordActivity(session.getId(), now);
            if (ended) {
                sessionMapper.update(new LambdaUpdateWrapper<StudySession>()
                        .eq(StudySession::getId, session.getId())
                        .set(StudySession::getEndedAt, now));
            }
            return new Saved(message.getId(), marker, ended);
        });
    }
}
