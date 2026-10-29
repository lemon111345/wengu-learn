package com.wengu.chat.experiment;

import com.wengu.NoDatabase;
import com.wengu.WenguApplication;
import com.wengu.chat.experiment.RatingSuggestion.Rating;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.llm.service.LlmService;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学习对话与评分建议实验（issue #14）：作者扮演学生，在命令行中和模型对话，学习 data-private/samples/chat-kps.json
 * 中的知识点。每学完一个，先自评，再看模型的评分建议，结果写入 data-private/experiments/chat-时间戳-模型名/。
 * <p>
 * 会真实调用模型、产生费用。需要在终端里交互输入，不能作为测试运行（类名不以 Test 结尾，mvnw test 不会执行）。
 * 在 backend 目录下：
 * <pre>
 * .\mvnw.cmd -q spring-boot:test-run "-Dspring-boot.run.main-class=com.wengu.chat.experiment.LearningChatExperiment"
 * </pre>
 * 不要先运行 chcp 65001：Java 会因此改用 UTF-8 输出，但经过 mvnw.cmd 后终端仍按 GBK 显示，中文全是乱码。
 * 换模型：设置 WENGU_LLM_MODEL 环境变量。
 */
public class LearningChatExperiment {

    static final String CHAT_PROMPT_VERSION = "v2";
    static final String RATING_PROMPT_VERSION = "v3";

    /** 讲解需要一些变化，但 DeepSeek 默认的 1.0 在抽取实验中波动很大，先用 0.7 观察。 */
    static final double CHAT_TEMPERATURE = 0.7;

    /** 评分要稳定，同抽取一样用 0.2。 */
    static final double RATING_TEMPERATURE = 0.2;

    /** 学生回答到倒数第二次时要求完整作答，达到这个次数后要求直接收尾，防止对话停不下来。 */
    static final int MAX_ANSWERS = 8;

    private static final Duration REPLY_TIMEOUT = Duration.ofMinutes(3);

    /** 从 backend 目录运行，data-private 在仓库根目录。 */
    private static final Path DATA = Path.of("..", "data-private");
    private static final Path KNOWLEDGE_POINTS = DATA.resolve("samples/chat-kps.json");

