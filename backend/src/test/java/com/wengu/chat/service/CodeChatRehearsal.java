package com.wengu.chat.service;

import com.wengu.NoDatabase;
import com.wengu.chat.dto.RatingSuggestionResult;
import com.wengu.chat.entity.Marker;
import com.wengu.chat.service.CodeChatSupport.CodeKp;
import com.wengu.chat.service.CodeChatSupport.SessionRecord;
import com.wengu.llm.dto.ChatTurn;
import com.wengu.llm.service.LlmException;
import com.wengu.llm.service.LlmService;
import com.wengu.review.fsrs.Rating;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 结合源码学习的预演（issue #89）：作者上场之前，由模型扮演学生走完第一次学习和复习，检查流程能不能走通；
 * 再比较复习开场的两种上下文：只放依据文件，或者放这批的全部文件。
 * <p>
 * 会真实调用模型、产生费用。类名不以 Test 结尾，平时的测试不会运行它。在 backend 目录下：
 * <pre>
 * .\mvnw.cmd -q spring-boot:test-run "-Dspring-boot.run.main-class=com.wengu.chat.service.CodeChatRehearsal"
 * </pre>
 * 结果写入 data-private/experiments/code-rehearsal-时间戳/，flows.md 是全程，openings.md 是开场比较。
 */
public class CodeChatRehearsal {

    private static final int OPENING_RUNS = 3;

    /** 抽取实验 A 组的全部文件，比较上下文时用。 */
    private static final List<String> ALL_FILES = List.of(
            "chat/controller/SessionController.java",
            "chat/service/ChatService.java",
            "chat/service/StudySessionService.java",
            "chat/service/RatingService.java",
            "chat/service/RatingSuggestionService.java",
            "review/service/ReviewService.java");

    private static final String STUDENT_PROMPT = """
            你在扮演一名学生，正在和老师学习自己项目里的一段实现。这段代码主要是 AI 帮忙写的，你只记得大概。
            项目里的事实如下，你只记得其中大约一半，其余的记不清或记错：
            %s
            - 回答老师的问题时用一到三句话，大约一半能答对，其余有遗漏或说错，偶尔直接说“这个忘了”。
            - 老师问当时为什么这样设计时，有时给出一个说得通的理由，有时说“不记得了，这段是 AI 写的”。
            - 大约每两次回答里有一次会反问老师用到的基础概念，比如“这个事件是什么东西？”，偶尔一条消息里问两个问题。
            - 老师要求完整作答时，用四到六句话回答，漏掉一两个要点。
            - 只输出学生说的话，不要解释你在扮演。""";

    private final LlmService llm;
    private final List<CodeKp> kps;
    private final String chatPrompt;
    private final String ratingPrompt;

    private record Flow(String text, SessionRecord record) {
    }

