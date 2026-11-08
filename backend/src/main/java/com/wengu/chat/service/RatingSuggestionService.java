package com.wengu.chat.service;

import com.wengu.chat.dto.RatingSuggestionResult;
import com.wengu.chat.dto.RatingSuggestionView;
import com.wengu.chat.entity.RatingSuggestion;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.RatingSuggestionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.knowledge.service.KnowledgePointService;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmException;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.llm.service.LlmService;
import com.wengu.review.fsrs.Rating;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * AI 评分建议（决策 008、026）：学完之后请模型根据整段对话给出四档评分中的一档、判断依据、没掌握的内容和超出范围的缺口。
 * <p>
 * 同步调用，每个会话只算一次：第一次调用时生成并存库，之后直接返回存好的结果。评分不依赖它，用户可以先评分。
 */
@Service
public class RatingSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(RatingSuggestionService.class);

    /** 结果解析失败或不完整时最多调用几次。模型调用本身出错（超时等）不重试，由用户决定 */
    static final int MAX_ATTEMPTS = 2;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final StudySessionService sessions;
    private final RatingSuggestionMapper suggestionMapper;
    private final KnowledgePointService knowledgePointService;
    private final LlmService llmService;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String systemPrompt = RatingPrompt.system();

    public RatingSuggestionService(StudySessionService sessions, RatingSuggestionMapper suggestionMapper,
                                   KnowledgePointService knowledgePointService, LlmService llmService,
                                   JsonMapper jsonMapper, Clock clock) {
        this.sessions = sessions;
        this.suggestionMapper = suggestionMapper;
        this.knowledgePointService = knowledgePointService;
        this.llmService = llmService;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * 这次学习的 AI 评分建议；还没有时调用模型生成并存库。
     *
     * @throws BusinessException 会话不存在时为 NOT_FOUND；不是学习会话时为 BAD_REQUEST；还没学完时为 CONFLICT；
     *                           模型出错或结果不可用时为 RATING_SUGGESTION_FAILED
     */
    public RatingSuggestionView suggest(long userId, long sessionId) {
        StudySession session = sessions.find(userId, sessionId);
        if (session.getType() != SessionType.STUDY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有学习会话需要评分");
        }
        if (session.getEndedAt() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "学完之后才能评分");
        }
        RatingSuggestion saved = suggestionMapper.selectById(sessionId);
        if (saved != null) {
            return view(saved);
        }

        StudyMaterial material = knowledgePointService.studyMaterial(userId, session.getKpId());
        LlmResult<RatingSuggestionResult> result = callModel(sessionId,
                RatingPrompt.input(material, sessions.messages(sessionId)));
        RatingSuggestionResult content = result.content();

        RatingSuggestion suggestion = new RatingSuggestion();
        suggestion.setSessionId(sessionId);
        suggestion.setUserId(userId);
        suggestion.setRating(content.rating());
        suggestion.setAnalysis(Objects.requireNonNullElse(content.analysis(), ""));
        suggestion.setWeakPoints(jsonMapper.writeValueAsString(orEmpty(content.weakPoints())));
        suggestion.setOutOfScopeGaps(jsonMapper.writeValueAsString(orEmpty(content.outOfScopeGaps())));
        suggestion.setModel(result.model());
        suggestion.setPromptVersion(RatingPrompt.VERSION);
        suggestion.setPromptTokens(result.promptTokens());
        suggestion.setCompletionTokens(result.completionTokens());
        suggestion.setCreatedAt(Instant.now(clock));
        try {
            suggestionMapper.insert(suggestion);
        } catch (DuplicateKeyException e) {
            // 两个请求同时生成（如刷新了评分页），先存进去的为准
            return view(suggestionMapper.selectById(sessionId));
        }
        return view(suggestion);
    }

    /**
     * 已经生成的评分建议，没有时为 null。只读，不调用模型：回顾当时的学习用（#82）。
     *
     * @throws BusinessException 会话不存在或不属于该用户时为 NOT_FOUND
     */
    public RatingSuggestionView saved(long userId, long sessionId) {
        sessions.find(userId, sessionId);
        RatingSuggestion saved = suggestionMapper.selectById(sessionId);
        return saved == null ? null : view(saved);
    }

    /** 这次学习的评分建议中“没掌握的”，还没有生成建议时为空。复习时针对它们提问（#21） */
    List<String> weakPoints(long sessionId) {
        RatingSuggestion saved = suggestionMapper.selectById(sessionId);
        return saved == null ? List.of() : jsonMapper.readValue(saved.getWeakPoints(), STRING_LIST);
    }

    /** 这次学习的 AI 建议评分，还没有生成时为 null。评分时记进流水的 rating_ai */
    public Rating suggestedRating(long sessionId) {
        RatingSuggestion saved = suggestionMapper.selectById(sessionId);
        return saved == null ? null : saved.getRating();
    }

    private LlmResult<RatingSuggestionResult> callModel(long sessionId, String input) {
        for (int attempt = 1; ; attempt++) {
            try {
                LlmResult<RatingSuggestionResult> result = llmService.call(systemPrompt, input,
                        RatingSuggestionResult.class, RatingPrompt.TEMPERATURE);
                if (result.content() != null && result.content().rating() != null) {
                    return result;
                }
                log.warn("会话 {} 的评分建议第 {} 次没有给出评分", sessionId, attempt);
            } catch (LlmOutputParseException e) {
                log.warn("会话 {} 的评分建议第 {} 次解析失败：{}", sessionId, attempt, e.getMessage());
            } catch (LlmException e) {
                // 返回码仍是 RATING_SUGGESTION_FAILED：前端据此知道可以直接评分；提示中带上模型出错的原因（决策 032）
                log.warn("会话 {} 的评分建议调用模型失败：{}", sessionId, e.getErrorCode());
                throw new BusinessException(ErrorCode.RATING_SUGGESTION_FAILED,
                        "AI 评分建议没有生成出来：" + e.getMessage() + "。也可以直接评分", e);
            } catch (RuntimeException e) {
                log.warn("会话 {} 的评分建议调用模型失败：{}", sessionId, e.toString());
                throw new BusinessException(ErrorCode.RATING_SUGGESTION_FAILED);
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw new BusinessException(ErrorCode.RATING_SUGGESTION_FAILED);
            }
        }
    }

    private RatingSuggestionView view(RatingSuggestion s) {
        return new RatingSuggestionView(s.getRating(), s.getAnalysis(),
                jsonMapper.readValue(s.getWeakPoints(), STRING_LIST),
                jsonMapper.readValue(s.getOutOfScopeGaps(), STRING_LIST));
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
