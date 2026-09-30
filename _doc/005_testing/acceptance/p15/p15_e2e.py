#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P15 验收③：真进程 E2E（真 jar、真 CLI、真两个 JVM 对撞、真坏库），不许用读代码代替。

复算: mvn -o -pl z-bot-core package -DskipTests && python3 _doc/005_testing/acceptance/p15/p15_e2e.py

红线 1：全程 --config-dir/--db 都指向临时目录，绝不碰 ~/.zbot；收尾核对 ~/.zbot 项数与两个 md5。
key 一律 stub-key-not-real，真 key 不进任何临时目录。

E2 是 MU-1（begin-immediate 被忽略）的唯一证据：13 个变异体里只有它 12 条单测全绿，
所以"IMMEDIATE 到底挡了什么"只能靠两个真进程对撞读数。
"""
import hashlib
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")

RESULTS = []


def check(name, ok, detail):
    RESULTS.append((name, bool(ok), detail))
    print("%-4s %s | %s" % ("PASS" if ok else "FAIL", name, detail), flush=True)
    return bool(ok)


def sh(cmd):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return p.returncode, p.stdout.decode("utf-8", "replace")


def one(cmd):
    rc, out = sh(cmd)
    if rc != 0:
        raise RuntimeError("命令失败 %s ⇒ %s" % (" ".join(cmd[:3]), out.strip()[:400]))
    return out.strip()


def flat(out, limit=300):
    """去掉 JDK 的 native-access 噪声，只留 CLI 自己的输出。"""
    keep = [l for l in out.split("\n")
            if l.strip() and not l.startswith("WARNING:")
            and "enable-native-access" not in l and "Restricted methods" not in l]
    return " ⏎ ".join(keep)[:limit]


def sql(db, statement):
    return one(["sqlite3", str(db), statement])


LEGACY_DDL = """
CREATE TABLE sessions ( id TEXT PRIMARY KEY,
  title TEXT NOT NULL DEFAULT '新会话', source TEXT NOT NULL DEFAULT 'cli',
  model TEXT, provider TEXT, message_count INTEGER NOT NULL DEFAULT 0,
  tokens INTEGER NOT NULL DEFAULT 0, api_calls INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, metadata TEXT);
CREATE TABLE messages ( seq INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,
  idx INTEGER NOT NULL, role TEXT, content TEXT, content_type TEXT, tool_name TEXT,
  tool_call_id TEXT, payload TEXT NOT NULL, timestamp TEXT NOT NULL, UNIQUE(session_id, idx));
CREATE INDEX idx_messages_session ON messages(session_id, idx);
CREATE TABLE session_model_usage ( id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,
  model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER, api_calls INTEGER, ts TEXT NOT NULL);
CREATE TABLE async_delegations ( id TEXT PRIMARY KEY, task TEXT NOT NULL, status TEXT NOT NULL,
  reply TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, finished_at TEXT);
CREATE VIRTUAL TABLE messages_fts USING fts5(session_id UNINDEXED, content);
CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
  INSERT INTO messages_fts(session_id, content) VALUES (new.session_id, new.content); END;
INSERT INTO sessions(id,title,model,provider,message_count,tokens,api_calls,created_at,updated_at)
  VALUES('old-1','老会话一','m1','p1',2,10,1,'2026-09-01T00:00:00','2026-09-01T00:00:00');
INSERT INTO sessions(id,title,model,provider,message_count,tokens,api_calls,created_at,updated_at)
  VALUES('old-2','老会话二','m2','p2',1,5,1,'2026-09-02T00:00:00','2026-09-02T00:00:00');
INSERT INTO messages(session_id,idx,role,content,content_type,payload,timestamp)
  VALUES('old-1',0,'user','遗留消息甲需要被检索','text','{"role":"user","content":"遗留消息甲需要被检索"}','2026-09-01T00:00:01');
INSERT INTO messages(session_id,idx,role,content,content_type,payload,timestamp)
  VALUES('old-1',1,'assistant','遗留消息乙','text','{"role":"assistant","content":"遗留消息乙"}','2026-09-01T00:00:02');
