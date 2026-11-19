package com.wengu.source.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.source.dto.CodeFileContent;
import com.wengu.source.dto.GenerateResult;
import com.wengu.source.dto.ImportCodeRequest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学习材料的导入和读取。导入面试题时调用模型切分题目，切分结果和原文一起保存，供用户预览后发起抽取（见决策 011）。
 */
@Service
public class SourceService {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    /**
     * 原文长度上限。带答案的 10 万字资料单次切分实验 3 次都成功，耗时 61～94 秒（决策 011）；
     * 更长的输入不按长度切块，提示用户分批导入。
     */
    public static final int MAX_CONTENT_LENGTH = 100_000;

    /**
     * 一次导入的题数上限。抽取时按每批约 30 题分批（决策 013），200 题约 7 批，要十几分钟；
     * 切分实验中 200 题单次调用稳定（决策 011）。
     */
    public static final int MAX_QUESTIONS = 200;

    /** 第一次的结果解析失败或校验不通过时，再试一次。 */
    private static final int MAX_ATTEMPTS = 2;

    private static final int NAME_MAX_LENGTH = 255;

    /**
     * 源码的总字符数上限（决策 050）。#89 实验中 14.5 万字符 DeepSeek 一次抽取完、约 1 分钟；
     * 后端不知道用户的上下文长度（决策 045），用固定上限。
     */
    public static final int MAX_CODE_CHARS = 150_000;

    /** 源码一次最多导入的文件数（决策 050）。 */
    public static final int MAX_CODE_FILES = 300;

    /** 源码文件路径的长度上限，和 source_file.path 的列宽一致。 */
    public static final int MAX_PATH_LENGTH = 500;

    private final SourceMapper sourceMapper;
    private final SourceFileMapper sourceFileMapper;
    private final QuestionSplitService splitService;
    private final QuestionGenerateService generateService;
    private final Clock clock;
    private final JsonMapper jsonMapper;

    public SourceService(SourceMapper sourceMapper, SourceFileMapper sourceFileMapper, QuestionSplitService splitService,
                         QuestionGenerateService generateService, Clock clock, JsonMapper jsonMapper) {
        this.sourceMapper = sourceMapper;
        this.sourceFileMapper = sourceFileMapper;
        this.splitService = splitService;
        this.generateService = generateService;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
    }

    /** 导入粘贴的资料，见 {@link #importContent}。 */
    public ImportedSource importQuestions(long userId, ImportQuestionsRequest request) {
        return importContent(userId, request.name(), request.content(), request.type());
    }

