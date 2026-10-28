package com.wengu.source.service;

import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmService;
import com.wengu.source.dto.SplitResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 调用模型，把用户粘贴的资料切分成题目。资料格式五花八门（一行一题、带答案、Markdown 表格、网页复制），
 * 规则写不全，所以交给模型；模型只输出题目原文，由 {@link SplitValidator} 核对确实出自原文。
 * <p>
 * prompt 按版本存放在 resources/prompts 下，调整时新增版本文件。
 */
@Service
public class QuestionSplitService {

    public static final String PROMPT_VERSION = "v1";

    /** 切分只是摘抄，不需要任何发挥。 */
    public static final double TEMPERATURE = 0.0;

    private static final String PROMPT_PATH = "prompts/question-split-" + PROMPT_VERSION + ".md";

    private final LlmService llmService;
    private final String systemPrompt;

    public QuestionSplitService(LlmService llmService) {
        this.llmService = llmService;
        this.systemPrompt = loadPrompt();
    }

    /**
     * 调用一次模型切分题目。返回的结果尚未校验，调用方用 {@link SplitValidator} 检查。
     *
     * @throws com.wengu.llm.service.LlmOutputParseException 模型返回无法解析时抛出
     */
    public LlmResult<SplitResult> split(String content) {
        return llmService.call(systemPrompt, formatContent(content), SplitResult.class, TEMPERATURE);
    }

    /** 用标签把资料包起来，资料里本身的标题、分隔线就不会和指令混在一起。 */
    static String formatContent(String content) {
        return "请从下面 <资料> 和 </资料> 之间的内容中找出面试题：\n\n<资料>\n" + content.strip() + "\n</资料>";
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 prompt 失败: " + PROMPT_PATH, e);
        }
    }
}
