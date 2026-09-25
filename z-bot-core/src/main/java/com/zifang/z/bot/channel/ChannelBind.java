package com.zifang.z.bot.channel;

import java.io.IOException;
import java.net.InetAddress;

/**
 * 通道监听地址解析 — 缺省只绑回环，暴露到别的网卡必须显式写出 host。
 *
 * <p>{@code new InetSocketAddress(port)} 绑的是通配地址。macOS 上通配监听可以和别的服务
 * 已经占住的 {@code 127.0.0.1:P} 共存：启动打印"成功"，请求却被那个邻居应答（实测过一次的
 * 间歇 400 就是这么来的），而且未鉴权端点顺手对整个 LAN 敞开。绑成具体的回环地址后，
 * 端口被占会当场 BindException，暴露范围也由 host 说了算。</p>
 */
final class ChannelBind {

    static final String ANY = "0.0.0.0";

    private ChannelBind() {
    }

    /** host 为空 ⇒ 回环；否则按字面解析（{@code 0.0.0.0} = 显式通配 opt-in）。 */
    static InetAddress resolve(String host) throws IOException {
        String h = host == null ? "" : host.trim();
        return h.isEmpty() ? InetAddress.getLoopbackAddress() : InetAddress.getByName(h);
    }

    /** 横幅里显示的监听地址 — 通配时不能照旧印 127.0.0.1，那会把"别的机器也能连"说成"只在本机"。 */
    static String describe(InetAddress addr) {
        if (addr == null) {
            return "(未监听)";
        }
        return addr.isAnyLocalAddress() ? ANY : addr.getHostAddress();
    }

    /** 非回环监听的提示；绑在回环上返 null（不打扰）。 */
    static String exposureWarning(InetAddress addr) {
        if (addr == null || addr.isLoopbackAddress()) {
            return null;
        }
        return "监听在 " + describe(addr) + "，同一网络内的其他机器可直接访问这些端点";
    }
}
