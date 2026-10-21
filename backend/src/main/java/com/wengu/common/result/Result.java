package com.wengu.common.result;

/**
 * 统一返回格式：所有接口的响应体都是 {code, message, data}。
 * <p>
 * HTTP 状态码表达真实结果（200 / 400 / 404 / 500），body 中的 code 给出更细的业务原因。
 *
 * @param code    返回码，取自 {@link ErrorCode} 的枚举名
 * @param message 给用户看的提示
 * @param data    成功时的数据，失败时为 null
 */
public record Result<T>(String code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.name(), ErrorCode.OK.getMessage(), data);
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static Result<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static Result<Void> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.name(), message, null);
    }
}
