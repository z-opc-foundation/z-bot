package com.zifang.z.bot;

/**
 * 版本面值在**源码侧**的唯一落点。
 *
 * <p>对外坐标的唯一真源是根 pom 的 {@code <revision>}（CI-friendly versions + flatten），
 * 而运行期有三条线会把版本说出去：bot 中心注册上报的 {@code BotAgent.BOT_VERSION}、
 * {@code z-bot --version} 那一行、MCP 握手 {@code initialize} 里自报的 client/server 版本。
 * 抬版时只要有一处没跟着 pom 走，说出去的版本线就是假的——而这类漂移**原本没有任何尺会读**
 * （测试断言的是"不许等于 0.2.0"，改成 0.1.9 照样绿），所以由
 * {@code BuildInfoDriftTest} 直接对 pom 字节回读数做逐处比对。</p>
 *
 * <p>本类之外，{@code src/main} 里不许再出现第二个版本面值：那 7 处旧字面量（{@code "0.2.0"} ×3、
 * {@code "0.2.0-dev"} ×3、{@code "z-bot/0.2.0"} 与 {@code "z-bot 0.2.0"}）全部改成从这里派生，
 * 常量表达式仍可在注解里用（picocli 的 {@code version = BuildInfo.CLI_VERSION}）。</p>
 */
public final class BuildInfo {

    private BuildInfo() {
    }

    /**
     * 必须与根 pom {@code <revision>} 逐字相等；抬版时这两处一起改，漏改由漂移测试判红。
     */
    public static final String REVISION = "0.2.1";

    /**
     * 跑在 {@code target/classes} 下（单测、IDE）时 jar manifest 不存在，自报版本落这个串。
     */
    public static final String DEV = REVISION + "-dev";

    /**
     * 上报给 bot 中心 / ACP 的版本串。
     */
    public static final String BOT_VERSION = "z-bot/" + REVISION;

    /**
     * picocli {@code --version} 打出来的那一行。
     */
    public static final String CLI_VERSION = "z-bot " + REVISION;

    /**
     * manifest 优先，读不到才落 {@link #DEV}——保持原来"版本号只有一个来源：jar manifest"的语义。
     *
     * <p>注意别在这里加"顺手兜个具体面值"的分支：那会让发布件与源码树的读数各说各话。</p>
     */
    public static String fromManifestOrDev(Class<?> anchor) {
        String v = anchor.getPackage().getImplementationVersion();
        return v == null || v.isEmpty() ? DEV : v;
    }
}
