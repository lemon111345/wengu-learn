package com.wengu.chat.service;

import com.wengu.NoDatabase;
import com.wengu.WenguApplication;
import com.wengu.chat.entity.Marker;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.StudyMaterial;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.service.LlmService;
import com.wengu.review.fsrs.Rating;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 复习换角度提问的实验（#21）。用 #14 第二轮的真实对话（data-private/experiments/chat-20261027-*）作为“上次的学习”，
 * 用 prompt v4 生成复习：
 * <ol>
 *   <li>开场：每个知识点生成 RUNS 次开场问题，和上次问过的问题对照，看有没有换角度、有没有围绕没掌握的点</li>
 *   <li>流程：FLOW_KPS 个知识点由模型扮演“记得不牢的学生”走完整段复习，看能不能经过完整作答再收尾、用了几轮</li>
 * </ol>
 * 放在 chat.service 包里，才能直接用 LearningChatPrompt 中包内可见的函数；类名不以 Test 结尾，平时的测试不会运行它。
 * <p>
 * 会真实调用模型、产生费用（约 35 次调用）。在 backend 目录下：
 * <pre>
 * .\mvnw.cmd -q spring-boot:test-run "-Dspring-boot.run.main-class=com.wengu.chat.service.ReviewAngleExperiment"
 * </pre>
 * 结果写入 data-private/experiments/review-时间戳-模型名/。
 */
public class ReviewAngleExperiment {

    static final int RUNS = 3;
    static final int FLOW_KPS = 2;

    private static final Path DATA = Path.of("..", "data-private");
    private static final Path EXPERIMENTS = DATA.resolve("experiments");
    private static final List<Path> KNOWLEDGE_POINT_FILES = List.of(
            DATA.resolve("samples/chat-kps.json"), DATA.resolve("samples/chat-kps-v1.json"));
    private static final String WEAK_PREFIX = "- 还没掌握的点：";
    private static final String AI_RATING_PREFIX = "- 评分：";

    /** 扮演学生：以前学过、记得不牢，答对一部分 */
    private static final String STUDENT_PROMPT = """
            你在扮演一名正在准备 Java 后端面试的学生，正在和老师复习一个以前学过的知识点，但记得不太牢。
            - 回答老师的问题时用一到三句话，大约一半能答对，其余有遗漏或说错，偶尔直接说“这个忘了”。
            - 老师要求完整作答时，用四到六句话回答，漏掉一两个要点。
            - 只输出学生说的话，不要解释你在扮演。""";

    record Kp(String title, KnowledgePointType type, String summary, List<String> sourceQuestions, String note) {
    }

    /** 上次的学习：知识点标题、老师的回复、没掌握的点、AI 评分 */
    record Previous(String title, List<String> teacherReplies, List<String> weakPoints, Rating rating) {
    }

    private final LlmService llm;
    private final Path outputDir;
    private final JsonMapper json = JsonMapper.builder().build();

