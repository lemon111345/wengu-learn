package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.common.time.StudyDay;
import com.wengu.knowledge.dto.KnowledgePointDetail;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.LibraryItem;
import com.wengu.knowledge.dto.LibrarySort;
import com.wengu.knowledge.dto.LibraryView;
import com.wengu.knowledge.dto.Mastery;
import com.wengu.knowledge.dto.NextReview;
import com.wengu.knowledge.dto.SortDirection;
import com.wengu.knowledge.dto.SourceRef;
import com.wengu.knowledge.dto.StudyRecord;
import com.wengu.knowledge.entity.ExtractionDraft;
import com.wengu.knowledge.entity.ExtractionDraftItem;
import com.wengu.knowledge.entity.KnowledgePoint;
import com.wengu.knowledge.entity.KpSource;
import com.wengu.knowledge.mapper.ExtractionDraftItemMapper;
import com.wengu.knowledge.mapper.ExtractionDraftMapper;
import com.wengu.knowledge.mapper.KnowledgePointMapper;
import com.wengu.knowledge.mapper.KpSourceMapper;
import com.wengu.review.dto.CardProgress;
import com.wengu.review.service.ReviewService;
import com.wengu.source.service.SourceService;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.service.UserSettingsService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识库页面的查询：列表和详情（#50，决策 030）。只读，修改在 {@link KnowledgePointService}。
 * <p>
 * 和 KnowledgePointService 一样，查询中显式排除已删除的知识点（决策 029）。
 * 掌握程度要用 review 的复习进度，学习日要用 user 的设置（knowledge → user，决策 002）。
 */
@Service
public class KnowledgeLibraryService {

    private final KnowledgePointMapper knowledgePointMapper;
    private final KpSourceMapper kpSourceMapper;
    private final ExtractionDraftItemMapper draftItemMapper;
    private final ExtractionDraftMapper draftMapper;
    private final ReviewService reviewService;
    private final SourceService sourceService;
    private final UserSettingsService settingsService;
    private final Clock clock;

    public KnowledgeLibraryService(KnowledgePointMapper knowledgePointMapper, KpSourceMapper kpSourceMapper,
                                   ExtractionDraftItemMapper draftItemMapper, ExtractionDraftMapper draftMapper,
                                   ReviewService reviewService, SourceService sourceService,
                                   UserSettingsService settingsService, Clock clock) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.kpSourceMapper = kpSourceMapper;
        this.draftItemMapper = draftItemMapper;
        this.draftMapper = draftMapper;
        this.reviewService = reviewService;
        this.sourceService = sourceService;
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
     * 知识点详情。
     * <ul>
     *   <li>要点是否改过：和确认时关联的草稿条目（AI 原文）对比，不另存（决策 029）</li>
     *   <li>学习记录：合并时留在被合并的知识点上（决策 029），这里按 merged_into 一起取出；
     *       merged_into 始终指向还在的知识点，只查一层</li>
     *   <li>来源：kp_source 关联的材料（合并时已经并过来了），题号来自自己和并进来的知识点的草稿条目</li>
     * </ul>
     *
     * @throws BusinessException 知识点不存在、不属于这个用户或已删除时为 NOT_FOUND
     */
    public KnowledgePointDetail detail(long userId, long kpId) {
        KnowledgePoint kp = knowledgePointMapper.selectById(kpId);
        if (kp == null || kp.getUserId() != userId || kp.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识点不存在");
        }
        List<KnowledgePoint> merged = knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                .select(KnowledgePoint::getId, KnowledgePoint::getTitle, KnowledgePoint::getDraftItemId)
                .eq(KnowledgePoint::getUserId, userId)
                .eq(KnowledgePoint::getMergedInto, kpId));

        CardProgress card = reviewService.progressOf(userId, List.of(kpId)).get(kpId);
        Mastery mastery = Mastery.of(kp.getType(), kp.getStatus(), card);
        SettingsView settings = settingsService.get(userId);
        LocalDate today = studyDate(Instant.now(clock), settings);

