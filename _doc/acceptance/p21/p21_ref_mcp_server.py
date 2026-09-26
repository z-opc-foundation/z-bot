#!/usr/bin/env python3
"""P21 参照 MCP server —— 由**官方 MCP Python SDK 1.27.1** 的 `mcp.server.fastmcp` 起。

刻意不用手搓的假端点：这样杠③(a)(b) 打的是"规范实现自己"，
z-bot transport 的任何线上错误都会在真 server 上暴露，而不是在自家桩里被互相容忍掉。

    python3 p21_ref_mcp_server.py --transport stdio
    python3 p21_ref_mcp_server.py --transport http --port 0 --port-file /path/to/port.txt

工具表可以被**外部 actor** 改：`p21_apply_table` 吃一个 JSON 控制文件的路径，
按里面的 add/drop 改自己的工具表，然后在这条连接上推
`notifications/tools/list_changed`（`ctx.session.send_tool_list_changed()`）。
"新表是什么"完全由 server 进程之外决定，server 只是执行 + 广播。

只用本机已装的包（mcp / uvicorn），不联网、不 pip install。
"""

import argparse
import asyncio
import json
import os
import sys

from mcp.server.fastmcp import Context, FastMCP

BASE_TOOLS = ("p21_echo", "p21_alpha", "p21_beta")

app = FastMCP("p21-ref-server")

SERVER_LOG = os.environ.get("P21_SERVER_LOG", "")


def set_server_log(path):
    """CLI 参数版：让 z-bot 的 transport 不必透传环境变量也能拿到服务端事实。"""
    global SERVER_LOG
    if path:
        SERVER_LOG = path


