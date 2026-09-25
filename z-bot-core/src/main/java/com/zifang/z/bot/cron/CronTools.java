package com.zifang.z.bot.cron;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.tool.Toolkit;

import java.util.List;
import java.util.Map;

/**
 * cronjob 工具：agent 自建/管理定时任务（到点后由调度器 spawn 隔离 agent 执行 prompt）。
 */
public final class CronTools {

    private CronTools() {
    }

    public static Tool cronTool(final CronScheduler scheduler) {
        return Toolkit.of("cronjob",
                "管理定时任务：按调度表达式周期性自动执行一个任务描述，结果按 deliver 路由投递。"
                        + "action: add | list | remove | pause | resume；"
                        + "schedule 支持 every 30s / every 5m / every 2h / hourly / daily 09:30 "
                        + "/ once 2026-09-26T09:00:00+08:00（一次性，至多跑一回）；"
                        + "deliver 支持 local（打印，缺省）/ origin（回到建任务的会话）/ "
                        + "<通道名>[:会话id] / all，可用逗号组合",
                new com.zifang.z.agent.kernel.tool.ToolSchemaBuilder()
                        .string("action", "add | list | remove | pause | resume")
                        .string("name", "任务名（add 必填）", false)
                        .string("prompt", "到点后执行的任务描述（add 必填）", false)
                        .string("schedule", "调度表达式（add 必填）", false)
                        .string("deliver", "投递路由：local | origin | <通道名>[:会话] | all（缺省 local）", false)
                        .string("id", "任务 id 前缀（remove/pause/resume 必填）", false)
                        .build(),
                args -> {
                    String action = str(args, "action").toLowerCase();
                    try {
                        switch (action) {
                            case "add": {
                                String name = str(args, "name");
                                String prompt = str(args, "prompt");
                                String schedule = str(args, "schedule");
                                if (name.isEmpty() || prompt.isEmpty() || schedule.isEmpty()) {
                                    return ToolResult.error("参数错误：add 需要 name/prompt/schedule");
                                }
                                CronJob job = scheduler.add(name, prompt, schedule, str(args, "deliver"));
                                return ToolResult.text("已创建定时任务 " + job.id + " (" + job.schedule
                                        + ", 投递=" + job.deliver + "): " + name);
                            }
                            case "list": {
                                List<CronJob> jobs = scheduler.list();
                                if (jobs.isEmpty()) {
                                    return ToolResult.text("暂无定时任务");
                                }
                                StringBuilder sb = new StringBuilder("定时任务 (" + jobs.size() + ")\n");
                                for (CronJob j : jobs) {
                                    sb.append("  ").append(j.id).append("  ")
                                            .append(j.enabled ? "ON " : "OFF").append("  ")
                                            .append(j.schedule).append("  ").append(j.name)
                                            .append("  投递=").append(j.deliver);
                                    if (j.runClaim != null) {
                                        sb.append("  [在跑，认领者 ").append(j.runClaim.by)
                                                .append(" @ ").append(j.runClaim.at).append(']');
                                    }
                                    if (j.isOneShot()) {
                                        sb.append("  已投递 ").append(j.dispatches).append(" 次");
                                    }
                                    if (!j.lastDelivery.isEmpty()) {
                                        sb.append("  投递结果: ").append(j.lastDelivery);
                                    }
                                    if (!j.lastResult.isEmpty()) {
                                        sb.append("  最近: ").append(j.lastResult);
                                    }
                                    sb.append('\n');
                                }
                                return ToolResult.text(sb.toString().trim());
                            }
                            case "remove":
                                return scheduler.remove(str(args, "id"))
                                        ? ToolResult.text("已删除") : ToolResult.error("未找到任务: " + str(args, "id"));
                            case "pause":
                                return scheduler.setEnabled(str(args, "id"), false)
                                        ? ToolResult.text("已暂停") : ToolResult.error("未找到任务: " + str(args, "id"));
                            case "resume":
                                return scheduler.setEnabled(str(args, "id"), true)
                                        ? ToolResult.text("已恢复") : ToolResult.error("未找到任务: " + str(args, "id"));
                            default:
                                return ToolResult.error("参数错误：action 必须是 add/list/remove/pause/resume");
                        }
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    } catch (Exception e) {
                        return ToolResult.error("定时任务操作失败: " + e.getMessage());
                    }
                });
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : v.toString();
    }
}
