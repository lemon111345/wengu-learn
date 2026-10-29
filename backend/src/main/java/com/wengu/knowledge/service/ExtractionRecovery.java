package com.wengu.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wengu.knowledge.entity.BatchStatus;
import com.wengu.knowledge.entity.ExtractionBatch;
import com.wengu.knowledge.mapper.ExtractionBatchMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 服务启动时，把上次运行留下的排队中和执行中的批次标为失败，由用户决定是否重试（决策 013）。
 * 不自动重跑：重跑要花钱，用户并不知道；如果正是这个任务把进程搞崩了，自动重跑会让应用反复崩溃。
 * <p>
 * 前提是只部署一个实例：多个实例时，这里会把其他实例正在执行的批次也标为失败。
 * <p>
 * 不访问数据库的启动方式（实验入口、LLM 实连测试）用 wengu.extraction.recover-on-startup=false 关掉它：
 * 启动事件会强制创建监听它的 bean，没有数据库时这个 bean 创建不出来。
 */
@Component
@ConditionalOnBooleanProperty(name = "wengu.extraction.recover-on-startup", matchIfMissing = true)
public class ExtractionRecovery {

    private static final Logger log = LoggerFactory.getLogger(ExtractionRecovery.class);

    static final String INTERRUPTED = "服务重启，任务中断";

    private final ExtractionBatchMapper batchMapper;
    private final Clock clock;

    public ExtractionRecovery(ExtractionBatchMapper batchMapper, Clock clock) {
        this.batchMapper = batchMapper;
        this.clock = clock;
    }

    /** @return 标为失败的批次数 */
    @EventListener(ApplicationReadyEvent.class)
    public int failInterrupted() {
        int count = batchMapper.update(new LambdaUpdateWrapper<ExtractionBatch>()
                .in(ExtractionBatch::getStatus, BatchStatus.PENDING, BatchStatus.RUNNING)
                .set(ExtractionBatch::getStatus, BatchStatus.FAILED)
                .set(ExtractionBatch::getError, INTERRUPTED)
                .set(ExtractionBatch::getFinishedAt, Instant.now(clock)));
        if (count > 0) {
            log.warn("服务重启，{} 个未完成的抽取批次已标为失败", count);
        }
        return count;
    }
}
