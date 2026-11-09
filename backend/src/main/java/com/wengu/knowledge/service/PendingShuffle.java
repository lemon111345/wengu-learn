package com.wengu.knowledge.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * 打乱待学知识点的顺序（#77）：只打乱组与组之间的顺序，同一组保持原来的先后。
 * <p>
 * 纯计算，不访问数据库；随机数由调用方传入，单元测试用固定种子得到确定的结果。
 */
final class PendingShuffle {

    private PendingShuffle() {
    }

    /**
     * @param items    按现在的学习顺序排列
     * @param groupKey 同一组的 key 相等
     * @param random   随机数来源
     * @return 打乱后的新列表，元素和 items 相同
     */
    static <T> List<T> shuffle(List<T> items, Function<T, ?> groupKey, Random random) {
        // LinkedHashMap：分组前的顺序确定，同一个种子的结果才确定
        Map<Object, List<T>> groups = new LinkedHashMap<>();
        for (T item : items) {
            groups.computeIfAbsent(groupKey.apply(item), key -> new ArrayList<>()).add(item);
        }
        List<List<T>> shuffled = new ArrayList<>(groups.values());
        Collections.shuffle(shuffled, random);
        return shuffled.stream().flatMap(List::stream).toList();
    }
}
