package com.wengu.knowledge.service;

import com.wengu.common.exception.BusinessException;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.source.dto.SourceQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 发起抽取前的两步纯计算：按勾选筛题，转成抽取 prompt 的输入。 */
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
}
