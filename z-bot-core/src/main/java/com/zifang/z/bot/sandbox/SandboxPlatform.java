package com.zifang.z.bot.sandbox;

/**
 * 沙箱支持的操作系统。
 */
public enum SandboxPlatform {
    LINUX,
    MACOS,
    UNSUPPORTED;

    public static SandboxPlatform detect() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            return LINUX;
        }
        if (os.contains("mac")) {
            return MACOS;
        }
        return UNSUPPORTED;
    }
}
