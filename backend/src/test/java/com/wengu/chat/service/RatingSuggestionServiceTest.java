package com.wengu.chat.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.RatingSuggestionResult;
import com.wengu.chat.dto.RatingSuggestionView;
import com.wengu.chat.entity.Marker;
import com.wengu.chat.entity.Message;
import com.wengu.chat.entity.MessageRole;
import com.wengu.chat.entity.RatingSuggestion;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.MessageMapper;
import com.wengu.chat.mapper.RatingSuggestionMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmException;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.llm.service.LlmService;
import com.wengu.review.fsrs.Rating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 评分建议（决策 026）：只生成一次、输入的格式、重试、模型出错。真实 MySQL，模型换成模拟对象。 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RatingSuggestionServiceTest {

    private static final long USER_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-11-02T02:00:00Z");

    @MockitoBean
    private LlmService llmService;

    @Autowired
    private RatingSuggestionService suggestionService;

    @Autowired
    private RatingSuggestionMapper suggestionMapper;

    @Autowired
    private StudySessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    private long kpId;

    @BeforeEach
    void reset() {
        suggestionMapper.delete(null);
        messageMapper.delete(null);
        sessionMapper.delete(null);
        knowledgePointMapper.delete(null);
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(USER_ID);
        kp.setType(KnowledgePointType.CONCEPT);
        kp.setTitle("什么是 volatile？");
        kp.setSummary("可见性；禁止重排序；不保证原子性");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(NOW);
        kp.setUpdatedAt(NOW);
        knowledgePointMapper.insert(kp);
        kpId = kp.getId();
    }

    /** 一个学习会话，带两条消息；ended 为 true 时已经学完 */
    private long session(SessionType type, boolean ended) {
        StudySession s = new StudySession();
        s.setUserId(USER_ID);
        s.setType(type);
        s.setKpId(kpId);
        s.setStartedAt(NOW);
        s.setEndedAt(ended ? NOW : null);
        s.setSkippedExplanation(false);
        s.setActiveSeconds(0);
        sessionMapper.insert(s);
        message(s.getId(), MessageRole.USER, "volatile 保证可见性，不保证原子性", null);
        message(s.getId(), MessageRole.ASSISTANT, "参考答法……", Marker.END);
        return s.getId();
    }

    private void message(long sessionId, MessageRole role, String content, Marker marker) {
        Message m = new Message();
        m.setUserId(USER_ID);
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setMarker(marker);
        m.setCreatedAt(NOW);
        messageMapper.insert(m);
    }

    private static LlmResult<RatingSuggestionResult> result(RatingSuggestionResult content) {
        return new LlmResult<>(content, "{...}", "test-model", "stop", 1000, 500);
    }

    private void modelReturns(RatingSuggestionResult content) {
        when(llmService.call(anyString(), anyString(), eq(RatingSuggestionResult.class), any()))
                .thenReturn(result(content));
    }

    @Test
    void 只读查询返回存好的建议_没有时为空且不调用模型() {
        long sessionId = session(SessionType.STUDY, true);

        assertThat(suggestionService.saved(USER_ID, sessionId)).isNull();
        verify(llmService, times(0)).call(anyString(), anyString(), eq(RatingSuggestionResult.class), any());
        assertThat(suggestionMapper.selectCount(null)).isZero();

        modelReturns(new RatingSuggestionResult("依据", List.of(), Rating.GOOD, List.of("漏了重排序")));
        suggestionService.suggest(USER_ID, sessionId);

        assertThat(suggestionService.saved(USER_ID, sessionId).rating()).isEqualTo(Rating.GOOD);
        assertThat(suggestionService.saved(USER_ID, sessionId).weakPoints()).containsExactly("漏了重排序");
        verify(llmService, times(1)).call(anyString(), anyString(), eq(RatingSuggestionResult.class), any());
        assertThatThrownBy(() -> suggestionService.saved(2, sessionId)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 模型调用失败时提示中带上原因() {
        long sessionId = session(SessionType.STUDY, true);
        when(llmService.call(anyString(), anyString(), eq(RatingSuggestionResult.class), any()))
                .thenThrow(new LlmException(ErrorCode.LLM_AUTH_FAILED));

        // 返回码仍是 RATING_SUGGESTION_FAILED：前端据此知道可以直接评分（决策 032）
        assertThatThrownBy(() -> suggestionService.suggest(USER_ID, sessionId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATING_SUGGESTION_FAILED);
                    assertThat(e.getMessage()).isEqualTo("AI 评分建议没有生成出来："
                            + ErrorCode.LLM_AUTH_FAILED.getMessage() + "。也可以直接评分");
                });
        verify(llmService, times(1)).call(anyString(), anyString(), eq(RatingSuggestionResult.class), any());
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 学完后生成一次并存库之后直接返回() {
        long sessionId = session(SessionType.STUDY, true);
        modelReturns(new RatingSuggestionResult("答出了可见性和原子性，漏了重排序", List.of("什么是 happens-before？"),
                Rating.HARD, List.of("volatile 禁止指令重排序")));

        RatingSuggestionView first = suggestionService.suggest(USER_ID, sessionId);
        RatingSuggestionView again = suggestionService.suggest(USER_ID, sessionId);

        assertThat(first).isEqualTo(new RatingSuggestionView(Rating.HARD, "答出了可见性和原子性，漏了重排序",
                List.of("volatile 禁止指令重排序"), List.of("什么是 happens-before？")));
        assertThat(again).isEqualTo(first);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(llmService, times(1)).call(anyString(), input.capture(), eq(RatingSuggestionResult.class), eq(0.2));
        // 输入：知识点、要点和去掉标记的对话
        assertThat(input.getValue()).startsWith("知识点：什么是 volatile？\n类型：CONCEPT（原子概念）\n要点：可见性；禁止重排序；不保证原子性")
                .contains("【学生】\nvolatile 保证可见性，不保证原子性\n\n【老师】\n参考答法……")
                .doesNotContain(Marker.END.text());
        RatingSuggestion saved = suggestionMapper.selectById(sessionId);
        assertThat(saved.getPromptVersion()).isEqualTo("v3");
        assertThat(saved.getModel()).isEqualTo("test-model");
        assertThat(suggestionService.suggestedRating(sessionId)).isEqualTo(Rating.HARD);
    }

    @Test
    void 没学完不能评分接着问不需要评分() {
        long unfinished = session(SessionType.STUDY, false);
        long followUp = session(SessionType.FOLLOW_UP, true);

        assertError(() -> suggestionService.suggest(USER_ID, unfinished), ErrorCode.CONFLICT);
        assertError(() -> suggestionService.suggest(USER_ID, followUp), ErrorCode.BAD_REQUEST);
        assertError(() -> suggestionService.suggest(2, unfinished), ErrorCode.NOT_FOUND);
        assertThat(suggestionService.suggestedRating(unfinished)).isNull();
    }

    @Test
    void 结果解析失败时重试一次模型漏掉列表时按空列表处理() {
        long sessionId = session(SessionType.STUDY, true);
        when(llmService.call(anyString(), anyString(), eq(RatingSuggestionResult.class), any()))
                .thenThrow(new LlmOutputParseException("返回内容不符合要求的 JSON 结构",
                        new LlmResult<>(null, "not json", "test-model", "stop", 10, 10), null))
                .thenReturn(result(new RatingSuggestionResult("依据", null, Rating.GOOD, null)));

        RatingSuggestionView view = suggestionService.suggest(USER_ID, sessionId);

        assertThat(view.rating()).isEqualTo(Rating.GOOD);
        assertThat(view.weakPoints()).isEmpty();
        assertThat(view.outOfScopeGaps()).isEmpty();
        verify(llmService, times(2)).call(anyString(), anyString(), eq(RatingSuggestionResult.class), any());
    }

    @Test
    void 两次都没给出评分或者模型出错时返回失败且不存() {
        long sessionId = session(SessionType.STUDY, true);
        modelReturns(new RatingSuggestionResult("依据", List.of(), null, List.of()));
        assertError(() -> suggestionService.suggest(USER_ID, sessionId), ErrorCode.RATING_SUGGESTION_FAILED);
        verify(llmService, times(2)).call(anyString(), anyString(), eq(RatingSuggestionResult.class), any());

        // 模型调用出错（如超时）不重试
        when(llmService.call(anyString(), anyString(), eq(RatingSuggestionResult.class), any()))
                .thenThrow(new RuntimeException("timeout"));
        assertError(() -> suggestionService.suggest(USER_ID, sessionId), ErrorCode.RATING_SUGGESTION_FAILED);
        verify(llmService, times(3)).call(anyString(), anyString(), eq(RatingSuggestionResult.class), any());
        assertThat(suggestionMapper.selectById(sessionId)).isNull();
    }
}
