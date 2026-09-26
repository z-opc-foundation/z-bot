package com.zifang.z.bot.acp;

import com.zifang.z.bot.ZBot;
import com.zifang.z.bot.cli.AcpCommand;
import org.junit.Test;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * §3.2 问 1：<b>ACP 命令到底有没有进了生产注册表</b>。
 *
 * <p>这条链此前是<b>零覆盖</b>的：{@code AcpProtocolSurfaceTest} 全部走
 * {@code AcpFakes.server(...)} 自造的 {@link AcpConnection}，一次都没有经过
 * {@link ZBot} 的 {@code @Command(subcommands = ...)}。也就是说，把 {@code ZBot.java} 里
 * {@code AcpCommand.class} 那一行摘掉，全套 ACP 测试仍然全绿 —— 那叫"测了个不存在的东西"。</p>
 *
 * <p>本类的三条断言都只读<b>生产</b>命令表（反射注解 + 真 {@link CommandLine} 规格），
 * 摘掉注册那一行必红（实测过程与读数见 {@code _doc/acceptance/p25/EVIDENCE.md} 杠② M1）。</p>
 */
public class AcpProductionRegistrationTest {

    /** 生产注册表：{@code ZBot} 注解上挂的 subcommands 里必须有一项名字就是 {@code acp}。 */
    @Test
    public void acpIsRegisteredInProductionZBotSubcommands() {
        CommandLine.Command ann = ZBot.class.getAnnotation(CommandLine.Command.class);
        assertNotNull("ZBot 必须带 @Command，否则整棵命令树都是编的", ann);

        List<String> names = new ArrayList<String>();
        Class<?> acpClass = null;
        for (Class<?> sub : ann.subcommands()) {
            CommandLine.Command subAnn = sub.getAnnotation(CommandLine.Command.class);
            assertNotNull("子命令 " + sub.getName() + " 自己没有 @Command", subAnn);
            names.add(subAnn.name());
            if ("acp".equals(subAnn.name())) {
                acpClass = sub;
            }
        }
        assertTrue("生产注册表里要有 acp，实测=" + names, names.contains("acp"));
        assertEquals("acp 这一格必须真指向 AcpCommand，而不是同名空壳",
                AcpCommand.class, acpClass);
    }

    /** 用户可见面：{@code z-bot --help} 的用法文本里要排得进 acp（IDE 侧就是这么找命令的）。 */
    @Test
    public void acpShowsUpInProductionUsageMessage() {
        String usage = new CommandLine(new ZBot()).getUsageMessage();
        assertTrue("z-bot --help 要列出 acp 子命令:\n" + usage, usage.contains("acp"));
    }

    /** 真 {@link CommandLine} 规格：{@code acp} 解析到的 userObject 就是 AcpCommand，且选项面齐全。 */
    @Test
    public void productionCommandLineResolvesAcpWithItsRealOptionSurface() {
        CommandLine root = new CommandLine(new ZBot());
        CommandLine acp = root.getSubcommands().get("acp");
        assertNotNull("getSubcommands() 里没有 acp: " + root.getSubcommands().keySet(), acp);
        assertTrue("userObject 必须是 AcpCommand 本体，实测="
                        + acp.getCommandSpec().userObject().getClass().getName(),
                acp.getCommandSpec().userObject() instanceof AcpCommand);

        for (String flag : new String[] {"--check", "--allow-always", "--model-catalog", "--config-dir"}) {
            assertTrue("acp 的选项面缺 " + flag + ": " + acp.getCommandSpec().options(),
                    acp.getCommandSpec().findOption(flag) != null);
        }
        // 阳性对照：同名不同串的假命令必须查不到 —— 否则上面那三条断言可能是"什么都能查到"。
        assertNull("注册表里不该有 acpx（阳性对照）", root.getSubcommands().get("acpx"));
        // 规模对照：注册表不能只剩 acp 一格，否则"有 acp"这句没有信息量。
        assertTrue("生产子命令至少 10 格，实测=" + root.getSubcommands().size(),
                root.getSubcommands().size() >= 10);
    }
}
