package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.CodeEvidence;
import com.wengu.knowledge.dto.CodeExtractionResult;
import com.wengu.knowledge.dto.CodeExtractionResult.KnowledgePoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 检查源码抽取的结果（#90，决策 050）：代码依据真实存在、每条要点带 2～3 个得分点、有标题。
 * 这些问题不会导致 JSON 解析失败，必须单独检查。
 * <p>
 * 不合格的知识点单独丢掉，合格的留下，不让一两个知识点拖垮整批：一次源码抽取要两三分钟，
 * 实测第一次调用几乎总有一两个知识点不合格（要点写了 5 条、得分点 1 个或 4 个），整批重抽又会出现新的不合格。
 * <p>
 * 纯函数，不依赖 Spring 和模型，可以直接单元测试。
 */
public final class CodeExtractionValidator {

    static final int MAX_KNOWLEDGE_POINTS = 20;
    static final int MAX_EVIDENCE = 4;
    static final int MAX_POINTS = 4;
    static final int MIN_SCORE_POINTS = 2;
    static final int MAX_SCORE_POINTS = 3;

    /** 编号要点的开头，如“1. ”“2、” */
    private static final Pattern NUMBERED = Pattern.compile("^\\d+[.、．]\\s*\\S.*");

    /**
     * 检查的结果。
     *
     * @param accepted 留下的知识点，按原来的顺序重新从 1 连续编号；为 null 表示整批不能用，要重试
     *                 （没有 knowledgePoints，或者抽出的全都不合格）。模型本来就一个都没抽出时是空列表，算成功
     * @param problems 发现的问题：丢掉了哪些、为什么，或者整批不能用的原因
     */
    public record Checked(List<KnowledgePoint> accepted, List<String> problems) {
    }

    private CodeExtractionValidator() {
    }

    /**
     * @param files 这次导入的文件，路径 → 内容
     */
    public static Checked check(Map<String, String> files, CodeExtractionResult result) {
        if (result == null || result.knowledgePoints() == null) {
            return new Checked(null, List.of("缺少 knowledgePoints"));
        }
        List<KnowledgePoint> sorted = result.knowledgePoints().stream()
                .sorted(Comparator.comparingInt(KnowledgePoint::order))
                .toList();
        List<KnowledgePoint> kept = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (KnowledgePoint kp : sorted) {
            List<String> found = problemsOf(files, kp);
            if (found.isEmpty()) {
                kept.add(kp);
            } else {
                problems.addAll(found);
            }
        }
        if (!sorted.isEmpty() && kept.isEmpty()) {
            return new Checked(null, problems);
        }
        if (kept.size() > MAX_KNOWLEDGE_POINTS) {
            problems.add("合格的知识点有 " + kept.size() + " 个，只留前 " + MAX_KNOWLEDGE_POINTS + " 个");
            kept = kept.subList(0, MAX_KNOWLEDGE_POINTS);
        }
        List<KnowledgePoint> renumbered = new ArrayList<>();
        for (KnowledgePoint kp : kept) {
            renumbered.add(new KnowledgePoint(kp.angle(), kp.evidence(), renumbered.size() + 1, kp.prerequisites(),
                    kp.summary(), kp.summaryDetails(), kp.title(), kp.unverifiable()));
        }
        return new Checked(renumbered, problems);
    }

    /** 一个知识点的问题，为空表示合格。问题前面带上模型给的编号，如“第 3 个知识点：” */
    static List<String> problemsOf(Map<String, String> files, KnowledgePoint kp) {
        String label = "第 " + kp.order() + " 个知识点：";
        List<String> problems = new ArrayList<>();
        if (kp.title() == null || kp.title().isBlank()) {
            problems.add(label + "没有标题");
        }
        problems.addAll(checkEvidence(files, kp, label));
        problems.addAll(checkScorePoints(kp.summary(), label));
        return problems;
    }

    /** 代码依据 1～4 处；文件必须在这次导入的文件中，symbol 的类名和方法名必须在这个文件里真实出现（#89 实验的校验）。 */
    static List<String> checkEvidence(Map<String, String> files, KnowledgePoint kp, String label) {
        if (kp.evidence() == null || kp.evidence().isEmpty()) {
            return List.of(label + "没有代码依据");
        }
        List<String> problems = new ArrayList<>();
        if (kp.evidence().size() > MAX_EVIDENCE) {
            problems.add(label + "代码依据有 " + kp.evidence().size() + " 处，最多 " + MAX_EVIDENCE + " 处");
        }
        for (CodeEvidence evidence : kp.evidence()) {
            String content = evidence.file() == null ? null : files.get(evidence.file());
            if (content == null) {
                problems.add(label + "文件不在导入的文件中：" + evidence.file());
                continue;
            }
            for (String name : evidence.symbol() == null ? new String[0] : evidence.symbol().split("[#.]")) {
                String identifier = name.replace("()", "").strip();
                if (!identifier.isEmpty()
                        && !Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(content).find()) {
                    problems.add(label + evidence.file() + " 中没有 " + identifier);
                }
            }
        }
        return problems;
    }

    /**
     * 要点的格式：1～4 条编号要点，每条下面 2～3 个以“- ”开头的得分点（决策 050）。
     * 其他的行（空行、要点写成了两行）不计数，只看编号要点和得分点的个数。
     */
    static List<String> checkScorePoints(String summary, String label) {
        if (summary == null || summary.isBlank()) {
            return List.of(label + "没有要点");
        }
        List<Integer> counts = new ArrayList<>();
        for (String raw : summary.split("\n")) {
            String line = raw.strip();
            if (NUMBERED.matcher(line).matches()) {
                counts.add(0);
            } else if (line.startsWith("-")) {
                if (counts.isEmpty()) {
                    return List.of(label + "得分点前面没有编号的要点");
                }
                counts.set(counts.size() - 1, counts.getLast() + 1);
            }
        }
        if (counts.isEmpty() || counts.size() > MAX_POINTS) {
            return List.of(label + "要点要有 1～" + MAX_POINTS + " 条，每条单独一行、用“1. 2. 3.”编号，实际有 "
                    + counts.size() + " 条");
        }
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < counts.size(); i++) {
            if (counts.get(i) < MIN_SCORE_POINTS || counts.get(i) > MAX_SCORE_POINTS) {
                problems.add(label + "第 " + (i + 1) + " 条要点有 " + counts.get(i) + " 个得分点，要 "
                        + MIN_SCORE_POINTS + "～" + MAX_SCORE_POINTS + " 个，每个单独一行、以“- ”开头");
            }
        }
        return problems;
    }
}
