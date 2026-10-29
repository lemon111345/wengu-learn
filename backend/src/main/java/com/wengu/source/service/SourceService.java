package com.wengu.source.service;

import com.wengu.common.context.CurrentUser;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 学习材料的导入和读取。导入面试题时调用模型切分题目，切分结果和原文一起保存，供用户预览后发起抽取（见决策 011）。
 */
@Service
public class SourceService {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    /** 原文长度上限。更长的输入按长度切块留给 #16。 */
    public static final int MAX_CONTENT_LENGTH = 30_000;

    /** 一次导入的题数上限，和单批抽取的上限一致。 */
    public static final int MAX_QUESTIONS = 30;

    /** 第一次的结果解析失败或校验不通过时，再试一次。 */
    private static final int MAX_ATTEMPTS = 2;

    private static final int NAME_MAX_LENGTH = 255;

    private final SourceMapper sourceMapper;
    private final QuestionSplitService splitService;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final JsonMapper jsonMapper;

    public SourceService(SourceMapper sourceMapper, QuestionSplitService splitService, CurrentUser currentUser,
                         Clock clock, JsonMapper jsonMapper) {
        this.sourceMapper = sourceMapper;
        this.splitService = splitService;
        this.currentUser = currentUser;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 导入面试题：切分题目，切分成功后才保存。失败时什么都不保存，用户改好原文重新提交即可。
     * <p>
     * 调用模型要几秒到十几秒，方法不加事务，避免长时间占用数据库连接；保存只有一条 insert，不需要事务。
     */
    public ImportedSource importQuestions(ImportQuestionsRequest request) {
        String name = request.name() == null ? "" : request.name().strip();
        String content = request.content() == null ? "" : request.content().strip();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写材料名称");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "材料名称不能超过 " + NAME_MAX_LENGTH + " 个字");
        }
        if (content.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请粘贴面试题");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容有 " + content.length() + " 字，超过了 "
                    + MAX_CONTENT_LENGTH + " 字的上限。可以删掉答案只保留题目，或者分成几批导入");
        }

        List<SplitQuestion> split = splitWithRetry(content);
        if (split.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "没有识别到题目，请检查粘贴的内容");
        }
        if (split.size() > MAX_QUESTIONS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "识别到 " + split.size() + " 道题，一次最多导入 "
                    + MAX_QUESTIONS + " 道，请分成几批导入");
        }

        List<SourceQuestion> questions = new ArrayList<>();
        for (int i = 0; i < split.size(); i++) {
            questions.add(new SourceQuestion(i + 1, split.get(i).text(), split.get(i).followUps()));
        }

        Source source = new Source();
        source.setUserId(currentUser.id());
        source.setType(SourceType.QUESTION);
        source.setName(name);
        source.setContent(content);
        source.setQuestions(jsonMapper.writeValueAsString(questions));
        source.setCreatedAt(Instant.now(clock));
        sourceMapper.insert(source);
        return new ImportedSource(source.getId(), name, questions);
    }

    /**
     * 读取导入时切分出的题目，供抽取使用。题号只在同一份材料内唯一。
     *
     * @throws BusinessException 材料不存在或不属于当前用户时为 NOT_FOUND；材料没有题目时为 BAD_REQUEST
     */
    public List<SourceQuestion> getQuestions(long sourceId) {
        Source source = sourceMapper.selectById(sourceId);
        if (source == null || source.getUserId() != currentUser.id()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "材料不存在");
        }
        if (source.getQuestions() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这份材料没有题目");
        }
        return List.of(jsonMapper.readValue(source.getQuestions(), SourceQuestion[].class));
    }

    /**
     * 调用模型切分，返回去重后的题目。只有输出解析失败或校验不通过时才重试；
     * 模型调用本身抛出的异常（网络错误、Key 不对）重试也没用，直接抛出。
     */
    private List<SplitQuestion> splitWithRetry(String content) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                LlmResult<SplitResult> result = splitService.split(content);
                List<String> problems = SplitValidator.validate(content, result.content());
                if (problems.isEmpty()) {
                    return SplitValidator.deduplicate(result.content().questions());
                }
                log.warn("题目切分校验不通过（第 {} 次）: model={}, problems={}", attempt, result.model(), problems);
            } catch (LlmOutputParseException e) {
                log.warn("题目切分结果无法解析（第 {} 次）: {}, finishReason={}",
                        attempt, e.getMessage(), e.getResult().finishReason());
            }
        }
        throw new BusinessException(ErrorCode.QUESTION_SPLIT_FAILED);
    }
}
