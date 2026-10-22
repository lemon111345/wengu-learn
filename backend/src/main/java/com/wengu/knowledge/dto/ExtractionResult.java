package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 一次抽取的结果，也是要求模型返回的 JSON 结构的根对象。
 */
public record ExtractionResult(List<ExtractedKnowledgePoint> knowledgePoints) {
}
