package com.wengu.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.review.dto.DueCard;
import com.wengu.review.dto.ReviewRecord;
import com.wengu.review.entity.CardState;
import com.wengu.review.entity.ReviewLog;
import com.wengu.review.fsrs.Card;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 评分与调度。只通过 kp_id 工作，不知道知识点是什么、属于哪种类型（决策 002）。
 * <p>
 * 目前有初始化和每日计划要用的查询；评分后更新调度状态、写评分流水由 #20 添加。
 */
@Service
public class ReviewService {

    private final CardStateMapper cardStateMapper;
    private final ReviewLogMapper reviewLogMapper;
    private final Clock clock;

    public ReviewService(CardStateMapper cardStateMapper, ReviewLogMapper reviewLogMapper, Clock clock) {
        this.cardStateMapper = cardStateMapper;
        this.reviewLogMapper = reviewLogMapper;
        this.clock = clock;
    }

    /**
     * 为新知识点初始化调度状态：新卡、立即到期、last_review 为空（即“新知识”，技术方案“调度状态说明”）。
     * <p>
     * 哪些知识点需要调度由调用方决定：SYSTEM_DESIGN、EXPERIENCE 不参与调度，knowledge 不会把它们传进来（决策 007）。
     * 在调用方的事务中执行；单独调用时自己开一个事务，要么全部写入，要么都不写。
     *
     * @param userId 知识点所属的用户
     * @param kpIds  知识点 id；已经初始化过的 kp_id 会因主键冲突而失败
     */
    @Transactional
    public void initCards(long userId, List<Long> kpIds) {
        Card card = Card.newCard(Instant.now(clock));
        for (Long kpId : kpIds) {
            cardStateMapper.insert(CardState.of(kpId, userId, card));
        }
    }

    /**
     * 到期复习的候选（每日计划第 2 步）：复习过（last_review 不为空）、并且在 before 之前到期。
     * 必须排除 last_review 为空的新卡：新卡创建时就立即到期，不排除就会被当成到期复习。
     * <p>
     * before 传“今天”的结束时间，今天晚些时候才到期的也算进来。顺序由 knowledge 决定，这里不排序。
     */
    public List<Long> dueKpIds(long userId, Instant before) {
        return kpIds(new LambdaQueryWrapper<CardState>()
                .select(CardState::getKpId)
                .eq(CardState::getUserId, userId)
                .isNotNull(CardState::getLastReview)
                .lt(CardState::getDue, before));
    }

    /** 新知识的候选（每日计划第 5 步）：从未复习过（last_review 为空）。顺序由 knowledge 决定。 */
    public List<Long> newKpIds(long userId) {
        return kpIds(new LambdaQueryWrapper<CardState>()
                .select(CardState::getKpId)
                .eq(CardState::getUserId, userId)
                .isNull(CardState::getLastReview));
    }

    /**
     * [start, end) 之间第一次学习的知识点数（每日计划第 3 步），用来扣除今天已经用掉的新知识名额。
     * 用 is_first_review 判断，不能用 state_before：新卡的 state 也是 LEARNING（决策 003）。
     */
    public int countFirstReviews(long userId, Instant start, Instant end) {
        return Math.toIntExact(reviewLogMapper.selectCount(new LambdaQueryWrapper<ReviewLog>()
                .eq(ReviewLog::getUserId, userId)
                .eq(ReviewLog::getFirstReview, true)
                .ge(ReviewLog::getReviewedAt, start)
                .lt(ReviewLog::getReviewedAt, end)));
    }

    /**
     * 在 [start, end) 之间到期的复习（#48 接下来 7 天要复习的）。和 {@link #dueKpIds} 一样排除新卡。
     * 按哪一天分组由调用方决定：学习日的划分属于每日计划，不属于调度。
     */
    public List<DueCard> dueBetween(long userId, Instant start, Instant end) {
        return cardStateMapper.selectList(new LambdaQueryWrapper<CardState>()
                        .select(CardState::getKpId, CardState::getDue)
                        .eq(CardState::getUserId, userId)
                        .isNotNull(CardState::getLastReview)
                        .ge(CardState::getDue, start)
                        .lt(CardState::getDue, end))
                .stream()
                .map(cs -> new DueCard(cs.getKpId(), cs.getDue()))
                .toList();
    }

    /** [start, end) 之间的评分，按评分时间排列。每日计划用它显示今天已完成的条目（#48）。 */
    public List<ReviewRecord> reviewsBetween(long userId, Instant start, Instant end) {
        return reviewLogMapper.selectList(new LambdaQueryWrapper<ReviewLog>()
                        .select(ReviewLog::getKpId, ReviewLog::getRatingUser, ReviewLog::getFirstReview,
                                ReviewLog::getReviewedAt)
                        .eq(ReviewLog::getUserId, userId)
                        .ge(ReviewLog::getReviewedAt, start)
                        .lt(ReviewLog::getReviewedAt, end)
                        .orderByAsc(ReviewLog::getReviewedAt)
                        .orderByAsc(ReviewLog::getId))
                .stream()
                .map(log -> new ReviewRecord(log.getKpId(), log.getRatingUser(), log.getFirstReview(),
                        log.getReviewedAt()))
                .toList();
    }

    private List<Long> kpIds(LambdaQueryWrapper<CardState> query) {
        return cardStateMapper.selectList(query).stream().map(CardState::getKpId).toList();
    }
}
