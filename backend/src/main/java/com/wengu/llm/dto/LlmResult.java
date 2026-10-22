package com.wengu.llm.dto;

/**
 * 一次结构化调用的结果：解析后的对象，以及排查问题、统计费用需要的原始信息。
 *
 * @param content          解析后的对象；解析失败时为 null（见 LlmOutputParseException）
 * @param rawText          模型返回的原始文本
 * @param model            实际使用的模型名
 * @param finishReason     结束原因。正常为 "stop"；"length" 表示达到输出上限、内容被截断
 * @param promptTokens     输入 token 数，服务商未返回时为 null
 * @param completionTokens 输出 token 数，服务商未返回时为 null
 */
public record LlmResult<T>(T content, String rawText, String model, String finishReason,
                           Integer promptTokens, Integer completionTokens) {

    /** 输出是否因为达到长度上限而被截断。 */
    public boolean truncated() {
        return "length".equalsIgnoreCase(finishReason);
    }
}
