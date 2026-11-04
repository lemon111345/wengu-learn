package com.wengu.source.dto;

import com.wengu.source.entity.SourceType;

/**
 * 导入资料的请求（粘贴文本）。上传文件走 multipart 接口，参数含义相同。
 *
 * @param name    材料名称，如“Redis 高频题”
 * @param content 粘贴的原文，可以带答案
 * @param type    资料类型：QUESTION（题目清单，从原文中识别题目）或 DOCUMENT（讲解资料，根据内容整理题目，#26）。
 *                不传时为 QUESTION，和 #26 之前一致
 */
public record ImportQuestionsRequest(String name, String content, SourceType type) {

    /** 题目清单 */
    public ImportQuestionsRequest(String name, String content) {
        this(name, content, null);
    }
}
