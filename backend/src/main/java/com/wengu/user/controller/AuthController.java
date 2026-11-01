package com.wengu.user.controller;

import com.wengu.common.context.CurrentUser;
import com.wengu.common.context.LoginSession;
import com.wengu.common.context.LoginUser;
import com.wengu.common.result.Result;
import com.wengu.user.dto.ChangePasswordRequest;
import com.wengu.user.dto.LoginRequest;
import com.wengu.user.dto.RegisterRequest;
import com.wengu.user.dto.RegistrationStatus;
import com.wengu.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册、登录、退出登录、当前用户、修改密码（决策 022）。哪些接口不需要登录见 SecurityConfig。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final LoginSession loginSession;
    private final CurrentUser currentUser;

    public AuthController(UserService userService, LoginSession loginSession, CurrentUser currentUser) {
        this.userService = userService;
        this.loginSession = loginSession;
        this.currentUser = currentUser;
    }

    /** 注册是否开放，不需要登录。 */
    @GetMapping("/registration")
    public Result<RegistrationStatus> registration() {
        return Result.ok(new RegistrationStatus(userService.registrationOpen()));
    }

    /** 注册后直接登录：注册页的按钮是“注册并开始”，接着进入今天页。 */
    @PostMapping("/register")
    public Result<LoginUser> register(@RequestBody RegisterRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        LoginUser user = userService.register(body);
        loginSession.login(user, request, response);
        return Result.ok(user);
    }

    @PostMapping("/login")
    public Result<LoginUser> login(@RequestBody LoginRequest body, HttpServletRequest request,
                                   HttpServletResponse response) {
        LoginUser user = userService.authenticate(body);
        loginSession.login(user, request, response);
        return Result.ok(user);
    }

    /** 没有登录时调用也返回成功：结果一样，都是“没有登录”。 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        loginSession.logout(request);
        return Result.ok();
    }

    /** 当前登录的用户。前端打开页面时调用，没有登录时返回 401。 */
    @GetMapping("/me")
    public Result<LoginUser> me() {
        return Result.ok(currentUser.get());
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest body) {
        userService.changePassword(currentUser.id(), body);
        return Result.ok();
    }
}
