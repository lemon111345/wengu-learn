package com.wengu.knowledge.dto;

import com.wengu.common.result.ErrorCode;
import com.wengu.knowledge.entity.BatchStatus;

import java.util.List;

/**
 * 草稿中的一批。重试过的批次只返回最新的一次。
 *
 * @param no            第几批，从 1 开始
 * @param questionIds   这一批的题号，对应 source 中的题号
 * @param failureReason 失败时给用户看的原因（决策 032），如“AI 模型拒绝了请求……”；
 *                      没有失败、或者原因不明（V12 之前失败的、服务重启中断的）时为 null
 */
public record BatchView(int no, BatchStatus status, List<Integer> questionIds, String failureReason) {

    /** 由批次记下的返回码得出原因。原始的 error 只用于排查，不返回前端（决策 013） */
    public static String failureReason(BatchStatus status, String errorCode) {
        if (status != BatchStatus.FAILED || errorCode == null) {
            return null;
        }
        try {
            return ErrorCode.valueOf(errorCode).getMessage();
        } catch (IllegalArgumentException e) {
            // 返回码改过名，库里还是旧的
            return null;
        }
    }
}
