package com.zifang.z.bot.tool;

import java.io.File;
import java.io.IOException;

/**
 * 文件沙箱：所有文件类工具的路径都相对 {@code ~/.zbot/workspace} 解析，
 * 越界（{@code ..} 逃逸到根目录之外）直接拒绝。
 */
public final class Sandbox {

    private final File root;

    public Sandbox(String rootPath) {
        this.root = new File(rootPath == null || rootPath.trim().isEmpty()
                ? System.getProperty("user.home") + "/.zbot/workspace" : rootPath.trim());
        if (!root.exists()) {
            this.root.mkdirs();
        }
    }

    public File root() {
        return root;
    }

    /**
     * 把工具入参解析成沙箱内的绝对路径。
     *
     * @return 规范化后的文件
     * @throws IOException 路径逃逸出沙箱
     */
    public File resolve(String path) throws IOException {
        File target = new File(root, path == null ? "" : path);
        String canonical = target.getCanonicalPath();
        if (!canonical.startsWith(root.getCanonicalPath() + File.separator)
                && !canonical.equals(root.getCanonicalPath())) {
            throw new IOException("路径超出沙箱目录: 「" + canonical + "」不在「" + root + "」下");
        }
        return new File(canonical);
    }
}
