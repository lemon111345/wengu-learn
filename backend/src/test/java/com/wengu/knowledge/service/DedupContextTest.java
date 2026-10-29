package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.CoveredQuestion;
import com.wengu.knowledge.dto.ResolvedCovered;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DedupContextTest {

    private static DedupContext.Existing existing(long id, String title) {
        return new DedupContext.Existing(id, title);
    }

    @Test
    void 已有知识点在前草稿条目在后连续编号() {
        DedupContext context = DedupContext.of(
                List.of(existing(11, "什么是 MVCC？"), existing(12, "Redis 为什么快？")),
                List.of(existing(301, "HashMap 的扩容机制是怎样的？")));

        assertThat(context.entries()).containsExactly(
                new DedupContext.Entry("E1", 11L, null, "什么是 MVCC？"),
                new DedupContext.Entry("E2", 12L, null, "Redis 为什么快？"),
                new DedupContext.Entry("E3", null, 301L, "HashMap 的扩容机制是怎样的？"));
    }

    @Test
    void 跳过空标题且编号不留空位() {
        DedupContext context = DedupContext.of(List.of(existing(1, " "), existing(2, " 什么是 MVCC？ ")), List.of());

        assertThat(context.entries()).containsExactly(new DedupContext.Entry("E1", 2L, null, "什么是 MVCC？"));
    }

    @Test
    void 格式为每行一个编号和标题且标题中的换行换成空格() {
        DedupContext context = DedupContext.of(List.of(existing(1, "什么是\nMVCC？")), List.of(existing(2, "Redis 为什么快？")));

        assertThat(context.format()).isEqualTo("[E1] 什么是 MVCC？\n[E2] Redis 为什么快？");
    }

    @Test
    void 按编号查找() {
        DedupContext context = DedupContext.of(List.of(existing(11, "什么是 MVCC？")), List.of());

        assertThat(context.find(" E1 ")).map(DedupContext.Entry::knowledgePointId).contains(11L);
        assertThat(context.find("E2")).isEmpty();
        assertThat(context.find(null)).isEmpty();
    }

    @Test
    void 没有任何标题时为空() {
        assertThat(DedupContext.of(List.of(), List.of()).isEmpty()).isTrue();
        assertThat(DedupContext.EMPTY.format()).isEmpty();
    }

    @Test
    void 把编号换回知识点id和条目id并记下标题() {
        DedupContext context = DedupContext.of(List.of(existing(11, "什么是 MVCC？")),
                List.of(existing(301, "HashMap 的扩容机制是怎样的？")));

        List<ResolvedCovered> resolved = context.resolve(List.of(
                new CoveredQuestion(5, List.of("E2", "E1"), "同一内容"),
                new CoveredQuestion(6, List.of("E9"), "编号不存在"),
                new CoveredQuestion(7, List.of("E1", "E9"), "部分编号不存在")));

        assertThat(resolved).containsExactly(
                new ResolvedCovered(5, List.of(11L), List.of(301L),
                        List.of("HashMap 的扩容机制是怎样的？", "什么是 MVCC？"), "同一内容"),
                new ResolvedCovered(7, List.of(11L), List.of(), List.of("什么是 MVCC？"), "部分编号不存在"));
        assertThat(context.resolve(null)).isEmpty();
    }
}
