# z-bot MCP 集成测试：环境依赖与正确版本

## 结论

z-bot 的 9 个 MCP 测试失败**不是代码缺陷**，是本机缺 Python 官方 MCP SDK。
补上 SDK 后 9/9 全绿（0.06s 的秒失败 → 真实跑 6~18s）。

## 复现与定位

    mvn -o -pl z-bot-core test -Dtest='McpParentWatchdogTest,McpRealStdioServerTest'

失败信息统一是：

    java.lang.AssertionError: 官方 MCP Python SDK 不可用（server 端 import 也过不去）

出处在 `RealMcpHarness.requireOfficialSdk()`，用
`importlib.metadata.version('mcp')` 探版本，正则要求 `\d+\.\d+.*`。

**两个坑叠在一起**：

1. `pip3 install mcp` 会被 **PEP 668** 拒（externally-managed-environment）。
   不要用 `--break-system-packages` 去污染系统 Python。
2. 装上之后**还是不通**：pip 默认装 **mcp 2.x**，而 2.x 把
   `mcp.server.fastmcp` 改名成 `mcp.server.mcpserver`。
   v1 代码 import 的是 `fastmcp`，于是报
   `No module named 'mcp.server.fastmcp'`（附一段迁移指南），
   看起来像"装了还是坏"，实际是**大版本不对**。

## 正确做法

    python3 -m venv "$HOME/.venvs/mcp"
    "$HOME/.venvs/mcp/bin/pip" install "mcp<2"      # 注意 <2
    "$HOME/.venvs/mcp/bin/python" -c "from importlib.metadata import version; \
        import mcp.server.fastmcp; print(version('mcp'))"
    # => 1.30.0

然后 `RealMcpHarness` 支持 `P21_PYTHON` 环境变量指定解释器，
不用改系统环境：

    P21_PYTHON="$HOME/.venvs/mcp/bin/python" mvn -o test

实测：`McpParentWatchdogTest` 4 passed（5.8s）、
`McpRealStdioServerTest` 5 passed（18.2s）。

### ⚠ 别把 venv 建在 /tmp（2026-10-04 第二次踩）

这份文档原先写的是 `/tmp/mcpvenv`，2026-10-04 照着它跑时那 9 条又红了 ——
因为 `/tmp` 被清过，venv 没了。**同一份文档、同一条命令，几天前能跑、今天不能跑**，
最费时间的就是这种：看起来像代码回归，其实是环境蒸发。

`$HOME/.venvs/` 只建一次，不会被任何常规清理碰到。同族的还有 z-skill 的
`playwright-core` 语料（装在 `z-lc-admin-ui/node_modules`，也不在 /tmp）。

### 排查这类"又红了"的顺序

1. 先确认是不是**同一个根因**（9 条是不是都报 `官方 MCP Python SDK 不可用`）——
   是就查环境，别改代码。
2. 三个解释器逐个试，确认不是"装到了另一个解释器里"：

       for p in python3 /usr/bin/python3 /opt/homebrew/bin/python3; do
           printf "%-28s " "$p"; $p -c "import mcp.server.fastmcp" 2>&1 | tail -1
       done

   实测这台机器三个都可能没有 `mcp`（`RealMcpHarness.python()` 的候选列表
   恰好就是这三个 + `P21_PYTHON`）。

## 为什么值得记

失败长得极像"9 个功能缺陷"，而且**失败得异常快**（全部 0.06s）——
真跑起来要 6~18s。**秒失败是环境没起来的信号**，不是业务逻辑崩了。
判据：先看 9 条失败是不是**同一个根因**，是的话查环境而不是改代码。
