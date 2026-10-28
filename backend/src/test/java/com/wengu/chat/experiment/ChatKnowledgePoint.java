package com.wengu.chat.experiment;

import com.wengu.knowledge.dto.KnowledgePointType;

import java.util.List;

/**
 * 实验用的知识点，从 data-private/samples/chat-kps.json 读取（取自 M0 v5 的抽取结果）。
 *
 * @param title           知识点标题
 * @param type            知识点类型
 * @param summary         抽取时生成的要点
 * @param sourceQuestions 来源面试题原文
 * @param note            作者学习前的自述（如“很熟”“完全不会”），只记入结果，不发给模型
 */
record ChatKnowledgePoint(String title, KnowledgePointType type, String summary, List<String> sourceQuestions,
                          String note) {
}
