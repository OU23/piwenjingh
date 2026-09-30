package com.example.toolbox.storage;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 迁移任务注册表（进程内存，M-22 / 契约 §9.9）。
 *
 * <p>选型禁 Redis，故任务状态存本进程；重启后丢失（迁移可重入，重新发起即可）。
 */
@Component
public class MigrationTaskStore {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final Map<String, MigrationTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 建任务并登记，返回任务引用（调用方随后交给执行器） */
    public MigrationTask create(String operator) {
        String taskId = "mig-" + LocalDate.now().format(DATE) + "-" + seq.incrementAndGet();
        MigrationTask task = new MigrationTask(taskId, operator);
        tasks.put(taskId, task);
        return task;
    }

    public Optional<MigrationTask> get(String taskId) {
        return taskId == null ? Optional.empty() : Optional.ofNullable(tasks.get(taskId));
    }
}
