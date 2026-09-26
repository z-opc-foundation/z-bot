package com.zifang.z.bot.channel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通道"配置不足"这一类错误：{@link ChannelRegistry} 在构造通道前发现声明的
 * {@code requires} 键没给全，或通道自己在真出站时发现缺凭据，都抛这一个。
 *
 * <p>它存在的唯一理由是<b>不许静默降级</b>：缺键必须让调用方拿到"缺哪一个键"的原文，
 * 而不是日志里一句"看起来发成功了"。它 extends {@link IOException}，所以
 * {@link ChannelBus} 的投递主路径会照常把它记成台账 {@code failed} 行（P16 红线 8 的口径：
 * 没发出去就不许结清）。</p>
 */
public class ChannelConfigException extends IOException {

    private static final long serialVersionUID = 1L;

    private final List<String> missingKeys;

    public ChannelConfigException(String message) {
        this(message, Collections.<String>emptyList());
    }

    /** {@code missingKeys} 用配置键的**短名**（{@code app-secret}），不带值 —— 值可能是凭据。 */
    public ChannelConfigException(String message, List<String> missingKeys) {
        super(message);
        this.missingKeys = missingKeys == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(missingKeys));
    }

    public List<String> missingKeys() {
        return missingKeys;
    }
}
