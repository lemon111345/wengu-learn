package com.wengu.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.chat.dto.MessageView;
import com.wengu.chat.dto.RatingOption;
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
import com.wengu.review.service.ReviewService;
import com.wengu.user.service.UserSettingsService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final ReviewService reviewService;
    private final UserSettingsService settingsService;
    private final Clock clock;

    /**
     * “正在生成”超过这么久，就当作已经失败，可以重试。和异步请求的上限（spring.mvc.async.request-timeout）一致。
     * 标记本来由 finishReply 清除，但 Reactor 遇到 LinkageError 这类致命错误时会直接抛出，doFinally 不一定执行（#95）
     */
    static final Duration REPLY_TIMEOUT = Duration.ofMinutes(10);

    // 正在生成回复的会话和开始的时刻。只有一个后端实例，放在内存中即可；重启后清空，没存下的回复由用户重试
    private final Map<Long, Instant> generating = new ConcurrentHashMap<>();

    public StudySessionService(StudySessionMapper sessionMapper, MessageMapper messageMapper,
                               KnowledgePointService knowledgePointService, ReviewService reviewService,
                               UserSettingsService settingsService, Clock clock) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.knowledgePointService = knowledgePointService;
        this.reviewService = reviewService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /**
     * 开始学习一个知识点。这个知识点最近的会话还没走完时返回那个会话（刷新、离开后再回来都走这里）：
     * 还没结束的接着学；学完了但还没评分的回去评分（#23），否则用户学完没评分就离开，再进来会新开一个会话，那次学习就白学了。
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
        StudySession latest = sessionMapper.selectOne(new LambdaQueryWrapper<StudySession>()
                .eq(StudySession::getUserId, userId)
                .eq(StudySession::getType, SessionType.STUDY)
                .eq(StudySession::getKpId, request.kpId())
                .orderByDesc(StudySession::getId)
                .last("LIMIT 1"));
        if (latest != null && (latest.getEndedAt() == null || !reviewService.isRated(latest.getId()))) {
            return view(latest, material.title());
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

    /**
     * 进入接着问：返回这个学习会话的接着问会话，没有就新建。每个学习会话最多一个接着问会话，
     * 反复进出都是同一段对话，老师记得前面问过什么（决策 025）。到达上限已经结束的也照样返回，前端据此提示。
     *
     * @param studySessionId 原来的学习会话
     * @throws BusinessException 会话不存在时为 NOT_FOUND；不是学习会话时为 BAD_REQUEST；学习还没结束时为 CONFLICT
     */
    public SessionView startFollowUp(long userId, long studySessionId) {
        StudySession study = find(userId, studySessionId);
        if (study.getType() != SessionType.STUDY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有学习会话可以接着问");
        }
        if (study.getEndedAt() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "学完这个知识点之后才能接着问");
        }
        StudySession followUp = sessionMapper.selectOne(new LambdaQueryWrapper<StudySession>()
                .eq(StudySession::getParentSessionId, studySessionId)
                .eq(StudySession::getType, SessionType.FOLLOW_UP)
                .orderByDesc(StudySession::getId)
                .last("LIMIT 1"));
        if (followUp == null) {
            Instant now = Instant.now(clock);
            followUp = new StudySession();
            followUp.setUserId(userId);
            followUp.setType(SessionType.FOLLOW_UP);
            followUp.setKpId(study.getKpId());
            followUp.setParentSessionId(studySessionId);
            followUp.setStartedAt(now);
            followUp.setSkippedExplanation(false);
            followUp.setActiveSeconds(0);
            followUp.setLastActiveAt(now);
            followUp.setPromptVersion(FollowUpPrompt.VERSION);
            sessionMapper.insert(followUp);
        }
        return get(userId, followUp.getId());
    }

    /** 会话和全部消息，刷新后恢复用。 */
    public SessionView get(long userId, long sessionId) {
        StudySession session = find(userId, sessionId);
        KnowledgePointSummary kp = session.getKpId() == null ? null
                : knowledgePointService.summaries(userId, List.of(session.getKpId())).get(session.getKpId());
        return view(session, kp == null ? null : kp.title());
    }

    /**
     * [start, end) 之间开始的会话的有效时长之和（秒），今天页的“今天已学 X 分钟”用（#19）。
     * 按会话开始的时间归到某一天：跨过分界时刻的会话整个算在开始的那天，不拆开。
     */
    public int activeSecondsBetween(long userId, Instant start, Instant end) {
        return sessionMapper.selectList(new LambdaQueryWrapper<StudySession>()
                        .select(StudySession::getActiveSeconds)
                        .eq(StudySession::getUserId, userId)
                        .ge(StudySession::getStartedAt, start)
                        .lt(StudySession::getStartedAt, end))
                .stream()
                .mapToInt(StudySession::getActiveSeconds)
                .sum();
    }

    /** @throws BusinessException 会话不存在或不属于这个用户时为 NOT_FOUND */
    StudySession find(long userId, long sessionId) {
        StudySession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    /**
     * 同一个知识点在这次之前的学习会话，最近的在前，最多 limit 个（#21 复习时取以前问过的问题）。
     * 新会话只在上一个评过分之后才开（见 start），所以这些会话都已经学完、评过分。
     */
    List<StudySession> earlierStudySessions(StudySession session, int limit) {
        return sessionMapper.selectList(new LambdaQueryWrapper<StudySession>()
                .eq(StudySession::getUserId, session.getUserId())
                .eq(StudySession::getType, SessionType.STUDY)
                .eq(StudySession::getKpId, session.getKpId())
                .lt(StudySession::getId, session.getId())
                .orderByDesc(StudySession::getId)
                .last("LIMIT " + limit));
    }

    /** 会话的全部消息，按时间排列。 */
    List<Message> messages(long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .orderByAsc(Message::getCreatedAt)
                .orderByAsc(Message::getId));
    }

    /** 标记这个会话开始生成回复；已经在生成时返回 false。超过 {@link #REPLY_TIMEOUT} 的标记不算。 */
    boolean tryStartReply(long sessionId) {
        Instant now = Instant.now(clock);
        Instant started = generating.putIfAbsent(sessionId, now);
        return started == null || (isExpired(started, now) && generating.replace(sessionId, started, now));
    }

    private boolean isGenerating(long sessionId) {
        Instant started = generating.get(sessionId);
        return started != null && !isExpired(started, Instant.now(clock));
    }

    private static boolean isExpired(Instant started, Instant now) {
        return !now.isBefore(started.plus(REPLY_TIMEOUT));
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
        return new SessionView(session.getId(), session.getType(), session.getKpId(), session.getParentSessionId(), kpTitle,
                session.getEndedAt() != null, Boolean.TRUE.equals(session.getSkippedExplanation()),
                isGenerating(session.getId()), session.getActiveSeconds(), rating(session),
                reviewService.isRatingChangeable(session.getId()), messages);
    }

    /** 学习会话已经评过分时，评分和下次复习的学习日；其他情况为 null */
    private RatingOption rating(StudySession session) {
        if (session.getType() != SessionType.STUDY) {
            return null;
        }
        return reviewService.reviewOf(session.getId())
                .map(r -> RatingService.option(r.rating(), r.due(), Instant.now(clock),
                        settingsService.get(session.getUserId())))
                .orElse(null);
    }
}
