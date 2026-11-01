package com.wengu.common.context;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把登录状态存进 Session、从 Session 中清掉。密码由 user 模块校验，通过之后调用这里（决策 022）。
 * <p>
 * 没有用 Spring Security 的表单登录：登录接口收发 JSON，由 Controller 自己处理，
 * 所以要像表单登录那样自己做两件事：更换 Session id，把登录信息存进 Session。
 */
@Component
public class LoginSession {

    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public void login(LoginUser user, HttpServletRequest request, HttpServletResponse response) {
        // 登录前就有的 Session（如浏览过登录页）换一个 id，防止 Session 固定攻击：
        // 别人事先让你用上他知道 id 的 Session，你登录之后他就能用这个 id 冒充你
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
