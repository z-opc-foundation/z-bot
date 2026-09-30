#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
"E0b 邻居真的攥住了跨进程锁" 这条断言的**前提**取证（p17_e2e.py E 段的地基）。

两件事必须实测，不能照 POSIX 手册猜：

  1. Python 的 lockf 与 JVM 的 FileChannel.tryLock 落在**同一个锁命名空间**
     （POSIX 记录锁与 BSD flock(2) 在 macOS 上是两套，"应该互通"是猜）。
  2. 反过来也要成立：**攥锁的一方放开之后，邻居必须立刻抢得到**。
     否则 E 段"放锁 ⇒ 请求完成"这一路是空的。

第 2 条为什么进来了：2026-09-26 实跑 r4 时，E0b 用的是"再起一个 Python 子进程
`lockf(fd, 3)`(F_TEST) 探一探"，读数永远是 ACQUIRED —— 本机（darwin 25.5 /
CPython 3.14 / APFS）**测不出**邻居在 0 字节文件上的记录锁，等于探针空跑；
而同一时刻真 JVM 的 `tryLock(0, Long.MAX_VALUE, false)` 拿到的是 null
（与被测代码 CronScheduler.acquireJobsLock 用的是同一个请求形状）。
所以这里的邻居探针一律用 **JVM**，不用 Python lockf。

复算: python3 _doc/acceptance/p17/probe_lock_namespace.py
退出码 0 = 双向都证到 ⇒ p17_e2e.py 的 E 段有效；非 0 = 探针不成立，E 段读数一律不采信。
"""
import os
import subprocess
import sys
import tempfile
import time

# Darwin 的 fcntl 模块不导出 F_TLOCK 这个名字，但 fcntl(2) 的数值语义照旧（2 = 非阻塞攥锁）。
F_TLOCK = 2

JAVA = """
import java.nio.channels.*;
import java.nio.file.*;

public class LockProbe {
    public static void main(String[] args) throws Exception {
        FileChannel ch = FileChannel.open(Paths.get(args[0]),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        // 与被测代码 CronScheduler.acquireJobsLock 同一个请求形状（整文件独占）
        FileLock lock = ch.tryLock(0L, Long.MAX_VALUE, false);
        System.out.println(lock == null ? "NULL" : "GRANTED");
        if (lock != null) {
            lock.release();
        }
        ch.close();
    }
}
"""


def java_neighbour(classes, target):
    """跑一次邻居 JVM：返回 'NULL'（抢不到 = 有人在攥）/ 'GRANTED'（抢得到）。"""
    r = subprocess.run(["java", "-cp", classes, "LockProbe", target],
                       capture_output=True, text=True, timeout=60)
    out = (r.stdout or "").strip()
    if r.returncode != 0 or out not in ("NULL", "GRANTED"):
        raise RuntimeError("邻居 JVM 没给出读数 (rc=%d): %s"
                           % (r.returncode, ((r.stderr or out).strip()[:300])))
    return out


def main():
    import fcntl

    work = tempfile.mkdtemp(prefix="zbot-lockprobe-")
    target = os.path.join(work, "target.lock")
    # 被测代码的锁文件也是空文件，这里对齐（别写出内容去改变区间语义）
    with open(target, "w") as fh:
        fh.write("")

    src = os.path.join(work, "LockProbe.java")
    with open(src, "w", encoding="utf-8") as fh:
        fh.write(JAVA)
    classes = os.path.join(work, "classes")
    os.makedirs(classes)
    r = subprocess.run(["javac", "-d", classes, src], capture_output=True, text=True)
    if r.returncode != 0:
        print("FATAL: javac 失败，量具根本没跑起来（这不等于「不互通」）: %s" % r.stderr.strip()[:300])
        return 2

    fd = os.open(target, os.O_RDWR)
    try:
        fcntl.lockf(fd, F_TLOCK)
    except OSError as e:
        os.close(fd)
        print("FATAL: 连自己都没攥住锁 (%s) —— 探针无意义" % e)
        return 2

    failures = []
    try:
        # ① 攥住期间：邻居必须抢不到（证"互通"，也证 E 段的猎物真进了陷阱）
        got = [java_neighbour(classes, target) for _ in range(2)]
        print("① python 攥锁期间，邻居 JVM tryLock => %s" % got)
        if got != ["NULL", "NULL"]:
            failures.append("攥锁期间邻居照样抢到（%s）⇒ 两套锁不互通，E 段是空跑" % got)

        # ② 放开之后：邻居必须马上抢得到（证放锁真的生效）
        os.close(fd)
        fd = -1
        released = None
        for _ in range(20):          # 内核回收有微小窗口，最多等 4s
            released = java_neighbour(classes, target)
            if released == "GRANTED":
                break
            time.sleep(0.2)
        print("② python 关描述符（放锁）后，邻居 JVM tryLock => %s" % released)
        if released != "GRANTED":
            failures.append("放锁后邻居仍抢不到（%s）⇒ 「放开 ⇒ 请求完成」那一环没有尺" % released)

        # ③ 再攥一次还能攥上（②的对照：排除"文件被玩坏了所以恒 GRANTED"）
        fd = os.open(target, os.O_RDWR)
        fcntl.lockf(fd, F_TLOCK)
        again = java_neighbour(classes, target)
        print("③ 重新攥锁，邻居 JVM tryLock => %s" % again)
        if again != "NULL":
            failures.append("重新攥锁邻居却抢到（%s）⇒ ②的 GRANTED 不是「放锁生效」，是尺坏了" % again)
    except RuntimeError as e:
        print("FATAL: 邻居探针跑飞，读数无效: %s" % e)
        return 2
    finally:
        # macOS 的 fcntl 模块拒绝 lockf(fd, F_ULOCK)（"unrecognized lockf argument"），
        # 而关掉描述符本来就会释放该进程在这个文件上的全部记录锁 —— 直接关。
        # 但**绝不能**用 os.close(os.dup(fd)) 来"释放"：dup 出来的 fd 一关，
        # 整个进程在该文件上的锁就全掉了（本机实测到这一坑）。
        if fd >= 0:
            os.close(fd)
        subprocess.run(["rm", "-rf", work], capture_output=True)

    if failures:
        for line in failures:
            print("BAD: " + line)
        print("⇒ p17_e2e.py 的 E 段读数一律不采信")
        return 1
    print("OK: 双向都证到 —— python lockf 与 JVM tryLock 同命名空间，且放锁即时可见"
          " ⇒ p17_e2e.py 的 E 段邻居探针有效")
    return 0


if __name__ == "__main__":
    sys.exit(main())
