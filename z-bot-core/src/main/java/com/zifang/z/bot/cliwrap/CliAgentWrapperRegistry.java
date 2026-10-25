package com.zifang.z.bot.cliwrap;

import com.zifang.z.bot.cliwrap.CliAgentConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * CLI Agent Wrapper 注册表：按 {@link CliAgentConfig#getType()} 路由到对应 wrapper。
 *
 * <p>持有所有已注册的 {@link CliAgentWrapper} 实例，{@link #get(String)} 遍历找到
 * 第一个 {@link CliAgentWrapper#supports(String)} 匹配的。
 *
 * <p>用法（starter 装配）：
 * <pre>{@code
 * CliAgentWrapperRegistry registry = new CliAgentWrapperRegistry(
 *     new ClaudeCodeWrapper(),
 *     new HermesProcessWrapper(),
 *     new OpencodeProcessWrapper()
 * );
 * }</pre>
 */
public class CliAgentWrapperRegistry {

    private static final Logger LOG = Logger.getLogger(CliAgentWrapperRegistry.class.getName());

    private final List<CliAgentWrapper> wrappers;

    public CliAgentWrapperRegistry(CliAgentWrapper... wrappers) {
        List<CliAgentWrapper> list = new ArrayList<CliAgentWrapper>();
        Collections.addAll(list, wrappers);
        this.wrappers = Collections.unmodifiableList(list);
        LOG.info("CliAgentWrapperRegistry initialized with " + list.size() + " wrappers");
    }

    public CliAgentWrapperRegistry(List<CliAgentWrapper> wrappers) {
        this.wrappers = Collections.unmodifiableList(new ArrayList<CliAgentWrapper>(wrappers));
        LOG.info("CliAgentWrapperRegistry initialized with " + this.wrappers.size() + " wrappers");
    }

    /**
     * 按 type 路由到 wrapper。返回 null 表示无匹配（调用方可回退原逻辑）。
     */
    public CliAgentWrapper get(String type) {
        if (type == null) {
            return null;
        }
        for (CliAgentWrapper w : wrappers) {
            if (w.supports(type)) {
                return w;
            }
        }
        LOG.warning("No CliAgentWrapper found for type: " + type + ", will fallback to default spawn");
        return null;
    }

    /**
     * 是否有 wrapper 支持该 type。
     */
    public boolean hasWrapper(String type) {
        return get(type) != null;
    }

    public List<CliAgentWrapper> getWrappers() {
        return wrappers;
    }
}
