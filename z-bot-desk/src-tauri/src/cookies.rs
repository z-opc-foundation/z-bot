//! SSO 登录态（Cookie）跨启动持久化。
//!
//! Apex 统一登录下发的 SSO Cookie 是会话 Cookie（无 expires），WKWebView
//! 只把它放在进程内存里，应用退出即丢失——导致每次打开 DMG 都要重新登录
//! （后端所有请求返回 101012001，前端清 localStorage 后跳登录页）。
//!
//! 本模块把 z-team 相关域（生产 `.zopc.top` / 旧内网域 `z-team.local`）的 Cookie
//! 镜像到用户目录 `.z-team/desk-cookies.json`（Windows 为 %USERPROFILE%）：
//! <ul>
//!   <li>启动时由 lib.rs 在主窗口创建前注入回共享 cookie store（含 HttpOnly）</li>
//!   <li>页面加载 / 定时 / 退出前回写快照</li>
//!   <li>过期语义不变：注入的 Cookie 若已在服务端失效，后端照样返回
//!       101012001 跳登录页，重新登录拿到的新 Cookie 会再次覆盖存档</li>
//! </ul>

use cookie::time::OffsetDateTime;
use cookie::{Cookie, Expiration, SameSite};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// 只镜像 z-team 相关域的 Cookie（生产 `.zopc.top` 公网域 / 旧内网域 `z-team.local`，
/// 覆盖 OPC SSO 与 FT 前端），无关 Cookie 不落盘
fn is_team_cookie_domain(domain: &str) -> bool {
    domain.contains("zopc.top") || domain.contains("z-team.local")
}

#[derive(Serialize, Deserialize)]
struct StoredCookie {
    name: String,
    value: String,
    domain: Option<String>,
    path: Option<String>,
    /// unix 秒；None = 会话 Cookie（WKWebView 不落盘，靠本文件跨启动恢复）
    expires_ts: Option<i64>,
    http_only: bool,
    secure: bool,
    /// "Strict" | "Lax" | "None"
    same_site: Option<String>,
}

fn store_path() -> Option<PathBuf> {
    // Windows 无 HOME 环境变量，统一走跨平台取法（USERPROFILE 优先）
    let home = crate::user_home()?;
    let dir = PathBuf::from(home).join(".z-team");
    if let Err(e) = std::fs::create_dir_all(&dir) {
        eprintln!("[desk-cookies] create dir failed: {}", e);
        return None;
    }
    Some(dir.join("desk-cookies.json"))
}

/// 过滤出 FT 相关域且未过期的 Cookie 并写盘，返回写入条数。
pub fn save(cookies: &[Cookie<'static>]) -> usize {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let stored: Vec<StoredCookie> = cookies
        .iter()
        .filter(|c| {
            c.domain()
                .map(|d| is_team_cookie_domain(d))
                .unwrap_or(false)
        })
        // 已过期的持久 Cookie 不再保存（注入回去也无意义，徒增噪音）
        .filter(|c| {
            !matches!(
                c.expires(),
                Some(Expiration::DateTime(t)) if t.unix_timestamp() <= now
            )
        })
        .map(to_stored)
        .collect();
    let count = stored.len();
    if let Some(path) = store_path() {
        match serde_json::to_string(&stored) {
            Ok(json) => {
                if let Err(e) = std::fs::write(&path, json) {
                    eprintln!("[desk-cookies] write failed: {}", e);
                }
            }
            Err(e) => eprintln!("[desk-cookies] serialize failed: {}", e),
        }
    }
    count
}

/// 迁移前的存档路径（历史名，仅用于读取老存档）。
fn legacy_store_path() -> Option<PathBuf> {
    let home = crate::user_home()?;
    Some(PathBuf::from(home)
        .join(".future-team")
        .join("desk-cookies.json"))
}

/// 读取存档并还原为可注入的 Cookie（文件不存在 / 解析失败返回空）。
///
/// 新目录 `~/.z-team` 里没有存档时，回落读迁移前的老存档目录
/// `~/.future-team`，避免升级后被要求重新登录；读到的内容会在下次保存时
/// 写进新目录，此后不再依赖老目录。
pub fn load() -> Vec<Cookie<'static>> {
    let raw = match store_path().and_then(|p| std::fs::read_to_string(p).ok()) {
        Some(r) => r,
        None => match legacy_store_path().and_then(|p| std::fs::read_to_string(p).ok()) {
            Some(r) => r,
            None => return Vec::new(), // 首次运行没有存档，静默返回
        },
    };
    let stored: Vec<StoredCookie> = match serde_json::from_str(&raw) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("[desk-cookies] parse failed: {}", e);
            return Vec::new();
        }
    };
    stored.into_iter().map(to_cookie).collect()
}

fn to_stored(c: &Cookie<'static>) -> StoredCookie {
    StoredCookie {
        name: c.name().to_string(),
        value: c.value().to_string(),
        domain: c.domain().map(|s| s.to_string()),
        path: c.path().map(|s| s.to_string()),
        expires_ts: match c.expires() {
            Some(Expiration::DateTime(t)) => Some(t.unix_timestamp()),
            // Session / 未设置：按会话 Cookie 保存，跨启动恢复全靠这份镜像
            _ => None,
        },
        http_only: c.http_only().unwrap_or(false),
        secure: c.secure().unwrap_or(false),
        same_site: c
            .same_site()
            .map(|s| {
                match s {
                    SameSite::Strict => "Strict",
                    SameSite::Lax => "Lax",
                    SameSite::None => "None",
                }
                .to_string()
            }),
    }
}

fn to_cookie(s: StoredCookie) -> Cookie<'static> {
    let mut c = Cookie::new(s.name, s.value);
    if let Some(d) = s.domain {
        c.set_domain(d);
    }
    if let Some(p) = s.path {
        c.set_path(p);
    }
    c.set_http_only(s.http_only);
    c.set_secure(s.secure);
    match s.same_site.as_deref() {
        Some("Strict") => c.set_same_site(SameSite::Strict),
        Some("Lax") => c.set_same_site(SameSite::Lax),
        Some("None") => c.set_same_site(SameSite::None),
        _ => {}
    }
    if let Some(ts) = s.expires_ts {
        if let Ok(dt) = OffsetDateTime::from_unix_timestamp(ts) {
            c.set_expires(dt);
        }
    }
    c
}
