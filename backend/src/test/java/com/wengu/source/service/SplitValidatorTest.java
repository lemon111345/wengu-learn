package com.wengu.source.service;

import com.wengu.source.dto.SplitQuestion;
import com.wengu.source.dto.SplitResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SplitValidatorTest {

    private static SplitQuestion q(String text, String... followUps) {
        return new SplitQuestion(text, List.of(followUps));
    }

    private static List<String> validate(String content, SplitQuestion... questions) {
        return SplitValidator.validate(content, new SplitResult(Arrays.asList(questions)));
    }

    @Test
    void 逐字出现在原文中的题目通过() {
        String content = """
                1. TCP 为什么需要三次握手？
                答：防止已失效的连接请求……
                追问：两次握手会有什么问题？
                2. 说说 TIME_WAIT 状态
                """;

        assertThat(validate(content, q("TCP 为什么需要三次握手？", "两次握手会有什么问题？"), q("说说 TIME_WAIT 状态"))).isEmpty();
    }

    @Test
    void 原文跨行或模型改了空白都不算失败() {
        String content = "TCP 为什么需要\n三次  握手？";

        assertThat(validate(content, q("TCP为什么需要三次握手？"))).isEmpty();
    }

    @Test
    void 模型去掉Markdown符号不算失败() {
        String content = """
                | 1 | **HashMap** 的 `put` 过程是怎样的？ | 中等 |
                > 说说 synchronized_锁升级
                ## 什么是 MVCC？
                ### SELECT \\* 会导致索引失效吗？
                """;

        assertThat(validate(content,
                q("HashMap 的 put 过程是怎样的？"),
                q("说说 synchronized锁升级"),
                q("什么是 MVCC？"),
                q("SELECT * 会导致索引失效吗？"))).isEmpty();
    }

    @Test
    void 全角半角不同不算失败() {
        String content = "Ｒｅｄｉｓ 为什么快？（单线程）";

        assertThat(validate(content, q("Redis 为什么快?(单线程)"))).isEmpty();
    }

    @Test
    void 改写的题目不通过() {
        String content = "说说 HashMap 的原理？";

        assertThat(validate(content, q("请介绍 HashMap 的实现原理")))
                .containsExactly("第 1 题不在原文中：请介绍 HashMap 的实现原理");
    }

    @Test
    void 追问逐条校验() {
        String content = "说说 HashMap 的原理？追问：为什么容量是 2 的幂？";

        assertThat(validate(content, q("说说 HashMap 的原理？", "为什么容量是 2 的幂？", "为什么线程不安全？")))
                .containsExactly("第 1 题的第 2 条追问不在原文中：为什么线程不安全？");
    }

    @Test
    void 空题和空追问不通过() {
        String content = "说说 HashMap 的原理？";

        assertThat(validate(content, q("  "), q("说说 HashMap 的原理？", ""), null))
                .containsExactly("第 1 题为空", "第 2 题的第 1 条追问为空", "第 3 题为空");
    }

    @Test
    void 缺少questions不通过() {
        assertThat(SplitValidator.validate("任意", new SplitResult(null))).containsExactly("缺少 questions");
        assertThat(SplitValidator.validate("任意", null)).containsExactly("缺少 questions");
    }

    @Test
    void 完全相同的重复题去重并保留第一次出现的() {
        List<SplitQuestion> result = SplitValidator.deduplicate(List.of(
                q("说说 HashMap 的原理？", "为什么容量是 2 的幂？"),
                q("什么是 MVCC？"),
                q(" 说说 **HashMap** 的原理? ", "为什么容量是 2 的幂？")));

        assertThat(result).containsExactly(
                q("说说 HashMap 的原理？", "为什么容量是 2 的幂？"),
                q("什么是 MVCC？"));
    }

    @Test
    void 主问题相同但追问不同的不去重() {
        List<SplitQuestion> result = SplitValidator.deduplicate(List.of(
                q("说说 HashMap 的原理？", "为什么容量是 2 的幂？"),
                q("说说 HashMap 的原理？")));

        assertThat(result).hasSize(2);
    }

    @Test
    void 去重时补齐为null的追问并去掉首尾空白() {
        List<SplitQuestion> result = SplitValidator.deduplicate(List.of(new SplitQuestion("  什么是 MVCC？ ", null)));

        assertThat(result).containsExactly(q("什么是 MVCC？"));
    }
}
