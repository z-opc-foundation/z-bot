package com.zifang.z.bot.center;

/**
 * Bot 后台 lifecycle 线程 — 心跳 + skill 同步。
 *
 * <p>注册后启动两个常驻 daemon 线程：</p>
 * <ul>
 *   <li><b>bot-heartbeat</b> 每 30s 调一次 center 的 {@code /heartbeat}</li>
 *   <li><b>bot-skill-sync</b> 每 60s 拉一次 pending skills 落盘到
 *       {@code <profile>/skills/<instanceCode>/<code>/SKILL.md}</li>
 * </ul>
 *
 * <p>线程都是 daemon，JVM 退出时不阻塞。</p>
 */
public class BotLifecycle {

    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long SYNC_INTERVAL_MS = 60_000L;

    private final BotCenterClient client;
    private final String version;

    private volatile boolean running;
    private Thread heartbeatThread;
    private Thread syncThread;

    public BotLifecycle(BotCenterClient client, String version) {
        this.client = client;
        this.version = version;
    }

    public synchronized void start() {
        if (!client.isEnabled()) {
            System.out.println("[bot-lifecycle] center 未启用，不起后台线程");
            return;
        }
        if (running) {
            return;
        }
        running = true;

        heartbeatThread = new Thread(this::heartbeatLoop, "bot-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        syncThread = new Thread(this::syncLoop, "bot-skill-sync");
        syncThread.setDaemon(true);
        syncThread.start();

        System.out.println("[bot-lifecycle] 后台线程已启动 (heartbeat=30s, skill-sync=60s)");
    }

    public synchronized void stop() {
        running = false;
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        if (syncThread != null) {
            syncThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void heartbeatLoop() {
        int i = 0;
        while (running) {
            try {
                client.heartbeat("ACTIVE", version);
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                i++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // center 挂了不能把 bot 卡死，等一个周期再试
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void syncLoop() {
        while (running) {
            try {
                int wrote = client.syncSkills();
                if (wrote > 0) {
                    System.out.println("[bot-lifecycle] skill sync 安装了 " + wrote + " 个 skill");
                }
                Thread.sleep(SYNC_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                try {
                    Thread.sleep(SYNC_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
