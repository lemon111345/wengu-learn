package com.wengu.review.fsrs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 读取 py-fsrs 生成的比对用例（tools/fsrs-testgen/generate.py 生成），并提供断言工具。
 */
final class PyFsrsCases {

    /** stability、difficulty、保持率允许的相对误差：不同语言的 pow 实现末位可能不同，多次迭代后会累积。 */
    static final double RELATIVE_TOLERANCE = 1e-9;

    private static final String RESOURCE = "/fsrs/py-fsrs-6.3.2-cases.json";

    private PyFsrsCases() {
    }

    static List<JsonNode> load() throws IOException {
        try (InputStream in = PyFsrsCases.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到用例文件 " + RESOURCE + "，请先运行 tools/fsrs-testgen/generate.py");
            }
            List<JsonNode> cases = new ArrayList<>();
            JsonMapper.builder().build().readTree(in).get("cases").forEach(cases::add);
            return cases;
        }
    }

    static boolean fuzzEnabled(JsonNode testCase) {
        return testCase.get("scheduler").get("enable_fuzzing").asBoolean();
    }

    static double[] parameters(JsonNode testCase) {
        JsonNode node = testCase.get("scheduler").get("parameters");
        double[] parameters = new double[node.size()];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = node.get(i).asDouble();
        }
        return parameters;
    }

    static List<Duration> steps(JsonNode testCase, String field) {
        List<Duration> steps = new ArrayList<>();
        testCase.get("scheduler").get(field).forEach(s -> steps.add(Duration.ofSeconds(s.asLong())));
        return steps;
    }

    static Card card(JsonNode node) {
        return new Card(
                State.of(node.get("state").asInt()),
                node.get("step").isNull() ? null : node.get("step").asInt(),
                node.get("stability").isNull() ? null : node.get("stability").asDouble(),
                node.get("difficulty").isNull() ? null : node.get("difficulty").asDouble(),
                Instant.parse(node.get("due").asString()),
                node.get("last_review").isNull() ? null : Instant.parse(node.get("last_review").asString()));
    }

    static void assertCard(Card expected, Card actual, String where) {
        assertEquals(expected.state(), actual.state(), where + " state");
        assertEquals(expected.step(), actual.step(), where + " step");
        assertClose(expected.stability(), actual.stability(), where + " stability");
        assertClose(expected.difficulty(), actual.difficulty(), where + " difficulty");
        assertEquals(expected.due(), actual.due(), where + " due");
        assertEquals(expected.lastReview(), actual.lastReview(), where + " lastReview");
    }

    static void assertClose(Double expected, Double actual, String what) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, what);
            return;
        }
        assertEquals(expected, actual, Math.abs(expected) * RELATIVE_TOLERANCE, what);
    }
}
