package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.common.time.StudyDay;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.LibraryItem;
import com.wengu.knowledge.dto.LibrarySort;
import com.wengu.knowledge.dto.LibraryView;
import com.wengu.knowledge.dto.Mastery;
import com.wengu.knowledge.dto.NextReview;
import com.wengu.knowledge.dto.SortDirection;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.review.dto.CardProgress;
import com.wengu.review.service.ReviewService;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.service.UserSettingsService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库页面的查询：列表和详情（#50，决策 030）。只读，修改在 {@link KnowledgePointService}。
 * <p>
 * 和 KnowledgePointService 一样，查询中显式排除已删除的知识点（决策 029）。
 * 掌握程度要用 review 的复习进度，学习日要用 user 的设置（knowledge → user，决策 002）。
 */
@Service
public class KnowledgeLibraryService {

    private final KnowledgePointMapper knowledgePointMapper;
    private final ReviewService reviewService;
    private final UserSettingsService settingsService;
    private final Clock clock;

    public KnowledgeLibraryService(KnowledgePointMapper knowledgePointMapper, ReviewService reviewService,
                                   UserSettingsService settingsService, Clock clock) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.reviewService = reviewService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** 一个知识点和它的掌握程度；card 为 null 表示没有调度状态 */
    private record Row(KnowledgePoint kp, Mastery mastery, CardProgress card) {
    }

    /**
     * 知识库列表。全部知识点在内存中处理：先判断掌握程度，按类型筛选后统计各掌握程度的数量，
     * 再按掌握程度筛选、排序。MVP 只有几百个知识点，和每日计划的取舍一样（决策 002）。
     *
     * @param mastery 只看这一种掌握程度，null 表示全部
     * @param type    只看这一种类型，null 表示全部
     */
    public LibraryView list(long userId, Mastery mastery, KnowledgePointType type,
                            LibrarySort sort, SortDirection direction) {
        // 先按学习顺序取出，后面的排序是稳定的：比不出先后的保持学习顺序
        List<KnowledgePoint> kps = knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                .select(KnowledgePoint::getId, KnowledgePoint::getTitle, KnowledgePoint::getType,
                        KnowledgePoint::getStatus)
                .eq(KnowledgePoint::getUserId, userId)
                .isNull(KnowledgePoint::getDeletedAt)
                .orderByAsc(KnowledgePoint::getSortOrder)
                .orderByAsc(KnowledgePoint::getId));
        Map<Long, CardProgress> progress = reviewService.progressOf(userId,
                kps.stream().map(KnowledgePoint::getId).toList());

        List<Row> typed = kps.stream()
                .filter(kp -> type == null || kp.getType() == type)
                .map(kp -> {
                    CardProgress card = progress.get(kp.getId());
                    return new Row(kp, Mastery.of(kp.getType(), kp.getStatus(), card), card);
                })
                .toList();

        // 数量随类型筛选变化，但不受掌握程度筛选影响：按钮上要显示切过去能看到几个
        Map<Mastery, Integer> counts = new EnumMap<>(Mastery.class);
        for (Mastery m : Mastery.values()) {
            counts.put(m, 0);
        }
        typed.forEach(row -> counts.merge(row.mastery(), 1, Integer::sum));

        List<Row> rows = new ArrayList<>(typed.stream()
                .filter(row -> mastery == null || row.mastery() == mastery)
                .toList());
        rows.sort(comparator(sort, direction));

        SettingsView settings = settingsService.get(userId);
        LocalDate today = studyDate(Instant.now(clock), settings);
        List<LibraryItem> items = rows.stream()
                .map(row -> new LibraryItem(row.kp().getId(), row.kp().getTitle(), row.kp().getType(),
                        row.mastery(), nextReview(row.mastery(), row.card(), today, settings)))
                .toList();
        return new LibraryView(kps.size(), counts, items);
    }

    /**
     * 排序规则（ui.md 5.9）：
     * <ul>
     *   <li>学习顺序：保持取出时的顺序</li>
     *   <li>掌握程度按稳定性，下次复习按到期时间；方向只影响学过的那些</li>
     *   <li>两种排序下，待学、暂停、只收录都排在学过的后面，依次是待学、暂停、只收录，组内保持学习顺序：
     *       它们没有稳定性或者不会复习，放进中间没有意义</li>
     * </ul>
     */
    private static Comparator<Row> comparator(LibrarySort sort, SortDirection direction) {
        if (sort == LibrarySort.ORDER) {
            return (a, b) -> 0;
        }
        Comparator<Row> key = sort == LibrarySort.MASTERY
                ? Comparator.comparing(row -> row.card().stability())
                : Comparator.comparing(row -> row.card().due());
        Comparator<Row> learned = direction == SortDirection.DESC ? key.reversed() : key;
        return Comparator.<Row>comparingInt(row -> group(row.mastery()))
                // 同一组中只有学过的才比较；其他组都返回 0，保持学习顺序
                .thenComparing((a, b) -> a.mastery().isLearned() ? learned.compare(a, b) : 0);
    }

    /** 排序时的分组：学过的在前，然后是待学、暂停、只收录 */
    private static int group(Mastery mastery) {
        return switch (mastery) {
            case SOLID, FAMILIAR, FAIR, SHAKY -> 0;
            case NEW -> 1;
            case SUSPENDED -> 2;
            case RECORD_ONLY -> 3;
        };
    }

    /**
     * 下次复习，只有学过的才有：待学的还没开始；暂停的虽然有到期时间，但暂停期间不会复习，显示出来会误导；
     * 只收录的不参与调度。
     */
    private static NextReview nextReview(Mastery mastery, CardProgress card, LocalDate today, SettingsView settings) {
        if (!mastery.isLearned()) {
            return null;
        }
        LocalDate date = studyDate(card.due(), settings);
        return new NextReview(date, ChronoUnit.DAYS.between(today, date));
    }

    private static LocalDate studyDate(Instant instant, SettingsView settings) {
        return StudyDay.of(instant, settings.zoneId(), settings.dayStartHour()).date();
    }
}
