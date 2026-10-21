package com.wengu.review.fsrs;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * 逐个回放 py-fsrs 的用例：每一步都用 Java 自己上一步的结果继续复习，因此误差会累积，比逐步重置更严格。
     * 开启 fuzz 的用例通过 {@link ReplayRandom} 使用和 Python 相同的随机数。
     */
    @TestFactory
    Stream<DynamicTest> 与py_fsrs结果一致() throws IOException {
        List<JsonNode> cases = PyFsrsCases.load();
        assertFalse(cases.isEmpty(), "没有读到用例");
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.get("name").asString(), () -> replay(c)));
    }

    private static void replay(JsonNode testCase) {
        JsonNode config = testCase.get("scheduler");
        ReplayRandom random = new ReplayRandom();
        Scheduler scheduler = new Scheduler(
                PyFsrsCases.parameters(testCase),
                config.get("desired_retention").asDouble(),
                PyFsrsCases.steps(testCase, "learning_steps_seconds"),
                PyFsrsCases.steps(testCase, "relearning_steps_seconds"),
                config.get("maximum_interval").asInt(),
                config.get("enable_fuzzing").asBoolean(),
                random);

        Card card = PyFsrsCases.card(testCase.get("initial_card"));
        int i = 0;
        for (JsonNode review : testCase.get("reviews")) {
            String where = "第 " + (++i) + " 次复习";
            Instant time = Instant.parse(review.get("review_datetime").asString());

            PyFsrsCases.assertClose(review.get("retrievability_before").asDouble(),
                    scheduler.retrievability(card, time), where + " 复习前保持率");

            // 每次复习只装入这一次 Python 用到的随机数，复习后必须恰好用完
            List<Double> randoms = new ArrayList<>();
            review.get("fuzz_randoms").forEach(r -> randoms.add(r.asDouble()));
            random.load(randoms);
            card = scheduler.review(card, Rating.of(review.get("rating").asInt()), time);
            random.assertExhausted(where);

            PyFsrsCases.assertCard(PyFsrsCases.card(review.get("expected")), card, where);
        }
    }

    @Test
    void 新卡未复习过() {
        Card card = Card.newCard(NOW);
        assertTrue(card.isNew());
        assertEquals(State.LEARNING, card.state());
        assertEquals(0, card.step());
        assertEquals(0, Scheduler.projectDefault().retrievability(card, NOW));
    }

    @Test
    void 项目默认配置下新卡评忘了也安排到第二天以后() {
        Card card = Scheduler.projectDefault().review(Card.newCard(NOW), Rating.AGAIN, NOW);
        assertFalse(card.isNew());
        assertEquals(State.REVIEW, card.state());
        assertTrue(card.due().isAfter(NOW.plusSeconds(86_399)), "间隔至少一天");
    }

    @Test
    void 参数个数不对时报错() {
        assertThrows(IllegalArgumentException.class,
                () -> new Scheduler(new double[20], 0.9, List.of(), List.of(), 36500, false, null));
    }

    @Test
    void 参数超出范围时报错() {
        double[] parameters = Scheduler.defaultParameters();
        parameters[4] = 0.5; // 合法范围 [1.0, 10.0]
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Scheduler(parameters, 0.9, List.of(), List.of(), 36500, false, null));
        assertTrue(e.getMessage().contains("parameters[4]"));
    }

    @Test
    void 开启fuzz时必须提供随机数来源() {
        assertThrows(NullPointerException.class,
                () -> new Scheduler(Scheduler.defaultParameters(), 0.9, List.of(), List.of(), 36500, true, null));
    }

    @Test
    void fuzz结果落在扰动区间的两端() {
        // 新卡评“简单”，默认参数下原始间隔为 8 天，扰动幅度 delta = 1 + 0.15 × 4.5 + 0.1 × 1 = 1.775，
        // 区间为 [rint(6.225), rint(9.775)] = [6, 10]，结果 = rint(随机数 × 5 + 6)：取 0 得到下界 6，取 0.8 得到 rint(10.0) = 10
        assertEquals(6, easyIntervalDays(() -> 0.0));
        assertEquals(10, easyIntervalDays(() -> 0.8));
    }

    @Test
    void fuzz取整与Python的round一致() {
        // 0.5 × 5 + 6 = 8.5：Python 的 round(8.5) 是 8（五成双），Math.round 会得到 9
        assertEquals(8, easyIntervalDays(() -> 0.5));
    }

    private static long easyIntervalDays(DoubleSupplier random) {
        Scheduler scheduler = new Scheduler(Scheduler.defaultParameters(), 0.9, List.of(), List.of(), 36500, true, random);
        Card card = scheduler.review(Card.newCard(NOW), Rating.EASY, NOW);
        return Duration.between(NOW, card.due()).toDays();
    }

    @Test
    void 复习状态的卡片缺少稳定性时报错() {
        Card broken = new Card(State.REVIEW, null, null, null, NOW, NOW);
        assertThrows(IllegalArgumentException.class,
                () -> Scheduler.projectDefault().review(broken, Rating.GOOD, NOW));
    }
}
