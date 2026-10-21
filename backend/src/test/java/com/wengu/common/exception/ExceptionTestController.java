package com.wengu.common.exception;

import com.wengu.common.result.ErrorCode;
import com.wengu.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅用于测试的接口，人为制造各种异常，验证 {@link GlobalExceptionHandler} 的处理结果。
 */
@RestController
class ExceptionTestController {

    @GetMapping("/test/business")
    Result<Void> business() {
        throw new BusinessException(ErrorCode.NOT_FOUND, "知识点不存在");
    }

    @GetMapping("/test/unexpected")
    Result<Void> unexpected() {
        throw new IllegalStateException("内部细节，不应返回给前端");
    }

    @GetMapping("/test/number")
    Result<Integer> number(@RequestParam int n) {
        return Result.ok(n);
    }
}
