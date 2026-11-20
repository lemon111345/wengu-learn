package com.wengu.chat.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.chat.dto.GapsRequest;
import com.wengu.chat.dto.RatingSuggestionView;
import com.wengu.chat.entity.RatingSuggestion;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.chat.mapper.RatingSuggestionMapper;
import com.wengu.chat.mapper.StudySessionMapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.service.ExtractionDraftService;
import com.wengu.review.fsrs.Rating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 评分页勾选的学习缺口加入知识库（#31，决策 053）：校验、只处理一次、失败时撤销。
 * 真实的 MySQL；knowledge 的建草稿换成模拟对象，抽取和自动确认在 GapDraftTest 中测过。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GapPickTest {

    private static final long USER_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-11-20T02:00:00Z");
    private static final List<String> GAPS = List.of("代理是什么？", "JDK 动态代理和 CGLIB 有什么区别？", "BeanPostProcessor 是什么？");

    @Autowired
    private RatingSuggestionService suggestionService;

    @Autowired
    private RatingSuggestionMapper suggestionMapper;

    @Autowired
    private StudySessionMapper sessionMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @MockitoBean
    private ExtractionDraftService draftService;

    private long kpId;

    @BeforeEach
    void reset() {
        suggestionMapper.delete(null);
        sessionMapper.delete(null);
        knowledgePointMapper.delete(null);
        KnowledgePoint kp = new KnowledgePoint();
        kp.setUserId(USER_ID);
        kp.setType(KnowledgePointType.MECHANISM);
        kp.setTitle("Spring 中一个单例 Bean 的创建过程是怎样的？");
        kp.setSummary("实例化；属性填充；初始化");
        kp.setStatus(KnowledgePointStatus.ACTIVE);
        kp.setSortOrder(1);
        kp.setCreatedAt(NOW);
        kp.setUpdatedAt(NOW);
        knowledgePointMapper.insert(kp);
        kpId = kp.getId();
    }

    private long session(SessionType type) {
        StudySession s = new StudySession();
        s.setUserId(USER_ID);
        s.setType(type);
        s.setKpId(kpId);
        s.setStartedAt(NOW);
        s.setEndedAt(NOW);
        s.setSkippedExplanation(false);
        s.setActiveSeconds(0);
        sessionMapper.insert(s);
        return s.getId();
    }

    /** 学完、有评分建议（带三条缺口）的学习会话 */
    private long suggested() {
        long sessionId = session(SessionType.STUDY);
        RatingSuggestion s = new RatingSuggestion();
        s.setSessionId(sessionId);
        s.setUserId(USER_ID);
        s.setRating(Rating.GOOD);
        s.setAnalysis("依据");
        s.setWeakPoints("[]");
        s.setOutOfScopeGaps("[\"" + String.join("\",\"", GAPS) + "\"]");
        s.setModel("test-model");
        s.setPromptVersion("v3");
        s.setCreatedAt(NOW);
        suggestionMapper.insert(s);
        return sessionId;
    }

    private static GapsRequest picks(Integer... indices) {
        return new GapsRequest(Arrays.asList(indices));
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 勾选的缺口按顺序交给knowledge_记下下标和草稿() {
        long sessionId = suggested();
        doReturn(42L).when(draftService).createGapDraft(anyLong(), anyString(), anyList());

        RatingSuggestionView view = suggestionService.addGaps(USER_ID, sessionId, picks(2, 0));

        verify(draftService).createGapDraft(USER_ID, "Spring 中一个单例 Bean 的创建过程是怎样的？",
                List.of(GAPS.get(0), GAPS.get(2)));
        assertThat(view.gapPicks()).containsExactly(0, 2);
        assertThat(view.gapDraftId()).isEqualTo(42L);
        assertThat(view.outOfScopeGaps()).isEqualTo(GAPS);
        assertThat(suggestionService.saved(USER_ID, sessionId)).isEqualTo(view);
    }

    @Test
    void 还没处理时两个新字段为空() {
        long sessionId = suggested();

        RatingSuggestionView view = suggestionService.saved(USER_ID, sessionId);

        assertThat(view.gapPicks()).isNull();
        assertThat(view.gapDraftId()).isNull();
    }

    @Test
    void 一次评分的缺口只处理一次() {
        long sessionId = suggested();
        doReturn(42L).when(draftService).createGapDraft(anyLong(), anyString(), anyList());
        suggestionService.addGaps(USER_ID, sessionId, picks(0));

        assertError(() -> suggestionService.addGaps(USER_ID, sessionId, picks(1)), ErrorCode.CONFLICT);
        verify(draftService, times(1)).createGapDraft(anyLong(), anyString(), anyList());
        assertThat(suggestionService.saved(USER_ID, sessionId).gapPicks()).containsExactly(0);
    }

    @Test
    void 还没有评分建议时不能加入() {
        long sessionId = session(SessionType.STUDY);

        assertError(() -> suggestionService.addGaps(USER_ID, sessionId, picks(0)), ErrorCode.CONFLICT);
        verify(draftService, never()).createGapDraft(anyLong(), anyString(), anyList());
    }

    @Test
    void 下标为空_越界_重复_负数时不加入() {
        long sessionId = suggested();

        for (GapsRequest bad : Arrays.asList(null, new GapsRequest(null), picks(), picks(3), picks(-1),
                picks(1, 1), picks(0, null))) {
            assertError(() -> suggestionService.addGaps(USER_ID, sessionId, bad), ErrorCode.BAD_REQUEST);
        }
        verify(draftService, never()).createGapDraft(anyLong(), anyString(), anyList());
        assertThat(suggestionService.saved(USER_ID, sessionId).gapPicks()).isNull();
    }

    @Test
    void 接着问的会话和别人的会话不能加入() {
        long followUp = session(SessionType.FOLLOW_UP);
        long sessionId = suggested();

        assertError(() -> suggestionService.addGaps(USER_ID, followUp, picks(0)), ErrorCode.BAD_REQUEST);
        assertError(() -> suggestionService.addGaps(2, sessionId, picks(0)), ErrorCode.NOT_FOUND);
        verify(draftService, never()).createGapDraft(anyLong(), anyString(), anyList());
    }

    @Test
    void 建草稿失败时撤销_之后可以再加入() {
        long sessionId = suggested();
        doThrow(new IllegalStateException("数据库连不上")).when(draftService)
                .createGapDraft(anyLong(), anyString(), anyList());

        assertThatThrownBy(() -> suggestionService.addGaps(USER_ID, sessionId, picks(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(suggestionService.saved(USER_ID, sessionId).gapPicks()).isNull();

        doReturn(7L).when(draftService).createGapDraft(anyLong(), anyString(), anyList());
        RatingSuggestionView view = suggestionService.addGaps(USER_ID, sessionId, picks(1));
        assertThat(view.gapPicks()).containsExactly(1);
        assertThat(view.gapDraftId()).isEqualTo(7L);
    }
}
