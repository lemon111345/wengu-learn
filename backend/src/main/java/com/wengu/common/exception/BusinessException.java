package com.wengu.common.exception;

import com.wengu.common.result.ErrorCode;

/**
 * 业务异常：表示“可预期的失败”，如知识点不存在、额度用完。
 * 由 {@link GlobalExceptionHandler} 转成对应状态码和统一返回格式，message 会直接展示给用户。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 带上原始异常，排查时能看到真正的原因（如大模型调用失败，决策 032） */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