    ReviewAngleExperiment(LlmService llm, String model) {
        this.llm = llm;
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        this.outputDir = EXPERIMENTS.resolve("review-" + time + "-" + model.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    public static void main(String[] args) throws IOException {
        SpringApplication app = new SpringApplication(WenguApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("logging.level.root", "WARN", NoDatabase.EXCLUDE_KEY, NoDatabase.EXCLUDE_VALUE,
                NoDatabase.LAZY_KEY, "true", NoDatabase.NO_RECOVERY_KEY, "false"));
        try (ConfigurableApplicationContext context = app.run(args)) {
            String model = context.getEnvironment().getProperty("spring.ai.openai.chat.options.model", "unknown");
            new ReviewAngleExperiment(context.getBean(LlmService.class), model).run();
        }
    }

    void run() throws IOException {
        Map<String, Kp> kps = loadKnowledgePoints();
        List<Previous> previous = new ArrayList<>();
        for (Path file : findTranscripts()) {
            previous.add(parse(Files.readString(file, StandardCharsets.UTF_8)));
        }
        Files.createDirectories(outputDir);
        String system = LearningChatPrompt.system();

        StringBuilder openings = new StringBuilder("# 复习开场（prompt " + LearningChatPrompt.VERSION + "，每题 " + RUNS + " 次）\n");
        StringBuilder flows = new StringBuilder("# 复习全程（模型扮演记得不牢的学生）\n");
        int flowCount = 0;
        for (Previous prev : previous) {
            Kp kp = kps.get(prev.title());
            if (kp == null) {
                System.out.println("找不到知识点，跳过：" + prev.title());
                continue;
            }
            StudyMaterial material = new StudyMaterial(0, kp.title(), kp.type(), kp.summary(), kp.sourceQuestions());
            LearningChatPrompt.Review review = new LearningChatPrompt.Review(1, prev.rating(),
                    LearningChatPrompt.askedQuestions(prev.teacherReplies()), prev.weakPoints());
            String kickoff = LearningChatPrompt.kickoff(material, review);

            openings.append("\n## ").append(kp.title()).append("\n\n### 发给模型的第一条消息\n\n```\n")
                    .append(kickoff).append("\n```\n\n### 开场\n");
            for (int i = 1; i <= RUNS; i++) {
                String reply = chat(system, List.of(ChatTurn.user(kickoff)), LearningChatPrompt.TEMPERATURE);
                openings.append("\n**第 ").append(i).append(" 次：**\n\n").append(reply.strip()).append("\n");
                System.out.println("开场 " + kp.title() + " #" + i);
            }

            if (flowCount < FLOW_KPS) {
                flowCount++;
                flows.append(flow(system, material, kickoff));
            }
        }
        Files.writeString(outputDir.resolve("openings.md"), openings, StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("flows.md"), flows, StandardCharsets.UTF_8);
        System.out.println("完成，结果在 " + outputDir.toAbsolutePath().normalize());
    }

    /** 一整段复习：老师用 v4，学生由模型扮演；轮数提示和正式代码一样（LearningChatPrompt.turnNote） */
    private String flow(String system, StudyMaterial material, String kickoff) {
        StringBuilder text = new StringBuilder("\n## ").append(material.title()).append("\n");
        List<ChatTurn> teacherView = new ArrayList<>(List.of(ChatTurn.user(kickoff)));
        List<ChatTurn> studentView = new ArrayList<>();
        boolean restated = false;
        boolean ended = false;
        int answers = 0;
        while (true) {
            String reply = chat(system, teacherView, LearningChatPrompt.TEMPERATURE).strip();
            teacherView.add(ChatTurn.assistant(reply));
            text.append("\n【老师】\n").append(reply).append("\n");
            restated |= reply.contains(Marker.RESTATE.text());
            if (reply.contains(Marker.END.text()) || answers >= LearningChatPrompt.MAX_ANSWERS) {
                ended = reply.contains(Marker.END.text());
                break;
            }
            // 学生看到的对话：老师是对方（user），自己是 assistant
            studentView.add(ChatTurn.user(reply.replace(Marker.RESTATE.text(), "（请完整作答）")));
            String answer = chat(STUDENT_PROMPT, studentView, 0.9).strip();
            studentView.add(ChatTurn.assistant(answer));
            answers++;
            text.append("\n【学生】\n").append(answer).append("\n");
            String note = LearningChatPrompt.turnNote(answers);
            teacherView.add(ChatTurn.user(note == null ? answer : answer + "\n\n" + note));
            System.out.println("全程 " + material.title() + " 第 " + answers + " 次回答");
        }
        text.append("\n**结果：**学生回答 ").append(answers).append(" 次；")
                .append(restated ? "经过了完整作答" : "没有经过完整作答").append("；")
                .append(ended ? "老师收尾结束" : "没有收尾").append("\n");
        return text.toString();
    }

    private String chat(String system, List<ChatTurn> history, double temperature) {
        List<String> parts = llm.stream(system, history, temperature).collectList().block();
        return parts == null ? "" : String.join("", parts);
    }

    /** 从 #14 的对话记录中取出老师的回复（去掉要求完整作答和收尾的）、没掌握的点、AI 评分 */
    static Previous parse(String markdown) {
        String title = markdown.lines().findFirst().orElseThrow().substring("# ".length());
        int dialogueStart = markdown.indexOf("\n## 对话\n");
        int selfStart = markdown.lastIndexOf("\n## 自评\n");
        int aiStart = markdown.lastIndexOf("\n## AI 评分建议\n");
        List<String> replies = new ArrayList<>();
        StringBuilder current = null;
        for (String line : markdown.substring(dialogueStart, selfStart).lines().toList()) {
            if (line.equals("【老师】") || line.equals("【学生】")) {
                if (current != null) {
                    replies.add(current.toString());
                }
                current = line.equals("【老师】") ? new StringBuilder() : null;
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            replies.add(current.toString());
        }
        // 对话记录里没有保存标记：要求完整作答的回复按 v2 的固定说法“像在面试中一样”识别，正式代码按消息上的标记过滤
        List<String> teacherReplies = replies.stream()
                .filter(r -> !r.contains(Marker.RESTATE.text()) && !r.contains(Marker.END.text())
                        && !r.contains("像在面试中一样"))
                .toList();
        String ai = markdown.substring(aiStart);
        List<String> weak = ai.lines().filter(l -> l.startsWith(WEAK_PREFIX)).findFirst()
                .map(l -> Arrays.stream(l.substring(WEAK_PREFIX.length()).split("；")).map(String::strip)
                        .filter(s -> !s.isEmpty()).toList())
                .orElse(List.of());
        Rating rating = ai.lines().filter(l -> l.startsWith(AI_RATING_PREFIX)).findFirst()
                .map(l -> Rating.valueOf(l.substring(AI_RATING_PREFIX.length()).split("（")[0].strip()))
                .orElse(Rating.HARD);
        return new Previous(title, teacherReplies, weak, rating);
    }

    private Map<String, Kp> loadKnowledgePoints() throws IOException {
        Map<String, Kp> kps = new HashMap<>();
        for (Path file : KNOWLEDGE_POINT_FILES) {
            List<Kp> list = json.readValue(Files.readString(file), new TypeReference<>() {
            });
            list.forEach(kp -> kps.putIfAbsent(kp.title(), kp));
        }
        return kps;
    }

    /** #14 第二轮（10 月 27 日，学习对话 v2）的 5 份对话记录 */
    private static List<Path> findTranscripts() throws IOException {
        try (Stream<Path> files = Files.walk(EXPERIMENTS, 2)) {
            return files
                    .filter(f -> f.getFileName().toString().matches("\\d{2}-transcript\\.md")
                            && f.getParent().getFileName().toString().startsWith("chat-20261027-"))
                    .sorted()
                    .toList();
        }
    }
}
