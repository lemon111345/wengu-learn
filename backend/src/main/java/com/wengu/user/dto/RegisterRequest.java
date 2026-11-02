package com.wengu.user.dto;

/**
 * 注册。
 *
 * @param username 2～32 个字符：中文、字母、数字、下划线、连字符
 * @param password 8～64 个字符
 * @param timezone 前端从浏览器读取的 IANA 时区，静默提交；没有或不合法时用 WENGU_TIMEZONE（决策 017）
 */
public record RegisterRequest(String username, String password, String timezone) {

    /** 不打印密码：record 默认的 toString 会带上所有字段，日志或调试器里一不小心就露出来了。 */
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", timezone=" + timezone + "]";
    }
}
