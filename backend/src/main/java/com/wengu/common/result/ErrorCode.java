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
    NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "不支持的请求方法"),
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
