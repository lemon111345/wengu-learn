package com.wengu.chat.service;

import com.wengu.chat.dto.RateRequest;
import com.wengu.chat.dto.RatingOption;
import com.wengu.chat.entity.SessionType;
import com.wengu.chat.entity.StudySession;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.common.time.StudyDay;
import com.wengu.review.dto.ReviewResult;
import com.wengu.review.fsrs.Rating;
import com.wengu.review.service.ReviewService;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.service.UserSettingsService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 学完后的评分（决策 002 第 5 条、决策 026）：评分预览和确认评分。
 * <p>
 * chat 核对会话（属于当前用户、是学习会话、已经学完、还没评过分），再交给 review 调度；
 * review 只认 kp_id，不知道会话是什么。下次复习的时间换算成用户的学习日返回，前端不用浏览器的日期。
 */
@Service
public class RatingService {

    private final StudySessionService sessions;
    private final RatingSuggestionService suggestions;
    private final ReviewService reviewService;
    private final UserSettingsService settingsService;
    private final Clock clock;

    public RatingService(StudySessionService sessions, RatingSuggestionService suggestions,
                         ReviewService reviewService, UserSettingsService settingsService, Clock clock) {
        this.sessions = sessions;
        this.suggestions = suggestions;
        this.reviewService = reviewService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** 四种评分分别对应的下次复习日期，按忘了、困难、良好、简单排列，不保存。 */
    public List<RatingOption> preview(long userId, long sessionId) {
        StudySession session = ratable(userId, sessionId);
        Map<Rating, Instant> dues = reviewService.preview(userId, session.getKpId());
        SettingsView settings = settingsService.get(userId);
        return Arrays.stream(Rating.values()).map(r -> option(r, dues.get(r), settings)).toList();
    }

    /**
     * 确认评分：更新调度状态、写评分流水，AI 建议已经生成的话一并记下。返回选中评分的下次复习日期。
     *
     * @throws BusinessException 评分为空时为 BAD_REQUEST；会话不能评分时见 {@link #ratable}
     */
    public RatingOption rate(long userId, long sessionId, RateRequest request) {
        if (request == null || request.rating() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择一个评分");
        }
        StudySession session = ratable(userId, sessionId);
        ReviewResult result = reviewService.rate(userId, session.getKpId(), sessionId, request.rating(),
                suggestions.suggestedRating(sessionId));
        return option(result.rating(), result.due(), settingsService.get(userId));
    }

    /**
     * 能评分的会话：属于当前用户、是学习会话、已经学完、还没评过分。
     *
     * @throws BusinessException 不存在时为 NOT_FOUND；接着问为 BAD_REQUEST；还没学完、已经评过分时为 CONFLICT
     */
    private StudySession ratable(long userId, long sessionId) {
        StudySession session = sessions.find(userId, sessionId);
        if (session.getType() != SessionType.STUDY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有学习会话需要评分");
        }
        if (session.getEndedAt() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "学完之后才能评分");
        }
        if (reviewService.isRated(sessionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "这次学习已经评过分了");
        }
        return session;
    }

    /** 把下次复习的时间换算成学习日，并算出距今天几个学习日 */
    private RatingOption option(Rating rating, Instant due, SettingsView settings) {
        LocalDate today = StudyDay.of(Instant.now(clock), settings.zoneId(), settings.dayStartHour()).date();
        LocalDate date = StudyDay.of(due, settings.zoneId(), settings.dayStartHour()).date();
        return new RatingOption(rating, date, ChronoUnit.DAYS.between(today, date));
    }
}
