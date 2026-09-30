#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P15b 杠③ —— 真进程 E2E：**老库升级不丢行 + 判定=要 的新列真被读到**。

不许用读代码代替：全程真 `java -jar`（shade 后的可执行 jar）+ 真 `sqlite3` CLI + 真临时 profile 目录。
"升级完成"不拿日志当盘上状态 —— 每一条关于盘面的判定都由**另起的 sqlite3 进程**回读字节
（P17 实测过"日志→落盘"有 0.1–8.8ms 的窗口，日志说了不算）。

覆盖三件本期的账：
  A. 升级前 P2 原样 DDL（sessions 11 列 / messages 10 列）+ 真数据 → 真 CLI 开一次库 →
     行数一条不丢、`PRAGMA table_info(sessions)` 恰好等于 head 的 15 列、`schema_version` 落到 3；
  B. P15 那 4 列（parent_session_id / ended_at / end_reason / archived）+ `message_count`
     被真 CLI 写、被独立进程读回，并且**改变了 CLI 的输出**（不是写了没人看）；
  C. 红线 1：key 一律 `stub-key-not-real`，且在同一条里反向钉住这个 stub 真进了产物
     （否则是空跑）；`~/.zbot` 只在跑前跑后各数一次项数与两个 md5 前缀，全程不读其内容。

