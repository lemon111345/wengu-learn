package com.wengu.knowledge.entity;

/**
 * 知识点与材料的多对多关联，对应 kp_source 表（V1）。主键是 (kp_id, source_id)，没有单独的 id。
 */
public class KpSource {

    private Long kpId;
    private Long sourceId;
    private Long userId;

    public static KpSource of(long kpId, long sourceId, long userId) {
        KpSource link = new KpSource();
        link.setKpId(kpId);
        link.setSourceId(sourceId);
        link.setUserId(userId);
        return link;
    }

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
