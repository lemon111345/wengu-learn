package com.wengu.source.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmException;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.source.dto.GenerateResult;
import com.wengu.source.dto.GeneratedQuestion;
import com.wengu.source.dto.ImportCodeRequest;
import com.wengu.source.dto.ImportCodeRequest.CodeFile;
import com.wengu.source.dto.ImportQuestionsRequest;
import com.wengu.source.dto.ImportedCode;
import com.wengu.source.dto.ImportedSource;
import com.wengu.source.dto.SourceQuestion;
import com.wengu.source.dto.SplitQuestion;
import com.wengu.source.dto.SplitResult;
import com.wengu.source.entity.Source;
import com.wengu.source.entity.SourceFile;
import com.wengu.source.entity.SourceType;
import com.wengu.source.mapper.SourceFileMapper;
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

    /** 测试中的当前用户。 */
    private static final long USER_ID = 1L;

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
    private SourceFileMapper sourceFileMapper;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private QuestionSplitService splitService;

    @BeforeEach
    void clean() {
        sourceFileMapper.delete(null);
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

        ImportedSource imported = sourceService.importQuestions(USER_ID, request(CONTENT));

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

        ImportedSource imported = sourceService.importQuestions(USER_ID, request(CONTENT));

        assertThat(imported.questions()).extracting(SourceQuestion::text).containsExactly("什么是 MVCC？");
        verify(splitService, times(2)).split(anyString());
    }

    @Test
    void 第一次无法解析时重试一次() {
        when(splitService.split(anyString()))
                .thenThrow(parseException())
                .thenReturn(result(q("什么是 MVCC？")));

        assertThat(sourceService.importQuestions(USER_ID, request(CONTENT)).questions()).hasSize(1);
        verify(splitService, times(2)).split(anyString());
    }

    @Test
    void 两次都失败时报错且不保存() {
        when(splitService.split(anyString()))
                .thenReturn(result(q("编造的题目？")))
                .thenThrow(parseException());

        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID, request(CONTENT)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_SPLIT_FAILED));
        verify(splitService, times(2)).split(anyString());
        assertThat(sourceMapper.selectCount(null)).isZero();
    }

    @Test
    void 模型调用抛异常时不重试也不保存() {
        when(splitService.split(anyString())).thenThrow(new IllegalStateException("连接超时"));

        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID, request(CONTENT)))
                .isInstanceOf(IllegalStateException.class);
        verify(splitService, times(1)).split(anyString());
        assertThat(sourceMapper.selectCount(null)).isZero();
    }

    @Test
    void 模型调用失败时原样抛出分好类的错误() {
        // LlmService 已经分好类（决策 032），由全局异常处理器返回 502 和提示，不再是 500
        when(splitService.split(anyString())).thenThrow(new LlmException(ErrorCode.LLM_AUTH_FAILED));

        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID, request(CONTENT)))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_AUTH_FAILED));
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

        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID, request(content.toString())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("识别到 " + (SourceService.MAX_QUESTIONS + 1) + " 道题")
                .hasMessageContaining("分成几批");
        assertThat(sourceMapper.selectCount(null)).isZero();
    }

    @Test
    void 没有识别到题目时报错() {
        when(splitService.split(anyString())).thenReturn(result());

        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID, request("今天天气不错")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有识别到题目");
    }

    @Test
    void 原文超长时提示怎么办且不调用模型() {
        String content = "说说 HashMap 的原理？".repeat(SourceService.MAX_CONTENT_LENGTH / 10);

        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID, request(content)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("删掉答案只保留题目")
                .hasMessageContaining("分成几批");
        verify(splitService, never()).split(anyString());
    }

    @Test
    void 名称或内容为空时报错() {
        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID, new ImportQuestionsRequest(" ", CONTENT)))
                .hasMessageContaining("材料名称");
        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID, new ImportQuestionsRequest("Java 基础", null)))
                .hasMessageContaining("请粘贴面试题");
        verify(splitService, never()).split(anyString());
    }

    @Test
    void 读取导入时保存的题目() {
        when(splitService.split(anyString())).thenReturn(result(
                q("说说 HashMap 的原理？", "为什么容量是 2 的幂？"),
                q("什么是 MVCC？")));
        ImportedSource imported = sourceService.importQuestions(USER_ID, request(CONTENT));

        assertThat(sourceService.getQuestions(USER_ID, imported.id())).isEqualTo(imported.questions());
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
            assertThatThrownBy(() -> sourceService.getQuestions(USER_ID, id))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @MockitoBean
    private QuestionGenerateService generateService;

    private static final String NOTE = """
            # 线程池笔记

            ## 核心参数

            corePoolSize 是核心线程数……

            ## 拒绝策略

            AbortPolicy 直接抛异常……
            """;

    private static LlmResult<GenerateResult> generated(GeneratedQuestion... questions) {
        return new LlmResult<>(new GenerateResult(List.of(questions)), "{...}", "test-model", "stop", 100, 50);
    }

    @Test
    void 讲解资料根据内容整理题目_保存为文档类型且不调用识别() {
        when(generateService.generate(anyString())).thenReturn(generated(
                new GeneratedQuestion("核心参数", "线程池有哪些核心参数？", List.of("无界队列有什么问题？")),
                new GeneratedQuestion("拒绝策略", "四种拒绝策略怎么选？", List.of())));

        ImportedSource imported = sourceService.importQuestions(USER_ID,
                new ImportQuestionsRequest("线程池笔记", NOTE, SourceType.DOCUMENT));

        assertThat(imported.type()).isEqualTo(SourceType.DOCUMENT);
        assertThat(imported.questions()).containsExactly(
                new SourceQuestion(1, "线程池有哪些核心参数？", List.of("无界队列有什么问题？")),
                new SourceQuestion(2, "四种拒绝策略怎么选？", List.of()));
        Source saved = sourceMapper.selectById(imported.id());
        assertThat(saved.getType()).isEqualTo(SourceType.DOCUMENT);
        assertThat(saved.getContent()).isEqualTo(NOTE.strip());
        verify(splitService, never()).split(anyString());
    }

    @Test
    void 整理的题目出处不在资料中时重试_两次都不行时报错且不保存() {
        when(generateService.generate(anyString()))
                .thenReturn(generated(new GeneratedQuestion("线程池监控", "怎么监控线程池？", List.of())))
                .thenThrow(parseException());

        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID,
                new ImportQuestionsRequest("线程池笔记", NOTE, SourceType.DOCUMENT)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_GENERATE_FAILED));
        verify(generateService, times(2)).generate(anyString());
        assertThat(sourceMapper.selectCount(null)).isZero();
    }

    @Test
    void 不传类型时按题目清单识别() {
        when(splitService.split(anyString())).thenReturn(result(q("什么是 MVCC？")));

        ImportedSource imported = sourceService.importQuestions(USER_ID, request(CONTENT));

        assertThat(imported.type()).isEqualTo(SourceType.QUESTION);
        verify(generateService, never()).generate(anyString());
    }

    @Test
    void 导入源码_保存材料和文件_路径统一成斜杠_换行统一后数字符() {
        ImportedCode imported = sourceService.importCode(USER_ID, new ImportCodeRequest(" wengu-learn ", List.of(
                new CodeFile("chat\\ChatService.java", "class ChatService {\r\n}\r\n"),
                new CodeFile("chat/RatingService.java", "class RatingService {}\n"))));

        assertThat(imported.name()).isEqualTo("wengu-learn");
        assertThat(imported.type()).isEqualTo(SourceType.CODE);
        assertThat(imported.files()).containsExactly(
                new ImportedCode.FileSummary("chat/ChatService.java", 22),
                new ImportedCode.FileSummary("chat/RatingService.java", 23));

        Source source = sourceMapper.selectById(imported.id());
        assertThat(source.getContent()).isEqualTo("chat/ChatService.java\nchat/RatingService.java");
        assertThat(source.getQuestions()).isNull();
        List<SourceFile> files = sourceFileMapper.selectList(new LambdaQueryWrapper<SourceFile>()
                .eq(SourceFile::getSourceId, imported.id()).orderByAsc(SourceFile::getFileOrder));
        assertThat(files).extracting(SourceFile::getFileOrder).containsExactly(1, 2);
        assertThat(files.getFirst().getContent()).isEqualTo("class ChatService {\n}\n");
        assertThat(files.getFirst().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void 源码按统一换行后的字符数算上限_刚好15万可以_多1个不行且不保存() {
        String exactly = "a\r\n".repeat(SourceService.MAX_CODE_CHARS / 2);

        sourceService.importCode(USER_ID, new ImportCodeRequest("刚好", List.of(new CodeFile("A.java", exactly))));

        assertRejected(new ImportCodeRequest("超出", List.of(new CodeFile("A.java", exactly), new CodeFile("B.java", "b"))),
                "共 150001 字符");
        assertThat(sourceMapper.selectCount(null)).isOne();
    }

    @Test
    void 源码的校验_都不保存() {
        assertRejected(new ImportCodeRequest("项目", List.of()), "请至少勾选一个文件");
        assertRejected(new ImportCodeRequest(" ", List.of(new CodeFile("A.java", "a"))), "请填写材料名称");
        assertRejected(new ImportCodeRequest("项目", List.of(new CodeFile("a\\A.java", "a"), new CodeFile("a/A.java", "b"))),
                "文件重复了：a/A.java");
        assertRejected(new ImportCodeRequest("项目", List.of(new CodeFile("Empty.java", " \n"))), "文件是空的：Empty.java");
        assertRejected(new ImportCodeRequest("项目", List.of(new CodeFile("logo.png", "PNG\0\0"))), "看起来不是文本文件：logo.png");
        assertRejected(new ImportCodeRequest("项目", List.of(new CodeFile("x".repeat(501), "a"))), "文件路径不能超过 500 个字符");
        assertRejected(new ImportCodeRequest("项目", IntStream.rangeClosed(1, 301)
                .mapToObj(i -> new CodeFile(i + ".java", "a")).toList()), "勾选了 301 个文件");
        assertThat(sourceMapper.selectCount(null)).isZero();
        assertThat(sourceFileMapper.selectCount(null)).isZero();
    }

    @Test
    void 源码不能走粘贴导入() {
        assertThatThrownBy(() -> sourceService.importQuestions(USER_ID,
                new ImportQuestionsRequest("项目", "class A {}", SourceType.CODE)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("源码请用“源码”方式导入");
        verify(splitService, never()).split(anyString());
    }

    private void assertRejected(ImportCodeRequest request, String message) {
        assertThatThrownBy(() -> sourceService.importCode(USER_ID, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).contains(message);
                });
    }

    private static LlmOutputParseException parseException() {
        return new LlmOutputParseException("返回内容不符合要求的 JSON 结构",
                new LlmResult<>(null, "不是 JSON", "test-model", "stop", 100, 5), new RuntimeException());
    }
}
