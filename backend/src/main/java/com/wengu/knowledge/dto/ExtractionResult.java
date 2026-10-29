package com.wengu.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 一次抽取的结果，也是要求模型返回的 JSON 结构的根对象。
 * <p>
 * 字段顺序就是模型的思考顺序：先写分组和重叠判断，再写哪些题已被已有知识点覆盖，最后只为剩下的内容输出知识点。
 * Spring AI 生成 JSON Schema 时默认按字段名字母排序，所以用 @JsonPropertyOrder 固定（决策 011 实测有效）。
 *
 * @param groups          主题分组，只是推理草稿，不入库
 * @param covered         已被已有知识点覆盖的题；没有给出已有知识点时为空（决策 014）
 * @param knowledgePoints 抽取出的新知识点
 */
@JsonPropertyOrder({"groups", "covered", "knowledgePoints"})
public record ExtractionResult(List<TopicGroup> groups, List<CoveredQuestion> covered,
                               List<ExtractedKnowledgePoint> knowledgePoints) {
}