    CodeChatRehearsal(LlmService llm, Environment env) throws IOException {
        this.llm = llm;
        this.kps = CodeChatExperiment.selectedKnowledgePoints(JsonMapper.builder().build(), env);
        this.chatPrompt = LearningChatPrompt.load("prompts/code-learning-chat-" + env.getProperty("code.chat.version", CodeChatExperiment.DEFAULT_CHAT_VERSION) + ".md");
        this.ratingPrompt = LearningChatPrompt.load("prompts/code-rating-suggestion-" + env.getProperty("code.rating.version", CodeChatExperiment.DEFAULT_RATING_VERSION) + ".md");
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = NoDatabase.start(args)) {
            new CodeChatRehearsal(context.getBean(LlmService.class), context.getEnvironment()).run();
        }
    }

    void run() throws IOException, InterruptedException, ExecutionException {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outputDir = Files.createDirectories(CodeChatExperiment.DATA.resolve("experiments").resolve("code-rehearsal-" + time));
        Map<String, String> allFiles = CodeChatExperiment.filesOf(ALL_FILES);

        List<Future<String[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(kps.size())) {
            for (CodeKp kp : kps) {
                futures.add(pool.submit(() -> rehearse(kp, allFiles)));
            }
        }
        StringBuilder flows = new StringBuilder("# 预演全程（模型扮演学生）\n");
        StringBuilder openings = new StringBuilder("# 复习开场：只放依据文件 vs 放全部文件，各 " + OPENING_RUNS + " 次\n");
        for (Future<String[]> future : futures) {
            String[] result = future.get();
            flows.append(result[0]);
            openings.append(result[1]);
        }
        Files.writeString(outputDir.resolve("flows.md"), flows, StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("openings.md"), openings, StandardCharsets.UTF_8);
        System.out.println("完成，结果在 " + outputDir.toAbsolutePath().normalize());
    }

    private String[] rehearse(CodeKp kp, Map<String, String> allFiles) throws IOException {
        Map<String, String> files = CodeChatExperiment.filesOf(CodeChatSupport.evidenceFiles(kp));
        Flow study = flow(kp, files, null);
        LearningChatPrompt.Review review = CodeChatSupport.reviewOf(study.record());
        Flow reviewFlow = flow(kp, files, review);

        StringBuilder openings = new StringBuilder("\n## " + kp.title() + "\n");
        for (Map.Entry<String, Map<String, String>> context : List.of(
                Map.entry("只放依据文件", files), Map.entry("放全部文件", allFiles))) {
            openings.append("\n### ").append(context.getKey()).append("\n");
            String kickoff = CodeChatSupport.kickoff(kp, context.getValue(), review);
            for (int i = 1; i <= OPENING_RUNS; i++) {
                openings.append("\n**第 ").append(i).append(" 次：**\n\n")
                        .append(chat(chatPrompt, List.of(ChatTurn.user(kickoff)), LearningChatPrompt.TEMPERATURE).strip())
                        .append("\n");
            }
        }
        System.out.println("完成：" + kp.title());
        return new String[]{"\n## " + kp.title() + "\n\n### 第一次学习\n" + study.text() + "\n### 复习\n" + reviewFlow.text(),
                openings.toString()};
    }

    /** 一整段对话，学生由模型扮演；轮数提示和正式代码一样。学生看不到源码，review 为 null 时是第一次学习。 */
    private Flow flow(CodeKp kp, Map<String, String> files, LearningChatPrompt.Review review) {
        String studentPrompt = STUDENT_PROMPT.formatted(kp.summary());
        List<ChatTurn> teacherView = new ArrayList<>(List.of(ChatTurn.user(CodeChatSupport.kickoff(kp, files, review))));
        List<ChatTurn> studentView = new ArrayList<>();
        List<ChatTurn> dialogue = new ArrayList<>();
        boolean restated = false;
        boolean ended = false;
        int answers = 0;
        while (true) {
            String reply = chat(chatPrompt, teacherView, LearningChatPrompt.TEMPERATURE).strip();
            teacherView.add(ChatTurn.assistant(reply));
            dialogue.add(ChatTurn.assistant(reply));
            restated |= reply.contains(Marker.RESTATE.text());
            if (reply.contains(Marker.END.text()) || answers >= LearningChatPrompt.MAX_ANSWERS) {
                ended = reply.contains(Marker.END.text());
                break;
            }
            // 学生看到的对话：老师是对方（user），自己是 assistant
            studentView.add(ChatTurn.user(reply.replace(Marker.RESTATE.text(), "（请完整作答）")));
            String answer = chat(studentPrompt, studentView, 0.9).strip();
            studentView.add(ChatTurn.assistant(answer));
            dialogue.add(ChatTurn.user(answer));
            answers++;
            String note = LearningChatPrompt.turnNote(answers);
            teacherView.add(ChatTurn.user(note == null ? answer : answer + "\n\n" + note));
        }

        RatingSuggestionResult suggestion = null;
        String ratingText;
        try {
            suggestion = llm.call(ratingPrompt, CodeChatSupport.ratingInput(kp, files, review != null, dialogue), RatingSuggestionResult.class,
                    CodeChatExperiment.RATING_TEMPERATURE).content();
            ratingText = suggestion.rating() + "\n\n依据：" + suggestion.analysis() + "\n\n没掌握：" + suggestion.weakPoints();
        } catch (RuntimeException e) {
            ratingText = "评分失败：" + e;
        }
        String text = "\n" + CodeChatSupport.formatDialogue(dialogue) + "\n\n**结果：**学生回答 " + answers + " 次；"
                + (restated ? "经过了完整作答" : "没有经过完整作答") + "；" + (ended ? "老师收尾结束" : "没有收尾")
                + "\n\n**AI 评分：**" + ratingText + "\n";
        // 复习用的 lastRating 在预演里没有自评，固定为 HARD；真实实验用作者的自评
        SessionRecord record = new SessionRecord(kp.order(), kp.title(), kp.angle(), "study", "", "", answers, ended, 0, 0, dialogue,
                Rating.HARD, "", "", "", "", suggestion, null, null, null);
        return new Flow(text, record);
    }

    /** 模型偶尔返回空内容，重试几次，不让一次偶发失败中断整轮预演。 */
    private String chat(String system, List<ChatTurn> history, double temperature) {
        for (int attempt = 1; ; attempt++) {
            try {
                List<String> parts = llm.stream(system, history, temperature).collectList().block();
                return parts == null ? "" : String.join("", parts);
            } catch (LlmException e) {
                if (attempt >= CodeChatExperiment.MAX_REPLY_ATTEMPTS) {
                    throw e;
                }
                System.out.println("retry after " + e.getErrorCode());
            }
        }
    }
}
