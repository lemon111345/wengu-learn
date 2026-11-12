package com.wengu.chat.service;

import com.wengu.NoDatabase;
import com.wengu.chat.dto.RatingSuggestionResult;
import com.wengu.chat.entity.Marker;
import com.wengu.chat.service.CodeChatSupport.CodeKp;
import com.wengu.chat.service.CodeChatSupport.Extraction;
import com.wengu.chat.service.CodeChatSupport.SessionRecord;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.service.LlmException;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.llm.service.LlmService;
import com.wengu.review.fsrs.Rating;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结合源码学习和复习的实验（issue #89）：作者扮演学生，在命令行中学习抽取实验得到的知识点。没学过的进入第一次学习，
 * 学过的进入复习。每次先自评，再看 AI 的评分建议，结果写入 data-private/experiments/code-chat-round轮次/。
 * <p>
 * 会真实调用模型、产生费用。需要在终端里交互输入，类名不以 Test 结尾，平时的测试不会运行它。在 backend 目录下：
 * <pre>
 * .\mvnw.cmd -q spring-boot:test-run "-Dspring-boot.run.main-class=com.wengu.chat.service.CodeChatExperiment"
 * </pre>
 * 用 -Dspring-boot.run.arguments 传参数：--code.round=2 --code.result=实验目录/A-1 --code.kps=1,3 --code.chat.version=v2
 * --code.rating.version=v2。不要先运行 chcp 65001，原因见 LearningChatExperiment。
 */
public class CodeChatExperiment {

    static final Path DATA = Path.of("..", "data-private");
    static final Path SOURCE_ROOT = Path.of("src/main/java/com/wengu");
    static final String DEFAULT_RESULT = "code-20261109-232743-deepseek/A-1";
    static final double RATING_TEMPERATURE = 0.2;
    static final int MAX_REPLY_ATTEMPTS = 3;

    private static final Duration REPLY_TIMEOUT = Duration.ofMinutes(3);