    /**
     * 导入上传的文件（#26）：先提取文字（{@link DocumentTextExtractor}），之后和粘贴的原文一样处理，见 {@link #importContent}。
     *
     * @param filename 原始文件名，按扩展名判断格式；没填材料名称时用去掉扩展名的文件名
     * @param name     材料名称，可以为空
     */
    public ImportedSource importFile(long userId, String filename, byte[] bytes, String name, SourceType type) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
        String content = DocumentTextExtractor.extract(filename, bytes);
        String sourceName = name == null || name.isBlank() ? baseName(filename) : name;
        return importContent(userId, sourceName, content, type);
    }

    /** 去掉路径和扩展名的文件名，最长 {@link #NAME_MAX_LENGTH} 个字 */
    static String baseName(String filename) {
        String base = filename == null ? "" : filename.replaceAll(".*[/\\\\]", "");
        int dot = base.lastIndexOf('.');
        base = (dot > 0 ? base.substring(0, dot) : base).strip();
        return base.length() > NAME_MAX_LENGTH ? base.substring(0, NAME_MAX_LENGTH) : base;
    }

    /**
     * 导入资料：题目清单从原文中识别题目（决策 011），讲解资料根据内容整理题目（#26，决策 037），成功后才保存。
     * 失败时什么都不保存，用户改好原文重新提交即可。上传的文件提取出文字后也走这里。
     * <p>
     * 调用模型要几秒到几十秒，方法不加事务，避免长时间占用数据库连接；保存只有一条 insert，不需要事务。
     *
     * @param type 为 null 时按题目清单处理
     */
    public ImportedSource importContent(long userId, String rawName, String rawContent, SourceType type) {
        if (type == SourceType.CODE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "源码请用“源码”方式导入");
        }
        SourceType sourceType = type == null ? SourceType.QUESTION : type;
        boolean document = sourceType == SourceType.DOCUMENT;
        String name = rawName == null ? "" : rawName.strip();
        String content = rawContent == null ? "" : rawContent.strip();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写材料名称");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "材料名称不能超过 " + NAME_MAX_LENGTH + " 个字");
        }
        if (content.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, document ? "资料内容是空的" : "请粘贴面试题");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容有 " + content.length() + " 字，超过了 "
                    + MAX_CONTENT_LENGTH + " 字的上限。" + (document ? "可以分成几批导入" : "可以删掉答案只保留题目，或者分成几批导入"));
        }

        List<SplitQuestion> found = document ? generateWithRetry(content) : splitWithRetry(content);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    document ? "没有从资料中整理出题目，请检查内容" : "没有识别到题目，请检查粘贴的内容");
        }
        if (found.size() > MAX_QUESTIONS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, (document ? "整理出 " : "识别到 ") + found.size()
                    + " 道题，一次最多导入 " + MAX_QUESTIONS + " 道，请分成几批导入");
        }

        List<SourceQuestion> questions = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            questions.add(new SourceQuestion(i + 1, found.get(i).text(), found.get(i).followUps()));
        }

        Source source = new Source();
        source.setUserId(userId);
        source.setType(sourceType);
        source.setName(name);
        source.setContent(content);
        source.setQuestions(jsonMapper.writeValueAsString(questions));
        source.setCreatedAt(Instant.now(clock));
        sourceMapper.insert(source);
        return new ImportedSource(source.getId(), name, sourceType, questions);
    }

    /**
     * 导入源码（#90，决策 050）：校验后在一个事务里保存材料和全部文件，不调用模型。之后由 POST /api/drafts 发起抽取。
     * <p>
     * 路径的 \ 统一换成 /，内容的 \r\n 统一换成 \n 后再数字符，前端按同样的算法显示已选字符数。
     * 内容原样保存，不去掉首尾空白：行号要和用户自己的文件对得上。
     */
    @Transactional
    public ImportedCode importCode(long userId, ImportCodeRequest request) {
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写材料名称");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "材料名称不能超过 " + NAME_MAX_LENGTH + " 个字");
        }
        List<ImportCodeRequest.CodeFile> input = request.files() == null ? List.of() : request.files();
        if (input.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请至少勾选一个文件");
        }
        if (input.size() > MAX_CODE_FILES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "勾选了 " + input.size() + " 个文件，一次最多 "
                    + MAX_CODE_FILES + " 个。只选一条业务链路的文件效果更好");
        }

        Set<String> paths = new HashSet<>();
        List<SourceFile> files = new ArrayList<>();
        int total = 0;
        for (ImportCodeRequest.CodeFile file : input) {
            String path = file.path() == null ? "" : file.path().strip().replace('\\', '/');
            if (path.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "有文件没有路径");
            }
            if (path.length() > MAX_PATH_LENGTH) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件路径不能超过 " + MAX_PATH_LENGTH + " 个字符：" + path);
            }
            if (!paths.add(path)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件重复了：" + path);
            }
            String content = file.content() == null ? "" : file.content().replace("\r\n", "\n");
            if (content.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件是空的：" + path);
            }
            if (content.indexOf('\0') >= 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "看起来不是文本文件：" + path);
            }
            total += content.length();
            SourceFile entity = new SourceFile();
            entity.setUserId(userId);
            entity.setPath(path);
            entity.setContent(content);
            entity.setChars(content.length());
            entity.setFileOrder(files.size() + 1);
            files.add(entity);
        }
        if (total > MAX_CODE_CHARS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "勾选的文件共 " + total + " 字符，超过了 " + MAX_CODE_CHARS
                    + " 字符的上限。取消勾选一些文件，或者只选一条业务链路");
        }

        Source source = new Source();
        source.setUserId(userId);
        source.setType(SourceType.CODE);
        source.setName(name);
        // 文件清单，一行一个路径，供以后“这次导入了哪些文件”显示；文件内容在 source_file
        source.setContent(files.stream().map(SourceFile::getPath).collect(Collectors.joining("\n")));
        source.setCreatedAt(Instant.now(clock));
        sourceMapper.insert(source);
        for (SourceFile file : files) {
            file.setSourceId(source.getId());
            sourceFileMapper.insert(file);
        }
        return new ImportedCode(source.getId(), name, SourceType.CODE, files.stream()
                .map(f -> new ImportedCode.FileSummary(f.getPath(), f.getChars()))
                .toList());
    }

    /**
     * 读取导入时切分出的题目，供抽取使用。题号只在同一份材料内唯一。
     *
     * @throws BusinessException 材料不存在或不属于这个用户时为 NOT_FOUND；材料没有题目时为 BAD_REQUEST
     */
    public List<SourceQuestion> getQuestions(long userId, long sourceId) {
        Source source = sourceMapper.selectById(sourceId);
        if (source == null || source.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "材料不存在");
        }
        if (source.getQuestions() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这份材料没有题目");
        }
        return List.of(jsonMapper.readValue(source.getQuestions(), SourceQuestion[].class));
    }

    /**
     * 材料的类型，发起抽取时用来决定按题目抽还是按源码抽（#90）。只查 id、user_id 和 type 三列，不读原文。
     *
     * @throws BusinessException 材料不存在或不属于这个用户时为 NOT_FOUND
     */
    public SourceType getType(long userId, long sourceId) {
        Source source = sourceMapper.selectOne(new LambdaQueryWrapper<Source>()
                .select(Source::getId, Source::getUserId, Source::getType)
                .eq(Source::getId, sourceId));
        if (source == null || source.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "材料不存在");
        }
        return source.getType();
    }

    /**
     * 源码导入的全部文件，按导入顺序（#90）。不是源码材料、或者不属于这个用户时为空列表，调用方先用 {@link #getType} 判断。
     */
    public List<CodeFileContent> getCodeFiles(long userId, long sourceId) {
        return sourceFileMapper.selectList(new LambdaQueryWrapper<SourceFile>()
                        .eq(SourceFile::getSourceId, sourceId)
                        .eq(SourceFile::getUserId, userId)
                        .orderByAsc(SourceFile::getFileOrder))
                .stream()
                .map(f -> new CodeFileContent(f.getPath(), f.getContent()))
                .toList();
    }

    /**
     * 源码导入中指定路径的文件，学习时取知识点依据的那几个（#90）。按导入顺序返回；不存在的路径、
     * 或者材料不属于这个用户时不在结果中。
     */
    public List<CodeFileContent> getCodeFiles(long userId, long sourceId, Collection<String> paths) {
        if (paths.isEmpty()) {
            return List.of();
        }
        return sourceFileMapper.selectList(new LambdaQueryWrapper<SourceFile>()
                        .eq(SourceFile::getSourceId, sourceId)
                        .eq(SourceFile::getUserId, userId)
                        .in(SourceFile::getPath, paths)
                        .orderByAsc(SourceFile::getFileOrder))
                .stream()
                .map(f -> new CodeFileContent(f.getPath(), f.getContent()))
                .toList();
    }

    /**
     * 按 id 查材料名称，供草稿列表显示。只查 id 和 name 两列，不读原文；不存在或不属于该用户的 id 不在结果中。
     */
    public Map<Long, String> getNames(long userId, Collection<Long> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return sourceMapper.selectList(new LambdaQueryWrapper<Source>()
                        .select(Source::getId, Source::getName)
                        .eq(Source::getUserId, userId)
                        .in(Source::getId, sourceIds))
                .stream()
                .collect(Collectors.toMap(Source::getId, Source::getName));
    }

    /**
     * 调用模型切分，返回去重后的题目。只有输出解析失败或校验不通过时才重试；
     * 模型调用本身抛出的异常（网络错误、Key 不对）重试也没用，直接抛出：它是分好类的 LlmException，
     * 由全局异常处理器返回对应的状态码和提示（决策 032）。
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

    /** 根据讲解资料整理题目，重试规则同 {@link #splitWithRetry}；核对见 {@link GenerateValidator}。 */
    private List<SplitQuestion> generateWithRetry(String content) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                LlmResult<GenerateResult> result = generateService.generate(content);
                List<String> problems = GenerateValidator.validate(content, result.content());
                if (problems.isEmpty()) {
                    return GenerateValidator.toQuestions(result.content().questions());
                }
                log.warn("整理题目校验不通过（第 {} 次）: model={}, problems={}", attempt, result.model(), problems);
            } catch (LlmOutputParseException e) {
                log.warn("整理题目结果无法解析（第 {} 次）: {}, finishReason={}",
                        attempt, e.getMessage(), e.getResult().finishReason());
            }
        }
        throw new BusinessException(ErrorCode.QUESTION_GENERATE_FAILED);
    }
}
