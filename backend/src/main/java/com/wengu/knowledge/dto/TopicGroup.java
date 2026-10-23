package com.wengu.knowledge.dto;

import java.util.List;

/**
 * 模型在输出知识点之前写下的主题分组，是它判断拆分与合并的推理草稿，不入库。
 *
 * @param topic       主题名
 * @param questionIds 这组包含的题号
 * @param overlaps    组内哪些题考的内容相同、大题拆出的哪个点与哪道题重复；没有重叠时为“无”
 */
public record TopicGroup(String topic, List<Integer> questionIds, String overlaps) {
}
