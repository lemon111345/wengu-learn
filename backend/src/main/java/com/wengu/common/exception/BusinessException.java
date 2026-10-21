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

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
