package com.wengu.knowledge.dto;

import com.wengu.knowledge.entity.KnowledgePointStatus;
import com.wengu.review.dto.CardProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static com.wengu.knowledge.entity.KnowledgePointStatus.ACTIVE;
import static com.wengu.knowledge.entity.KnowledgePointStatus.SUSPENDED;
import static org.assertj.core.api.Assertions.assertThat;

class MasteryTest {

    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

    private static CardProgress reviewed(double stability) {
        return new CardProgress(1, stability, NOW, NOW);
    }

    @ParameterizedTest
    @CsvSource({
            "100, SOLID",
            "21, SOLID",
            "20.99, FAMILIAR",
            "7, FAMILIAR",
            "6.99, FAIR",
            "2, FAIR",
            "1.99, SHAKY",
            "0.1, SHAKY"
    })
    void 学过的按稳定性分档_含下限不含上限(double stability, Mastery expected) {
        assertThat(Mastery.of(KnowledgePointType.CONCEPT, ACTIVE, reviewed(stability))).isEqualTo(expected);
    }

    @Test
    void 还没复习过或没有调度状态的是待学() {
        CardProgress newCard = new CardProgress(1, null, NOW, null);
        assertThat(Mastery.of(KnowledgePointType.CONCEPT, ACTIVE, newCard)).isEqualTo(Mastery.NEW);
        assertThat(Mastery.of(KnowledgePointType.CONCEPT, ACTIVE, null)).isEqualTo(Mastery.NEW);
    }

    @Test
    void 暂停优先于待学和稳定性() {
        assertThat(Mastery.of(KnowledgePointType.CONCEPT, SUSPENDED, reviewed(30))).isEqualTo(Mastery.SUSPENDED);
        assertThat(Mastery.of(KnowledgePointType.CONCEPT, SUSPENDED, null)).isEqualTo(Mastery.SUSPENDED);
    }

    @Test
    void 只收录的类型优先于暂停_改过来时保留的调度状态也不看() {
        assertThat(Mastery.of(KnowledgePointType.SYSTEM_DESIGN, ACTIVE, null)).isEqualTo(Mastery.RECORD_ONLY);
        assertThat(Mastery.of(KnowledgePointType.EXPERIENCE, SUSPENDED, null)).isEqualTo(Mastery.RECORD_ONLY);
        assertThat(Mastery.of(KnowledgePointType.SYSTEM_DESIGN, ACTIVE, reviewed(30))).isEqualTo(Mastery.RECORD_ONLY);
    }

    @Test
    void 只有按稳定性分档的四种算学过() {
        assertThat(Mastery.values()).filteredOn(Mastery::isLearned)
                .containsExactly(Mastery.SOLID, Mastery.FAMILIAR, Mastery.FAIR, Mastery.SHAKY);
    }

    @Test
    void 状态枚举只有两种() {
        // of 只区分暂停和其他；状态加了新值时提醒回来看这里的判断
        assertThat(KnowledgePointStatus.values()).containsExactly(ACTIVE, SUSPENDED);
    }
}
