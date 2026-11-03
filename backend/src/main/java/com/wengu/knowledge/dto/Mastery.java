package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.review.dto.CardProgress;

/**
 * 掌握程度，知识库的筛选按钮和“掌握”一列（ui.md 5.9，#50）。
 * <p>
 * 学过的按 FSRS 稳定性分档（技术方案“前端设计带来的需求”）：不按最近一次评分（太粗），
 * 也不按可提取率（每天都在变，标签会跳）。稳定性大致是“记住的概率降到 90% 要多少天”，学得越牢越大。
 * 区间含下限、不含上限。
 */
public enum Mastery {

    /** 稳定性 ≥ 21 天 */
    SOLID,
    /** 稳定性 7～21 天 */
    FAMILIAR,
    /** 稳定性 2～7 天 */
    FAIR,
    /** 稳定性 < 2 天 */
    SHAKY,
    /** 待学：还没学过 */
    NEW,
    /** 暂停复习 */
    SUSPENDED,
    /** 只收录：不参与调度的类型（M1 的系统设计、个人经历，ui.md 第 8 节） */
    RECORD_ONLY;

    /** 牢固的下限（天） */
    public static final double SOLID_DAYS = 21;
    /** 熟悉的下限（天） */
    public static final double FAMILIAR_DAYS = 7;
    /** 一般的下限（天） */
    public static final double FAIR_DAYS = 2;

    /**
     * 判断一个知识点的掌握程度，按先后顺序：
     * <ol>
     *   <li>类型不参与调度 → 只收录。这个类型本来就学不了，暂不暂停都一样；改过来时保留的调度状态也不看（决策 029）</li>
     *   <li>暂停 → 暂停</li>
     *   <li>没有调度状态或还没复习过 → 待学</li>
     *   <li>其余按稳定性分档</li>
     * </ol>
     *
     * @param card 调度状态，没有时为 null
     */
    public static Mastery of(KnowledgePointType type, KnowledgePointStatus status, CardProgress card) {
        if (!type.isScheduled()) {
            return RECORD_ONLY;
        }
        if (status == KnowledgePointStatus.SUSPENDED) {
            return SUSPENDED;
        }
        if (card == null || card.lastReview() == null) {
            return NEW;
        }
        double stability = card.stability();
        if (stability >= SOLID_DAYS) {
            return SOLID;
        }
        if (stability >= FAMILIAR_DAYS) {
            return FAMILIAR;
        }
        if (stability >= FAIR_DAYS) {
            return FAIR;
        }
        return SHAKY;
    }

    /** 是否学过、按稳定性分档的四种：只有它们有“下次复习”，排序时排在待学、暂停、只收录之前 */
    public boolean isLearned() {
        return switch (this) {
            case SOLID, FAMILIAR, FAIR, SHAKY -> true;
            case NEW, SUSPENDED, RECORD_ONLY -> false;
        };
    }
}
