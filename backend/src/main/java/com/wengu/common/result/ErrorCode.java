package com.wengu.common.result;

import org.springframework.http.HttpStatus;

/**
 * 返回码。枚举名直接作为返回给前端的 code（如 "NOT_FOUND"），因此改名等于改接口，需谨慎。
 * <p>
 * 这里只放通用返回码，各业务模块需要时在此追加（如 KP_NOT_FOUND）。
 */
public enum ErrorCode {

    OK(HttpStatus.OK, "成功"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求参数错误"),
    /** 没有登录，或登录已经失效。前端收到后跳转登录页 */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "请先登录"),
    /** 不区分“用户不存在”和“密码错误”，不让人借此试探哪些用户名已注册 */
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    /** 已经登录，但请求被拒绝。目前只有 CSRF 令牌缺失或不对这一种情况 */
    FORBIDDEN(HttpStatus.FORBIDDEN, "请求被拒绝，请刷新页面重试"),
    /** 部署时用 WENGU_ALLOW_REGISTRATION 关闭了注册（决策 017） */
    REGISTRATION_CLOSED(HttpStatus.FORBIDDEN, "暂未开放注册"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "不支持的请求方法"),
    /** 资源当前的状态不允许这个操作，如草稿还在抽取中时重试 */
    CONFLICT(HttpStatus.CONFLICT, "当前状态不允许这个操作"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被使用"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),

    /** 模型的切分结果重试后仍不可用。502：上游（模型）返回了无效的结果 */
    QUESTION_SPLIT_FAILED(HttpStatus.BAD_GATEWAY, "题目识别失败，可以重试，或检查原文格式");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /** 默认提示语，抛异常时可以用更具体的提示覆盖。 */
    public String getMessage() {
        return message;
    }
}
