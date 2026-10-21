package com.wengu.common.controller;

import com.wengu.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查：只表示进程活着、能处理请求，不检查数据库等外部依赖。
 * 供 Docker Compose healthcheck 和手动排查使用。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Result<Map<String, String>> health() {
        return Result.ok(Map.of("status", "UP"));
    }
}
