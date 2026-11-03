package com.wengu.source.dto;

import java.util.List;

/** 模型根据讲解资料整理题目的输出，对应 prompts/question-generate-v1.md 的输出格式。 */
public record GenerateResult(List<GeneratedQuestion> questions) {
}
