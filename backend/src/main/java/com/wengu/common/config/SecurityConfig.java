package com.wengu.common.config;

import com.wengu.common.result.ErrorCode;
import com.wengu.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 登录与鉴权（决策 017、022）：凭证是服务端 Session，浏览器只拿到一个 HttpOnly 的 Cookie。
 * <p>
 * 除了注册、登录和健康检查，所有接口都要求登录。没有登录时返回统一格式的 401，不跳转登录页：
 * 页面跳转由前端负责。
 * <p>
 * 这里不读用户表，所以放在 common；校验密码在 user 模块，通过后由 {@link com.wengu.common.context.LoginSession}
 * 把登录信息存进 Session。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/auth/registration").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/logout")
                        .permitAll()
                        // 出错时 Spring Boot 转发到 /error 生成响应，不放开的话真正的错误会被 401 盖住
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // CSRF 令牌放在前端能读到的 Cookie（XSRF-TOKEN）中，前端在请求头 X-XSRF-TOKEN 中带回。
                // 跨站的页面读不到这个 Cookie，也就伪造不了请求头
                .csrf(csrf -> csrf.spa())
                // 令牌默认要用到时才生成，GET 请求用不到，Cookie 就一直不写，前端第一次 POST（登录）就会被拒。
                // 每个请求都读一下令牌，没有时生成并写进 Cookie
                .addFilterAfter((request, response, chain) -> {
                    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                    if (token != null) {
                        token.getToken();
                    }
                    chain.doFilter(request, response);
                }, CsrfFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                write(response, ErrorCode.UNAUTHORIZED, jsonMapper))
                        .accessDeniedHandler((request, response, ex) ->
                                write(response, ErrorCode.FORBIDDEN, jsonMapper)))
                // 只有 JSON 接口：不要登录页、浏览器弹窗式的登录，退出登录由 AuthController 处理
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 默认会把未登录的请求存进 Session，登录后跳回去。前端自己记着要回哪里，不需要；
                // 存的话每个未登录的请求都会创建一个 Session
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /** 默认用 bcrypt，存进库里的哈希带 {bcrypt} 前缀，以后换算法时旧密码照样能校验。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static void write(HttpServletResponse response, ErrorCode errorCode, JsonMapper jsonMapper)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(), Result.fail(errorCode));
    }
}