    private final LlmService llm;
    private final List<CodeKp> kps;
    private final String chatVersion;
    private final String ratingVersion;
    private final String chatPrompt;
    private final String ratingPrompt;
    private final Path outputDir;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, consoleCharset()));

    CodeChatExperiment(LlmService llm, Environment env) throws IOException {
        this.llm = llm;
        this.kps = selectedKnowledgePoints(json, env);
        this.chatVersion = env.getProperty("code.chat.version", "v1");
        this.ratingVersion = env.getProperty("code.rating.version", "v1");
        this.chatPrompt = LearningChatPrompt.load("prompts/code-learning-chat-" + chatVersion + ".md");
        this.ratingPrompt = LearningChatPrompt.load("prompts/code-rating-suggestion-" + ratingVersion + ".md");
        this.outputDir = Files.createDirectories(
                DATA.resolve("experiments").resolve("code-chat-round" + env.getProperty("code.round", "1")));
    }

    public static void main(String[] args) throws IOException {
        try (ConfigurableApplicationContext context = NoDatabase.start(args)) {
            new CodeChatExperiment(context.getBean(LlmService.class), context.getEnvironment()).run();
        }
    }

    /** 从抽取结果中按序号取出要学的知识点。预演也用它。 */
    static List<CodeKp> selectedKnowledgePoints(JsonMapper json, Environment env) throws IOException {
        Path result = DATA.resolve("experiments").resolve(env.getProperty("code.result", DEFAULT_RESULT)).resolve("result.json");
        Extraction extraction = json.readValue(Files.readString(result), Extraction.class);
        List<Integer> orders = Arrays.stream(env.getProperty("code.kps", "2,8,9,10").split(","))
                .map(s -> Integer.parseInt(s.strip()))
                .toList();
        return extraction.knowledgePoints().stream().filter(kp -> orders.contains(kp.order())).toList();
    }

    /** 读出知识点依据涉及的源码。键和抽取时一样，是相对 com/wengu 的路径。 */
    static Map<String, String> filesOf(List<String> paths) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        for (String path : paths) {
            files.put(path, Files.readString(SOURCE_ROOT.resolve(path)));
        }
        return files;
    }

    void run() throws IOException {
        System.out.println("[编码] stdout=" + System.getProperty("stdout.encoding") + "，stdin=" + consoleCharset());
        System.out.println("回答可以写多行，空一行（连按两次回车）才发送；输入 /end 可以随时结束当前知识点。答题时请不要看代码。");
        System.out.println("结果目录：" + outputDir.toAbsolutePath().normalize());
        while (true) {
            System.out.println();
            for (int i = 0; i < kps.size(); i++) {
                System.out.println("  " + (i + 1) + ". [" + status(kps.get(i)) + "] " + kps.get(i).title());
            }
            String choice = prompt("输入序号开始，q 退出：");
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
                learn(kps.get(index));
            } catch (RuntimeException e) {
                System.out.println("\n[出错] " + e + "\n这次没有保存，可以重新选择。");
            }
        }
    }

    private String status(CodeKp kp) {
        return Files.exists(file(kp, "review", "json")) ? "已复习"
                : Files.exists(file(kp, "study", "json")) ? "待复习" : "未学";
    }

    private Path file(CodeKp kp, String mode, String extension) {
        return outputDir.resolve("kp-%02d-%s.%s".formatted(kp.order(), mode, extension));
    }

    private void learn(CodeKp kp) throws IOException {
        boolean isReview = Files.exists(file(kp, "study", "json"));
        LearningChatPrompt.Review review = isReview
                ? CodeChatSupport.reviewOf(json.readValue(Files.readString(file(kp, "study", "json")), SessionRecord.class))
                : null;
        String mode = isReview ? "review" : "study";
        Map<String, String> files = filesOf(CodeChatSupport.evidenceFiles(kp));

        System.out.println("\n========== " + (isReview ? "复习：" : "学习：") + kp.title() + " ==========\n");
        Instant start = Instant.now();
        String kickoff = CodeChatSupport.kickoff(kp, files, review);
        List<ChatTurn> history = new ArrayList<>(List.of(ChatTurn.user(kickoff)));
        List<ChatTurn> dialogue = new ArrayList<>();
        int answers = 0;
        boolean endedByModel = false;
        while (true) {
            String reply = streamReply(history);
            history.add(ChatTurn.assistant(reply));
            dialogue.add(ChatTurn.assistant(reply));
            if (reply.contains(Marker.END.text())) {
                endedByModel = true;
                break;
            }
            if (answers >= LearningChatPrompt.MAX_ANSWERS) {
                break;
            }
            String answer = readAnswer();
            if (answer == null || answer.equals("/end")) {
                break;
            }
            answers++;
            String note = LearningChatPrompt.turnNote(answers);
            history.add(ChatTurn.user(note == null ? answer : answer + "\n\n" + note));
            dialogue.add(ChatTurn.user(answer));
        }
        long seconds = Duration.between(start, Instant.now()).toSeconds();

        // 先自评，再看 AI 的建议，避免被 AI 的结论带偏
        System.out.println("\n---------- 自评（填完才会显示 AI 的评分建议）----------");
        Rating self = readRating();
        String understood = promptNonNull("讲解让你学懂了吗？");
        String codeDependent = promptNonNull("追问离不开这份代码吗（换个项目就不成立）？");
        String teacherWrong = promptNonNull("老师说错代码了吗？");
        String note = promptNonNull("备注（可以留空）：");

        RatingSuggestionResult suggestion = null;
        String ratingError = null;
        try {
            suggestion = llm.call(ratingPrompt, CodeChatSupport.ratingInput(kp, files, isReview, dialogue),
                    RatingSuggestionResult.class, RATING_TEMPERATURE).content();
        } catch (LlmOutputParseException e) {
            ratingError = e.getMessage() + "，原始输出：" + e.getResult().rawText();
        } catch (RuntimeException e) {
            ratingError = e.toString();
        }
        if (suggestion != null) {
            System.out.println("\nAI 建议：" + suggestion.rating() + "（你的自评：" + self + "）");
            System.out.println("判断依据：" + suggestion.analysis());
            System.out.println("还没掌握的点：" + suggestion.weakPoints());
            System.out.println("超出范围的缺口：" + suggestion.outOfScopeGaps());
        } else {
            System.out.println("\n[评分建议失败] " + ratingError);
        }

        SessionRecord record = new SessionRecord(kp.order(), kp.title(), kp.angle(), mode, chatVersion, ratingVersion, answers,
                endedByModel, seconds, kickoff.length(), dialogue, self, understood, codeDependent, teacherWrong, note,
                suggestion, ratingError);
        Files.writeString(file(kp, mode, "json"), json.writeValueAsString(record), StandardCharsets.UTF_8);
        Files.writeString(file(kp, mode, "md"), CodeChatSupport.transcript(record), StandardCharsets.UTF_8);
        System.out.println("已保存到 " + file(kp, mode, "md").toAbsolutePath().normalize());
    }

    /** 模型偶尔返回空内容，自动重试，否则学到一半整段对话都会丢。 */
    private String streamReply(List<ChatTurn> history) {
        for (int attempt = 1; ; attempt++) {
            try {
                System.out.print("老师：");
                List<String> chunks = llm.stream(chatPrompt, history, LearningChatPrompt.TEMPERATURE)
                        .doOnNext(chunk -> {
                            System.out.print(chunk);
                            System.out.flush();
                        })
                        .collectList()
                        .block(REPLY_TIMEOUT);
                System.out.println();
                return chunks == null ? "" : String.join("", chunks);
            } catch (LlmException e) {
                System.out.println("\n[模型这次没有回复：" + e.getMessage() + "]");
                if (attempt >= MAX_REPLY_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /** 读一次回答：遇到空行才算写完，粘贴的多行文本不会被拆开。第一行是 /end 时直接返回。 */
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
            if (s.matches("[1-4]")) {
                return Rating.values()[Integer.parseInt(s) - 1];
            }
            System.out.println("请输入 1～4");
        }
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
}
