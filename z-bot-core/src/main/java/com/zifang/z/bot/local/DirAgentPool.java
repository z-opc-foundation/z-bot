package com.zifang.z.bot.local;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.tool.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地执行模式的目录 agent 池：每个工作文件夹一个专属 {@link BotAgent} 实例。
 *
 * <p>与全局单例 agent 的关系：</p>
 * <ul>
 *   <li>会话库共享同一份 state.db / sessionsDir（新 configDir 派生），所以现有
 *       {@code /v1/sessions} 面对所有池内会话照常可见、可读、可删；</li>
 *   <li>记忆 / 技能 / 审批名单同源，文件夹只是换了沙箱根（exec/文件工具困在目录里）；</li>
 *   <li>cron 调度器注入全局那一个 —— 池里每个 agent 再各起一个调度器会把定时任务跑 N 遍；</li>
 *   <li>不接 center（子代理口径），避免重复注册生命周期。</li>
 * </ul>
 *
 * <p>实例按目录懒建；{@link #clear()} 丢掉缓存让下一次对话用最新模型配置重建
 * （模型客户端在 BotAgent 构建期冻结，这是配置热生效的唯一通路）。</p>
 */
public final class DirAgentPool {

    private static final Logger LOG = LoggerFactory.getLogger(DirAgentPool.class);

    private final BotAgent global;
    private final File configDir;
    private final Map<String, BotAgent> pool = new LinkedHashMap<String, BotAgent>();

    public DirAgentPool(BotAgent global) {
        this.global = global;
        BotConfig cfg = global == null ? null : global.getConfig();
        this.configDir = cfg != null && cfg.getConfigDir() != null
                ? cfg.getConfigDir() : BotConfig.defaultConfigDir();
    }

    /**
     * 取某目录的专属 agent；{@code dir} 为空 = 默认沙箱根（不绑文件夹的任务）。
     *
     * @param dir 绝对路径目录；不存在时退回默认沙箱根并记日志（前端建项目时已校验）
     */
    public synchronized BotAgent forDir(String dir) {
        String key = normalize(dir);
        BotAgent existing = pool.get(key);
        if (existing != null) {
            return existing;
        }
        BotConfig cfg = BotConfig.load(configDir);
        Sandbox sandbox;
        if (key.isEmpty()) {
            sandbox = new Sandbox(BotConfig.resolveWorkspaceDir(configDir, null).getAbsolutePath());
        } else {
            File f = new File(key);
            if (!f.isDirectory()) {
                LOG.warn("[DirAgentPool] 目录不可用，退回默认沙箱根: {}", key);
                sandbox = new Sandbox(BotConfig.resolveWorkspaceDir(configDir, null).getAbsolutePath());
            } else {
                sandbox = new Sandbox(f.getAbsolutePath());
            }
        }
        BotAgent agent = BotAgent.builder(cfg)
                .sandbox(sandbox)
                .withoutCenter()
                .cronScheduler(global != null ? global.getCronScheduler() : null)
                .build();
        pool.put(key, agent);
        LOG.info("[DirAgentPool] 建目录 agent: model={} sandbox={}",
                cfg.getModel(), sandbox.root());
        return agent;
    }

    /** 丢掉全部缓存实例（模型配置更新后调用）；运行中的实例不 shutdown，让在途回合自然收尾。 */
    public synchronized void clear() {
        for (Map.Entry<String, BotAgent> en : pool.entrySet()) {
            BotAgent a = en.getValue();
            if (a.isRunning()) {
                LOG.warn("[DirAgentPool] {} 有在途回合，实例被替换后不再复用", en.getKey());
                continue;
            }
            try {
                a.shutdown();
            } catch (RuntimeException e) {
                LOG.warn("[DirAgentPool] shutdown 失败(忽略): {}", e.getMessage());
            }
        }
        pool.clear();
    }

    private static String normalize(String dir) {
        if (dir == null || dir.trim().isEmpty()) {
            return "";
        }
        return new File(dir.trim()).getAbsolutePath();
    }
}
