package com.zifang.z.bot.cliwrap.dto;

import com.zifang.util.json.JsonUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 信息（列表/详情 API 返回）。
 *
 * <p>type 取值：
 * <ul>
 *   <li>{@code jar} — 自研本地 Agent（JAR 内置 ReAct 引擎）</li>
 *   <li>{@code hermes} — Hermes CLI Agent</li>
 *   <li>{@code claude-code} — Claude Code CLI Agent</li>
 * </ul>
 */
public class AgentInfo {

    private String id;
    private String name;
    private String type;
    private String status = "running";
    private long createdAt;

    // CLI agent 专属字段
    private String command;
    private List<String> args = new ArrayList<String>();
    private String workingDir;

    public AgentInfo() {
    }

    public AgentInfo(String id, String name, String type) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
    }

    public static AgentInfo fromJson(String json) {
        return JsonUtil.fromJson(json, AgentInfo.class);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    public String toJson() {
        return JsonUtil.toJson(this);
    }
}
