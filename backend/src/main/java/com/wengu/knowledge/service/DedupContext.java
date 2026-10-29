package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.CoveredQuestion;
import com.wengu.knowledge.dto.ResolvedCovered;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 去重上下文：抽取时告诉模型哪些知识点已经有了，让它不要重复输出（决策 014）。纯计算，不依赖 Spring。
 * <p>
 * 来源有两个：用户已有的知识点，以及同一份草稿中已经成功的其他批次的条目。每个标题编号为 E1、E2……，
 * 用 E 开头和题号区分开；模型在 covered 中引用这些编号，程序再换回知识点 id 或条目 id。
 *
 * @param entries 按编号排列，已有知识点在前、草稿条目在后
 */
public record DedupContext(List<Entry> entries) {

    public static final DedupContext EMPTY = new DedupContext(List.of());

    /**
     * 一个已有标题。knowledgePointId 和 draftItemId 恰好有一个不为空。
     *
     * @param label 编号，如 "E3"
     */
    public record Entry(String label, Long knowledgePointId, Long draftItemId, String title) {
    }

    /** 已有知识点或草稿条目的 id 和标题。 */
    public record Existing(long id, String title) {
    }

    /** 按“已有知识点、草稿条目”的顺序编号，跳过空标题。 */
    public static DedupContext of(List<Existing> knowledgePoints, List<Existing> draftItems) {
        List<Entry> entries = new ArrayList<>();
        for (Existing kp : knowledgePoints) {
            add(entries, kp.id(), null, kp.title());
        }
        for (Existing item : draftItems) {
            add(entries, null, item.id(), item.title());
        }
        return new DedupContext(List.copyOf(entries));
    }

    private static void add(List<Entry> entries, Long kpId, Long itemId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        entries.add(new Entry("E" + (entries.size() + 1), kpId, itemId, title.strip()));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 发给模型的文本：每行“[E1] 标题”。 */
    public String format() {
        return entries.stream()
                .map(e -> "[" + e.label() + "] " + oneLine(e.title()))
                .collect(Collectors.joining("\n"));
    }

    public Optional<Entry> find(String label) {
        String wanted = label == null ? "" : label.strip();
        return entries.stream().filter(e -> e.label().equals(wanted)).findFirst();
    }

    /**
     * 把模型给出的编号换回知识点 id 或条目 id，并记下当时的标题。应在校验通过后调用；
     * 找不到的编号直接跳过，一个编号都没找到的题不保留。
     */
    public List<ResolvedCovered> resolve(List<CoveredQuestion> covered) {
        if (covered == null) {
            return List.of();
        }
        List<ResolvedCovered> resolved = new ArrayList<>();
        for (CoveredQuestion c : covered) {
            List<Entry> found = (c.existingIds() == null ? List.<String>of() : c.existingIds()).stream()
                    .map(this::find)
                    .flatMap(Optional::stream)
                    .toList();
            if (found.isEmpty()) {
                continue;
            }
            resolved.add(new ResolvedCovered(c.questionId(),
                    found.stream().map(Entry::knowledgePointId).filter(Objects::nonNull).toList(),
                    found.stream().map(Entry::draftItemId).filter(Objects::nonNull).toList(),
                    found.stream().map(Entry::title).toList(),
                    c.reason()));
        }
        return resolved;
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ");
    }
}
