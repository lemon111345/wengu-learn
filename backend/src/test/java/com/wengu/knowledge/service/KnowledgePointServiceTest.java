package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.dto.KnowledgePointSummary;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.UpdateKnowledgePointRequest;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KpRevision;
import com.wengu.knowledge.entity.KpSource;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpRevisionMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.review.entity.CardState;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 知识点的查询、编辑、删除、合并（#25，决策 029）。用真实的 MySQL。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KnowledgePointServiceTest {

    private static final long USER_ID = 1L;
    private static final long SOURCE_ID = 900L;

    @Autowired
    private KnowledgePointService service;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private KpSourceMapper kpSourceMapper;

    @Autowired
    private KpRevisionMapper revisionMapper;

    @Autowired
    private CardStateMapper cardStateMapper;

    @Autowired
    private ReviewService reviewService;

    /** 按顺序建好的三个知识点：原理机制、原子概念、系统设计（只收录） */
    private long mechanism;
    private long concept;
    private long systemDesign;

    @BeforeEach
    void setUp() {
        cardStateMapper.delete(null);
        revisionMapper.delete(null);
        kpSourceMapper.delete(null);
        knowledgePointMapper.delete(null);
        List<Long> ids = service.create(USER_ID, SOURCE_ID, List.of(
                new KnowledgePointService.NewKnowledgePoint(KnowledgePointType.MECHANISM, "HashMap 是怎么扩容的？", "要点一", null),
                new KnowledgePointService.NewKnowledgePoint(KnowledgePointType.CONCEPT, "什么是 volatile？", "要点二", null),
                new KnowledgePointService.NewKnowledgePoint(KnowledgePointType.SYSTEM_DESIGN, "如何设计秒杀？", "要点三", null)));
        mechanism = ids.get(0);
        concept = ids.get(1);
        systemDesign = ids.get(2);
    }

    private List<KpRevision> revisions(long kpId) {
        return revisionMapper.selectList(new LambdaQueryWrapper<KpRevision>()
                .eq(KpRevision::getKpId, kpId).orderByAsc(KpRevision::getId));
    }

    private static void assertErrorCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    /** 直接在库里标记删除；删除接口由后面的提交加上 */
    private void markDeleted(long kpId) {
        knowledgePointMapper.update(new LambdaUpdateWrapper<KnowledgePoint>()
                .set(KnowledgePoint::getDeletedAt, Instant.now())
                .eq(KnowledgePoint::getId, kpId));
    }

    @Test
    void 删除的知识点不在去重上下文中() {
        markDeleted(concept);

        assertThat(service.listTitles(USER_ID)).extracting(DedupContext.Existing::id)
                .containsExactly(mechanism, systemDesign);
    }

    @Test
    void 删除的知识点不进每日计划() {
        markDeleted(concept);

        assertThat(service.activeInOrder(USER_ID, List.of(mechanism, concept)))
                .extracting(KnowledgePointSummary::id).containsExactly(mechanism);
    }

    @Test
    void 删除的知识点不在今天已完成中显示() {
        markDeleted(concept);

        assertThat(service.summaries(USER_ID, List.of(mechanism, concept))).containsOnlyKeys(mechanism);
    }

    @Test
    void 删除的知识点不算进总数() {
        markDeleted(concept);

        assertThat(service.count(USER_ID)).isEqualTo(2);
    }

    @Test
    void 删除的知识点不能再学() {
        markDeleted(concept);

        assertThatThrownBy(() -> service.studyMaterial(USER_ID, concept))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 关联材料时跳过删除的知识点() {
        markDeleted(concept);

        service.linkToSource(USER_ID, 901L, List.of(mechanism, concept));

        assertThat(kpSourceMapper.selectList(new LambdaQueryWrapper<KpSource>().eq(KpSource::getSourceId, 901L)))
                .extracting(KpSource::getKpId).containsExactly(mechanism);
    }

    @Test
    void 新知识点的排序接在删除的之后() {
        markDeleted(systemDesign);

        long added = service.create(USER_ID, null, List.of(new KnowledgePointService.NewKnowledgePoint(
                KnowledgePointType.CONCEPT, "什么是 CAS？", "要点四", null))).getFirst();

        // 删除的那个排在第 3，撤销后两个不能重复
        assertThat(knowledgePointMapper.selectById(added).getSortOrder()).isEqualTo(4);
    }

    @Test
    void 编辑后留下修改前的快照() {
        service.update(USER_ID, concept, new UpdateKnowledgePointRequest("  volatile 保证了什么？ ", null, "可见性和有序性"));
        service.update(USER_ID, concept, new UpdateKnowledgePointRequest(null, null, "可见性、有序性，不保证原子性"));

        KnowledgePoint kp = knowledgePointMapper.selectById(concept);
        assertThat(kp.getTitle()).isEqualTo("volatile 保证了什么？");
        assertThat(kp.getSummary()).isEqualTo("可见性、有序性，不保证原子性");
        assertThat(kp.getType()).isEqualTo(KnowledgePointType.CONCEPT);
        // 每次存的都是修改前的内容
        assertThat(revisions(concept)).extracting(KpRevision::getTitle, KpRevision::getSummary).containsExactly(
                tuple("什么是 volatile？", "要点二"),
                tuple("volatile 保证了什么？", "可见性和有序性"));
        assertThat(revisions(concept).getFirst().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void 没有改动时不留记录() {
        service.update(USER_ID, concept, new UpdateKnowledgePointRequest("什么是 volatile？", KnowledgePointType.CONCEPT, " 要点二 "));
        service.update(USER_ID, concept, new UpdateKnowledgePointRequest(null, null, null));

        assertThat(revisions(concept)).isEmpty();
    }

    @Test
    void 标题或要点为空和标题过长时不改() {
        assertErrorCode(() -> service.update(USER_ID, concept, new UpdateKnowledgePointRequest("  ", null, null)), ErrorCode.BAD_REQUEST);
        assertErrorCode(() -> service.update(USER_ID, concept, new UpdateKnowledgePointRequest(null, null, " \n ")), ErrorCode.BAD_REQUEST);
        assertErrorCode(() -> service.update(USER_ID, concept, new UpdateKnowledgePointRequest("问".repeat(501), null, null)), ErrorCode.BAD_REQUEST);

        assertThat(knowledgePointMapper.selectById(concept).getTitle()).isEqualTo("什么是 volatile？");
        assertThat(revisions(concept)).isEmpty();
    }

    @Test
    void 不能编辑删除的和别人的知识点() {
        markDeleted(concept);
        UpdateKnowledgePointRequest request = new UpdateKnowledgePointRequest("新标题", null, null);

        assertErrorCode(() -> service.update(USER_ID, concept, request), ErrorCode.NOT_FOUND);
        assertErrorCode(() -> service.update(USER_ID + 1, mechanism, request), ErrorCode.NOT_FOUND);
        assertErrorCode(() -> service.update(USER_ID, 999_999L, request), ErrorCode.NOT_FOUND);
    }

    @Test
    void 改成只收录的类型后不进每日计划_改回来进度还在() {
        reviewService.rate(USER_ID, concept, null, Rating.GOOD, null);
        CardState reviewed = cardStateMapper.selectById(concept);

        service.update(USER_ID, concept, new UpdateKnowledgePointRequest(null, KnowledgePointType.EXPERIENCE, null));
        assertThat(service.activeInOrder(USER_ID, List.of(mechanism, concept)))
                .extracting(KnowledgePointSummary::id).containsExactly(mechanism);
        assertThat(cardStateMapper.selectById(concept)).isNotNull();

        service.update(USER_ID, concept, new UpdateKnowledgePointRequest(null, KnowledgePointType.CONCEPT, null));
        assertThat(service.activeInOrder(USER_ID, List.of(mechanism, concept)))
                .extracting(KnowledgePointSummary::id).containsExactly(mechanism, concept);
        assertThat(cardStateMapper.selectById(concept).getLastReview()).isEqualTo(reviewed.getLastReview());
    }

    @Test
    void 只收录的改成能学的类型后作为新知识进每日计划() {
        assertThat(cardStateMapper.selectById(systemDesign)).isNull();

        service.update(USER_ID, systemDesign, new UpdateKnowledgePointRequest(null, KnowledgePointType.MECHANISM, null));

        CardState card = cardStateMapper.selectById(systemDesign);
        assertThat(card.getLastReview()).isNull();
        assertThat(card.getUserId()).isEqualTo(USER_ID);
        assertThat(service.activeInOrder(USER_ID, List.of(systemDesign)))
                .extracting(KnowledgePointSummary::id).containsExactly(systemDesign);
    }
}
