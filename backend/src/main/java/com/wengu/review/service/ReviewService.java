package com.wengu.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.review.dto.CardProgress;
import com.wengu.review.dto.DueCard;
import com.wengu.review.dto.ReviewRecord;
import com.wengu.review.dto.ReviewResult;
import com.wengu.review.entity.CardState;
import com.wengu.review.entity.ReviewLog;
import com.wengu.review.fsrs.Card;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.fsrs.Scheduler;
import com.wengu.review.mapper.CardStateMapper;
import com.wengu.review.mapper.ReviewLogMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 评分与调度。只通过 kp_id 工作，不知道知识点是什么、属于哪种类型（决策 002）。
 * <p>
 * 初始化调度状态、评分与预览（#20），以及每日计划、知识库（#50）要用的查询。
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
     * 知识点改成参与调度的类型时（#25）：还没有调度状态的初始化成新卡；已经有的不动，原来的复习进度接着用。
     * 和 {@link #initCards} 分开：那里重复初始化说明确认草稿出了错，应该失败；这里“已经有了”是正常情况。
     * <p>
     * 改成只收录的类型时不删调度状态，由 knowledge 在每日计划中按类型排除（决策 029）。
     */
    @Transactional
    public void ensureCard(long userId, long kpId) {
        if (cardStateMapper.selectById(kpId) == null) {
            cardStateMapper.insert(CardState.of(kpId, userId, Card.newCard(Instant.now(clock))));
        }
    }

    /**
     * 评分预览：四种评分分别算出下次复习的时间，不保存（#20）。
     *
     * @throws BusinessException 这个知识点没有调度状态（不存在、不属于该用户、或者是只收录的类型）时为 NOT_FOUND
     */
    public Map<Rating, Instant> preview(long userId, long kpId) {
        return dues(kpId, card(userId, kpId).toCard(), Instant.now(clock));
    }

    /**
     * 已评分会话的预览：按评分前的快照和原来的评分时间，算出四种评分各自的下次复习时间，不保存（决策 039）。
     *
     * @throws BusinessException 这个会话没有评分时为 NOT_FOUND；不能修改时为 CONFLICT，见 {@link #changeableLog}
     */
    public Map<Rating, Instant> previewChange(long userId, long sessionId) {
        ReviewLog log = changeableLog(userId, sessionId);
        return dues(log.getKpId(), log.cardBefore(), log.getReviewedAt());
    }

    private static Map<Rating, Instant> dues(long kpId, Card card, Instant at) {
        Map<Rating, Instant> dues = new EnumMap<>(Rating.class);
        for (Rating rating : Rating.values()) {
            dues.put(rating, schedulerFor(kpId, card).review(card, rating, at).due());
        }
        return dues;
    }

    /**
     * 评分：用 FSRS 算出新的调度状态，更新 card_state，写一条评分流水（决策 002 第 5 条，#20）。
     * 两步在一个事务里，要么都写入，要么都不写。
     *
     * @param sessionId 这次学习的会话；同一个会话只能评一次分
     * @param ratingAi  AI 建议的评分，没有时为 null
     * @throws BusinessException 知识点没有调度状态时为 NOT_FOUND；这个会话已经评过分时为 CONFLICT
     */
    @Transactional
    public ReviewResult rate(long userId, long kpId, Long sessionId, Rating ratingUser, Rating ratingAi) {
        if (sessionId != null && isRated(sessionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "这次学习已经评过分了");
        }
        Card before = card(userId, kpId).toCard();
        // 数据库只存到毫秒。截断后再计算，改分时读回的评分时间才和这里用的完全相同（决策 039）
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        Card after = schedulerFor(kpId, before).review(before, ratingUser, now);
        boolean firstReview = before.lastReview() == null;

        saveCard(kpId, after);
        ReviewLog log = new ReviewLog();
        log.setUserId(userId);
        log.setKpId(kpId);
        log.setSessionId(sessionId);
        log.setRatingUser(ratingUser);
        log.setRatingAi(ratingAi);
        log.snapshotBefore(before);
        log.setFirstReview(firstReview);
        log.setDueAfter(after.due());
        log.setReviewedAt(now);
        try {
            reviewLogMapper.insert(log);
        } catch (DuplicateKeyException e) {
            // 两个评分请求同时到达，都通过了上面的检查，由 session_id 的唯一索引拦下后到的那个；事务回滚
            throw new BusinessException(ErrorCode.CONFLICT, "这次学习已经评过分了");
        }
        return new ReviewResult(ratingUser, after.due(), firstReview);
    }

    /**
     * 修改评分（决策 039）：恢复评分前的快照，用原来的评分时间按新评分重算，结果和第一次就选这一档相同。
     * 不能在已经按旧评分算过的状态上再算：FSRS 是非线性的，难度还会被截在 1～10，算不回去。
     * <p>
     * 只改 card_state 和这条流水的 rating_user、due_after；评分时间、是否第一次、AI 建议都不变。
     *
     * @throws BusinessException 这个会话没有评分时为 NOT_FOUND；不能修改时为 CONFLICT，见 {@link #changeableLog}
     */
    @Transactional
    public ReviewResult changeRating(long userId, long sessionId, Rating ratingUser) {
        ReviewLog log = changeableLog(userId, sessionId);
        Card before = log.cardBefore();
        Card after = schedulerFor(log.getKpId(), before).review(before, ratingUser, log.getReviewedAt());

        saveCard(log.getKpId(), after);
        reviewLogMapper.update(new LambdaUpdateWrapper<ReviewLog>()
                .eq(ReviewLog::getId, log.getId())
                .set(ReviewLog::getRatingUser, ratingUser)
                .set(ReviewLog::getDueAfter, after.due()));
        return new ReviewResult(ratingUser, after.due(), log.getFirstReview());
    }

    /** 这个会话的评分现在还能不能改；没有评分时为 false。 */
    public boolean isRatingChangeable(long sessionId) {
        ReviewLog log = logOf(sessionId);
        return log != null && log.hasSnapshot() && isLatest(log);
    }

    /**
     * 能修改的评分流水：属于该用户、有快照、是这个知识点最近一次评分。
     *
     * @throws BusinessException 没有评分或不属于该用户时为 NOT_FOUND；旧记录、之后又复习过时为 CONFLICT
     */
    private ReviewLog changeableLog(long userId, long sessionId) {
        ReviewLog log = logOf(sessionId);
        if (log == null || log.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "这次学习还没有评分");
        }
        if (!log.hasSnapshot()) {
            throw new BusinessException(ErrorCode.CONFLICT, "这是早期的评分记录，不能修改");
        }
        if (!isLatest(log)) {
            throw new BusinessException(ErrorCode.CONFLICT, "这次评分之后又复习过，不能修改");
        }
        return log;
    }

    private ReviewLog logOf(long sessionId) {
        return reviewLogMapper.selectOne(new LambdaQueryWrapper<ReviewLog>()
                .eq(ReviewLog::getSessionId, sessionId));
    }

    /** 这条流水是不是这个知识点最近一次评分。之后的评分是在这次的结果上算的，改了这次，后面的就对不上了 */
    private boolean isLatest(ReviewLog log) {
        ReviewLog latest = reviewLogMapper.selectOne(new LambdaQueryWrapper<ReviewLog>()
                .select(ReviewLog::getId)
                .eq(ReviewLog::getKpId, log.getKpId())
                .orderByDesc(ReviewLog::getReviewedAt)
                .orderByDesc(ReviewLog::getId)
                .last("LIMIT 1"));
        return latest.getId().equals(log.getId());
    }

    private void saveCard(long kpId, Card card) {
        // 不用 updateById：它默认跳过值为 null 的字段，而进入 REVIEW 后 step 要变成 null，恢复新卡时 last_review 也可能为空
        cardStateMapper.update(new LambdaUpdateWrapper<CardState>()
                .eq(CardState::getKpId, kpId)
                .set(CardState::getState, card.state())
                .set(CardState::getStep, card.step())
                .set(CardState::getStability, card.stability())
                .set(CardState::getDifficulty, card.difficulty())
                .set(CardState::getDue, card.due())
                .set(CardState::getLastReview, card.lastReview()));
    }

    /**
     * 这个会话的评分结果，没评过分时为空（#23 学习页刷新后显示已评的分）。
     * V10 之前的流水没有 due_after，这里不会遇到：那时还没有学习会话的评分。
     */
    public Optional<ReviewResult> reviewOf(long sessionId) {
        return Optional.ofNullable(logOf(sessionId))
                .map(l -> new ReviewResult(l.getRatingUser(), l.getDueAfter(), l.getFirstReview()));
    }

    /** 这个会话是否已经评过分 */
    public boolean isRated(long sessionId) {
        return reviewLogMapper.exists(new LambdaQueryWrapper<ReviewLog>().eq(ReviewLog::getSessionId, sessionId));
    }

    private CardState card(long userId, long kpId) {
        CardState card = cardStateMapper.selectById(kpId);
        if (card == null || card.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "这个知识点没有复习安排");
        }
        return card;
    }

    /**
     * fuzz（给间隔加随机扰动）的随机数由“这张卡 + 上次复习时间”决定（决策 026）：
     * 同一次评分，预览和实际评分算出的日期一样，前端可以先按预览显示“X月X日再见”；
     * 每次复习的上次复习时间不同，扰动照样分散，同一天学的卡不会总在同一天到期。
     */
    private static Scheduler schedulerFor(long kpId, Card card) {
        Random random = new Random(Objects.hash(kpId, card.lastReview()));
        return Scheduler.projectDefault(random::nextDouble);
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
        return reviewLogMapper.selectList(recordQuery(userId)
                        .ge(ReviewLog::getReviewedAt, start)
                        .lt(ReviewLog::getReviewedAt, end)
                        .orderByAsc(ReviewLog::getReviewedAt)
                        .orderByAsc(ReviewLog::getId))
                .stream()
                .map(ReviewService::toRecord)
                .toList();
    }

    /**
     * 这些知识点的全部评分，最近的在前（#50 知识库详情的学习记录）。
     * 合并时学习记录留在被合并的知识点上（决策 029），由 knowledge 把它们的 id 一起传进来；review 不知道合并。
     * 不属于该用户的 id 查不到。
     */
    public List<ReviewRecord> reviewsOf(long userId, Collection<Long> kpIds) {
        if (kpIds.isEmpty()) {
            return List.of();
        }
        return reviewLogMapper.selectList(recordQuery(userId)
                        .in(ReviewLog::getKpId, kpIds)
                        .orderByDesc(ReviewLog::getReviewedAt)
                        .orderByDesc(ReviewLog::getId))
                .stream()
                .map(ReviewService::toRecord)
                .toList();
    }

    /** 某个用户的评分流水，只取 {@link ReviewRecord} 用到的列 */
    private static LambdaQueryWrapper<ReviewLog> recordQuery(long userId) {
        return new LambdaQueryWrapper<ReviewLog>()
                .select(ReviewLog::getKpId, ReviewLog::getRatingUser, ReviewLog::getRatingAi,
                        ReviewLog::getFirstReview, ReviewLog::getReviewedAt)
                .eq(ReviewLog::getUserId, userId);
    }

    private static ReviewRecord toRecord(ReviewLog log) {
        return new ReviewRecord(log.getKpId(), log.getRatingUser(), log.getRatingAi(), log.getFirstReview(),
                log.getReviewedAt());
    }

    /**
     * 这些知识点的复习进度（#50 知识库的掌握程度和下次复习），按 kp_id 查。
     * 没有调度状态的（只收录的类型）不在结果中；不属于该用户的 id 也查不到。
     */
    public Map<Long, CardProgress> progressOf(long userId, Collection<Long> kpIds) {
        if (kpIds.isEmpty()) {
            return Map.of();
        }
        return cardStateMapper.selectList(new LambdaQueryWrapper<CardState>()
                        .select(CardState::getKpId, CardState::getStability, CardState::getDue,
                                CardState::getLastReview)
                        .eq(CardState::getUserId, userId)
                        .in(CardState::getKpId, kpIds))
                .stream()
                .collect(Collectors.toMap(CardState::getKpId,
                        cs -> new CardProgress(cs.getKpId(), cs.getStability(), cs.getDue(), cs.getLastReview())));
    }

    private List<Long> kpIds(LambdaQueryWrapper<CardState> query) {
        return cardStateMapper.selectList(query).stream().map(CardState::getKpId).toList();
    }
}