INSERT INTO messages(session_id,idx,role,content,content_type,payload,timestamp)
  VALUES('old-2',0,'user','遗留消息丙','text','{"role":"user","content":"遗留消息丙"}','2026-09-02T00:00:01');
INSERT INTO session_model_usage(session_id,model,prompt_tokens,completion_tokens,api_calls,ts)
  VALUES('old-1','m1',7,3,1,'2026-09-01T00:00:03');
INSERT INTO async_delegations(id,task,status,reply,created_at,finished_at)
  VALUES('del-1','老任务','done','老回复','2026-09-01T00:00:04','2026-09-01T00:00:05');
"""

TABLES = ("sessions", "messages", "session_model_usage", "async_delegations")


def counts(db):
    return {t: int(sql(db, "SELECT COUNT(*) FROM %s" % t)) for t in TABLES}


def main():
    if not os.path.exists(JAR):
        print("FATAL: 找不到 %s，先跑 mvn -o -pl z-bot-core package -DskipTests" % JAR)
        return 2
    home = os.path.expanduser("~/.zbot")
    zbot_before = sorted(os.listdir(home))
    md5 = lambda p: hashlib.md5(open(p, "rb").read()).hexdigest() if os.path.exists(p) else None
    cfg_before = md5(os.path.join(home, "config.properties"))
    db_before = md5(os.path.join(home, "state.db"))

    work = tempfile.mkdtemp(prefix="p15_e2e_")
    print("workdir=%s\njar=%s\n~/.zbot 跑前项数=%d" % (work, JAR, len(zbot_before)))

    rc, out = sh(["javac", "-cp", JAR, "-d", work, os.path.join(HERE, "AbWriter.java")])
    if rc != 0:
        print("FATAL: AbWriter 编译失败\n" + out)
        return 2
    print("AbWriter 编译 OK\n")

    def cfg(dirpath, extra=""):
        os.makedirs(dirpath, exist_ok=True)
        with open(os.path.join(dirpath, "config.properties"), "w", encoding="utf-8") as fh:
            fh.write("api.key=stub-key-not-real\nbase.url=http://127.0.0.1:1/v1\n"
                     "model=stub-model\n" + extra)

    def cli(cfgdir, db, *args):
        return sh(["java", "-jar", JAR, "sessions", "--config-dir", cfgdir, "--db", db] + list(args))

    # ============ E1 老库 → head：真 CLI 触发迁移，一行不丢 ============
    cfgdir = os.path.join(work, "cfg")
    cfg(cfgdir)
    legacy = os.path.join(work, "legacy.db")
    sql(legacy, LEGACY_DDL)
    before = counts(legacy)
    trig_before = sql(legacy, "SELECT group_concat(name) FROM sqlite_master WHERE type='trigger'")
    check("E1a 老库建好（4 表 + 老 FTS 只有 INSERT 触发器）",
          before == {"sessions": 2, "messages": 3, "session_model_usage": 1, "async_delegations": 1}
          and trig_before == "messages_ai", "counts=%s triggers=%s" % (before, trig_before))

    rc, out = cli(cfgdir, legacy, "version")
    print("  $ sessions version →\n" + "\n".join("      " + l for l in out.split("\n")[:14]))
    version = int(sql(legacy, "SELECT version FROM schema_version"))
    cols = [l.split("|")[1] for l in sql(legacy, "PRAGMA table_info(sessions)").split("\n") if l]
    new_cols = [c for c in ("parent_session_id", "ended_at", "end_reason", "archived") if c in cols]
    tables = set(l.split("|")[1] for l in sql(legacy,
                  "SELECT type||'|'||name FROM sqlite_master WHERE type='table'").split("\n") if "|" in l)
    after = counts(legacy)
    check("E1b schema_version 落到 head=3 且 sessions 有 4 个新列",
          rc == 0 and version == 3 and len(new_cols) == 4,
          "rc=%d version=%d new_cols=%s" % (rc, version, new_cols))
    check("E1c 新表 state_meta/compression_locks/gateway_routing 到位",
          {"state_meta", "compression_locks", "gateway_routing"} <= tables,
          "tables=%s" % sorted(tables))
    check("E1d 迁移一行不丢", all(after[t] == before[t] for t in before),
          "before=%s after=%s" % (before, after))

    rc, out = cli(cfgdir, legacy, "search", "遗留消息甲")
    check("E1e 老 FTS（缺 delete 触发器）就地重建后仍能全文命中",
          rc == 0 and "old-1" in out, "rc=%d out=%s" % (rc, flat(out, 280)))
    trig_after = sql(legacy, "SELECT group_concat(name) FROM sqlite_master WHERE type='trigger'")
    fts_now = int(sql(legacy, "SELECT COUNT(*) FROM messages_fts"))
    check("E1f 补上了 messages_ad/messages_au 触发器且 FTS 行数=messages 行数",
          "messages_ad" in trig_after and fts_now == after["messages"],
          "triggers=%s fts=%d messages=%d" % (trig_after, fts_now, after["messages"]))

    rc, out = cli(cfgdir, legacy, "version", "--check")
    check("E1g version --check 在迁移后的库上通过", rc == 0 and "FAIL" not in out.upper(),
          "rc=%d out=%s" % (rc, flat(out, 300)))

    # ============ E2 两个真 JVM 对撞同一 session（2×2：IMMEDIATE × 重试阶梯） ============
    cases = [
        ("C1 改前形态: deferred + 0 重试 + busy=0", "false", 0, 0, False),
        ("C2 只有阶梯: deferred + 15 重试 + busy=1000", "false", 1000, 15, True),
        ("C3 只有 IMMEDIATE: immediate + 0 重试 + busy=1000", "true", 1000, 0, True),
        ("C4 P15 默认: immediate + 15 重试 + busy=1000", "true", 1000, 15, True),
    ]
    N = 240
    for idx, (label, imm, busy, retries, clean_expected) in enumerate(cases):
        db = os.path.join(work, "ab%d.db" % idx)
        rc, out = cli(cfgdir, db, "list")  # 先让一个进程把库建到 head，别把"建库竞态"混进对撞
        if rc != 0:
            check("E2 %s" % label, False, "预建库就失败: rc=%d %s" % (rc, out[:200]))
            continue
        start_at = int(time.time() * 1000) + 2500
        procs = [subprocess.Popen(["java", "-cp", JAR + ":" + work, "AbWriter", db, tag, str(N),
                                   imm, str(busy), str(retries), str(start_at)],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                 for tag in ("A", "B")]
        lines = []
        for p in procs:
            o = p.communicate()[0].decode("utf-8", "replace")
            lines += [l for l in o.split("\n") if l.startswith("RESULT") or "Exception" in l]
        parsed = [dict(kv.split("=", 1) for kv in l.split() if "=" in kv)
                  for l in lines if l.startswith("RESULT")]
        if len(parsed) != 2:
            check("E2 %s" % label, False, "两个写进程没都交出读数: %s" % lines[:2])
            continue
        # 行数只从驱动侧读一次：两个写进程各自的 JDBC 快照都是"整表"，相加会翻倍
        rows, distinct, span, count_col = [int(x or 0) for x in sql(db,
            "SELECT COUNT(*), COUNT(DISTINCT content), COALESCE(MAX(idx)+1,0),"
            " (SELECT message_count FROM sessions WHERE id='ab-shared') FROM messages"
            " WHERE session_id='ab-shared'").split("|")]
        ok = sum(int(d["ok"]) for d in parsed)
        lost = sum(int(d["lost"]) for d in parsed)
        slow = max(int(d["ms"]) for d in parsed)
        if clean_expected:
            verdict = rows == 2 * N and distinct == rows and span == rows and lost == 0 and count_col == rows
        else:
            # C1 是"改前"读数：它必须丢，否则这道 A/B 就没有证明任何东西
            verdict = lost > 0 or rows != 2 * N
        check("E2 %s%s" % (label, "（改前应有损伤）" if not clean_expected else ""), verdict,
              "落库 rows=%d/%d 唯一内容=%d idx 跨度=%d message_count=%d 调用方 lost=%d(ok=%d) 最慢=%dms | %s"
              % (rows, 2 * N, distinct, span, count_col, lost, ok, slow,
                 " ; ".join("%s: ok=%s lost=%s lastError=%s"
                            % (d["tag"], d["ok"], d["lost"], (d["lastError"] or "null")[:70]) for d in parsed)))

    # ============ E3 坏库自愈：真 CLI 打开被写烂的库 ============
    broken_dir = os.path.join(work, "broken")
    cfg(broken_dir)
    broken = os.path.join(broken_dir, "state.db")
    sql(broken, "CREATE TABLE t(x); INSERT INTO t VALUES(1);")
    with open(broken, "r+b") as fh:
        fh.seek(0)
        fh.write(b"\x00\x00\x00\x00not a sqlite header at all" + b"\xff" * 4096)
    size_before = os.path.getsize(broken)
    rc, out = cli(broken_dir, broken, "list")
    aside = sorted(f for f in os.listdir(broken_dir) if f.startswith("state.db.corrupt"))
    check("E3 坏库改名备份后重建空库（CLI 不崩、原件没被覆盖）",
          rc == 0 and len(aside) == 1 and os.path.exists(broken)
          and os.path.getsize(os.path.join(broken_dir, aside[0])) == size_before,
          "rc=%d aside=%s 备份大小=%s 新库大小=%d out=%s"
          % (rc, aside, os.path.getsize(os.path.join(broken_dir, aside[0])) if aside else "-",
             os.path.getsize(broken) if os.path.exists(broken) else -1,
             flat(out, 220)))

    # ============ E4 prune 的默认档在真 CLI 上确实保守 ============
    pdir = os.path.join(work, "prune")
    cfg(pdir)
    pdb = os.path.join(pdir, "state.db")
    rc, out = cli(pdir, pdb, "list")  # head 建库（才有 ended_at/archived 列）
    old = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(time.time() - 30 * 86400))
    sql(pdb, "INSERT INTO sessions(id,title,created_at,updated_at,ended_at,end_reason,message_count)"
             " VALUES('ended-with-msg','有货的已结束会话','{o}','{o}','{o}','user',1)".format(o=old))
    sql(pdb, "INSERT INTO messages(session_id,idx,role,content,payload,timestamp)"
             " VALUES('ended-with-msg',0,'user','这条必须活过默认 prune','{{\"content\":\"x\"}}','{o}')".format(o=old))
    sql(pdb, "INSERT INTO sessions(id,title,created_at,updated_at,ended_at,end_reason)"
             " VALUES('ended-empty','空的已结束会话','{o}','{o}','{o}','user')".format(o=old))
    sql(pdb, "INSERT INTO sessions(id,title,created_at,updated_at) VALUES('in-flight','在飞','{o}','{o}')".format(o=old))

    rc, out = cli(pdir, pdb, "prune", "--days", "7")
    survivors = set(sql(pdb, "SELECT id FROM sessions").split("\n"))
    print("  $ sessions prune --days 7 →\n" + "\n".join("      " + l for l in out.split("\n")[:8]))
    check("E4a 默认 prune 只清空会话：有货的/在飞的都不动",
          rc == 0 and {"ended-with-msg", "in-flight"} <= survivors and "ended-empty" not in survivors,
          "rc=%d survivors=%s" % (rc, sorted(survivors)))
    msgs_left = int(sql(pdb, "SELECT COUNT(*) FROM messages WHERE session_id='ended-with-msg'"))
    check("E4b 有货会话的消息原样在", msgs_left == 1, "messages=%d" % msgs_left)

    rc, out = cli(pdir, pdb, "prune", "--days", "7", "--include-non-empty")
    survivors2 = set(sql(pdb, "SELECT id FROM sessions").split("\n"))
    fts_left = int(sql(pdb, "SELECT COUNT(*) FROM messages_fts WHERE session_id='ended-with-msg'"))
    check("E4c 只有 --include-non-empty 才硬删有货会话（在飞仍不动）",
          rc == 0 and "ended-with-msg" not in survivors2 and "in-flight" in survivors2,
          "rc=%d survivors=%s" % (rc, sorted(survivors2)))
    check("E4d 硬删连带清了 messages 与 FTS 行",
          int(sql(pdb, "SELECT COUNT(*) FROM messages WHERE session_id='ended-with-msg'")) == 0
          and fts_left == 0, "messages_fts 残留=%d" % fts_left)

    sql(pdb, "INSERT INTO sessions(id,title,created_at,updated_at) VALUES('in-flight2','在飞二',strftime('%Y-%m-%dT%H:%M:%S','now'),strftime('%Y-%m-%dT%H:%M:%S','now'))")
    sql(pdb, "INSERT INTO sessions(id,title,created_at,updated_at,ended_at,end_reason)"
             " VALUES('ended2','又结束一个','{o}','{o}','{o}','user')".format(o=old))
    before_dry = set(sql(pdb, "SELECT id FROM sessions").split("\n"))
    rc, out = cli(pdir, pdb, "prune", "--days", "7", "--dry-run")
    after_dry = set(sql(pdb, "SELECT id FROM sessions").split("\n"))
    check("E4e --dry-run 只点名不动手", rc == 0 and before_dry == after_dry and "dry-run" in out,
          "rc=%d 命中前后=%s→%s out=%s" % (rc, sorted(before_dry), sorted(after_dry),
                                        flat(out, 220)))

    # ============ E5 配置键真有的牙：--config-dir 里的 prune.auto 传到 store ============
    adir = os.path.join(work, "auto")
    cfg(adir, "agent.state.prune.auto=true\nagent.state.prune.retention.days=1\n")
    creator = os.path.join(work, "creator")
    cfg(creator)  # 建库用"不带 prune.auto"的配置：last_auto_prune 记在库里，必须让第一次 auto 开库落在灌数据之后
    adb = os.path.join(adir, "state.db")
    rc, out = cli(creator, adb, "list")
    sql(adb, "INSERT INTO sessions(id,title,created_at,updated_at,ended_at,end_reason)"
             " VALUES('old-ended','早就结束的会话','{o}','{o}','{o}','user')".format(o=old))
    sql(adb, "INSERT INTO sessions(id,title,created_at,updated_at) VALUES('fresh','今天新建的',strftime('%Y-%m-%dT%H:%M:%S','now'),strftime('%Y-%m-%dT%H:%M:%S','now'))")
    rc, out = cli(adir, adb, "list", "--all")
    left = set(sql(adb, "SELECT id FROM sessions").split("\n"))
    check("E5 agent.state.prune.auto(+retention.days) 经 --config-dir 真清了一次",
          rc == 0 and "old-ended" not in left and "fresh" in left,
          "rc=%d 剩余=%s out=%s" % (rc, sorted(left), flat(out, 220)))

    cfg2 = os.path.join(work, "noauto")
    cfg(cfg2)  # 不设 prune.auto ⇒ 默认关
    db2 = os.path.join(work, "noauto.db")
    rc, out = cli(cfg2, db2, "list")
    sql(db2, "INSERT INTO sessions(id,title,created_at,updated_at,ended_at,end_reason)"
             " VALUES('old-ended','早就结束的会话','{o}','{o}','{o}','user')".format(o=old))
    rc, out = cli(cfg2, db2, "list", "--all")
    check("E5b 不配 prune.auto 时同样的库一动不动（默认关）",
          rc == 0 and "old-ended" in set(sql(db2, "SELECT id FROM sessions").split("\n")),
          "rc=%d 剩余=%s" % (rc, sql(db2, "SELECT group_concat(id) FROM sessions")))

    # ============ E6 红线 1：~/.zbot 一点没动 ============
    zbot_after = sorted(os.listdir(home))
    check("E6 红线1: ~/.zbot 项数与内容跑前跑后一致",
          zbot_before == zbot_after and md5(os.path.join(home, "config.properties")) == cfg_before
          and md5(os.path.join(home, "state.db")) == db_before,
          "项数=%d→%d cfg_md5=%s→%s state_md5=%s→%s"
          % (len(zbot_before), len(zbot_after), (cfg_before or "")[:8],
             (md5(os.path.join(home, "config.properties")) or "")[:8],
             (db_before or "none")[:8], (md5(os.path.join(home, "state.db")) or "none")[:8]))

    good = sum(1 for _, ok, _ in RESULTS if ok)
    print("\n== P15 E2E %d/%d 通过 ==" % (good, len(RESULTS)))
    for name, ok, detail in RESULTS:
        if not ok:
            print("  FAIL %s | %s" % (name, detail))
    print("临时目录留着复核: %s" % work)
    return 0 if good == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
