package com.wengu.user.dto;

public record LoginRequest(String username, String password) {

    /** 不打印密码，见 {@link RegisterRequest#toString()}。 */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}
