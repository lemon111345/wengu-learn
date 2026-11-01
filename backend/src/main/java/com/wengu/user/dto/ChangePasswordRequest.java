package com.wengu.user.dto;

public record ChangePasswordRequest(String oldPassword, String newPassword) {

    /** 不打印密码，见 {@link RegisterRequest#toString()}。 */
    @Override
    public String toString() {
        return "ChangePasswordRequest[]";
    }
}
