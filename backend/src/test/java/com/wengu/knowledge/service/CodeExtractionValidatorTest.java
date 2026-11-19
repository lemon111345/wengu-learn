package com.wengu.knowledge.service;

import com.wengu.knowledge.dto.CodeEvidence;
import com.wengu.knowledge.dto.CodeExtractionResult;
import com.wengu.knowledge.dto.CodeExtractionResult.KnowledgePoint;
import com.wengu.source.dto.CodeFileContent;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CodeExtractionValidatorTest {

    private static final Map<String, String> FILES = Map.of(
            "chat/ChatService.java", "class ChatService {\n    void send() {}\n}\n",
            "chat/RatingService.java", "class RatingService {\n    void rate() {}\n}\n");

    /** 两条要点，各 2、3 个得分点 */
    private static final String SUMMARY = """
            1. 先校验再保存。
            - 先校验内容
            - 校验通过才保存
            2. 保存后推给前端。
            - 保存成功后才推送
            - 推送失败不回滚
            - 前端刷新后能看到""";

    private static KnowledgePoint kp(int order, String summary, List<CodeEvidence> evidence) {
        return new KnowledgePoint("CHAIN", evidence, order, List.of(), summary, "", "发一条消息时依次发生了什么？", List.of());
    }

    private static KnowledgePoint kp(int order) {
        return kp(order, SUMMARY, List.of(new CodeEvidence("chat/ChatService.java", "ChatService#send")));
    }

    private static List<String> validate(KnowledgePoint... kps) {
        return CodeExtractionValidator.validate(FILES, new CodeExtractionResult(List.of(kps)));
    }

    @Test
    void 合格的结果没有问题_一个都没抽出也算合格() {
        assertThat(validate(kp(1), kp(2, SUMMARY, List.of(new CodeEvidence("chat/RatingService.java", "RatingService"))))).isEmpty();
        assertThat(validate()).isEmpty();
    }

    @Test
    void 依据的文件和名字必须真实存在() {
        assertThat(validate(kp(1, SUMMARY, List.of(new CodeEvidence("chat/Other.java", "Other")))))
                .containsExactly("第 1 个知识点：文件不在导入的文件中：chat/Other.java");
        assertThat(validate(kp(1, SUMMARY, List.of(new CodeEvidence("chat/ChatService.java", "ChatService#reply()")))))
                .containsExactly("第 1 个知识点：chat/ChatService.java 中没有 reply");
        assertThat(validate(kp(1, SUMMARY, List.of()))).containsExactly("第 1 个知识点：没有代码依据");
        assertThat(validate(kp(1, SUMMARY, Collections.nCopies(5, new CodeEvidence("chat/ChatService.java", "ChatService")))))
                .containsExactly("第 1 个知识点：代码依据有 5 处，最多 4 处");
    }

    @Test
    void 每条要点要有2到3个得分点() {
        List<String> problems = validate(kp(1, """
                1. 只有一个得分点。
                - 一个
                2. 有四个得分点。
                - 一
                - 二
                - 三
                - 四""", List.of(new CodeEvidence("chat/ChatService.java", "ChatService"))));

        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).startsWith("第 1 个知识点：第 1 条要点有 1 个得分点");
        assertThat(problems.get(1)).startsWith("第 1 个知识点：第 2 条要点有 4 个得分点");
    }

    @Test
    void 要点的条数和格式() {
        String five = IntStream.rangeClosed(1, 5).mapToObj(i -> i + ". 要点\n- 甲\n- 乙").reduce((a, b) -> a + "\n" + b).orElseThrow();
        assertThat(CodeExtractionValidator.checkScorePoints(five, "")).containsExactly(
                "要点要有 1～4 条，每条单独一行、用“1. 2. 3.”编号，实际有 5 条");
        // 要点写在一行里、没有得分点：认不出编号
        assertThat(CodeExtractionValidator.checkScorePoints("1. 甲。2. 乙。", "")).containsExactly(
                "第 1 条要点有 0 个得分点，要 2～3 个，每个单独一行、以“- ”开头");
        assertThat(CodeExtractionValidator.checkScorePoints("- 甲\n- 乙", "")).containsExactly("得分点前面没有编号的要点");
        assertThat(CodeExtractionValidator.checkScorePoints("要点没有编号", "")).containsExactly(
                "要点要有 1～4 条，每条单独一行、用“1. 2. 3.”编号，实际有 0 条");
        assertThat(CodeExtractionValidator.checkScorePoints(" ", "")).containsExactly("没有要点");
        // 要点续写成两行、中间有空行，都不影响计数
        assertThat(CodeExtractionValidator.checkScorePoints("1、要点的前半句\n接着写后半句\n\n- 甲\n  - 乙", "")).isEmpty();
    }

    @Test
    void 编号连续_数量和标题() {
        assertThat(validate(kp(1), kp(3))).containsExactly("order 要从 1 开始连续编号，实际是 [1, 3]");
        assertThat(validate(IntStream.rangeClosed(1, 21).mapToObj(CodeExtractionValidatorTest::kp)
                .toArray(KnowledgePoint[]::new))).containsExactly("知识点有 21 个，最多 20 个");
        KnowledgePoint untitled = new KnowledgePoint("CHAIN", List.of(new CodeEvidence("chat/ChatService.java", "ChatService")),
                1, List.of(), SUMMARY, "", " ", List.of());
        assertThat(validate(untitled)).containsExactly("第 1 个知识点：没有标题");
        assertThat(CodeExtractionValidator.validate(FILES, new CodeExtractionResult(null))).containsExactly("缺少 knowledgePoints");
    }

    @Test
    void 输入里每个文件前面一行路径_和实验的输入相同() {
        String input = CodeExtractionService.formatFiles(List.of(
                new CodeFileContent("a/A.java", "class A {}"), new CodeFileContent("b/B.java", "class B {}\n")));

        assertThat(input).isEqualTo("以下是学生项目中的 2 个文件。\n\n===== 文件：a/A.java =====\nclass A {}\n"
                + "\n===== 文件：b/B.java =====\nclass B {}\n");
    }
}
