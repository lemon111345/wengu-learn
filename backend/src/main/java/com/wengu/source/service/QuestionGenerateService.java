package com.wengu.source.service;

import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmService;
import com.wengu.source.dto.GenerateResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 调用模型，根据讲解资料（笔记、文章）整理出题目（#26，决策 037）。这类资料里没有现成的题目，
 * {@link QuestionSplitService} 找不出来；整理出的题目由 {@link GenerateValidator} 核对出处确实在资料中。
 * <p>
 * prompt 按版本存放在 resources/prompts 下，调整时新增版本文件。
 */
@Service
public class QuestionGenerateService {

    public static final String PROMPT_VERSION = "v1";

    /** 出题要稳定，和抽取一致；实验中 3 次结果只在合并方式上有差别 */
    public static final double TEMPERATURE = 0.2;

    private static final String PROMPT_PATH = "prompts/question-generate-" + PROMPT_VERSION + ".md";

    private final LlmService llmService;
    private final String systemPrompt;

    public QuestionGenerateService(LlmService llmService) {
        this.llmService = llmService;
        this.systemPrompt = loadPrompt();
    }

    /**
     * 调用一次模型整理题目。返回的结果尚未校验，调用方用 {@link GenerateValidator} 检查。
     *
     * @throws com.wengu.llm.service.LlmOutputParseException 模型返回无法解析时抛出
     */
    public LlmResult<GenerateResult> generate(String content) {
        return llmService.call(systemPrompt, formatContent(content), GenerateResult.class, TEMPERATURE);
    }

    /** 用标签把资料包起来，资料里本身的标题、分隔线就不会和指令混在一起（和切分一致） */
    static String formatContent(String content) {
        return "请根据下面 <资料> 和 </资料> 之间的内容整理面试题：\n\n<资料>\n" + content.strip() + "\n</资料>";
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 prompt 失败: " + PROMPT_PATH, e);
        }
    }
}
