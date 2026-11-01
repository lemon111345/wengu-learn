package com.wengu;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MockMvc 请求带上 CSRF 令牌：Cookie 和请求头中放同一个值，和浏览器里的前端一样（SecurityConfig）。
 * 服务端只核对两者是否一致，跨站的页面读不到 Cookie，也就填不出请求头。
 * <p>
 * 不用 spring-security-test 的 {@code csrf()}：它会把 Spring 上下文中 CsrfFilter 的令牌仓库换成测试用的，
 * 而且不会换回来。同一个上下文里的测试共用这个过滤器，用过一次之后，服务端就不再往 Cookie 里写令牌了，
 * 检查真实流程的测试会因为执行顺序而时过时不过。
 */
public final class TestCsrf {

    private static final String TOKEN = "test-csrf-token";

    private TestCsrf() {
    }

    public static RequestPostProcessor csrfToken() {
        return request -> {
            List<Cookie> cookies = new ArrayList<>();
            if (request.getCookies() != null) {
                cookies.addAll(Arrays.asList(request.getCookies()));
            }
            cookies.add(new Cookie("XSRF-TOKEN", TOKEN));
            request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN", TOKEN);
            return request;
        };
    }
}
