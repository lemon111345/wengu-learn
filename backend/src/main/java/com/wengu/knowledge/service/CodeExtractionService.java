package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.CodeExtractionResult;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmService;
import com.wengu.source.dto.CodeFileContent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 从源码中抽取个人经历类知识点（#90，决策 050）。prompt 在 #89 实验的 v2 上加了得分点（决策 048）。
 * 返回的结果尚未校验，调用方用 {@link CodeExtractionValidator} 检查。
 */
@Service
public class CodeExtractionService {

    /** 存进 extraction_batch.prompt_version，和题目抽取的版本号区分开 */
    public static final String PROMPT_VERSION = "code-v3";

    private static final String PROMPT_PATH = "prompts/code-extraction-v3.md";

    private final LlmService llmService;
    private final String systemPrompt;

    public CodeExtractionService(LlmService llmService) {
        this.llmService = llmService;
        this.systemPrompt = loadPrompt();
    }

    /**
     * @throws com.wengu.llm.service.LlmOutputParseException 模型返回无法解析时抛出
     */
    public LlmResult<CodeExtractionResult> extract(List<CodeFileContent> files) {
        return llmService.call(systemPrompt, formatFiles(files), CodeExtractionResult.class,
                KnowledgeExtractionService.TEMPERATURE);
    }

    /** 用户消息：每个文件前面一行“===== 文件：路径 =====”，模型在 evidence.file 中原样引用这个路径。和 #89 实验的输入相同。 */
    static String formatFiles(List<CodeFileContent> files) {
        StringBuilder input = new StringBuilder("以下是学生项目中的 " + files.size() + " 个文件。\n");
        for (CodeFileContent file : files) {
            input.append("\n===== 文件：").append(file.path()).append(" =====\n").append(file.content());
            if (!file.content().endsWith("\n")) {
                input.append('\n');
            }
        }
        return input.toString();
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 prompt 失败: " + PROMPT_PATH, e);
        }
    }
}
