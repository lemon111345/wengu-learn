package com.wengu.user.dto;

/**
 * 注册是否开放。注册页据此决定显示表单还是“暂未开放注册”。
 *
 * @param open 由 WENGU_ALLOW_REGISTRATION 控制，默认开放（决策 017）
 */
public record RegistrationStatus(boolean open) {
}
