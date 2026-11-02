package com.wengu.common.context;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 当前用户。各模块通过它拿到 user_id，不为此依赖 user 模块（见决策 002）。
 * <p>
 * 登录信息由 Spring Security 从 Session 中取出，放在当前请求线程上，所以只在 Controller 中使用：
 * Controller 取出 id 传给 Service。后台线程和 SSE 的异步线程读不到它（决策 013、022）。
 */
@Component
public class CurrentUser {

    public long id() {
        return get().id();
    }

    /**
     * @throws IllegalStateException 没有登录信息。除了注册、登录等开放的接口，其他接口都要求登录（SecurityConfig），
     *                               出现这种情况说明是在开放的接口或后台线程中调用了它
     */
    public LoginUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        throw new IllegalStateException("当前线程没有登录信息");
    }
}
