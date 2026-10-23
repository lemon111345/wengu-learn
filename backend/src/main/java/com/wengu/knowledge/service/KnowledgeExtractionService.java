package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 从一批面试题中抽取知识点。所有题目一次发给模型，模型才能跨题合并相同的知识点。
 * <p>
 * prompt 放在 resources/prompts 下，文件名带版本号；调整 prompt 时新增版本文件，便于比较不同版本的效果。
 */
@Service
public class KnowledgeExtractionService {

    public static final String PROMPT_VERSION = "v3";

    /** 抽取要的是稳定，不是多样：默认 temperature（DeepSeek 为 1.0）下，同一 prompt 两次结果差别很大。 */
    public static final double TEMPERATURE = 0.2;

    private static final String PROMPT_PATH = "prompts/knowledge-extraction-" + PROMPT_VERSION + ".md";

    private final LlmService llmService;
    private final String systemPrompt;

    public KnowledgeExtractionService(LlmService llmService) {
        this.llmService = llmService;
        this.systemPrompt = loadPrompt();
    }

    /**
     * 抽取知识点。返回的结果尚未校验，调用方用 {@link ExtractionValidator} 检查。
     *
     * @throws com.wengu.llm.service.LlmOutputParseException 模型返回无法解析时抛出
     */
    public LlmResult<ExtractionResult> extract(List<ExtractionQuestion> questions) {
        return llmService.call(systemPrompt, formatQuestions(questions), ExtractionResult.class, TEMPERATURE);
    }

    /** 题目列表格式：每行“[题号] 题目”，题号即模型在 sourceQuestionIds 中引用的数字。 */
    static String formatQuestions(List<ExtractionQuestion> questions) {
        String lines = questions.stream()
                .map(q -> "[" + q.id() + "] " + q.text().strip())
                .collect(Collectors.joining("\n"));
        return "以下是 " + questions.size() + " 道面试题，方括号内是题号：\n\n" + lines;
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 prompt 失败: " + PROMPT_PATH, e);
        }
    }
}
