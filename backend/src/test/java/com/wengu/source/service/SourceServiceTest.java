package com.wengu.source.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.source.dto.ImportQuestionsRequest;
import com.wengu.source.dto.ImportedSource;
import com.wengu.source.dto.SourceQuestion;
import com.wengu.source.dto.SplitQuestion;
import com.wengu.source.dto.SplitResult;
import com.wengu.source.entity.Source;
import com.wengu.source.entity.SourceType;
import com.wengu.source.mapper.SourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用真实的 MySQL 和模拟的模型测试导入流程：重试、去重、上限，以及失败时不保存。 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SourceServiceTest {

    private static final String CONTENT = """
            1. 说说 HashMap 的原理？
            答：数组加链表……
            追问：为什么容量是 2 的幂？
            2. 什么是 MVCC？
            3. 说说 HashMap 的原理？
            """;

    @Autowired
    private SourceService sourceService;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private QuestionSplitService splitService;

    @BeforeEach
    void clean() {
        sourceMapper.delete(null);
    }

    private static LlmResult<SplitResult> result(SplitQuestion... questions) {
        return new LlmResult<>(new SplitResult(List.of(questions)), "{...}", "test-model", "stop", 100, 50);
    }

    private static SplitQuestion q(String text, String... followUps) {
        return new SplitQuestion(text, List.of(followUps));
    }

    private static ImportQuestionsRequest request(String content) {
        return new ImportQuestionsRequest("Java 基础", content);
    }

    @Test
    void 切分成功后保存原文和题目() {
        when(splitService.split(anyString())).thenReturn(result(
                q("说说 HashMap 的原理？", "为什么容量是 2 的幂？"),
                q("什么是 MVCC？"),
                q("说说 HashMap 的原理？", "为什么容量是 2 的幂？")));

        ImportedSource imported = sourceService.importQuestions(request(CONTENT));

        List<SourceQuestion> expected = List.of(
                new SourceQuestion(1, "说说 HashMap 的原理？", List.of("为什么容量是 2 的幂？")),
                new SourceQuestion(2, "什么是 MVCC？", List.of()));
        assertThat(imported.questions()).isEqualTo(expected);

        Source saved = sourceMapper.selectById(imported.id());
        assertThat(saved.getType()).isEqualTo(SourceType.QUESTION);
        assertThat(saved.getName()).isEqualTo("Java 基础");
        assertThat(saved.getContent()).isEqualTo(CONTENT.strip()).contains("答：数组加链表");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(List.of(jsonMapper.readValue(saved.getQuestions(), SourceQuestion[].class))).isEqualTo(expected);
    }

    @Test
    void 第一次校验不通过时重试一次() {
        when(splitService.split(anyString()))
                .thenReturn(result(q("请介绍 HashMap 的实现原理")))
                .thenReturn(result(q("什么是 MVCC？")));

        ImportedSource imported = sourceService.importQuestions(request(CONTENT));

        assertThat(imported.questions()).extracting(SourceQuestion::text).containsExactly("什么是 MVCC？");
        verify(splitService, times(2)).split(anyString());
    }

    @Test
    void 第一次无法解析时重试一次() {
        when(splitService.split(anyString()))
                .thenThrow(parseException())
                .thenReturn(result(q("什么是 MVCC？")));

        assertThat(sourceService.importQuestions(request(CONTENT)).questions()).hasSize(1);
        verify(splitService, times(2)).split(anyString());
    }

    @Test
    void 两次都失败时报错且不保存() {
        when(splitService.split(anyString()))
                .thenReturn(result(q("编造的题目？")))
                .thenThrow(parseException());

        assertThatThrownBy(() -> sourceService.importQuestions(request(CONTENT)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_SPLIT_FAILED));
        verify(splitService, times(2)).split(anyString());
        assertThat(sourceMapper.selectCount(null)).isZero();
    }

    @Test
    void 模型调用抛异常时不重试也不保存() {
        when(splitService.split(anyString())).thenThrow(new IllegalStateException("连接超时"));

        assertThatThrownBy(() -> sourceService.importQuestions(request(CONTENT)))
                .isInstanceOf(IllegalStateException.class);
        verify(splitService, times(1)).split(anyString());
        assertThat(sourceMapper.selectCount(null)).isZero();
    }

    @Test
    void 超过题数上限时报错() {
        List<SplitQuestion> questions = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        IntStream.rangeClosed(1, SourceService.MAX_QUESTIONS + 1).forEach(i -> {
            questions.add(q("第 " + i + " 题？"));
            content.append("第 ").append(i).append(" 题？\n");
        });
        when(splitService.split(anyString())).thenReturn(result(questions.toArray(SplitQuestion[]::new)));

        assertThatThrownBy(() -> sourceService.importQuestions(request(content.toString())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("识别到 " + (SourceService.MAX_QUESTIONS + 1) + " 道题")
                .hasMessageContaining("分成几批");
        assertThat(sourceMapper.selectCount(null)).isZero();
    }

    @Test
    void 没有识别到题目时报错() {
        when(splitService.split(anyString())).thenReturn(result());

        assertThatThrownBy(() -> sourceService.importQuestions(request("今天天气不错")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有识别到题目");
    }

    @Test
    void 原文超长时提示怎么办且不调用模型() {
        String content = "说说 HashMap 的原理？".repeat(SourceService.MAX_CONTENT_LENGTH / 10);

        assertThatThrownBy(() -> sourceService.importQuestions(request(content)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("删掉答案只保留题目")
                .hasMessageContaining("分成几批");
        verify(splitService, never()).split(anyString());
    }

    @Test
    void 名称或内容为空时报错() {
        assertThatThrownBy(() -> sourceService.importQuestions(new ImportQuestionsRequest(" ", CONTENT)))
                .hasMessageContaining("材料名称");
        assertThatThrownBy(() -> sourceService.importQuestions(new ImportQuestionsRequest("Java 基础", null)))
                .hasMessageContaining("请粘贴面试题");
        verify(splitService, never()).split(anyString());
    }

    @Test
    void 读取导入时保存的题目() {
        when(splitService.split(anyString())).thenReturn(result(
                q("说说 HashMap 的原理？", "为什么容量是 2 的幂？"),
                q("什么是 MVCC？")));
        ImportedSource imported = sourceService.importQuestions(request(CONTENT));

        assertThat(sourceService.getQuestions(imported.id())).isEqualTo(imported.questions());
    }

    @Test
    void 读取不存在或不属于当前用户的材料时报404() {
        Source others = new Source();
        others.setUserId(999L);
        others.setType(SourceType.QUESTION);
        others.setName("别人的题");
        others.setContent("什么是 MVCC？");
        others.setQuestions("[]");
        others.setCreatedAt(Instant.now());
        sourceMapper.insert(others);

        for (long id : new long[]{others.getId(), others.getId() + 1}) {
            assertThatThrownBy(() -> sourceService.getQuestions(id))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    private static LlmOutputParseException parseException() {
        return new LlmOutputParseException("返回内容不符合要求的 JSON 结构",
                new LlmResult<>(null, "不是 JSON", "test-model", "stop", 100, 5), new RuntimeException());
    }
}
