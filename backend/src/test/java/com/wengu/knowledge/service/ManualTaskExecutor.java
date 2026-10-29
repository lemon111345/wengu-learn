package com.wengu.knowledge.service;

import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 测试用的线程池：提交的任务先存着，调用 {@link #runAll()} 时才在测试线程中依次执行。
 * 这样能看到任务执行前的状态（排队中、重启中断），执行结果也是确定的。
 */
class ManualTaskExecutor implements TaskExecutor {

    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean full;

    @Override
    public void execute(Runnable task) {
        if (full) {
            throw new TaskRejectedException("队列已满");
        }
        tasks.add(task);
    }

    void runAll() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }

    /** 模拟队列已满：之后提交的任务都被拒绝。 */
    void setFull(boolean full) {
        this.full = full;
    }

    void reset() {
        tasks.clear();
        full = false;
    }
}
