package com.wengu.user.controller;

import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import com.wengu.user.dto.SettingsView;
import com.wengu.user.dto.UpdateSettingsRequest;
import com.wengu.user.service.UserSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final UserSettingsService settingsService;
    private final CurrentUser currentUser;

    public SettingsController(UserSettingsService settingsService, CurrentUser currentUser) {
        this.settingsService = settingsService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Result<SettingsView> get() {
        return Result.ok(settingsService.get(currentUser.id()));
    }

    /** 只修改传了的字段，例如 {"dailyNewLimit": 5}。 */
    @PatchMapping
    public Result<SettingsView> update(@RequestBody UpdateSettingsRequest request) {
        return Result.ok(settingsService.update(currentUser.id(), request));
    }
}
