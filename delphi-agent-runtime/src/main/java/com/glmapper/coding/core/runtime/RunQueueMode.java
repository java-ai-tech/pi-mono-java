package com.glmapper.coding.core.runtime;

public enum RunQueueMode {
    /**
     * 中断当前运行，立即执行新任务
     */
    INTERRUPT,
    /**
     * 等待当前运行完成后执行新任务
     */
    FOLLOWUP,
    /**
     * 将新任务放入队列，等待前面的任务完成后再执行
     */
    STEER,
    /**
     * 丢弃当前运行，执行新任务
     */
    DROP,
    /**
     * 拒绝新任务，保持当前运行不变
     */
    REJECT
}