复算: mvn -o -pl z-bot-core package -DskipTests && python3 -u _doc/acceptance/p15b/p15b_e2e.py
退出码 0 = 全过；任何一条 FAIL 直接非 0；jar 缺失 = 2（FATAL，不算过）。
"""
import hashlib
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
WORK = "/private/tmp/zbot-p15b-e2e"
CFG = os.path.join(WORK, "cfg")
DB = os.path.join(CFG, "state.db")
STUB_KEY = "stub-key-not-real"

HEAD_SESSION_COLS = ["id", "title", "source", "model", "provider", "message_count", "tokens",
                     "api_calls", "created_at", "updated_at", "metadata", "parent_session_id",
                     "ended_at", "end_reason", "archived"]

# ---------------------------------------------------------------------------
# 升级前的样子：ae5aff7 的 StateStore.initSchema 字面量**手抄**（不从生产代码取，
# 否则改了生产 DDL 会把"老库"跟着改掉，本 E2E 就自证清白了）。
# ---------------------------------------------------------------------------
PRE_P15_SESSIONS_DDL = (
    "CREATE TABLE sessions ("
    " id TEXT PRIMARY KEY,"
    " title TEXT NOT NULL DEFAULT '新会话',"
    " source TEXT NOT NULL DEFAULT 'cli',"
    " model TEXT, provider TEXT,"
    " message_count INTEGER NOT NULL DEFAULT 0,"
    " tokens INTEGER NOT NULL DEFAULT 0,"
    " api_calls INTEGER NOT NULL DEFAULT 0,"
    " created_at TEXT NOT NULL,"
    " updated_at TEXT NOT NULL,"
    " metadata TEXT)")

PRE_P15_MESSAGES_DDL = (
    "CREATE TABLE messages ("
    " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
    " session_id TEXT NOT NULL,"
    " idx INTEGER NOT NULL,"
    " role TEXT, content TEXT, content_type TEXT,"
    " tool_name TEXT, tool_call_id TEXT,"
    " payload TEXT NOT NULL,"
    " timestamp TEXT NOT NULL,"
    " UNIQUE(session_id, idx))")

RESULTS = []


def check(name, ok, detail=""):
    ok = bool(ok)
    RESULTS.append((name, ok, detail))
    print("%-6s %-62s %s" % ("PASS" if ok else "FAIL", name, detail), flush=True)
    return ok


def sh(args):
    r = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return r.returncode, r.stdout.decode("utf-8", "replace")


def cli(*args):
    """真进程：java -jar … sessions --config-dir <临时 profile> <子命令…>。

    `--config-dir` 是 sessions 级选项（`java -jar … sessions -h` 实测），
    放在子命令**之前**；红线 1 靠它把整条路径从 ~/.zbot 引开。
    """
    rc, out = sh(["java", "-jar", JAR, "sessions", "--config-dir", CFG] + list(args))
    lines = [x for x in out.split("\n") if not x.startswith("WARNING")]
    return rc, "\n".join(lines)


def q(sql):
    """独立 sqlite3 进程读盘 —— 被测进程说了不算。"""
    rc, out = sh(["sqlite3", "-batch", DB, sql])
    return out.strip() if rc == 0 else "<<SQLERR: %s>>" % out.strip()


def migration_line(out):
    """只看'本次开库迁移'那一行 —— 台账行里永远带着历史 0->3 的 columns=[...]，拿它判幂等会假失败。"""
    for ln in out.split("\n"):
        if ln.startswith("本次开库迁移"):
            return ln
    return ""


def zbot_home_probe():
    """红线 1：只数项数 + 取两个 md5 前 8 位，绝不读内容、绝不打印值。"""
    home = os.path.expanduser("~/.zbot")
    if not os.path.isdir(home):
        return {"entries": -1, "md5": {}}
    st = {}
    for f in ("config.properties", "state.db"):
        p = os.path.join(home, f)
        # 只读来算 md5（ticket 杠④ 要求的读数），不打印、不复制、不进产物；不存在就记 absent
        if os.path.isfile(p):
            with open(p, "rb") as fh:
                st[f] = hashlib.md5(fh.read()).hexdigest()[:8]
        else:
            st[f] = "absent"
    return {"entries": len(os.listdir(home)), "md5": st}


def main():
    if not os.path.isfile(JAR):
        print("FATAL: 找不到可执行 jar %s ⇒ 先 mvn -o -pl z-bot-core package -DskipTests" % JAR,
              flush=True)
        return 2
    for tool in ("java", "sqlite3"):
        if subprocess.call(["which", tool], stdout=subprocess.PIPE) != 0:
            print("FATAL: 缺 %s，本 E2E 不能空跑" % tool, flush=True)
            return 2

    before = zbot_home_probe()
    print("红线1 跑前: entries=%s md5=%s" % (before["entries"], before["md5"]), flush=True)

    if os.path.isdir(WORK):
        shutil.rmtree(WORK)
    os.makedirs(CFG)
    # 看起来像真配置：key 一律 stub，下面 S5a 反向钉住它真进了产物
    with open(os.path.join(CFG, "config.properties"), "w", encoding="utf-8") as fh:
        fh.write("model.id=stub-model\nllm.provider=stub\nminimax.api.key=%s\n" % STUB_KEY)

    # ===== S1 造一个"升级前"的老库（真 sqlite3 进程灌真数据）=====
    rc, out = sh(["sqlite3", "-batch", DB, PRE_P15_SESSIONS_DDL])
    if not check("S1a P2 原样 sessions DDL 建库", rc == 0, out[:120]):
        return 1
    rc, out = sh(["sqlite3", "-batch", DB, PRE_P15_MESSAGES_DDL])
    if not check("S1b P2 原样 messages DDL 建表", rc == 0, out[:120]):
        return 1
    seed = [
        # 有货的：2 条消息、在飞
        "INSERT INTO sessions(id,title,source,message_count,created_at,updated_at)"
        " VALUES('keep-msgs','有货的','cli',2,'2024-01-02 10:00:00','2024-01-02 11:00:00')",
        # 空的老会话：0 消息、**没** end ⇒ prune 不该碰
        "INSERT INTO sessions(id,title,source,message_count,created_at,updated_at)"
        " VALUES('keep-empty','空且在飞','cli',0,'2024-01-02 10:00:00','2024-01-02 11:00:00')",
        # 待处理：0 消息、ISO 'T' 时间戳（step 3 的靶子）
        "INSERT INTO sessions(id,title,source,message_count,created_at,updated_at)"
        " VALUES('doomed','待结束的','cli',0,'2024-01-02T10:00:00','2024-01-02T11:00:00')",
        "INSERT INTO messages(session_id,idx,role,content,payload,timestamp)"
        " VALUES('keep-msgs',0,'user','QUANTUM_老消息',"
        "'{\"role\":\"user\",\"content\":\"QUANTUM_老消息\"}','2024-01-02 10:00:00')",
        "INSERT INTO messages(session_id,idx,role,content,payload,timestamp)"
        " VALUES('keep-msgs',1,'assistant','另一条',"
        "'{\"role\":\"assistant\",\"content\":\"另一条\"}','2024-01-02 10:00:01')",
    ]
    for sql in seed:
        rc, out = sh(["sqlite3", "-batch", DB, sql])
        if rc != 0:
            check("S1c 灌老数据", False, out[:200])
            return 1
    n0 = q("SELECT COUNT(*) FROM sessions")
    m0 = q("SELECT COUNT(*) FROM messages")
    cols0 = q("PRAGMA table_info(sessions)").split("\n")
    check("S1d 升级前盘上就是 3 会话 / 2 消息 / 11 列 / 无 schema_version 表",
          (n0, m0, str(len(cols0))) == ("3", "2", "11")
          and q("SELECT name FROM sqlite_master WHERE name='schema_version'") == "",
          "实测 sessions=%s messages=%s cols=%s schema_version_table=%r"
          % (n0, m0, len(cols0), q("SELECT name FROM sqlite_master WHERE name='schema_version'")))
    names0 = [ln.split("|")[1] for ln in cols0 if ln]
    check("S1e 升级前确实**没有** P15 那 4 列（有就说明本 E2E 是空跑）",
          not set(["parent_session_id", "ended_at", "end_reason", "archived"]) & set(names0),
          "实测列=%s" % ",".join(names0))

    # ===== S2 真 CLI 开一次库 ⇒ 迁移真发生在盘上 =====
    rc, out = cli("version")
    line = migration_line(out)
    check("S2a 真 java -jar 开老库 rc=0", rc == 0, (out.strip().split("\n") or [""])[0][:150])
    check("S2b 日志里本次跑了 3 步并点名加了 4 列（仅作线索，判定看 S2c/S2d 盘上）",
          "p15-schema-alignment" in line and "sessions.archived" in line, line[:150])
    v = q("SELECT version FROM schema_version")
    check("S2c 盘上 schema_version=3（独立进程回读，不信日志）", v == "3", "实测 version=%s" % v)
    live = [ln.split("|")[1] for ln in q("PRAGMA table_info(sessions)").split("\n") if ln]
    check("S2d 升级后 PRAGMA 列**与顺序**都 == head 的 15 列", live == HEAD_SESSION_COLS,
          "实测 %d 列: %s" % (len(live), ",".join(live)))
    n1, m1 = q("SELECT COUNT(*) FROM sessions"), q("SELECT COUNT(*) FROM messages")
    check("S2e **老库升级不丢行**：sessions 3→3、messages 2→2",
          (n1, m1) == ("3", "2"), "实测 sessions=%s messages=%s" % (n1, m1))
    titles = q("SELECT group_concat(title, ',') FROM (SELECT title FROM sessions ORDER BY id)")
    # 期望按 id 序（doomed, keep-empty, keep-msgs）；第一次跑把 ORDER BY 的次序猜错了，
    # 猜错的是**断言**而不是产品码，改断言、不放宽（仍要求整串精确相等）。
    check("S2f 三行的 title 一字未改",
          titles == "待结束的,空且在飞,有货的", "实测=%s" % titles)
    mq = q("SELECT COUNT(*) FROM messages WHERE payload LIKE '%QUANTUM%'")
    mc = q("SELECT content FROM messages WHERE session_id='keep-msgs' AND idx=0")
    check("S2g 老消息原文还在（QUANTUM 探针 + content 列可读）",
          mq == "1" and mc == "QUANTUM_老消息", "实测 payload 命中=%s content=%s" % (mq, mc))
    ts = q("SELECT created_at || '/' || updated_at FROM sessions WHERE id='doomed'")
    check("S2h step3 把 doomed 的 ISO 'T' 时间戳就地规范化（值被改、行没丢）",
          ts == "2024-01-02 10:00:00/2024-01-02 11:00:00", "实测=%s" % ts)
    ts2 = q("SELECT created_at FROM sessions WHERE id='keep-msgs'")
    check("S2i 本来就规范化的行一步没动（阶梯不无故改写）", ts2 == "2024-01-02 10:00:00",
          "实测=%s" % ts2)
    rc, out = cli("version", "--check")
    check("S2j 升级后的库过 head 对齐校验（version --check rc=0）", rc == 0,
          out.strip().split("\n")[-1][:150] if out.strip() else "rc=%s" % rc)

    # ===== S3 判定=要 的新列真被读到（写经真 CLI，读经独立进程，且改变 CLI 输出）=====
    # ended_at + end_reason
    rc, out = cli("end", "doomed", "--reason=e2e-p15b")
    ended = q("SELECT (ended_at IS NOT NULL) || '|' || COALESCE(end_reason,'<NULL>') "
              "FROM sessions WHERE id='doomed'")
    check("S3a `sessions end` 真把 ended_at/end_reason 写进盘上",
          rc == 0 and ended.startswith("1|") and "e2e-p15b" in ended,
          "盘上=%s rc=%s" % (ended, rc))
    _rc, lst = cli("list")
    row = [x for x in lst.split("\n") if x.startswith("doomed")]
    check("S3b ended_at 真被 list 读出来（END 列变 'end'，不是只写不看）",
          len(row) == 1 and row[0].split()[1] == "end", "实测行=%s" % (row or ["<无行>"]))
    inflight = [x for x in lst.split("\n") if x.startswith("keep-empty")]
    check("S3c 未 end 的会话在同一个 list 里还是 '-'（ended_at 是判据而非摆设）",
          len(inflight) == 1 and inflight[0].split()[1] == "-", "实测行=%s" % (inflight or ["<无行>"]))

    # message_count
    km = [x for x in lst.split("\n") if x.startswith("keep-msgs")]
    check("S3d list 的 MSGS 列就是盘上的 message_count（2）",
          len(km) == 1 and km[0].split()[2] == "2"
          and q("SELECT message_count FROM sessions WHERE id='keep-msgs'") == "2",
          "实测行=%s" % (km or ["<无行>"]))

    # archived
    rc, out = cli("archive", "keep-msgs")
    arch = q("SELECT archived FROM sessions WHERE id='keep-msgs'")
    _rc, default_view = cli("list")
    _rc, all_view = cli("list", "--all")
    check("S3e `sessions archive` 真写盘上 archived=1", rc == 0 and arch == "1",
          "盘上=%s rc=%s" % (arch, rc))
    check("S3f archived=1 改变 CLI 缺省输出（缺省看不见、--all 看得见且标 arch）",
          "keep-msgs" not in default_view and "keep-msgs" in all_view
          and [x for x in all_view.split("\n") if x.startswith("keep-msgs")][0].split()[1] == "arch",
          "缺省含=%s / --all 含=%s" % ("keep-msgs" in default_view, "keep-msgs" in all_view))
    rc, out = cli("archive", "--unarchive", "keep-msgs")
    check("S3g --unarchive 真把盘上写回 0（不是只在内存里翻旗）",
          rc == 0 and q("SELECT archived FROM sessions WHERE id='keep-msgs'") == "0",
          "盘上=%s" % q("SELECT archived FROM sessions WHERE id='keep-msgs'"))

    # parent_session_id：值由本脚本用 sqlite3 直接灌（P2 老库没这列，本期也没有写父指针的 CLI；
    # 这里要证的只是"升级出来的列被真生产路径读到"，读路径 = sessions lineage）
    sh(["sqlite3", "-batch", DB, "UPDATE sessions SET parent_session_id='keep-msgs' WHERE id='keep-empty'"])
    rc, lin = cli("lineage", "keep-empty")
    check("S3h lineage 真读了 parent_session_id（缺列的话这条命令无从成立）",
          rc == 0 and "keep-msgs" in lin and "keep-empty" in lin,
          "盘上=%s | CLI=%s" % (q("SELECT parent_session_id FROM sessions WHERE id='keep-empty'"),
                                lin.strip().replace("\n", " / ")[:150]))

    # prune 的闸门同时吃 ended_at 与 message_count ⇒ 一次点杀 doomed
    rc, out = cli("prune", "--days=0")
    left = q("SELECT group_concat(id, ',') FROM (SELECT id FROM sessions ORDER BY id)")
    check("S3i prune 按 ended_at+message_count 精确挑中 doomed，另两条活着",
          rc == 0 and left == "keep-empty,keep-msgs",
          "盘上剩=%s | CLI=%s" % (left, out.strip().replace("\n", " / ")[:130]))
    check("S3j 在飞的 keep-empty（0 消息但没 end）没被误删 —— ended_at 是硬闸门",
          "keep-empty" in left, "盘上剩=%s" % left)

    # stats：sessions= 计数要跟盘上一致（独立进程核对）
    rc, stats = cli("stats")
    disk_n = q("SELECT COUNT(*) FROM sessions")
    check("S3k stats 的 sessions= 与盘上行数一致（真读了这张表）",
          rc == 0 and ("sessions=%s " % disk_n) in stats and ("messages=" in stats),
          "盘上=%s | CLI=%s" % (disk_n, stats.strip().replace("\n", " / ")[:150]))

    # ===== S4 幂等：再开一次不许再加列/再跑阶梯 =====
    before_cols = len([x for x in q("PRAGMA table_info(sessions)").split("\n") if x])
    ledger_before = q("SELECT value FROM state_meta WHERE key='schema_last_migration'")
    rc, out = cli("version")
    after_cols = len([x for x in q("PRAGMA table_info(sessions)").split("\n") if x])
    line2 = migration_line(out)
    check("S4a 第二次开库列数不变（15→15）", before_cols == after_cols == 15,
          "%d→%d" % (before_cols, after_cols))
    check("S4b 第二次开库那行只报 3->3、不再报加了列",
          "3->3" in line2 and "columns=[" not in line2, line2[:150])
    check("S4c 二次开库仍不丢行（sessions=2）",
          q("SELECT COUNT(*) FROM sessions") == "2",
          "实测 sessions=%s" % q("SELECT COUNT(*) FROM sessions"))
    check("S4d 二次开库没改写迁移台账（state_meta.schema_last_migration 字节一致 ⇒ 真没再跑阶梯）",
          q("SELECT value FROM state_meta WHERE key='schema_last_migration'") == ledger_before
          and ledger_before.startswith("20"),
          "台账 %d 字节未变" % len(ledger_before))

    # ===== S5 红线 1：stub key 真进了被测产物（反向钉），且产物里没有真 key 的形状 =====
    cfg_txt = open(os.path.join(CFG, "config.properties"), encoding="utf-8").read()
    prod_all = ""
    for root, _dirs, files in os.walk(CFG):
        for f in files:
            with open(os.path.join(root, f), "rb") as fh:
                prod_all += fh.read().decode("utf-8", "replace")
    check("S5a stub 真在被测 profile 目录里（钉住'S5 不是空跑'）",
          STUB_KEY in cfg_txt and STUB_KEY in prod_all,
          "只比对存在性，不打印值；cfg 文件数=%d" % len([f for f in os.listdir(CFG)])
          )
    check("S5b 产物里没有任何真 key 的痕迹（无 125 字符值、无 sk- 前缀）",
          "sk-" not in prod_all and max([len(x.split("=", 1)[1]) for x in
                                         cfg_txt.split("\n") if "=" in x] or [0]) < 40,
          "cfg 字节数=%d" % len(cfg_txt))

    after = zbot_home_probe()
    print("红线1 跑后: entries=%s md5=%s" % (after["entries"], after["md5"]), flush=True)
    check("S6 红线1：~/.zbot 跑前后一模一样（8 项 + 两个 md5 前缀未变）",
          before == after and after["entries"] == 8
          and after["md5"].get("config.properties") == "2dadaed0"
          and after["md5"].get("state.db") == "690ddbc0",
          "%s → %s" % (before, after))

    npass = sum(1 for _n, ok, _d in RESULTS if ok)
    print("\n== P15b 杠③ 真进程 E2E: %d/%d 通过 ==" % (npass, len(RESULTS)), flush=True)
    for name, ok, detail in RESULTS:
        if not ok:
            print("   FAIL %s | %s" % (name, detail), flush=True)
    return 0 if npass == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
