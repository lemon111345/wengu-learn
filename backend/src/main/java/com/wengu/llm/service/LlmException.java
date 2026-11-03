package com.wengu.llm.service;

import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 大模型调用失败（#27，决策 032）。{@link LlmService} 把 SDK 抛出的各种异常都转成它，
 * 调用方不用认识 openai-java 的异常类；没有被处理的，由全局异常处理器直接返回对应的状态码和提示。
 * <p>
 * 输出解析失败不算调用失败，仍然是 {@link LlmOutputParseException}：模型回应了，只是内容不合格，调用方可以重试。
 */
public class LlmException extends BusinessException {

    public LlmException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, errorCode.getMessage(), cause);
    }

    public LlmException(ErrorCode errorCode) {
        super(errorCode);
    }

    /** 已经是 LlmException 的原样返回，其他的按 {@link #classify} 分类 */
    static LlmException from(Throwable e) {
        return e instanceof LlmException llm ? llm : new LlmException(classify(e), e);
    }

    /**
     * 按异常链分类。SDK 抛出的异常（用假的模型服务实测过，见 LlmErrorTest）：
     * <ul>
     *   <li>401、403、404、429、5xx：对应的 {@code com.openai.errors} 异常；流式调用外面多包一层 CompletionException</li>
     *   <li>超时：OpenAIIoException，原因链中有 InterruptedIOException（SocketTimeoutException 是它的子类）</li>
     *   <li>连不上：OpenAIIoException，原因链中有 ConnectException</li>
     * </ul>
     * 先看具体的，再看笼统的：超时和连不上都是 OpenAIIoException，要先在原因链中找超时。
     */
    static ErrorCode classify(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        if (has(chain, UnauthorizedException.class) || has(chain, PermissionDeniedException.class)) {
            return ErrorCode.LLM_AUTH_FAILED;
        }
        if (has(chain, NotFoundException.class)) {
            return ErrorCode.LLM_MODEL_NOT_FOUND;
        }
        if (has(chain, RateLimitException.class)) {
            return ErrorCode.LLM_RATE_LIMITED;
        }
        if (has(chain, InterruptedIOException.class)) {
            return ErrorCode.LLM_TIMEOUT;
        }
        boolean serverError = chain.stream().anyMatch(t ->
                t instanceof InternalServerException
                        || (t instanceof OpenAIServiceException s && s.statusCode() >= 500));
        if (serverError || has(chain, OpenAIIoException.class) || has(chain, IOException.class)) {
            return ErrorCode.LLM_UNAVAILABLE;
        }
        return ErrorCode.LLM_FAILED;
    }

    private static boolean has(List<Throwable> chain, Class<? extends Throwable> type) {
        return chain.stream().anyMatch(type::isInstance);
    }
}
