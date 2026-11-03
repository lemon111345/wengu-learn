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
    QUESTION_SPLIT_FAILED(HttpStatus.BAD_GATEWAY, "题目识别失败，可以重试，或检查原文格式"),
    /** AI 评分建议没有生成出来（模型出错、或结果重试后仍不可用）。评分不依赖它，用户可以直接评分 */
    RATING_SUGGESTION_FAILED(HttpStatus.BAD_GATEWAY, "AI 评分建议没有生成出来，可以重试，也可以直接评分"),

    // 大模型调用失败（#27，决策 032）：由 llm 模块按异常分类，提示直接显示给用户。
    // 上游（模型服务）的问题用 502，超时用 504。提示只讲现象和怎么办，不提环境变量（ui.md 设计原则 5）。
    // 抽取批次的失败原因也按名称存进 extraction_batch.error_code，改名要同时考虑库里已有的值
    /** 401、403 */
    LLM_AUTH_FAILED(HttpStatus.BAD_GATEWAY, "AI 模型拒绝了请求，可能是 API Key 不对或已经失效"),
    /** 404：模型名不对，或本地模型还没下载 */
    LLM_MODEL_NOT_FOUND(HttpStatus.BAD_GATEWAY, "找不到配置的 AI 模型，可能是模型名不对，或者还没有下载"),
    /** 429 */
    LLM_RATE_LIMITED(HttpStatus.BAD_GATEWAY, "AI 模型的调用太频繁或额度用完了，请稍后再试"),
    LLM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 模型太久没有回应，可以重试"),
    /** 正常结束但没有正文，多见于把回答放进思考内容的推理模型。不自动重试：多半每次都一样 */
    LLM_EMPTY_RESPONSE(HttpStatus.BAD_GATEWAY, "AI 模型没有返回内容，可以重试；反复出现时，可能是模型只输出了思考过程"),
    /** 连不上、网络错误、5xx */
    LLM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "连不上 AI 模型，可以重试"),
    /** 其他调用错误，如 400 */
    LLM_FAILED(HttpStatus.BAD_GATEWAY, "AI 模型调用失败，可以重试"),
    /** 调用成功，但输出重试后仍不符合要求。题目切分、评分建议有自己的返回码，这个用于抽取批次的失败原因 */
    LLM_INVALID_OUTPUT(HttpStatus.BAD_GATEWAY, "AI 模型的输出不符合要求，可以重试");

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
