package com.wengu.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.chat.dto.AnswerSummary;
import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.time.StudyDay;
import com.wengu.knowledge.dto.KnowledgePointSummary;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.review.dto.ReviewRecord;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.service.ReviewService;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.service.UserSettingsService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 答法速查（#112）：每个学过的知识点一条，带最近一次学习的面试参考答法。只读，不调用模型。
 * <p>
 * 参考答法读取时从收尾中切出（决策 047）。全部一次返回，搜索在前端做，和知识库一样；题量上千时再考虑分页。
 */
@Service
public class AnswerService {

    private final StudySessionService sessions;
    private final StudySessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final KnowledgePointService knowledgePointService;
    private final ReviewService reviewService;
    private final UserSettingsService settingsService;

    public AnswerService(StudySessionService sessions, StudySessionMapper sessionMapper, MessageMapper messageMapper,
                         KnowledgePointService knowledgePointService, ReviewService reviewService,
                         UserSettingsService settingsService) {
        this.sessions = sessions;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.knowledgePointService = knowledgePointService;
        this.reviewService = reviewService;
        this.settingsService = settingsService;
    }

    /**
     * 现在还在的知识点中学过的，每个一条，最近学的在前。
     * 已合并或删除的知识点不列：速查是为了复习现在的知识点，合并进来的学习记录到知识库详情里看。
     */
    public List<AnswerSummary> list(long userId) {
        List<StudySession> ended = sessionMapper.selectList(new LambdaQueryWrapper<StudySession>()
                .eq(StudySession::getUserId, userId)
                .eq(StudySession::getType, SessionType.STUDY)
                .isNotNull(StudySession::getEndedAt)
                .orderByDesc(StudySession::getEndedAt)
                .orderByDesc(StudySession::getId));
        // 每个知识点只留最近的一次，保持“最近学的在前”的顺序
        Map<Long, StudySession> latest = new LinkedHashMap<>();
        ended.forEach(s -> latest.putIfAbsent(s.getKpId(), s));
        Map<Long, KnowledgePointSummary> existing = knowledgePointService.summaries(userId, latest.keySet());
        latest.keySet().retainAll(existing.keySet());
        if (latest.isEmpty()) {
            return List.of();
        }

        List<Long> sessionIds = latest.values().stream().map(StudySession::getId).toList();
        Map<Long, String> answers = referenceAnswers(sessionIds);
        Map<Long, Rating> ratings = new HashMap<>();
        for (ReviewRecord record : reviewService.reviewsOf(userId, latest.keySet())) {
            if (record.sessionId() != null) {
                ratings.putIfAbsent(record.sessionId(), record.rating());
            }
        }
        SettingsView settings = settingsService.get(userId);
        Set<Long> due = sessions.dueToday(userId, latest.keySet(), settings);

        return latest.values().stream()
                .map(s -> new AnswerSummary(s.getKpId(), existing.get(s.getKpId()).title(), s.getId(),
                        answers.get(s.getId()), ratings.get(s.getId()),
                        StudyDay.of(s.getEndedAt(), settings.zoneId(), settings.dayStartHour()).date(),
                        due.contains(s.getKpId())))
                .toList();
    }

    /** 每个会话最后一条老师回复就是收尾，从中切出参考答法；找不到的不在结果中 */
    private Map<Long, String> referenceAnswers(List<Long> sessionIds) {
        Map<Long, Message> closings = new HashMap<>();
        messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .in(Message::getSessionId, sessionIds)
                        .eq(Message::getRole, MessageRole.ASSISTANT)
                        .orderByAsc(Message::getCreatedAt)
                        .orderByAsc(Message::getId))
                .forEach(m -> closings.put(m.getSessionId(), m));
        Map<Long, String> answers = new HashMap<>();
        closings.forEach((sessionId, closing) -> {
            String answer = ReferenceAnswers.extract(closing.getContent());
            if (answer != null) {
                answers.put(sessionId, answer);
            }
        });
        return answers;
    }
}
