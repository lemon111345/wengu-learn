package com.wengu.user.service;

import com.wengu.TestcontainersConfiguration;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.dto.UpdateSettingsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserSettingsServiceTest {

    @Autowired
    private UserSettingsService settingsService;

    /** 测试共用一个数据库，每个测试前把默认用户的设置恢复成 V1 中的默认值。 */
    @BeforeEach
    void restoreDefaults() {
        settingsService.update(1, new UpdateSettingsRequest("Asia/Shanghai", 4, 3));
    }

    @Test
    void 读取默认用户的设置() {
        assertThat(settingsService.get(1)).isEqualTo(new SettingsView("Asia/Shanghai", 4, 3));
    }

    @Test
    void 只修改传了的字段() {
        SettingsView updated = settingsService.update(1, new UpdateSettingsRequest(null, null, 5));

        assertThat(updated).isEqualTo(new SettingsView("Asia/Shanghai", 4, 5));
        assertThat(settingsService.get(1)).isEqualTo(updated);

        settingsService.update(1, new UpdateSettingsRequest(" America/New_York ", 0, null));
        assertThat(settingsService.get(1)).isEqualTo(new SettingsView("America/New_York", 0, 5));
    }

    @Test
    void 边界值可以保存() {
        settingsService.update(1, new UpdateSettingsRequest(null, 23, 0));
        assertThat(settingsService.get(1)).isEqualTo(new SettingsView("Asia/Shanghai", 23, 0));

        settingsService.update(1, new UpdateSettingsRequest(null, null, UserSettingsService.MAX_DAILY_NEW_LIMIT));
        assertThat(settingsService.get(1).dailyNewLimit()).isEqualTo(UserSettingsService.MAX_DAILY_NEW_LIMIT);
    }

    @Test
    void 不合法的值返回400且不修改() {
        assertBadRequest(new UpdateSettingsRequest("Mars/Olympus", null, null));
        assertBadRequest(new UpdateSettingsRequest(null, 24, null));
        assertBadRequest(new UpdateSettingsRequest(null, -1, null));
        assertBadRequest(new UpdateSettingsRequest(null, null, -1));
        assertBadRequest(new UpdateSettingsRequest(null, null, UserSettingsService.MAX_DAILY_NEW_LIMIT + 1));
        // 一个字段不合法时，同一请求中合法的字段也不保存
        assertBadRequest(new UpdateSettingsRequest(null, 5, 99));

        assertThat(settingsService.get(1)).isEqualTo(new SettingsView("Asia/Shanghai", 4, 3));
    }

    private void assertBadRequest(UpdateSettingsRequest request) {
        assertThatThrownBy(() -> settingsService.update(1, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }
}
