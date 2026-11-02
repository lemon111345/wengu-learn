package com.wengu.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.chat.dto.MessageView;
import com.wengu.chat.dto.SessionView;
import com.wengu.chat.dto.StartSessionRequest;
import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointSummary;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.knowledge.service.KnowledgePointService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学习会话：开始或恢复、读取、自动计时（决策 025）。对话本身见 {@link ChatService}。
 * <p>
 * 所有方法由调用方传入 userId（决策 022）；会话不属于这个用户时一律当作不存在。
 */
@Service
public class StudySessionService {

    /** 两次活动之间的间隔最多算这么久：中途离开不计时，写一段很长的完整作答也不会被整段扣掉 */
    static final Duration IDLE_CAP = Duration.ofMinutes(10);

    private final StudySessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final KnowledgePointService knowledgePointService;
    private final Clock clock;

    // 正在生成回复的会话。只有一个后端实例，放在内存中即可；重启后清空，没存下的回复由用户重试
    private final Set<Long> generating = ConcurrentHashMap.newKeySet();

    public StudySessionService(StudySessionMapper sessionMapper, MessageMapper messageMapper,
                               KnowledgePointService knowledgePointService, Clock clock) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.knowledgePointService = knowledgePointService;
        this.clock = clock;
    }

    /**
     * 开始学习一个知识点。这个知识点有还没结束的会话时返回那个会话，接着学（刷新、离开后再回来都走这里）。
     * 只创建会话，不调用模型：老师的第一句由前端接着调用 reply 生成。
     *
     * @throws BusinessException 知识点不存在时为 NOT_FOUND；只收录、不参与复习的类型为 BAD_REQUEST
     */
    public SessionView start(long userId, StartSessionRequest request) {
        if (request == null || request.kpId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少知识点 id");
        }
        StudyMaterial material = knowledgePointService.studyMaterial(userId, request.kpId());
        if (!material.type().isScheduled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这类知识点暂时只收录，不能学习");
        }
        StudySession open = sessionMapper.selectOne(new LambdaQueryWrapper<StudySession>()
                .eq(StudySession::getUserId, userId)
                .eq(StudySession::getType, SessionType.STUDY)
                .eq(StudySession::getKpId, request.kpId())
                .isNull(StudySession::getEndedAt)
                .orderByDesc(StudySession::getId)
                .last("LIMIT 1"));
        if (open != null) {
            return view(open, material.title());
        }

        Instant now = Instant.now(clock);
        StudySession session = new StudySession();
        session.setUserId(userId);
        session.setType(SessionType.STUDY);
        session.setKpId(request.kpId());
        session.setStartedAt(now);
        session.setSkippedExplanation(false);
        session.setActiveSeconds(0);
        session.setLastActiveAt(now);
        session.setPromptVersion(LearningChatPrompt.VERSION);
        sessionMapper.insert(session);
        return view(session, material.title());
    }

    /** 会话和全部消息，刷新后恢复用。 */
    public SessionView get(long userId, long sessionId) {
        StudySession session = find(userId, sessionId);
        KnowledgePointSummary kp = session.getKpId() == null ? null
                : knowledgePointService.summaries(userId, List.of(session.getKpId())).get(session.getKpId());
        return view(session, kp == null ? null : kp.title());
    }

    /** @throws BusinessException 会话不存在或不属于这个用户时为 NOT_FOUND */
    StudySession find(long userId, long sessionId) {
        StudySession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    /** 会话的全部消息，按时间排列。 */
    List<Message> messages(long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .orderByAsc(Message::getCreatedAt)
                .orderByAsc(Message::getId));
    }

    /** 标记这个会话开始生成回复；已经在生成时返回 false。 */
    boolean tryStartReply(long sessionId) {
        return generating.add(sessionId);
    }

    void finishReply(long sessionId) {
        generating.remove(sessionId);
    }

    /**
     * 记一次活动（学生发消息、老师回复完）：把距上次活动的时间加进有效时长，最多加 {@link #IDLE_CAP}。
     * 读、算、写不在一个事务里：同一个会话同时只有一个回复在生成，不会并发修改。
     */
    void recordActivity(long sessionId, Instant now) {
        StudySession session = sessionMapper.selectById(sessionId);
        sessionMapper.update(new LambdaUpdateWrapper<StudySession>()
                .eq(StudySession::getId, sessionId)
                .set(StudySession::getActiveSeconds,
                        session.getActiveSeconds() + activeIncrement(session.getLastActiveAt(), now))
                .set(StudySession::getLastActiveAt, now));
    }

    /** 两次活动之间计入有效时长的秒数：间隔超过 {@link #IDLE_CAP} 的只算 {@link #IDLE_CAP}。 */
    static int activeIncrement(Instant last, Instant now) {
        if (last == null || now.isBefore(last)) {
            return 0;
        }
        return (int) Math.min(Duration.between(last, now).toSeconds(), IDLE_CAP.toSeconds());
    }

    private SessionView view(StudySession session, String kpTitle) {
        List<MessageView> messages = messages(session.getId()).stream()
                .map(m -> new MessageView(m.getId(), m.getRole(), m.getContent(), m.getMarker(), m.getCreatedAt()))
                .toList();
        return new SessionView(session.getId(), session.getType(), session.getKpId(), kpTitle,
                session.getEndedAt() != null, Boolean.TRUE.equals(session.getSkippedExplanation()),
                generating.contains(session.getId()), messages);
    }
}
