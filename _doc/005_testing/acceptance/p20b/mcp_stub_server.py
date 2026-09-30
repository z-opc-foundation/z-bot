#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真 MCP server 子进程（stdio / newline-delimited JSON-RPC），给 p20b 的 E2E 用。

用法: mcp_stub_server.py <pid 文件> <工具清单文件>
  * 启动即把自己的 pid 写进 <pid 文件>（外部据此确认"这个进程真起来了 / 真死了"）；
  * tools/list 的内容每次都现读 <工具清单文件>（reload 前改一下就能模拟 server 换工具名）；
  * tools/call 回一段文本；
  * 协议面只实现 initialize / notifications/initialized / tools/list / tools/call，
    与 kernel StdioMcpTransport + McpClientFactory 的握手对齐。

日志一律走 stderr（真 stdio 传输会丢弃 stderr），别污染 stdout 的 JSON-RPC 通道。
"""
import json
import os
import sys
import time


def log(msg):
    sys.stderr.write("[stub-mcp pid=%d %s] %s\n" % (os.getpid(), time.strftime("%H:%M:%S"), msg))
    sys.stderr.flush()


def read_tools(path):
    tools = []
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                name, _, desc = line.partition(":")
                tools.append({"name": name.strip(),
                              "description": (desc or "").strip() or ("工具 " + name.strip()),
                              "inputSchema": {"type": "object",
                                              "properties": {"text": {"type": "string"}}}})
    except OSError as e:
        log("读清单失败: %s" % e)
    return tools


def main():
    if len(sys.argv) < 3:
        log("缺参数: pid 文件 / 工具清单文件")
        return 2
    pid_file, tools_file = sys.argv[1], sys.argv[2]
    with open(pid_file, "w", encoding="utf-8") as fh:
        fh.write("%d\n" % os.getpid())
    log("起进程, 清单=%s" % tools_file)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            log("非 JSON 行: %s" % line[:80])
            continue
        method = req.get("method")
        rid = req.get("id")
        if method == "initialize":
            resp = {"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {"listChanged": True}},
                "serverInfo": {"name": "p20b-stub", "version": "1.0"}}}
        elif method == "notifications/initialized":
            continue
        elif method == "tools/list":
            resp = {"jsonrpc": "2.0", "id": rid, "result": {"tools": read_tools(tools_file)}}
        elif method == "tools/call":
            params = req.get("params") or {}
            name = params.get("name")
            text = ((params.get("arguments") or {}).get("text")) or ""
            resp = {"jsonrpc": "2.0", "id": rid, "result": {
                "content": "stub:%s:%s" % (name, text)}}
        elif rid is None:
            continue
        else:
            resp = {"jsonrpc": "2.0", "id": rid, "result": {}}
        # 默认紧凑分隔符。kernel StdioMcpTransport.request 用 contains("\"id\":" + id) 认回执，
        # 而 json.dumps 默认写成 "id": 1（带空格）—— 真实 server（官方 MCP SDK / 任何非手写
        # 序列化的实现）都是带空格这一侧，故 STUB_PRETTY=1 保留该形状作缺陷复现开关。
        seps = (", ", ": ") if os.environ.get("STUB_PRETTY") else (",", ":")
        sys.stdout.write(json.dumps(resp, separators=seps) + "\n")
        sys.stdout.flush()
    log("stdin 关了就退")
    return 0


if __name__ == "__main__":
    sys.exit(main())
