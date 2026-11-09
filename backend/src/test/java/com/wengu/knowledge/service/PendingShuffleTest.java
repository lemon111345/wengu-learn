package com.wengu.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 打乱待学顺序的纯计算（#77）。元素写成“组-序号”，组名就是分组的 key。 */
class PendingShuffleTest {

    private static final List<String> ITEMS = List.of(
            "a-1", "a-2", "a-3", "b-1", "c-1", "c-2", "d-1", "e-1", "f-1", "g-1", "g-2");

    private static String group(String item) {
        return item.substring(0, item.indexOf('-'));
    }

    private static List<String> shuffle(List<String> items, long seed) {
        return PendingShuffle.shuffle(items, PendingShuffleTest::group, new Random(seed));
    }

    @Test
    void 同一个种子结果相同_确实打乱了() {
        assertThat(shuffle(ITEMS, 42)).isEqualTo(shuffle(ITEMS, 42));
        assertThat(shuffle(ITEMS, 42)).isNotEqualTo(ITEMS);
        assertThat(shuffle(ITEMS, 42)).isNotEqualTo(shuffle(ITEMS, 43));
    }

    @Test
    void 元素不多不少_同一组相邻且先后不变() {
        IntStream.range(0, 200).forEach(seed -> {
            List<String> result = shuffle(ITEMS, seed);

            assertThat(result).containsExactlyInAnyOrderElementsOf(ITEMS);
            assertThat(result.indexOf("a-2")).isEqualTo(result.indexOf("a-1") + 1);
            assertThat(result.indexOf("a-3")).isEqualTo(result.indexOf("a-1") + 2);
            assertThat(result.indexOf("c-2")).isEqualTo(result.indexOf("c-1") + 1);
            assertThat(result.indexOf("g-2")).isEqualTo(result.indexOf("g-1") + 1);
        });
    }

    @Test
    void 组内的先后以传入的顺序为准_不按内容排序() {
        List<String> reversedInGroup = List.of("a-3", "a-1", "b-1", "c-1");

        IntStream.range(0, 50).forEach(seed -> {
            List<String> result = shuffle(reversedInGroup, seed);
            assertThat(result.indexOf("a-1")).isEqualTo(result.indexOf("a-3") + 1);
        });
    }

    @Test
    void 空列表和只有一组时不变() {
        assertThat(shuffle(List.of(), 1)).isEmpty();
        assertThat(shuffle(List.of("a-1", "a-2"), 1)).containsExactly("a-1", "a-2");
    }
}
