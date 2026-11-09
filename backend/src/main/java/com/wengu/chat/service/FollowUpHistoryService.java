package com.wengu.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.wengu.common.time.StudyDay;
import com.wengu.knowledge.dto.KnowledgePointSummary;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.review.service.ReviewService;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.service.UserSettingsService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 问答记录（决策 043）：列出用户的接着问，判断一场对话还能不能继续问。只读，不建会话、不调用模型。
 * <p>
 * 搜索读出消息后按文字过滤：标题在 knowledge 模块，SQL 不能跨模块查；接着问的数据量很小，到几千场以后再改。
 */
@Service
public class FollowUpHistoryService {

    static final String STUDY_NOT_ENDED = "学完这个知识点之后才能接着问";
    static final String KNOWLEDGE_POINT_GONE = "这个知识点已合并或删除，只能查看，不能继续问";
    static final String LIMIT_REACHED = "这场对话的提问次数已经到上限，只能查看";

    private final StudySessionService sessions;
    private final StudySessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final KnowledgePointService knowledgePointService;
    private final ReviewService reviewService;
    private final UserSettingsService settingsService;
    private final Clock clock;

    public FollowUpHistoryService(StudySessionService sessions, StudySessionMapper sessionMapper,
                                  MessageMapper messageMapper, KnowledgePointService knowledgePointService,
                                  ReviewService reviewService, UserSettingsService settingsService, Clock clock) {
        this.sessions = sessions;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.knowledgePointService = knowledgePointService;
        this.reviewService = reviewService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /**
     * 有提问的接着问，最近活动的在前。
     *
     * @param keyword 不区分大小写地匹配知识点标题、问题和回答；为空时不过滤
     */
    public List<FollowUpSummary> list(long userId, String keyword) {
        List<StudySession> followUps = sessionMapper.selectList(new LambdaQueryWrapper<StudySession>()
                .eq(StudySession::getUserId, userId)
                .eq(StudySession::getType, SessionType.FOLLOW_UP)
                .orderByDesc(StudySession::getLastActiveAt)
                .orderByDesc(StudySession::getId));
        Map<Long, List<Message>> messages = messagesBySession(followUps.stream().map(StudySession::getId).toList());
        // 进入过接着问但没有提问的，不是一场对话
        List<StudySession> asked = followUps.stream()
                .filter(f -> !questions(messages.getOrDefault(f.getId(), List.of())).isEmpty())
                .toList();
        if (asked.isEmpty()) {
            return List.of();
        }

        SettingsView settings = settingsService.get(userId);
        Set<Long> kpIds = asked.stream().map(StudySession::getKpId).collect(Collectors.toSet());
        Map<Long, KnowledgePointSummary> existing = knowledgePointService.summaries(userId, kpIds);
        Set<Long> due = dueToday(userId, kpIds, settings);
        String normalized = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);

        return asked.stream()
                .map(f -> new Row(f, title(userId, f.getKpId(), existing), messages.get(f.getId())))
                .filter(row -> matches(row, normalized))
                .map(row -> summary(row, existing, due, settings))
                .toList();
    }

    /**
     * 一次学习的接着问状态。
     *
     * @throws BusinessException 不存在或不属于这个用户时为 NOT_FOUND；不是学习会话时为 BAD_REQUEST
     */
    public FollowUpStatus status(long userId, long studySessionId) {
        StudySession study = sessions.find(userId, studySessionId);
        if (study.getType() != SessionType.STUDY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这不是一次学习记录");
        }
        StudySession followUp = sessions.findFollowUp(studySessionId);
        Map<Long, KnowledgePointSummary> existing = knowledgePointService.summaries(userId, List.of(study.getKpId()));
        SettingsView settings = settingsService.get(userId);
        String reason = readOnlyReason(study, followUp, existing.containsKey(study.getKpId()));
        return new FollowUpStatus(studySessionId, followUp == null ? null : followUp.getId(),
                title(userId, study.getKpId(), existing), reason == null, reason,
                dueToday(userId, List.of(study.getKpId()), settings).contains(study.getKpId()),
                settings.teacherName());
    }

    private static String readOnlyReason(StudySession study, StudySession followUp, boolean kpExists) {
        if (study.getEndedAt() == null) {
            return STUDY_NOT_ENDED;
        }
        if (!kpExists) {
            return KNOWLEDGE_POINT_GONE;
        }
        return followUp != null && followUp.getEndedAt() != null ? LIMIT_REACHED : null;
    }

    private record Row(StudySession followUp, String title, List<Message> messages) {
    }

    private static boolean matches(Row row, String keyword) {
        return keyword.isEmpty() || contains(row.title(), keyword)
                || row.messages().stream().anyMatch(m -> contains(m.getContent(), keyword));
    }

    private static boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private FollowUpSummary summary(Row row, Map<Long, KnowledgePointSummary> existing, Set<Long> due,
                                    SettingsView settings) {
        StudySession followUp = row.followUp();
        List<Message> questions = questions(row.messages());
        Instant lastActive = followUp.getLastActiveAt() != null ? followUp.getLastActiveAt() : followUp.getStartedAt();
        return new FollowUpSummary(followUp.getParentSessionId(), followUp.getId(), row.title(),
                questions.getLast().getContent(), questions.size(),
                StudyDay.of(lastActive, settings.zoneId(), settings.dayStartHour()).date(),
                existing.containsKey(followUp.getKpId()) && followUp.getEndedAt() == null,
                due.contains(followUp.getKpId()));
    }

    private Map<Long, List<Message>> messagesBySession(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .in(Message::getSessionId, sessionIds)
                        .orderByAsc(Message::getCreatedAt)
                        .orderByAsc(Message::getId))
                .stream()
                .collect(Collectors.groupingBy(Message::getSessionId));
    }

    private static List<Message> questions(List<Message> messages) {
        return messages.stream().filter(m -> m.getRole() == MessageRole.USER).toList();
    }

    /** 知识点已合并或删除时，学习记录还在，标题照常显示（决策 029） */
    private String title(long userId, long kpId, Map<Long, KnowledgePointSummary> existing) {
        KnowledgePointSummary kp = existing.get(kpId);
        return kp != null ? kp.title() : knowledgePointService.titleIncludingDeleted(userId, kpId);
    }

    /** 这些知识点中今天要复习、还没复习的，口径和每日计划第 2 步相同：暂停的由 knowledge 过滤（决策 002） */
    private Set<Long> dueToday(long userId, Collection<Long> kpIds, SettingsView settings) {
        StudyDay today = StudyDay.of(Instant.now(clock), settings.zoneId(), settings.dayStartHour());
        Set<Long> candidates = new HashSet<>(reviewService.dueKpIds(userId, today.end()));
        candidates.retainAll(kpIds);
        return knowledgePointService.activeInOrder(userId, candidates).stream()
                .map(KnowledgePointSummary::id)
                .collect(Collectors.toSet());
    }
}
