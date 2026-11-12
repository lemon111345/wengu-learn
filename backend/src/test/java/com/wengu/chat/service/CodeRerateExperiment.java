package com.wengu.chat.service;

import com.wengu.NoDatabase;
import com.wengu.WenguApplication;
import com.wengu.chat.dto.RatingSuggestionResult;
import com.wengu.chat.service.CodeChatSupport.CodeKp;
import com.wengu.chat.service.CodeChatSupport.Extraction;
import com.wengu.chat.service.CodeChatSupport.SessionRecord;
import com.wengu.llm.dto.LlmResult;
import com.wengu.llm.service.LlmOutputParseException;
import com.wengu.llm.service.LlmService;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import tools.jackson.databind.SerializationFeature;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 结合源码学习的离线重评（issue #89）：不重新对话，用 code-chat-round轮次/ 下已有的记录重新调用评分建议，
 * 每份记录评 RUNS 次，看同一段对话的评分稳不稳。
 * <p>
 * 评分输入和学习时完全一样（{@link CodeChatSupport#ratingInput}），知识点和源码从抽取结果和当前的源码重新读取。
 * 结果写入 data-private/experiments/code-rerate-时间戳/：ratings.csv 是各次的评分，details.md 是每次的完整输出。
 * <p>
 * 会真实调用模型、产生费用（记录数 × RUNS 次）。在 backend 目录下：
 * <pre>
 * .\mvnw.cmd -q spring-boot:test-run "-Dspring-boot.run.main-class=com.wengu.chat.service.CodeRerateExperiment"
 * </pre>
 * 参数和 {@link CodeChatExperiment} 相同：--code.round、--code.result、--code.rating.version。
 */
public class CodeRerateExperiment {

    static final int RUNS = 3;

    private final LlmService llm;
    private final Environment env;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    CodeRerateExperiment(LlmService llm, Environment env) {
        this.llm = llm;
        this.env = env;
    }

    public static void main(String[] args) throws IOException {
        SpringApplication app = new SpringApplication(WenguApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("logging.level.root", "WARN", NoDatabase.EXCLUDE_KEY, NoDatabase.EXCLUDE_VALUE,
                NoDatabase.LAZY_KEY, "true", NoDatabase.NO_RECOVERY_KEY, "false"));
        try (ConfigurableApplicationContext context = app.run(args)) {
            new CodeRerateExperiment(context.getBean(LlmService.class), context.getEnvironment()).run();
        }
    }

    void run() throws IOException {
        Path experiments = CodeChatExperiment.DATA.resolve("experiments");
        Path roundDir = experiments.resolve("code-chat-round" + env.getProperty("code.round", "1"));
        String ratingVersion = env.getProperty("code.rating.version", "v1");
        String ratingPrompt = LearningChatPrompt.load("prompts/code-rating-suggestion-" + ratingVersion + ".md");
        Path result = experiments.resolve(env.getProperty("code.result", CodeChatExperiment.DEFAULT_RESULT)).resolve("result.json");
        Map<Integer, CodeKp> kps = json.readValue(Files.readString(result), Extraction.class).knowledgePoints().stream()
                .collect(Collectors.toMap(CodeKp::order, Function.identity()));
        List<Path> records;
        try (Stream<Path> files = Files.list(roundDir)) {
            records = files.filter(f -> f.getFileName().toString().matches("kp-\\d{2}-(study|review)\\.json")).sorted().toList();
        }

        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outputDir = Files.createDirectories(experiments.resolve("code-rerate-" + time));
        List<String> header = new ArrayList<>(List.of("记录", "知识点", "自评", "当时的 AI 评分"));
        for (int run = 1; run <= RUNS; run++) {
            header.add(ratingVersion + " 第 " + run + " 次");
        }
        StringBuilder csv = new StringBuilder("﻿").append(csvLine(header)).append("\r\n");
        StringBuilder details = new StringBuilder("# 源码学习的离线重评明细\n\n- 记录：" + roundDir.getFileName()
                + "\n- 评分 prompt：" + ratingVersion + "，温度 " + CodeChatExperiment.RATING_TEMPERATURE + "，各 " + RUNS + " 次\n");
        System.out.println("记录 " + records.size() + " 份，评分 prompt " + ratingVersion + "，各 " + RUNS + " 次");
        System.out.println("结果目录：" + outputDir.toAbsolutePath().normalize());

        int total = records.size() * RUNS;
        int done = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        for (Path file : records) {
            SessionRecord record = json.readValue(Files.readString(file), SessionRecord.class);
            CodeKp kp = kps.get(record.order());
            String name = file.getFileName().toString().replace(".json", "");
            String original = record.suggestion() == null ? "无" : String.valueOf(record.suggestion().rating());
            String input = CodeChatSupport.ratingInput(kp, CodeChatExperiment.filesOf(CodeChatSupport.evidenceFiles(kp)),
                    record.mode().equals("review"), record.dialogue());
            List<String> row = new ArrayList<>(List.of(name, record.title(), String.valueOf(record.selfRating()), original));
            details.append("\n## ").append(name).append("：").append(record.title())
                    .append("\n\n- 自评：").append(record.selfRating()).append("，当时的 AI 评分：").append(original).append('\n');
            for (int run = 1; run <= RUNS; run++) {
                String rating;
                details.append("\n### 第 ").append(run).append(" 次：");
                try {
                    LlmResult<RatingSuggestionResult> r = llm.call(ratingPrompt, input, RatingSuggestionResult.class,
                            CodeChatExperiment.RATING_TEMPERATURE);
                    promptTokens += r.promptTokens() == null ? 0 : r.promptTokens();
                    completionTokens += r.completionTokens() == null ? 0 : r.completionTokens();
                    rating = String.valueOf(r.content().rating());
                    details.append(rating).append("\n\n").append(r.content().analysis()).append('\n');
                } catch (LlmOutputParseException e) {
                    rating = "出错";
                    details.append("出错\n\n").append(e.getMessage()).append("，原始输出：\n\n").append(e.getResult().rawText()).append('\n');
                } catch (RuntimeException e) {
                    rating = "出错";
                    details.append("出错\n\n").append(e).append('\n');
                }
                row.add(rating);
                System.out.printf("[%d/%d] %s 第 %d 次：%s%n", ++done, total, name, run, rating);
            }
            csv.append(csvLine(row)).append("\r\n");
            // 每份记录评完就写入，中途出错也不会丢掉已经评完的
            Files.writeString(outputDir.resolve("ratings.csv"), csv, StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("details.md"), details, StandardCharsets.UTF_8);
        }
        System.out.println("完成。输入 token " + promptTokens + "，输出 token " + completionTokens);
    }

    private static String csvLine(List<String> fields) {
        return fields.stream().map(f -> "\"" + f.replace("\"", "\"\"") + "\"").collect(Collectors.joining(","));
    }
}
