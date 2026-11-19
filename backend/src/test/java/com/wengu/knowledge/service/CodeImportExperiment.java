package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.TestcontainersConfiguration;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftItemView;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.entity.ExtractionBatch;
import com.wengu.knowledge.mapper.ExtractionBatchMapper;
import com.wengu.source.dto.ImportCodeRequest;
import com.wengu.source.dto.ImportCodeRequest.CodeFile;
import com.wengu.source.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 源码导入的真实抽取（#90）：在临时数据库里导入本项目的评分链路（#89 实验的 A 组，6 个文件），
 * 走正式的导入 → 发起抽取 → 后台执行，用真实的模型抽一次，看 code-extraction-v3 拆出的得分点合不合适。
 * <p>
 * 会真实调用模型、产生费用（1～2 次调用），默认跳过。不连开发库。在 backend 目录下：
 * <pre>
 * .\mvnw.cmd test "-Dtest=CodeImportExperiment" "-Dllm.experiment=true"
 * </pre>
 * 结果写入 data-private/experiments/code-import-时间戳/draft.md。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "llm.experiment", matches = "true")
class CodeImportExperiment {

    private static final long USER_ID = 1L;

    /** 测试从 backend 目录运行 */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/wengu");
    private static final Path DATA = Path.of("..", "data-private");

    private static final List<String> RATING_CHAIN = List.of(
            "chat/controller/SessionController.java",
            "chat/service/ChatService.java",
            "chat/service/StudySessionService.java",
            "chat/service/RatingService.java",
            "chat/service/RatingSuggestionService.java",
            "review/service/ReviewService.java");

    @Autowired
    private SourceService sourceService;

    @Autowired
    private ExtractionDraftService draftService;

    @Autowired
    private ExtractionBatchMapper batchMapper;

    /** 后台任务在测试线程中执行，跑完再读结果 */
    @TestBean(name = "extractionExecutor")
    private TaskExecutor testExecutor;

    static TaskExecutor testExecutor() {
        return new ManualTaskExecutor();
    }

    @Test
    void 导入评分链路的源码并抽取() throws Exception {
        List<CodeFile> files = "large".equals(System.getProperty("code.files")) ? largeSet() : chain();
        long sourceId = sourceService.importCode(USER_ID, new ImportCodeRequest("wengu-learn 评分链路", files)).id();
        long draftId = draftService.create(USER_ID, new CreateDraftRequest(sourceId, null)).id();
        ((ManualTaskExecutor) testExecutor).runAll();

        DraftView view = draftService.get(USER_ID, draftId);
        List<ExtractionBatch> batches = batchMapper.selectList(new LambdaQueryWrapper<ExtractionBatch>()
                .eq(ExtractionBatch::getDraftId, draftId).orderByAsc(ExtractionBatch::getId));
        StringBuilder out = new StringBuilder("# 源码抽取（code-extraction-v3）\n\n- 状态：" + view.status()
                + "\n- 文件：" + files.size() + " 个，" + files.stream().mapToInt(f -> f.content().length()).sum() + " 字符\n");
        for (ExtractionBatch b : batches) {
            out.append("- 批次：").append(b.getStatus()).append("，调用 ").append(b.getAttempts()).append(" 次，")
                    .append(b.getPromptTokens()).append(" + ").append(b.getCompletionTokens()).append(" token，模型 ")
                    .append(b.getModel()).append("，耗时 ")
                    .append(java.time.Duration.between(b.getStartedAt(), b.getFinishedAt()).toSeconds()).append(" 秒\n");
            if (b.getError() != null) {
                out.append("- 最后一次的问题：\n\n```\n").append(b.getError()).append("\n```\n");
            }
        }
        for (DraftItemView item : view.items()) {
            out.append("\n## ").append(item.order()).append(". ").append(item.title()).append("\n\n")
                    .append(item.summary()).append("\n\n依据：")
                    .append(item.evidence().stream().map(e -> e.file() + " " + e.symbol()).collect(Collectors.joining("；")))
                    .append("\n");
        }
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = Files.createDirectories(DATA.resolve("experiments").resolve("code-import-" + time));
        Files.writeString(dir.resolve("draft.md"), out, StandardCharsets.UTF_8);
        if (!batches.isEmpty() && batches.getLast().getRawOutput() != null) {
            Files.writeString(dir.resolve("raw.txt"), batches.getLast().getRawOutput(), StandardCharsets.UTF_8);
        }
        System.out.println("结果在 " + dir.toAbsolutePath().normalize());
    }

    private static List<CodeFile> chain() throws java.io.IOException {
        List<CodeFile> files = new ArrayList<>();
        for (String path : RATING_CHAIN) {
            files.add(new CodeFile(path, Files.readString(SOURCE_ROOT.resolve(path), StandardCharsets.UTF_8)));
        }
        return files;
    }

    /**
     * 接近上限的一组（-Dcode.files=large）：chat、review、plan 三个模块，去掉 dto、entity、mapper（导入页默认不勾），
     * 按路径排序，加到 15 万字符为止。看输出很长时会不会超时、会不会被截断。
     */
    private static List<CodeFile> largeSet() throws java.io.IOException {
        List<Path> paths = new ArrayList<>();
        for (String module : List.of("chat", "review", "plan")) {
            try (var walk = Files.walk(SOURCE_ROOT.resolve(module))) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().matches(".*[\\\\/](dto|entity|mapper)[\\\\/].*"))
                        .forEach(paths::add);
            }
        }
        paths.sort(null);
        List<CodeFile> files = new ArrayList<>();
        int total = 0;
        for (Path p : paths) {
            String content = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (total + content.length() > SourceService.MAX_CODE_CHARS) {
                break;
            }
            total += content.length();
            files.add(new CodeFile(SOURCE_ROOT.relativize(p).toString().replace('\\', '/'), content));
        }
        return files;
    }
}