    private final LlmService llm;
    private final String model;
    private final String chatPrompt = loadPrompt("prompts/learning-chat-" + CHAT_PROMPT_VERSION + ".md");
    private final String ratingPrompt = loadPrompt("prompts/rating-suggestion-" + RATING_PROMPT_VERSION + ".md");
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, consoleCharset()));
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final Path outputDir;

    /** meta.json 中每个知识点一条，学完一个就重写一次文件，中途退出也不会丢。 */
    private final List<Map<String, Object>> records = new ArrayList<>();

    LearningChatExperiment(LlmService llm, String model) throws IOException {
        this.llm = llm;
        this.model = model;
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        this.outputDir = DATA.resolve("experiments").resolve("chat-" + time + "-" + model.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    public static void main(String[] args) throws IOException {
        SpringApplication app = new SpringApplication(WenguApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("logging.level.root", "WARN", NoDatabase.EXCLUDE_KEY, NoDatabase.EXCLUDE_VALUE,
                NoDatabase.LAZY_KEY, "true", NoDatabase.NO_RECOVERY_KEY, "false"));
        try (ConfigurableApplicationContext context = app.run(args)) {
            String model = context.getEnvironment().getProperty("spring.ai.openai.chat.options.model", "unknown");
            new LearningChatExperiment(context.getBean(LlmService.class), model).run();
        }
    }

    void run() throws IOException {
        List<ChatKnowledgePoint> kps = json.readValue(Files.readString(KNOWLEDGE_POINTS), new TypeReference<>() {
        });
        // 中文乱码时用来排查：输出、输入各按什么编码处理
        System.out.println("[编码] stdout=" + System.getProperty("stdout.encoding")
                + "，stdin=" + consoleCharset() + "，console=" + (System.console() != null));
        System.out.println("模型 " + model + "，学习对话 prompt " + CHAT_PROMPT_VERSION
                + "，评分建议 prompt " + RATING_PROMPT_VERSION);
        System.out.println("回答可以写多行，空一行（连按两次回车）才发送；输入 /end 可以随时结束当前知识点。");
        System.out.println("结果目录：" + outputDir.toAbsolutePath().normalize());

        Set<Integer> learned = new HashSet<>();
        while (true) {
            System.out.println();
            for (int i = 0; i < kps.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + kps.get(i).title() + (learned.contains(i) ? "  （已学）" : ""));
            }
            String choice = prompt("输入序号开始学习，q 退出：");
            if (choice == null || choice.equalsIgnoreCase("q")) {
                return;
            }
            int index;
            try {
                index = Integer.parseInt(choice) - 1;
            } catch (NumberFormatException e) {
                continue;
            }
            if (index < 0 || index >= kps.size()) {
                continue;
            }
            try {
                learn(index + 1, kps.get(index));
                learned.add(index);
            } catch (RuntimeException e) {
                // 模型调用失败等：这个知识点不保存，可以重新选择再学一次
                System.out.println("\n[出错] " + e + "\n这个知识点没有保存。");
            }
        }
    }

    private void learn(int number, ChatKnowledgePoint kp) throws IOException {
        System.out.println("\n========== " + kp.title() + " ==========\n");
        Instant start = Instant.now();
        List<ChatTurn> history = new ArrayList<>();
        history.add(ChatTurn.user(ChatExperimentSupport.kickoff(kp)));
        int answers = 0;
        boolean endedByModel = false;

        while (true) {
            String reply = streamReply(history);
            history.add(ChatTurn.assistant(reply));
            if (ChatExperimentSupport.containsEndMarker(reply)) {
                endedByModel = true;
                break;
            }
            if (answers >= MAX_ANSWERS) {
                // 已经要求收尾，模型仍没有输出结束标记
                break;
            }
            String answer = readAnswer();
            if (answer == null || answer.equals("/end")) {
                break;
            }
            answers++;
            history.add(ChatTurn.user(ChatExperimentSupport.withTurnNote(answer, answers, MAX_ANSWERS)));
        }
        List<ChatTurn> dialogue = history.subList(1, history.size());

        // 先自评，再看 AI 的建议，避免被 AI 的结论带偏
        System.out.println("\n---------- 自评（填完才会显示 AI 的评分建议）----------");
        SelfAssessment self = new SelfAssessment(
                readRating(),
                promptNonNull("讲解让你学懂了吗？"),
                promptNonNull("追问有效吗？"),
                promptNonNull("备注（可以留空）："));

        RatingSuggestion suggestion = null;
        String ratingError = null;
        Map<String, Object> record = new LinkedHashMap<>();
        try {
            LlmResult<RatingSuggestion> result = llm.call(ratingPrompt,
                    ChatExperimentSupport.ratingInput(kp, dialogue), RatingSuggestion.class, RATING_TEMPERATURE);
            suggestion = result.content();
            record.put("ratingPromptTokens", result.promptTokens());
            record.put("ratingCompletionTokens", result.completionTokens());
        } catch (LlmOutputParseException e) {
            ratingError = e.getMessage() + "，原始输出：" + e.getResult().rawText();
        } catch (RuntimeException e) {
            ratingError = e.toString();
        }

        if (suggestion != null) {
            System.out.println("\nAI 建议：" + suggestion.rating().display() + "（你的自评：" + self.rating().display() + "）");
            System.out.println("判断依据：" + suggestion.analysis());
            System.out.println("还没掌握的点：" + (suggestion.weakPoints() == null || suggestion.weakPoints().isEmpty()
                    ? "无" : String.join("；", suggestion.weakPoints())));
            System.out.println("超出范围的缺口：" + (suggestion.outOfScopeGaps() == null || suggestion.outOfScopeGaps().isEmpty()
                    ? "无" : String.join("；", suggestion.outOfScopeGaps())));
        } else {
            System.out.println("\n[评分建议失败] " + ratingError);
        }

        save(number, kp, dialogue, answers, endedByModel, self, suggestion, ratingError, start, record);
    }

    /** 流式输出模型的回复，同时拼成完整文本返回。 */
    private String streamReply(List<ChatTurn> history) {
        System.out.print("老师：");
        List<String> chunks = llm.stream(chatPrompt, history, CHAT_TEMPERATURE)
                .doOnNext(chunk -> {
                    System.out.print(chunk);
                    System.out.flush();
                })
                .collectList()
                .block(REPLY_TIMEOUT);
        System.out.println();
        return chunks == null ? "" : String.join("", chunks);
    }

    /**
     * 读一次回答：可以写多行，遇到空行才算写完，这样粘贴的多行文本不会被拆成几次回答。
     * 还没写内容时的空行忽略；第一行是 /end 时直接返回；输入结束（Ctrl+Z）时返回已写的内容，没有内容则返回 null。
     */
    private String readAnswer() throws IOException {
        System.out.print("\n你（空一行发送）：");
        System.out.flush();
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = in.readLine();
            if (line == null) {
                return lines.isEmpty() ? null : String.join("\n", lines);
            }
            if (lines.isEmpty() && line.strip().equals("/end")) {
                return "/end";
            }
            if (line.isBlank()) {
                if (lines.isEmpty()) {
                    continue;
                }
                return String.join("\n", lines);
            }
            lines.add(line.stripTrailing());
        }
    }

    private Rating readRating() throws IOException {
        while (true) {
            String s = prompt("自评（1 忘了 / 2 困难 / 3 良好 / 4 简单）：");
            if (s == null) {
                throw new IllegalStateException("输入已结束");
            }
            try {
                return Rating.ofNumber(Integer.parseInt(s.strip()));
            } catch (IllegalArgumentException e) {
                System.out.println("请输入 1～4");
            }
        }
    }

    private void save(int number, ChatKnowledgePoint kp, List<ChatTurn> dialogue, int answers, boolean endedByModel,
                      SelfAssessment self, RatingSuggestion suggestion, String ratingError, Instant start,
                      Map<String, Object> record) throws IOException {
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("%02d-transcript.md".formatted(number)),
                ChatExperimentSupport.transcript(kp, dialogue, self, suggestion, ratingError), StandardCharsets.UTF_8);

        Path csv = outputDir.resolve("ratings.csv");
        if (Files.notExists(csv)) {
            Files.writeString(csv, ChatExperimentSupport.BOM + ChatExperimentSupport.csvLine(ChatExperimentSupport.CSV_HEADER)
                    + "\r\n", StandardCharsets.UTF_8);
        }
        Files.writeString(csv, ChatExperimentSupport.csvRow(kp, answers, endedByModel, self, suggestion) + "\r\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        record.put("title", kp.title());
        record.put("answers", answers);
        record.put("endedByModel", endedByModel);
        record.put("durationSeconds", Duration.between(start, Instant.now()).toSeconds());
        record.put("ratingError", ratingError);
        records.add(record);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("time", LocalDateTime.now().toString());
        meta.put("model", model);
        meta.put("chatPromptVersion", CHAT_PROMPT_VERSION);
        meta.put("ratingPromptVersion", RATING_PROMPT_VERSION);
        meta.put("chatTemperature", CHAT_TEMPERATURE);
        meta.put("ratingTemperature", RATING_TEMPERATURE);
        meta.put("maxAnswers", MAX_ANSWERS);
        meta.put("knowledgePoints", records);
        Files.writeString(outputDir.resolve("meta.json"), json.writeValueAsString(meta), StandardCharsets.UTF_8);
        System.out.println("已保存到 " + outputDir.toAbsolutePath().normalize());
    }

    private String prompt(String message) throws IOException {
        System.out.print(message);
        System.out.flush();
        String line = in.readLine();
        return line == null ? null : line.strip();
    }

    private String promptNonNull(String message) throws IOException {
        String line = prompt(message);
        return line == null ? "" : line;
    }

    /** 控制台的编码：Windows 中文系统默认是 GBK，按 UTF-8 读会把输入的中文读成乱码。 */
    private static Charset consoleCharset() {
        Console console = System.console();
        if (console != null) {
            return console.charset();
        }
        String encoding = System.getProperty("stdout.encoding");
        return encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
    }

    static String loadPrompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 prompt 失败: " + path, e);
        }
    }
}
