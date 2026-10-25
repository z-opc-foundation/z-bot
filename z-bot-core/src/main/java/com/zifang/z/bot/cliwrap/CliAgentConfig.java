package com.zifang.z.bot.cliwrap;

import com.zifang.z.bot.cliwrap.dto.AgentInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * CLI Agent 配置（运行时持有，非 YAML 持久化）。
 *
 * <p>描述一个 CLI Agent 的启动方式：命令、参数模板、工作目录。
 * 参数列表中的 {@code {prompt}} 占位符在执行时替换为实际用户输入。
 */
public class CliAgentConfig {

    private final String id;
    private final String name;
    private final String type; // "hermes" | "claude-code"
    private final String command;
    private final List<String> args;
    private final String workingDir;

    public CliAgentConfig(String id, String name, String type,
                          String command, List<String> args, String workingDir) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.command = command;
        this.args = args != null ? new ArrayList<String>(args) : new ArrayList<String>();
        this.workingDir = workingDir;
    }

    /**
     * 默认参数模板。
     */
    public static List<String> defaultArgs(String type) {
        List<String> args = new ArrayList<String>();
        if ("claude-code".equals(type)) {
            args.add("-p");
            args.add("{prompt}");
            args.add("--output-format");
            args.add("stream-json");
            args.add("--verbose");
        } else if ("hermes".equals(type)) {
            args.add("chat");
            args.add("-q");
            args.add("{prompt}");
            args.add("-Q");
        }
        return args;
    }

    /**
     * 默认命令。
     */
    public static String defaultCommand(String type) {
        if ("claude-code".equals(type)) {
            return "claude";
        } else if ("hermes".equals(type)) {
            return "hermes";
        }
        return "";
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    /**
     * 构建实际命令参数（替换 {prompt} 占位符）。
     */
    public List<String> resolveArgs(String prompt) {
        List<String> resolved = new ArrayList<String>(args.size());
        for (String arg : args) {
            resolved.add(arg.replace("{prompt}", prompt != null ? prompt : ""));
        }
        return resolved;
    }

    /**
     * 转为 AgentInfo DTO。
     */
    public AgentInfo toInfo() {
        AgentInfo info = new AgentInfo(id, name, type);
        info.setCommand(command);
        info.setArgs(new ArrayList<String>(args));
        info.setWorkingDir(workingDir);
        return info;
    }
}
