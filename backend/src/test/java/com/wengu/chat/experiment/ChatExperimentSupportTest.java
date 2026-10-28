package com.wengu.chat.experiment;

import com.wengu.chat.experiment.RatingSuggestion.Rating;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.llm.dto.ChatTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatExperimentSupportTest {

    private final ChatKnowledgePoint kp = new ChatKnowledgePoint(
            "什么是回表？", KnowledgePointType.CONCEPT, "二级索引查到主键后回聚簇索引取整行",
            List.of("MySQL 中的回表是什么？", "什么是覆盖索引？"), "很熟");

    @Test
    void 第一条消息包含知识点信息但不包含作者自述() {
        String kickoff = ChatExperimentSupport.kickoff(kp);

        assertThat(kickoff)
                .contains("知识点标题：什么是回表？")
                .contains("类型：CONCEPT（原子概念）")
                .contains("二级索引查到主键后回聚簇索引取整行")
                .contains("- MySQL 中的回表是什么？\n- 什么是覆盖索引？")
                .endsWith("请开始。")
                .doesNotContain("很熟");
    }

    @Test
    void 结束标记单独成行或写在行尾都能识别() {
        assertThat(ChatExperimentSupport.containsEndMarker("总结……\n【学习结束】")).isTrue();
        assertThat(ChatExperimentSupport.containsEndMarker("总结……【学习结束】")).isTrue();
        assertThat(ChatExperimentSupport.containsEndMarker("你能说说回表的过程吗？")).isFalse();
        assertThat(ChatExperimentSupport.containsEndMarker(null)).isFalse();
    }

    @Test
    void 评分输入按角色标出对话() {
        List<ChatTurn> dialogue = List.of(
                ChatTurn.assistant("你知道什么是聚簇索引吗？"),
                ChatTurn.user("  主键索引，叶子节点存整行  "));

        String input = ChatExperimentSupport.ratingInput(kp, dialogue);

        assertThat(input)
                .startsWith("知识点：什么是回表？")
                .endsWith("【老师】\n你知道什么是聚簇索引吗？\n\n【学生】\n主键索引，叶子节点存整行");
    }

    @Test
    void 倒数第二次回答要求完整作答最后一次要求收尾() {
        assertThat(ChatExperimentSupport.withTurnNote("答案", 6, 8)).isEqualTo("答案");
        assertThat(ChatExperimentSupport.withTurnNote("答案", 7, 8))
                .isEqualTo("答案\n\n" + ChatExperimentSupport.RESTATE_NOTE);
        assertThat(ChatExperimentSupport.withTurnNote("答案", 8, 8))
                .isEqualTo("答案\n\n" + ChatExperimentSupport.MAX_TURNS_NOTE);
    }

    @Test
    void CSV行计算差距并正确转义() {
        SelfAssessment self = new SelfAssessment(Rating.GOOD, "是", "有效，但第二问偏了", "说 \"还行\"");
        RatingSuggestion ai = new RatingSuggestion("回答完整, 无遗漏", List.of("B+ 树为什么矮？"), Rating.EASY,
                List.of("覆盖索引", "索引代价"));

        String row = ChatExperimentSupport.csvRow(kp, 4, true, self, ai);

        assertThat(row)
                .startsWith("\"什么是回表？\",\"CONCEPT（原子概念）\",\"很熟\",\"4\",\"是\",\"EASY（简单）\"")
                .contains("\"回答完整, 无遗漏\",\"覆盖索引\n索引代价\",\"B+ 树为什么矮？\",\"GOOD（良好）\",\"1\"")
                .endsWith("\"说 \"\"还行\"\"\"");
    }

    @Test
    void 对话记录能解析回写入前的内容() {
        List<ChatTurn> dialogue = List.of(
                ChatTurn.assistant("先摸个底：\n\n你知道什么是聚簇索引吗？"),
                ChatTurn.user("主键索引，叶子节点存整行"),
                ChatTurn.assistant("对。参考答法……\n\n【学习结束】"));
        SelfAssessment self = new SelfAssessment(Rating.GOOD, "是", "有效", "");
        RatingSuggestion ai = new RatingSuggestion("答出了核心", List.of(), Rating.HARD, List.of("覆盖索引"));

        ChatExperimentSupport.ParsedTranscript parsed =
                ChatExperimentSupport.parseTranscript(ChatExperimentSupport.transcript(kp, dialogue, self, ai, null));

        assertThat(parsed.title()).isEqualTo("什么是回表？");
        assertThat(parsed.dialogue()).isEqualTo(dialogue);
        assertThat(parsed.selfRating()).isEqualTo(Rating.GOOD);
        assertThat(parsed.aiRating()).isEqualTo(Rating.HARD);
    }

    @Test
    void 评分建议失败的记录解析出的AI评分为空() {
        List<ChatTurn> dialogue = List.of(ChatTurn.assistant("你知道什么是聚簇索引吗？"), ChatTurn.user("不知道"));
        SelfAssessment self = new SelfAssessment(Rating.AGAIN, "否", "有效", "");

        ChatExperimentSupport.ParsedTranscript parsed =
                ChatExperimentSupport.parseTranscript(ChatExperimentSupport.transcript(kp, dialogue, self, null, "超时"));

        assertThat(parsed.selfRating()).isEqualTo(Rating.AGAIN);
        assertThat(parsed.aiRating()).isNull();
    }

    @Test
    void 评分建议失败时AI相关列留空() {
        SelfAssessment self = new SelfAssessment(Rating.HARD, "部分", "有效", "");

        String row = ChatExperimentSupport.csvRow(kp, 8, false, self, null);

        assertThat(row).contains("\"否\",\"\",\"\",\"\",\"\",\"HARD（困难）\",\"\"");
    }
}
