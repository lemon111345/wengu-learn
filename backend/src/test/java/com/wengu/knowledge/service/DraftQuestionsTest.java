package com.wengu.knowledge.service;

import com.wengu.common.exception.BusinessException;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.source.dto.SourceQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 发起抽取前的纯计算：按勾选筛题，转成抽取 prompt 的输入，分批。 */
class DraftQuestionsTest {

    private static final List<SourceQuestion> ALL = List.of(
            new SourceQuestion(1, "说说 HashMap 的原理？", List.of()),
            new SourceQuestion(2, "什么是 MVCC？", List.of()),
            new SourceQuestion(3, "Redis 为什么快？", List.of()));

    @Test
    void 不传题号时选全部() {
        assertThat(ExtractionDraftService.select(ALL, null)).isEqualTo(ALL);
    }

    @Test
    void 按勾选筛题并保持原文顺序() {
        assertThat(ExtractionDraftService.select(ALL, List.of(3, 1, 3)))
                .extracting(SourceQuestion::id).containsExactly(1, 3);
    }

    @Test
    void 勾选为空或题号不存在时报错() {
        assertThatThrownBy(() -> ExtractionDraftService.select(ALL, List.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少选择一道题");
        assertThatThrownBy(() -> ExtractionDraftService.select(ALL, List.of(2, 9, 7)))
                .isInstanceOf(BusinessException.class).hasMessage("题号不存在：[7, 9]");
    }

    @Test
    void 没有追问时只有题目() {
        assertThat(ExtractionDraftService.toExtractionQuestion(new SourceQuestion(5, " 什么是 MVCC？ ", List.of())))
                .isEqualTo(new ExtractionQuestion(5, "什么是 MVCC？"));
    }

    @Test
    void 追问拼在同一行() {
        SourceQuestion q = new SourceQuestion(3, "说说 HashMap 的原理？",
                List.of("为什么容量是 2 的幂？", "1.8 为什么引入红黑树？"));

        assertThat(ExtractionDraftService.toExtractionQuestion(q)).isEqualTo(new ExtractionQuestion(3,
                "说说 HashMap 的原理？（追问：为什么容量是 2 的幂？；1.8 为什么引入红黑树？）"));
    }

    @Test
    void 题目中的换行换成空格() {
        SourceQuestion q = new SourceQuestion(1, "JVM 的内存区域\n是怎么划分的？", List.of("堆和栈\r\n有什么区别？"));

        assertThat(ExtractionDraftService.toExtractionQuestion(q).text())
                .isEqualTo("JVM 的内存区域 是怎么划分的？（追问：堆和栈 有什么区别？）");
    }

    private static List<Integer> sizes(int count) {
        List<Integer> items = IntStream.rangeClosed(1, count).boxed().toList();
        return ExtractionDraftService.partition(items, 30).stream().map(List::size).toList();
    }

    @Test
    void 不超过一批时不拆() {
        assertThat(sizes(1)).containsExactly(1);
        assertThat(sizes(30)).containsExactly(30);
    }

    @Test
    void 超过一批时各批题数尽量相等() {
        assertThat(sizes(31)).containsExactly(16, 15);
        assertThat(sizes(60)).containsExactly(30, 30);
        assertThat(sizes(61)).containsExactly(21, 20, 20);
        assertThat(sizes(200)).containsExactly(29, 29, 29, 29, 28, 28, 28);
    }

    @Test
    void 分批保持原文顺序() {
        List<Integer> items = IntStream.rangeClosed(1, 31).boxed().toList();

        List<List<Integer>> batches = ExtractionDraftService.partition(items, 30);

        assertThat(batches.get(0)).first().isEqualTo(1);
        assertThat(batches.get(0)).last().isEqualTo(16);
        assertThat(batches.get(1)).first().isEqualTo(17);
        assertThat(batches.stream().flatMap(List::stream).toList()).isEqualTo(items);
    }
}