def note(event, **kw):
    """服务端事实的一等记录（E2E 读它，不读 z-bot 的日志）。"""
    rec = {"event": event}
    rec.update(kw)
    line = json.dumps(rec, ensure_ascii=False, sort_keys=True)
    if SERVER_LOG:
        with open(SERVER_LOG, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
            fh.flush()
    print("[p21-ref] " + line, file=sys.stderr, flush=True)


def visible_tools():
    return sorted(app._tool_manager._tools.keys())


@app.tool()
def p21_echo(text: str) -> str:
    """把输入原样回一遍（tools/call 的最小可用性证明）。"""
    note("call", tool="p21_echo", text=text)
    return "echo:" + text


@app.tool()
def p21_alpha() -> str:
    """零参数工具。"""
    note("call", tool="p21_alpha")
    return "alpha-ok"


@app.tool()
def p21_beta(query: str) -> str:
    """一个必填参数。"""
    note("call", tool="p21_beta", query=query)
    return "beta:" + query


def _make_added_tool(name):
    def fn() -> str:
        return "dynamic:" + name

    fn.__name__ = name
    fn.__doc__ = "由外部 actor 通过 p21_apply_table 加进来的工具"
    return fn


def _make_dropped_stub(name):
    def fn() -> str:
        return "gone:" + name

    fn.__name__ = name
    fn.__doc__ = "占位，用来说明 drop 是真摘了不是隐藏"
    return fn


def _apply(control_path):
    with open(control_path, encoding="utf-8") as fh:
        ctl = json.load(fh)
    added = []
    for name in ctl.get("add", []):
        app._tool_manager.add_tool(_make_added_tool(name), name=name,
                                   description="外部 actor 新增")
        added.append(name)
    dropped = []
    for name in ctl.get("drop", []):
        if name in app._tool_manager._tools:
            app._tool_manager.remove_tool(name)
            dropped.append(name)
    return {"added": added, "dropped": dropped, "table": visible_tools()}


@app.tool()
async def p21_stall(seconds: float) -> str:
    """故意睡这么久不回话 —— 用来量"超时到底是不是真的"（§1.2）。"""
    note("stall", seconds=seconds)
    await asyncio.sleep(seconds)
    return "stalled:%s" % seconds


@app.tool()
async def p21_apply_table(control_file: str, ctx: Context) -> str:
    """按外部控制文件改工具表，改完立刻在这条连接上推 list_changed。"""
    before = visible_tools()
    result = _apply(control_file)
    result["before"] = before
    note("table_changed", **result)
    await ctx.session.send_tool_list_changed()
    note("notification_sent", method="notifications/tools/list_changed")
    return json.dumps(result, ensure_ascii=False, sort_keys=True)


def _advertise_list_changed(enable=True):
    """让 **http 路径**在 initialize 里真广告 `capabilities.tools.listChanged`。

    为什么必须自己动手术（本机 site-packages 取证，官方 SDK mcp 1.27.1）：
      * `mcp/server/streamable_http_manager.py:196` 与 `:276` 调的是**不带参数**的
        `self.app.create_initialization_options()`；
      * `mcp/server/lowlevel/server.py:185` 于是落到 `notification_options or NotificationOptions()`
        ⇒ `tools_changed=False`；
      * `get_capabilities()`（`:216`）把它原样写进 `ToolsCapability(listChanged=...)`
        ⇒ 官方 FastMCP 的 streamable-http 路径**默认永远广告 listChanged=false**，
        哪怕它自己照推 `notifications/tools/list_changed`。
    stdio 路径没这毛病（本文件 `run_stdio()` 显式传了 `_init_options()`），
    所以"广告与兑现对账"这个能力位在 http 上是**结构性缺失**，只能补。

    做法：不改 SDK 一个字节，只在**实例上**把这个方法包一层，缺省注入
    `NotificationOptions(tools_changed=True)`；显式传了参数的（stdio 那条）一律原样放行。

    `enable=False` 就是"摘掉它"的对照臂 —— 工单要求"必须同时证明摘掉它 listChanged 就不在"，
    否则这条能力位是空断言。
    """
    lowlevel = app._mcp_server
    if getattr(lowlevel, "_p21_wrapped", False):
        return
    original = lowlevel.create_initialization_options

    def patched(notification_options=None, experimental_capabilities=None):
        if notification_options is None and enable:
            from mcp.server.lowlevel.server import NotificationOptions
            notification_options = NotificationOptions(tools_changed=True)
        return original(notification_options=notification_options,
                        experimental_capabilities=experimental_capabilities)

    lowlevel.create_initialization_options = patched
    lowlevel._p21_wrapped = True


def _init_options(enable=True):
    """让 server 在 initialize 里真的广告 capabilities.tools.listChanged=true。

    `create_initialization_options()` 的默认值是空 NotificationOptions ⇒ 三位都是 false，
    那样 z-bot 侧读到的对端能力位是假的"我不会推"，广告与兑现就没有对账物了。
    """
    from mcp.server.lowlevel.server import NotificationOptions
    return app._mcp_server.create_initialization_options(
        notification_options=NotificationOptions(tools_changed=bool(enable)))


async def run_stdio(enable_advertise=True):
    from mcp.server.stdio import stdio_server
    _advertise_list_changed(enable_advertise)
    note("listening", transport="stdio", tools=visible_tools(),
         advertises_list_changed=enable_advertise)
    async with stdio_server() as (read, write):
        await app._mcp_server.run(read, write, _init_options(enable_advertise))


async def run_http(port, port_file, enable_advertise=True):
    import uvicorn
    _advertise_list_changed(enable_advertise)
    starlette_app = app.streamable_http_app()
    port = int(os.environ.get("P21_REF_PORT", port))
    config = uvicorn.Config(starlette_app, host="127.0.0.1", port=port,
                            log_level="warning", lifespan="on", access_log=False)
    server = uvicorn.Server(config)
    task = asyncio.create_task(server.serve())
    while not server.started:
        await asyncio.sleep(0.02)
        if task.done():
            await task
            return
    bound = server.servers[0].sockets[0].getsockname()[1]
    url = "http://127.0.0.1:%d%s" % (bound, app.settings.streamable_http_path)
    note("listening", transport="streamable-http", url=url, tools=visible_tools(),
         advertises_list_changed=enable_advertise)
    if port_file:
        with open(port_file, "w", encoding="utf-8") as fh:
            fh.write(url + "\n")
            fh.flush()
    await task


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--transport", choices=["stdio", "http"], default="stdio")
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--port-file", default="")
    ap.add_argument("--log-file", default="")
    # 对照臂开关：默认开。`--no-list-changed` 用来证明"能力位断言不是空断言"
    # （摘掉它 ⇒ 官方 http 路径回到 SDK 默认的 listChanged=false，见 _advertise_list_changed 的取证）
    ap.add_argument("--no-list-changed", dest="list_changed", action="store_false", default=True)
    args = ap.parse_args()
    set_server_log(args.log_file)
    if args.transport == "stdio":
        asyncio.run(run_stdio(args.list_changed))
    else:
        asyncio.run(run_http(args.port, args.port_file, args.list_changed))


if __name__ == "__main__":
    main()
