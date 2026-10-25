package com.zifang.z.bot.sandbox;

import java.util.*;

/**
 * 沙箱配置。
 * <p>
 * 复刻自 z-team commit 36b84904 的 {@code SandboxConfig}：
 * 携带当前 Agent 的白名单 + 命令，避免每次都查白名单加载器。
 * <p>
 * 通过 {@link Builder} 构造：
 * <pre>{@code
 *   SandboxConfig cfg = SandboxConfig.builder()
 *       .workingDirectory("/workspace")
 *       .agentCode("agent_x")
 *       .allowedCommands(Set.of("grep", "cat"))
 *       .build();
 * }</pre>
 */
public class SandboxConfig {

    private final String workingDirectory;
    private final boolean networkRestricted;
    private final List<String> additionalWritePaths;
    private final String shell;
    private final String bwrapPath;
    private final long timeoutSeconds;
    private final int maxCaptureBytes;
    private final boolean weakerNestedSandbox;
    private final String agentCode;
    private final Set<String> allowedCommands;
    private final boolean enabled;

    private SandboxConfig(Builder b) {
        this.workingDirectory = b.workingDirectory;
        this.networkRestricted = b.networkRestricted;
        this.additionalWritePaths = Collections.unmodifiableList(
                new ArrayList<String>(b.additionalWritePaths));
        this.shell = b.shell;
        this.bwrapPath = b.bwrapPath;
        this.timeoutSeconds = b.timeoutSeconds;
        this.maxCaptureBytes = b.maxCaptureBytes;
        this.weakerNestedSandbox = b.weakerNestedSandbox;
        this.agentCode = b.agentCode;
        if (b.allowedCommands == null) {
            this.allowedCommands = null;
        } else {
            this.allowedCommands = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(b.allowedCommands));
        }
        this.enabled = b.enabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public boolean isNetworkRestricted() {
        return networkRestricted;
    }

    public List<String> getAdditionalWritePaths() {
        return additionalWritePaths;
    }

    public String getShell() {
        return shell;
    }

    public String getBwrapPath() {
        return bwrapPath;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxCaptureBytes() {
        return maxCaptureBytes;
    }

    public boolean isWeakerNestedSandbox() {
        return weakerNestedSandbox;
    }

    public String getAgentCode() {
        return agentCode;
    }

    /**
     * 命令白名单（basename，小写）。{@code null} 表示不过滤。
     */
    public Set<String> getAllowedCommands() {
        return allowedCommands;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public SandboxCommandWhitelist toWhitelist() {
        return new SandboxCommandWhitelist() {
            @Override
            public boolean isEnabled() {
                // allowedCommands == null 表示不过滤（对齐上游"未配置即信任 OS 层沙箱"语义）
                return enabled && allowedCommands != null;
            }

            @Override
            public String agentCode() {
                return agentCode;
            }

            @Override
            public Set<String> allowedCommands() {
                return allowedCommands != null ? allowedCommands : Collections.emptySet();
            }
        };
    }

    public static class Builder {
        private final List<String> additionalWritePaths = new ArrayList<String>();
        private String workingDirectory;
        private boolean networkRestricted = true;
        private String shell;
        private String bwrapPath;
        private long timeoutSeconds = 120L;
        private int maxCaptureBytes = 256 * 1024;
        private boolean weakerNestedSandbox;
        private String agentCode = DefaultSandboxCommandWhitelist.DEFAULT_AGENT_CODE;
        /** {@code null} = 不过滤（写入安全由 Seatbelt/bwrap 路径白名单在 OS 层强制）；空集合拒绝全部。 */
        private Set<String> allowedCommands;
        private boolean enabled = true;

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * 默认 true：禁止出站网络。
         */
        public Builder networkRestricted(boolean networkRestricted) {
            this.networkRestricted = networkRestricted;
            return this;
        }

        public Builder additionalWritePath(String path) {
            if (path != null && !path.trim().isEmpty()) {
                additionalWritePaths.add(path);
            }
            return this;
        }

        public Builder shell(String shell) {
            this.shell = shell;
            return this;
        }

        public Builder bwrapPath(String bwrapPath) {
            this.bwrapPath = bwrapPath;
            return this;
        }

        public Builder timeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder maxCaptureBytes(int maxCaptureBytes) {
            this.maxCaptureBytes = maxCaptureBytes;
            return this;
        }

        /**
         * 无特权 Docker 等环境可开启弱嵌套沙箱模式。
         */
        public Builder weakerNestedSandbox(boolean weakerNestedSandbox) {
            this.weakerNestedSandbox = weakerNestedSandbox;
            return this;
        }

        public Builder agentCode(String agentCode) {
            this.agentCode = agentCode;
            return this;
        }

        /**
         * 命令白名单。{@code null} 不过滤；空集合拒绝全部命令。
         */
        public Builder allowedCommands(Set<String> allowedCommands) {
            this.allowedCommands = allowedCommands;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public SandboxConfig build() {
            if (workingDirectory == null || workingDirectory.trim().isEmpty()) {
                throw new IllegalArgumentException("workingDirectory is required");
            }
            return new SandboxConfig(this);
        }
    }
}
