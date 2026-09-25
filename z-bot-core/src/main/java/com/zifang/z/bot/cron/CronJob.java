package com.zifang.z.bot.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * 一条定时任务。持久化在 {@code <configDir>/cron/jobs.json}（数组）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CronJob {

    public String id;
    public String name;
    /** 到点后交给 agent 执行的任务描述。 */
    public String prompt;
    /** 调度表达式（every Ns/Nm/Nh | hourly | daily HH:MM）。 */
    public String schedule;
    public boolean enabled = true;
    /** 最近一次触发时间（ISO-8601），纯台账。 */
    public String lastRun;
    /** 最近一次执行结果摘要，供 /cron list 查看。 */
    public String lastResult = "";

    public CronJob() {
    }

    public CronJob(String id, String name, String prompt, String schedule) {
        this.id = id;
        this.name = name;
        this.prompt = prompt;
        this.schedule = schedule;
    }

    /** 解析并校验调度表达式；坏表达式抛 IllegalArgumentException。 */
    public transient CronSchedule parsed;

    public CronSchedule schedule() {
        if (parsed == null) {
            parsed = CronSchedule.parse(schedule);
        }
        return parsed;
    }

    public void markRun(String result) {
        this.lastRun = Instant.now().toString();
        this.lastResult = result == null ? "" :
                (result.length() > 200 ? result.substring(0, 200) + "…" : result);
    }
}
