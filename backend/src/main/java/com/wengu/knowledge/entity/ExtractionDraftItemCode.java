package com.wengu.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/** 源码抽出的草稿条目特有的数据，对应 extraction_draft_item_code 表（#90，决策 050）。主键就是条目 id。 */
public class ExtractionDraftItemCode {

    @TableId(type = IdType.INPUT)
    private Long itemId;
    private Long userId;
    /** 细节，没有时为 null */
    private String details;
    /** 代码依据 JSON：[{file, symbol}] */
    private String evidence;
    /** 代码证明不了的问题 JSON */
    private String unverifiable;
    /** 前置概念 JSON：[{concept, explanation}] */
    private String prerequisites;

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getUnverifiable() {
        return unverifiable;
    }

    public void setUnverifiable(String unverifiable) {
        this.unverifiable = unverifiable;
    }

    public String getPrerequisites() {
        return prerequisites;
    }

    public void setPrerequisites(String prerequisites) {
        this.prerequisites = prerequisites;
    }
}
