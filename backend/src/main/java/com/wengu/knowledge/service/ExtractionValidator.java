package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.ExtractedKnowledgePoint;
import com.wengu.knowledge.dto.ExtractionQuestion;
import com.wengu.knowledge.dto.ExtractionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 检查模型抽取结果是否满足 prompt 中的硬性要求。模型可能漏题、编造题号、编号不连续，
 * 这些问题不会导致 JSON 解析失败，必须单独检查。
 * <p>
 * 纯函数，不依赖 Spring 和模型，可以直接单元测试。
 */
public final class ExtractionValidator {

    private ExtractionValidator() {
    }

    /** 返回发现的问题，没有问题时返回空列表。 */
    public static List<String> validate(List<ExtractionQuestion> questions, ExtractionResult result) {
        List<String> problems = new ArrayList<>();
        if (result == null || result.knowledgePoints() == null || result.knowledgePoints().isEmpty()) {
            problems.add("没有抽取出任何知识点");
            return problems;
        }

        Set<Integer> questionIds = questions.stream().map(ExtractionQuestion::id).collect(Collectors.toSet());
        Set<Integer> covered = new HashSet<>();
        Set<String> titles = new HashSet<>();
        List<Integer> orders = new ArrayList<>();

        for (ExtractedKnowledgePoint kp : result.knowledgePoints()) {
            String name = "知识点 " + kp.order() + "「" + kp.title() + "」";
            orders.add(kp.order());
            if (kp.title() == null || kp.title().isBlank()) {
                problems.add(name + "：标题为空");
            } else if (!titles.add(kp.title().strip())) {
                problems.add(name + "：标题与其他知识点重复");
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
                    covered.add(id);
                } else {
                    problems.add(name + "：来源题号 " + id + " 不存在");
                }
            }
        }

        Set<Integer> missing = new TreeSet<>(questionIds);
        missing.removeAll(covered);
        if (!missing.isEmpty()) {
            problems.add("以下题目没有对应的知识点：" + missing);
        }

        List<Integer> sorted = orders.stream().sorted().toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) != i + 1) {
                problems.add("order 应为从 1 开始的连续编号，实际为 " + sorted);
                break;
            }
        }
        return problems;
    }
}
