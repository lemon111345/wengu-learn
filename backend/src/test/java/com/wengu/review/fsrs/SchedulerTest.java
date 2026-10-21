package com.wengu.review.fsrs;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * 逐个回放 py-fsrs 的用例：每一步都用 Java 自己上一步的结果继续复习，因此误差会累积，比逐步重置更严格。
     * 开启 fuzz 的用例需要回放随机数，不在这里运行。
     */
    @TestFactory
    Stream<DynamicTest> 与py_fsrs结果一致() throws IOException {
        List<JsonNode> cases = PyFsrsCases.load().stream().filter(c -> !PyFsrsCases.fuzzEnabled(c)).toList();
        assertFalse(cases.isEmpty(), "没有读到用例");
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.get("name").asString(), () -> replay(c)));
    }

    private static void replay(JsonNode testCase) {
        Scheduler scheduler = new Scheduler(
                PyFsrsCases.parameters(testCase),
                testCase.get("scheduler").get("desired_retention").asDouble(),
                PyFsrsCases.steps(testCase, "learning_steps_seconds"),
                PyFsrsCases.steps(testCase, "relearning_steps_seconds"),
                testCase.get("scheduler").get("maximum_interval").asInt());

        Card card = PyFsrsCases.card(testCase.get("initial_card"));
        int i = 0;
        for (JsonNode review : testCase.get("reviews")) {
            String where = "第 " + (++i) + " 次复习";
            Instant time = Instant.parse(review.get("review_datetime").asString());

            PyFsrsCases.assertClose(review.get("retrievability_before").asDouble(),
                    scheduler.retrievability(card, time), where + " 复习前保持率");

            card = scheduler.review(card, Rating.of(review.get("rating").asInt()), time);
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
                () -> new Scheduler(new double[20], 0.9, List.of(), List.of(), 36500));
    }

    @Test
    void 参数超出范围时报错() {
        double[] parameters = Scheduler.defaultParameters();
        parameters[4] = 0.5; // 合法范围 [1.0, 10.0]
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Scheduler(parameters, 0.9, List.of(), List.of(), 36500));
        assertTrue(e.getMessage().contains("parameters[4]"));
    }

    @Test
    void 复习状态的卡片缺少稳定性时报错() {
        Card broken = new Card(State.REVIEW, null, null, null, NOW, NOW);
        assertThrows(IllegalArgumentException.class,
                () -> Scheduler.projectDefault().review(broken, Rating.GOOD, NOW));
    }
}