        Map<Long, ExtractionDraftItem> draftItems = draftItems(userId, Stream.concat(Stream.of(kp), merged.stream())
                .map(KnowledgePoint::getDraftItemId).filter(Objects::nonNull).toList());
        ExtractionDraftItem own = kp.getDraftItemId() == null ? null : draftItems.get(kp.getDraftItemId());
        String aiSummary = own == null ? null : own.getSummary();
        boolean edited = aiSummary != null && !aiSummary.strip().equals(kp.getSummary().strip());

        return new KnowledgePointDetail(kp.getId(), kp.getTitle(), kp.getType(), kp.getStatus(), mastery,
                nextReview(mastery, card, today, settings), kp.getSummary(), aiSummary, edited,
                studyRecords(userId, kpId, merged, settings), sources(userId, kpId, draftItems.values()));
    }

    /** 学习记录，最近的在前；并进来的知识点的记录带上它的标题 */
    private List<StudyRecord> studyRecords(long userId, long kpId, List<KnowledgePoint> merged, SettingsView settings) {
        Map<Long, String> mergedTitles = merged.stream()
                .collect(Collectors.toMap(KnowledgePoint::getId, KnowledgePoint::getTitle));
        List<Long> ids = new ArrayList<>(mergedTitles.keySet());
        ids.add(kpId);
        return reviewService.reviewsOf(userId, ids).stream()
                .map(r -> new StudyRecord(studyDate(r.reviewedAt(), settings), r.firstReview(), r.rating(),
                        r.ratingAi(), mergedTitles.get(r.kpId()), r.sessionId()))
                .toList();
    }

    private Map<Long, ExtractionDraftItem> draftItems(long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return draftItemMapper.selectList(new LambdaQueryWrapper<ExtractionDraftItem>()
                        .eq(ExtractionDraftItem::getUserId, userId)
                        .in(ExtractionDraftItem::getId, ids))
                .stream()
                .collect(Collectors.toMap(ExtractionDraftItem::getId, item -> item));
    }

    /**
     * 来源材料和题号。材料来自 kp_source；题号按“草稿条目 → 草稿的材料”找到，只认 kp_source 中有的材料。
     * 材料被删掉了（名称查不到）的不显示。
     */
    private List<SourceRef> sources(long userId, long kpId, Collection<ExtractionDraftItem> items) {
        List<Long> sourceIds = kpSourceMapper.selectList(new LambdaQueryWrapper<KpSource>()
                        .eq(KpSource::getKpId, kpId)
                        .orderByAsc(KpSource::getSourceId))
                .stream().map(KpSource::getSourceId).toList();
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = sourceService.getNames(userId, sourceIds);

        Map<Long, Long> draftSources = items.isEmpty() ? Map.of()
                : draftMapper.selectList(new LambdaQueryWrapper<ExtractionDraft>()
                        .select(ExtractionDraft::getId, ExtractionDraft::getSourceId)
                        .in(ExtractionDraft::getId, items.stream().map(ExtractionDraftItem::getDraftId).toList()))
                .stream()
                .filter(draft -> draft.getSourceId() != null)
                .collect(Collectors.toMap(ExtractionDraft::getId, ExtractionDraft::getSourceId));
        Map<Long, Set<Integer>> questions = new HashMap<>();
        for (ExtractionDraftItem item : items) {
            Long sourceId = draftSources.get(item.getDraftId());
            if (sourceId != null) {
                Arrays.stream(item.getSourceQuestionIds().split(","))
                        .map(String::strip)
                        .map(Integer::valueOf)
                        .forEach(id -> questions.computeIfAbsent(sourceId, k -> new TreeSet<>()).add(id));
            }
        }

        return sourceIds.stream()
                .filter(names::containsKey)
                .map(id -> new SourceRef(id, names.get(id), List.copyOf(questions.getOrDefault(id, Set.of()))))
                .toList();
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
