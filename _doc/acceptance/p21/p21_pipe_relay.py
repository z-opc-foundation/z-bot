#!/usr/bin/env python3
"""P21 取证用的 stdio 中继：原样转发 stdin/stdout，同时把两个方向逐行落盘。

存在的理由：要证"server 真的推了 notifications/tools/list_changed 且这行字节
真的到了 client 的 stdout"，就必须有一个 client 之外的观察点 —— 否则
"client 没收到"和"server 没推"在 client 侧长得一模一样。

用法： python3 p21_pipe_relay.py --log /path/log -- <server argv...>
     （也吃环境变量 P21_RELAY_LOG）
日志行格式：  <方向> <epoch秒> <原始字节行>      方向 S2C=server→client, C2S=client→server
"""

import os
import subprocess
import sys
import threading
import time

LOCK = threading.Lock()


def main():
    log_path = os.environ.get("P21_RELAY_LOG", "")
    while len(sys.argv) > 2 and sys.argv[1] == "--log":
        log_path = sys.argv[2]
        del sys.argv[1:3]
    argv = sys.argv[1:]
    if argv and argv[0] == "--":
        argv = argv[1:]
    if not argv:
        print("usage: P21_RELAY_LOG=... p21_pipe_relay.py -- <cmd> [args]", file=sys.stderr)
        return 2
    log = open(log_path, "ab", buffering=0) if log_path else None

    child = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=sys.stderr)

    def pump(direction, src, dst):
        for raw in iter(src.readline, b""):
            if log is not None:
                with LOCK:
                    log.write(("%s %.6f " % (direction, time.time())).encode("utf-8") + raw)
            try:
                dst.write(raw)
                dst.flush()
            except (BrokenPipeError, ValueError):
                return

    t = threading.Thread(target=pump, args=("C2S", sys.stdin.buffer, child.stdin), daemon=True)
    t.start()
    pump("S2C", child.stdout, sys.stdout.buffer)
    child.wait()
    if log is not None:
        log.close()
    return child.returncode or 0


if __name__ == "__main__":
    sys.exit(main())
