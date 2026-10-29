package com.wengu.review.service;

import com.wengu.review.entity.CardState;
import com.wengu.review.fsrs.Card;
import com.wengu.review.mapper.CardStateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 评分与调度。只通过 kp_id 工作，不知道知识点是什么、属于哪种类型（决策 002）。
 * <p>
 * 目前只有初始化；评分后更新调度状态、写评分流水由 #20 添加。
 */
@Service
public class ReviewService {

    private final CardStateMapper cardStateMapper;
    private final Clock clock;

    public ReviewService(CardStateMapper cardStateMapper, Clock clock) {
        this.cardStateMapper = cardStateMapper;
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
}
