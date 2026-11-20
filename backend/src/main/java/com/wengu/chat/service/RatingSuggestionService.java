package com.wengu.chat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.chat.dto.GapsRequest;
import com.wengu.chat.dto.RatingSuggestionResult;
import com.wengu.chat.dto.RatingSuggestionView;
import com.wengu.chat.entity.RatingSuggestion;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.RatingSuggestionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.knowledge.service.ExtractionDraftService;
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

    private static final TypeReference<List<Integer>> INT_LIST = new TypeReference<>() {
    };

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final StudySessionService sessions;
    private final RatingSuggestionMapper suggestionMapper;
    private final KnowledgePointService knowledgePointService;
    private final ExtractionDraftService draftService;
    private final LlmService llmService;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String systemPrompt = RatingPrompt.system();
    private final String codeSystemPrompt = CodeChatPrompt.ratingSystem();

    public RatingSuggestionService(StudySessionService sessions, RatingSuggestionMapper suggestionMapper,
                                   KnowledgePointService knowledgePointService, ExtractionDraftService draftService,
                                   LlmService llmService, JsonMapper jsonMapper, Clock clock) {
        this.sessions = sessions;
        this.suggestionMapper = suggestionMapper;
        this.knowledgePointService = knowledgePointService;
        this.draftService = draftService;
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
        // 源码知识点（#90）用源码的评分 prompt：核心要点和细节分开、带上源码，复习时 EASY 的标准不同（决策 048）。
        // 这次之前有过学习会话就是复习：同一个知识点上一次学完评了分，start 才会新建会话
        boolean code = material.code() != null;
        String input = code
                ? CodeChatPrompt.ratingInput(material, !sessions.earlierStudySessions(session, 1).isEmpty(),
                sessions.messages(sessionId))
                : RatingPrompt.input(material, sessions.messages(sessionId));
        LlmResult<RatingSuggestionResult> result = callModel(sessionId, code ? codeSystemPrompt : systemPrompt, input);
        RatingSuggestionResult content = result.content();

        RatingSuggestion suggestion = new RatingSuggestion();
        suggestion.setSessionId(sessionId);
        suggestion.setUserId(userId);
        suggestion.setRating(content.rating());
        suggestion.setAnalysis(Objects.requireNonNullElse(content.analysis(), ""));
        suggestion.setWeakPoints(jsonMapper.writeValueAsString(orEmpty(content.weakPoints())));
        suggestion.setOutOfScopeGaps(jsonMapper.writeValueAsString(orEmpty(content.outOfScopeGaps())));
        suggestion.setModel(result.model());
        suggestion.setPromptVersion(code ? CodeChatPrompt.RATING_VERSION : RatingPrompt.VERSION);
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

    /**
     * 把勾选的学习缺口加入知识库（#31，决策 053）：记下勾了哪几条，交给 knowledge 建缺口草稿，后台抽取完自动确认。
     * 一次评分建议的缺口只处理一次。不要求先评分。
     * <p>
     * 先用条件更新“占住”这组缺口，再建草稿：先查后写挡不住两个同时到达的请求，条件更新由数据库保证只有一个成功。
     * 建草稿失败时撤销占位，用户可以再点一次。两步没有放进同一个事务：建草稿要在它自己的事务提交后才交给后台线程，
     * 套在外层事务里，后台线程读不到批次。
     *
     * @throws BusinessException 会话不存在或不属于该用户时为 NOT_FOUND；不是学习会话、下标为空、越界或重复时为 BAD_REQUEST；
     *                           还没有评分建议、已经处理过时为 CONFLICT
     */
    public RatingSuggestionView addGaps(long userId, long sessionId, GapsRequest request) {
        StudySession session = sessions.find(userId, sessionId);
        if (session.getType() != SessionType.STUDY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有学习会话有学习缺口");
        }
        RatingSuggestion saved = suggestionMapper.selectById(sessionId);
        if (saved == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 评分建议还没有生成");
        }
        List<String> gaps = jsonMapper.readValue(saved.getOutOfScopeGaps(), STRING_LIST);
        List<Integer> picks = validPicks(request, gaps.size());

        int claimed = suggestionMapper.update(new LambdaUpdateWrapper<RatingSuggestion>()
                .eq(RatingSuggestion::getSessionId, sessionId)
                .isNull(RatingSuggestion::getGapPicks)
                .set(RatingSuggestion::getGapPicks, jsonMapper.writeValueAsString(picks)));
        if (claimed == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "这次的学习缺口已经处理过了");
        }
        long draftId;
        try {
            draftId = draftService.createGapDraft(userId,
                    knowledgePointService.titleIncludingDeleted(userId, session.getKpId()),
                    picks.stream().map(gaps::get).toList());
        } catch (RuntimeException e) {
            suggestionMapper.update(new LambdaUpdateWrapper<RatingSuggestion>()
                    .eq(RatingSuggestion::getSessionId, sessionId)
                    .set(RatingSuggestion::getGapPicks, null));
            throw e;
        }
        suggestionMapper.update(new LambdaUpdateWrapper<RatingSuggestion>()
                .eq(RatingSuggestion::getSessionId, sessionId)
                .set(RatingSuggestion::getGapDraftId, draftId));
        return view(suggestionMapper.selectById(sessionId));
    }

    /** 下标至少一个、都在范围内、不重复，返回从小到大排好的 */
    private static List<Integer> validPicks(GapsRequest request, int gapCount) {
        if (request == null || request.indices() == null || request.indices().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请至少勾选一条");
        }
        if (request.indices().stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "勾选的缺口不存在");
        }
        List<Integer> sorted = request.indices().stream().sorted().toList();
        for (int i = 0; i < sorted.size(); i++) {
            Integer index = sorted.get(i);
            if (index < 0 || index >= gapCount) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "勾选的缺口不存在：" + index);
            }
            if (i > 0 && index.equals(sorted.get(i - 1))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "勾选的缺口重复了：" + index);
            }
        }
        return sorted;
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

    private LlmResult<RatingSuggestionResult> callModel(long sessionId, String system, String input) {
        for (int attempt = 1; ; attempt++) {
            try {
                LlmResult<RatingSuggestionResult> result = llmService.call(system, input,
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
                jsonMapper.readValue(s.getOutOfScopeGaps(), STRING_LIST),
                s.getGapPicks() == null ? null : jsonMapper.readValue(s.getGapPicks(), INT_LIST),
                s.getGapDraftId());
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
