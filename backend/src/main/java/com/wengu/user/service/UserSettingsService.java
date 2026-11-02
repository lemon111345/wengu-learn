package com.wengu.user.service;

import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.dto.UpdateSettingsRequest;
import com.wengu.user.entity.UserSettings;
import com.wengu.user.mapper.UserSettingsMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * 用户设置的读取和修改。其他模块只通过这里读设置，不访问 user_settings 表（决策 002）。
 */
@Service
public class UserSettingsService {

    /** 每天新学数量的上限，防止误填一个很大的数。 */
    public static final int MAX_DAILY_NEW_LIMIT = 50;

    /** 新用户的默认值（决策 009 第 5 条）。 */
    static final int DEFAULT_DAILY_NEW_LIMIT = 3;
    static final int DEFAULT_DAY_START_HOUR = 4;

    private final UserSettingsMapper settingsMapper;
    /** 注册时读不到浏览器时区，或者它不合法时使用，来自 WENGU_TIMEZONE（决策 017）。 */
    private final String defaultTimezone;

    public UserSettingsService(UserSettingsMapper settingsMapper,
                               @Value("${wengu.default-timezone}") String defaultTimezone) {
        this.settingsMapper = settingsMapper;
        this.defaultTimezone = validTimezone(defaultTimezone);
    }

    /**
     * 写入新用户的默认设置，注册时调用。浏览器提交的时区合法就用它，否则用 WENGU_TIMEZONE，不报错：
     * 时区是静默提交的，用户不知道有这回事，以后可以在设置页改。
     */
    void createDefaults(long userId, String browserTimezone) {
        UserSettings settings = new UserSettings();
        settings.setUserId(userId);
        settings.setDailyNewLimit(DEFAULT_DAILY_NEW_LIMIT);
        settings.setDayStartHour(DEFAULT_DAY_START_HOUR);
        settings.setTimezone(timezoneOrDefault(browserTimezone));
        settingsMapper.insert(settings);
    }

    private String timezoneOrDefault(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return defaultTimezone;
        }
        try {
            return validTimezone(timezone);
        } catch (BusinessException e) {
            return defaultTimezone;
        }
    }

    /**
     * 读取用户设置。
     *
     * @throws IllegalStateException 用户没有设置行。注册时会同时写入设置，出现这种情况说明数据有问题
     */
    public SettingsView get(long userId) {
        UserSettings settings = settingsMapper.selectById(userId);
        if (settings == null) {
            throw new IllegalStateException("用户 " + userId + " 没有设置");
        }
        return toView(settings);
    }

    /**
     * 只修改传了的字段，修改后立即生效：每日计划每次都实时计算，不缓存（技术方案“每日计划的计算逻辑”）。
     *
     * @throws BusinessException 字段不合法时为 BAD_REQUEST
     */
    public SettingsView update(long userId, UpdateSettingsRequest request) {
        UserSettings settings = settingsMapper.selectById(userId);
        if (settings == null) {
            throw new IllegalStateException("用户 " + userId + " 没有设置");
        }
        if (request.timezone() != null) {
            settings.setTimezone(validTimezone(request.timezone()));
        }
        if (request.dayStartHour() != null) {
            if (request.dayStartHour() < 0 || request.dayStartHour() > 23) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "一天的开始时间应在 0～23 点之间");
            }
            settings.setDayStartHour(request.dayStartHour());
        }
        if (request.dailyNewLimit() != null) {
            if (request.dailyNewLimit() < 0 || request.dailyNewLimit() > MAX_DAILY_NEW_LIMIT) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "每天新学的数量应在 0～" + MAX_DAILY_NEW_LIMIT + " 之间");
            }
            settings.setDailyNewLimit(request.dailyNewLimit());
        }
        settingsMapper.updateById(settings);
        return toView(settings);
    }

    /** 必须是 IANA 时区（如 Asia/Shanghai）。也接受 +08:00 这样的固定偏移，但它没有夏令时，一般不推荐。 */
    private static String validTimezone(String timezone) {
        String trimmed = timezone.strip();
        try {
            return ZoneId.of(trimmed).getId();
        } catch (DateTimeException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "时区不合法：" + trimmed + "，应为 Asia/Shanghai 这样的时区");
        }
    }

    private static SettingsView toView(UserSettings settings) {
        return new SettingsView(settings.getTimezone(), settings.getDayStartHour(), settings.getDailyNewLimit());
    }
}
