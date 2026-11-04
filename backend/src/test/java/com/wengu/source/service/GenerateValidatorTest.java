package com.wengu.source.service;

import com.wengu.source.dto.GenerateResult;
import com.wengu.source.dto.GeneratedQuestion;
import com.wengu.source.dto.SplitQuestion;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 根据讲解资料整理出的题目的核对（#26）。纯函数。 */
class GenerateValidatorTest {

    private static final String CONTENT = """
            # 线程池笔记

            ## 核心参数

            corePoolSize 是核心线程数……

            ## **四种** 拒绝策略

            AbortPolicy 直接抛异常……
            """;

    private static GenerateResult result(GeneratedQuestion... questions) {
        return new GenerateResult(Arrays.asList(questions));
    }

    @Test
    void 出处在资料中时通过_忽略空白和Markdown符号() {
        assertThat(GenerateValidator.validate(CONTENT, result(
                new GeneratedQuestion("核心参数", "线程池有哪些核心参数？", List.of("为什么要给线程起名字？")),
                new GeneratedQuestion("四种拒绝策略", "四种拒绝策略怎么选？", List.of()),
                new GeneratedQuestion("无", "资料没有标题时写“无”？", null)))).isEmpty();
    }

    @Test
    void 出处不在资料中_或者为空时不通过() {
        assertThat(GenerateValidator.validate(CONTENT, result(
                new GeneratedQuestion("线程池的监控", "怎么监控线程池？", List.of()),
                new GeneratedQuestion(" ", "什么是核心线程？", List.of()))))
                .containsExactly("第 1 题的出处不在资料中：线程池的监控", "第 2 题没有写出处");
    }

    @Test
    void 题目或追问为空时不通过() {
        assertThat(GenerateValidator.validate(CONTENT, result(
                new GeneratedQuestion("核心参数", "", List.of("  ")),
                null)))
                .containsExactly("第 1 题为空", "第 1 题的第 1 条追问为空", "第 2 题为空");
        assertThat(GenerateValidator.validate(CONTENT, null)).containsExactly("缺少 questions");
    }

    @Test
    void 转成题目时去掉重复的主问题并去掉首尾空白() {
        List<SplitQuestion> questions = GenerateValidator.toQuestions(List.of(
                new GeneratedQuestion("核心参数", " 线程池有哪些核心参数？ ", List.of(" 追问 ")),
                new GeneratedQuestion("核心参数", "线程池有哪些**核心参数**？", List.of()),
                new GeneratedQuestion("四种拒绝策略", "拒绝策略怎么选？", null)));

        assertThat(questions).containsExactly(
                new SplitQuestion("线程池有哪些核心参数？", List.of("追问")),
                new SplitQuestion("拒绝策略怎么选？", List.of()));
    }
}
