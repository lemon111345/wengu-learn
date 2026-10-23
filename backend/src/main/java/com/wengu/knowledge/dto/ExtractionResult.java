package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 一次抽取的结果，也是要求模型返回的 JSON 结构的根对象。
 * <p>
 * groups 必须排在 knowledgePoints 前面：模型按顺序生成，先写下分组和重叠判断，输出知识点时才能据此合并。
 * Spring AI 生成的 JSON Schema 按字段名字母排序，不按声明顺序，groups 在前是因为 g 排在 k 前面。改字段名时要注意。
 *
 * @param groups          主题分组，只是推理草稿，不入库
 * @param knowledgePoints 抽取出的知识点
 */
public record ExtractionResult(List<TopicGroup> groups, List<ExtractedKnowledgePoint> knowledgePoints) {
}
