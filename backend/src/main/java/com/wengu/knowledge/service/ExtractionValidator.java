package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.CoveredQuestion;
import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 检查模型抽取结果是否满足 prompt 中的硬性要求。模型可能漏题、编造题号或已有知识点编号、编号不连续，
 * 这些问题不会导致 JSON 解析失败，必须单独检查。
 * <p>
 * 纯函数，不依赖 Spring 和模型，可以直接单元测试。
 */
public final class ExtractionValidator {

    private ExtractionValidator() {
    }

    /** 不带去重上下文的抽取。 */
    public static List<String> validate(List<ExtractionQuestion> questions, ExtractionResult result) {
        return validate(questions, DedupContext.EMPTY, result);
    }

    /**
     * 返回发现的问题，没有问题时返回空列表。
     * <p>
     * 每道题要么对应至少一个新知识点，要么被已有知识点覆盖（写在 covered 中），也可以两者都有（部分覆盖）。
     * 所有题都被覆盖时，可以没有新知识点。
     */
    public static List<String> validate(List<ExtractionQuestion> questions, DedupContext context,
                                        ExtractionResult result) {
        List<String> problems = new ArrayList<>();
        if (result == null) {
            problems.add("没有抽取结果");
            return problems;
        }
        List<ExtractedKnowledgePoint> knowledgePoints = result.knowledgePoints() == null ? List.of()
                : result.knowledgePoints();
        List<CoveredQuestion> covered = result.covered() == null ? List.of() : result.covered();

        Set<Integer> questionIds = questions.stream().map(ExtractionQuestion::id).collect(Collectors.toSet());
        Set<Integer> answered = new HashSet<>();
        checkCovered(covered, questionIds, context, answered, problems);
        checkKnowledgePoints(knowledgePoints, questionIds, context, answered, problems);

        Set<Integer> missing = new TreeSet<>(questionIds);
        missing.removeAll(answered);
        if (!missing.isEmpty()) {
            problems.add("以下题目既没有对应的知识点，也没有标为已有：" + missing);
        }
        return problems;
    }

    private static void checkCovered(List<CoveredQuestion> covered, Set<Integer> questionIds, DedupContext context,
                                     Set<Integer> answered, List<String> problems) {
        for (CoveredQuestion c : covered) {
            String name = "covered 中的第 " + c.questionId() + " 题";
            if (!questionIds.contains(c.questionId())) {
                problems.add(name + "：题号不存在");
                continue;
            }
            if (c.existingIds() == null || c.existingIds().isEmpty()) {
                problems.add(name + "：没有写覆盖它的已有知识点编号");
                continue;
            }
            List<String> unknown = c.existingIds().stream().filter(id -> context.find(id).isEmpty()).toList();
            if (!unknown.isEmpty()) {
                problems.add(name + "：已有知识点编号 " + unknown + " 不存在");
                continue;
            }
            answered.add(c.questionId());
        }
    }

    private static void checkKnowledgePoints(List<ExtractedKnowledgePoint> knowledgePoints, Set<Integer> questionIds,
                                             DedupContext context, Set<Integer> answered, List<String> problems) {
        // 程序只能查出和已有标题完全相同的重复；问法不同的重复只能靠模型判断
        Map<String, String> existingTitles = new HashMap<>();
        context.entries().forEach(e -> existingTitles.putIfAbsent(normalize(e.title()), e.label()));
        Set<String> titles = new HashSet<>();
        List<Integer> orders = new ArrayList<>();

        for (ExtractedKnowledgePoint kp : knowledgePoints) {
            String name = "知识点 " + kp.order() + "「" + kp.title() + "」";
            orders.add(kp.order());
            if (kp.title() == null || kp.title().isBlank()) {
                problems.add(name + "：标题为空");
            } else if (!titles.add(normalize(kp.title()))) {
                problems.add(name + "：标题与其他知识点重复");
            } else if (existingTitles.containsKey(normalize(kp.title()))) {
                problems.add(name + "：标题与已有知识点 " + existingTitles.get(normalize(kp.title())) + " 相同");
            }
            if (kp.type() == null) {
                problems.add(name + "：缺少类型");
            }
            if (kp.sourceQuestionIds() == null || kp.sourceQuestionIds().isEmpty()) {
                problems.add(name + "：没有来源题号");
                continue;
            }
            for (Integer id : kp.sourceQuestionIds()) {
                if (questionIds.contains(id)) {
                    answered.add(id);
                } else {
                    problems.add(name + "：来源题号 " + id + " 不存在");
                }
            }
        }

        List<Integer> sorted = orders.stream().sorted().toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) != i + 1) {
                problems.add("order 应为从 1 开始的连续编号，实际为 " + sorted);
                break;
            }
        }
    }

    /** 比较标题时忽略首尾和中间的空白。 */
    private static String normalize(String title) {
        return title.replaceAll("\\s+", "");
    }
}
