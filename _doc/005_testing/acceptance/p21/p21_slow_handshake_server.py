#!/usr/bin/env python3
"""P21 取证用的"慢握手"假 MCP server：只为钉住 ZBotStdioMcpTransport 的两条时间预算。

一对相反方向的猎物，缺一条守卫就是空跑：
  1. initialize 前先睡 N 秒 ⇒ 若握手也吃"单请求超时"，connect() 必红（握手被误伤）。
  2. 握手之后对任何请求都不再回话 ⇒ 若单请求超时被抬到握手的预算，request() 必不红
     （真正的 deadline 又被写成了摆设 —— 内核 §1.2 那个老毛病）。

只做 JSON-RPC 的 initialize 这一层，不引官方 SDK：这条守卫不该依赖第三方包在不在。

用法： python3 p21_slow_handshake_server.py [握手延迟秒数，默认 1.5]
"""

import json
import sys
import time

PROTOCOL_VERSION = "2025-06-18"


def read_line():
    line = sys.stdin.readline()
    if not line:
        return None
    try:
        return json.loads(line)
    except ValueError:
        return {}


def main():
    delay = float(sys.argv[1]) if len(sys.argv) > 1 else 1.5
    req = read_line()
    if req is None:
        return 0
    time.sleep(delay)
    resp = {
        "jsonrpc": "2.0",
        "id": req.get("id"),
        "result": {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "slow-handshake", "version": "0"},
        },
    }
    sys.stdout.write(json.dumps(resp) + "\n")
    sys.stdout.flush()
    # 故意不回：让调用方的单请求超时成为唯一可能的结局。
    while read_line() is not None:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
