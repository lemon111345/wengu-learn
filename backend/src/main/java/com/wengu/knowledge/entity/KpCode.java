package com.wengu.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/** 源码知识点特有的数据，对应 kp_code 表（#90，决策 050）。确认源码草稿时从草稿条目复制过来，主键就是知识点 id。 */
public class KpCode {

    @TableId(type = IdType.INPUT)
    private Long kpId;
    private Long userId;
    /** 来自哪次源码导入 */
    private Long sourceId;
    /** 细节，没有时为 null */
    private String details;
    /** 代码依据 JSON：[{file, symbol}] */
    private String evidence;
    /** 代码证明不了的问题 JSON */
    private String unverifiable;
    /** 前置概念 JSON：[{concept, explanation}] */
    private String prerequisites;

    /** 从草稿条目的源码数据复制一份。 */
    public static KpCode of(long kpId, long sourceId, ExtractionDraftItemCode code) {
        KpCode kpCode = new KpCode();
        kpCode.setKpId(kpId);
        kpCode.setUserId(code.getUserId());
        kpCode.setSourceId(sourceId);
        kpCode.setDetails(code.getDetails());
        kpCode.setEvidence(code.getEvidence());
        kpCode.setUnverifiable(code.getUnverifiable());
        kpCode.setPrerequisites(code.getPrerequisites());
        return kpCode;
    }

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
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
