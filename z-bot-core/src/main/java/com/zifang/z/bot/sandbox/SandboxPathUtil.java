package com.zifang.z.bot.sandbox;

import java.io.File;
import java.io.IOException;

/**
 * 沙箱路径归一化与转义工具。
 */
public final class SandboxPathUtil {

    private SandboxPathUtil() {
    }

    public static String normalize(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path is empty");
        }
        String p = path.trim();
        String home = System.getProperty("user.home", "");
        if ("~".equals(p)) {
            p = home;
        } else if (p.startsWith("~/") && home != null && !home.isEmpty()) {
            p = home + p.substring(1);
        }
        File file = new File(p);
        if (!file.isAbsolute()) {
            file = file.getAbsoluteFile();
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * Seatbelt profile 路径转义。
     */
    public static String escapeSeatbeltPath(String path) {
        if (path == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(path.length() + 4);
        sb.append('"');
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
