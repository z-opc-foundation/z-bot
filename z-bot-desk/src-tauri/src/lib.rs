mod agent_process;
mod cookies;

use cookie::{Cookie, SameSite};
use std::io::Read;
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem, Submenu};
use tauri::tray::TrayIconBuilder;
use tauri::{LogicalPosition, LogicalSize, Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};

struct AgentState {
    child: Mutex<Option<std::process::Child>>,
    current_env: Mutex<String>,
    update_url: Mutex<Option<String>>,
    /// 当前用户是否 admin：仅 admin 显示「环境切换」菜单（默认 false，全部走生产环境）
    is_admin: Mutex<bool>,
    /// FT 功能面板最近一次失焦时间：用于区分「点击悬浮标导致的失焦收起」与「真正的打开请求」
    launcher_focus_lost_at: Mutex<Option<std::time::Instant>>,
}

/// 本地环境：z-bot 自带的 HTTP 服务（serve 默认端口 28080），
/// 同一进程/同源提供 `/workspace`（本次 React 前端）与 `/v1/**`（对话与会话面）。
const LOCAL_BASE: &str = "http://127.0.0.1:28080";
/// 生产环境：z-opc 主壳（公网域 opc.zopc.top）。
/// 工作台在主壳里的路由就是 `/workspace`（main.jsx: <Route path="/workspace/*" element={<ZTeam/>/>），
/// 所以主窗口直接用 `{PROD_BASE}/workspace` 即可命中工作台；
/// 业务 API 在生产域**直接**走 `/z-team/api/**`（z-team servlet context）。
/// ⚠ 不要加 `/kapi` 前缀：线上前端用 `NEED_KAPI`（host 正则只认退休的 zteam.local 系）
/// 决定是否带前缀，opc.zopc.top 不匹配 ⇒ 前端走的是不带前缀的 `/z-team/**`；
/// 而 `/kapi/z-team/**` 在 Traefik 里**没有路由**，实测落进主壳 catch-all（SPA/401）。
/// ⚠ 旧值是内网专属域 z-team.local —— 公网 DNS 查不到（NXDOMAIN），
/// 装在外网的机器上主窗口必然白屏，故生产基址改指公网 OPC。
const PROD_BASE: &str = "https://opc.zopc.top";

/// 页面 host 域（SSO token 注入 host 域副本用），与 PROD_BASE 一致
const PAGE_HOST_DOMAIN: &str = "opc.zopc.top";

/// SSO Cookie 名判定 —— 与 z-opc 生产配置 `sso.token-cookie-names` 对齐：
/// 生产 OPC 域（.zopc.top）下发的会话 cookie 叫 `sso_token` / `zctc_token`；
/// `z_team_token` 是旧 z-team.local 域的遗留名，本地调试仍可能出现。
/// ⚠ 这三者**互不相同**（旧代码里写成了同一个名字的重复条件），
/// 只认 `z_team_token` 会导致生产环境读不到任何登录态。
fn is_sso_cookie_name(name: &str) -> bool {
    name.contains("sso_token") || name.contains("zctc_token") || name.contains("z_team_token")
}

/// 跨平台用户主目录：Windows 无 HOME 环境变量（用户目录是 USERPROFILE）
fn user_home() -> Option<String> {
    std::env::var("USERPROFILE")
        .or_else(|_| std::env::var("HOME"))
        .ok()
}

fn env_base(env: &str) -> &'static str {
    if env == "local" {
        LOCAL_BASE
    } else {
        PROD_BASE
    }
}

fn env_label(env: &str) -> &'static str {
    if env == "local" {
        "本地环境"
    } else {
        "生产环境"
    }
}

/// 查找 z-bot JAR 路径（打包后和开发环境两处候选）
fn find_jar() -> Option<String> {
    let mut jar_candidates: Vec<Option<std::path::PathBuf>> = Vec::new();
    #[cfg(target_os = "macos")]
    jar_candidates.push(
        // 打包后：.app/Contents/MacOS/binary → parent(MacOS) → parent(Contents) → Resources/resources/
        std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            .map(|p| p.join("Resources/resources/z-bot-core.jar")),
    );
    #[cfg(target_os = "windows")]
    {
        // NSIS 安装布局：exe 同级 resources/ 子目录（Tauri 标准），
        // 兼容交叉打包时 resources 平铺到安装根的已知行为
        if let Some(exe_dir) = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        {
            jar_candidates.push(Some(exe_dir.join("resources/z-bot-core.jar")));
            jar_candidates.push(Some(exe_dir.join("z-bot-core.jar")));
        }
    }
    jar_candidates.push(
        // 开发环境：src-tauri/resources/
        {
            let manifest_dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
            let jar = manifest_dir.join("resources/z-bot-core.jar");
            if jar.exists() { Some(jar) } else { None }
        },
    );
    jar_candidates
        .into_iter()
        .flatten()
        .find(|p| p.exists())
        .map(|p| p.to_string_lossy().to_string())
}

/// 检查更新。
/// z-bot 桌面端尚无独立发布通道：原 z-team OSS 的 latest.json 递的是 z-team-agent 的
/// DMG，装下去会把本壳替换掉，故显式拒绝而不是让存量地址继续生效。
#[tauri::command]
fn check_for_update() -> Result<serde_json::Value, String> {
    Err("z-bot 桌面端尚未配置更新通道".to_string())
}

/// 下载更新安装包：macOS 拉 DMG 并挂载打开；Windows 拉 NSIS 安装器后静默升级
#[tauri::command]
async fn download_and_install_dmg(url: String) -> Result<String, String> {
    // 同步命令在主线程 WebView2 事件分发栈内执行，阻塞式下载（超时上限 600s）
    // 会冻住整个 UI 且阻塞其余 IPC；转 async + spawn_blocking 到独立线程池执行。
    tauri::async_runtime::spawn_blocking(move || download_and_install_dmg_impl(url))
        .await
        .map_err(|e| format!("下载任务执行失败: {}", e))?
}

fn download_and_install_dmg_impl(url: String) -> Result<String, String> {
    #[cfg(target_os = "windows")]
    {
        let home = std::env::var("USERPROFILE").map_err(|_| "no USERPROFILE env".to_string())?;
        let dest = format!("{}\\Downloads\\ZBot_update.exe", home);
        let resp = ureq::get(&url)
            .timeout(std::time::Duration::from_secs(600))
            .call()
            .map_err(|e| format!("下载失败: {}", e))?;
        let mut file = std::fs::File::create(&dest).map_err(|e| format!("创建文件失败: {}", e))?;
        std::io::copy(&mut resp.into_reader(), &mut file)
            .map_err(|e| format!("写入失败: {}", e))?;
        drop(file);
        // 静默安装（NSIS /S）；安装器模板自带旧实例处理，装完菜单/桌面快捷方式自动更新
        let mut cmd = std::process::Command::new(&dest);
        cmd.arg("/S");
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000);
        }
        cmd.spawn().map_err(|e| format!("启动安装器失败: {}", e))?;
        return Ok(dest);
    }
    #[cfg(not(target_os = "windows"))]
    {
        let home = std::env::var("HOME").map_err(|_| "no HOME env".to_string())?;
        let dest = format!("{}/Downloads/ZBot_update.dmg", home);
        let status = std::process::Command::new("/usr/bin/curl")
            .args(["-L", "-o", &dest, &url])
            .status()
            .map_err(|e| format!("curl failed: {}", e))?;
        if !status.success() {
            return Err("download failed".to_string());
        }
        std::process::Command::new("/usr/bin/open")
            .arg(&dest)
            .spawn()
            .map_err(|e| format!("open failed: {}", e))?;
        Ok(dest)
    }
}

fn current_env(app: &tauri::AppHandle) -> String {
    app.state::<AgentState>()
        .current_env
        .lock()
        .unwrap()
        .clone()
}

/// 启动时恢复上次保存的 SSO Cookie。
///
/// <p>Apex SSO 下发的是会话 Cookie（无 expires），WKWebView 不落盘，退出即丢。
/// 这里用临时隐藏 webview（about:blank，无网络请求）把它注入共享 cookie store
/// （default WKWebsiteDataStore，所有窗口共享，含 HttpOnly），注入完成后再创建
/// 主窗口，保证首个请求就带上登录态。
/// <p>临时窗口不销毁（隐藏保留）：set_cookie 经 event loop 派发，销毁窗口可能
/// 与写入发生时序竞争；隐藏窗口无开销，on_page_load 已排除其落盘。
fn inject_persisted_cookies(app: &tauri::AppHandle) {
    let mut stored = cookies::load();
    if stored.is_empty() {
        return;
    }
    // WebKit Strict Secure Cookies 策略：同键“非 Secure 覆盖已有 Secure”的写入
    // 会被静默拒绝（实测 inject ok=14/14 但 store 读回缺非 Secure 条目）。登录后
    // 快照里同名 token 存在 Secure 与非 Secure 两条，必须非 Secure 先落地、
    // Secure 后注入才能并存；顺序颠倒则非 Secure 全部被丢 → 明文页面无 token
    // → 每次打开都要重新登录。sort_by_key 稳定排序，false(0) 排在 true(1) 前。
    stored.sort_by_key(|c| c.secure().unwrap_or(false));
    let win = match WebviewWindowBuilder::new(
        app,
        "cookie-bootstrap",
        WebviewUrl::External("about:blank".parse().unwrap()),
    )
    .visible(false)
    .build()
    {
        Ok(w) => w,
        Err(e) => {
            eprintln!("[desk-cookies] bootstrap window failed: {}", e);
            return;
        }
    };
    let mut ok = 0usize;
    let mut total = 0usize;
    for cookie in stored {
        let mut c = cookie;
        // WebKit 规则：SameSite=None 必须配 Secure 属性，否则整条被静默拒绝。
        // Apex SSO 在 https 域下发 SameSite=None; Secure 形态，而登录后页面会在
        // http 响应里另写一份非 Secure 副本（明文页面请求只会携带它，kapi 网关
        // 鉴权靠这份）。镜像还原时非 Secure 条目必须降级 SameSite=Lax，否则注入
        // 被拒 → http 页面无可用 Cookie → 每次打开都要重新登录。
        if !c.secure().unwrap_or(false) && matches!(c.same_site(), Some(SameSite::None)) {
            c.set_same_site(SameSite::Lax);
        }
        // 登录跳转链下发的 Set-Cookie（带 Secure 属性）可能把 host 域条目
        // 覆盖成 Secure 并随快照落盘——host 域 token 注入时强制翻回非
        // Secure，打破覆盖循环（非 Secure Cookie 在 https 页面同样随请求发送）。
        let name = c.name().to_string();
        if c.domain().map(|d| d == PAGE_HOST_DOMAIN).unwrap_or(false)
            && is_sso_cookie_name(&name)
        {
            c.set_secure(false);
            c.set_same_site(SameSite::Lax);
        }
        total += 1;
        if win.set_cookie(c.clone()).is_ok() {
            ok += 1;
        }
        // 追加一份页面 host 域副本：与 SSO 下发的 domain-cookie（生产 .zopc.top）
        // 唯一键不同，即使登录跳转链的 Set-Cookie 覆盖了 domain-cookie，
        // 页面仍能携带这份副本通过网关鉴权（https 下非 Secure 同样随请求发送）。
        if is_sso_cookie_name(&name) {
            let mut host = Cookie::new(name, c.value().to_string());
            host.set_domain(PAGE_HOST_DOMAIN);
            host.set_path("/");
            host.set_http_only(true);
            host.set_secure(false);
            host.set_same_site(SameSite::Lax);
            total += 1;
            if win.set_cookie(host).is_ok() {
                ok += 1;
            }
        }
    }
    println!("[desk-cookies] inject ok={}/{}", ok, total);
    // 注入后读回共享 store 自检（set_cookie 经 event loop 异步派发，需等待）：
    // 条目缺失说明被 WebKit 拒绝（形态非法）或被后续 Set-Cookie 覆盖。
    let probe = win.clone();
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(1000));
        if let Ok(all) = probe.cookies() {
            for c in &all {
                println!(
                    "[desk-cookies] store: {} dom={:?} secure={} ss={:?}",
                    c.name(),
                    c.domain(),
                    c.secure().unwrap_or(false),
                    c.same_site()
                );
            }
        }
    });
}

/// 把主窗口当前 cookie store 的 FT 域 Cookie 快照落盘。
fn persist_sso_cookies(app: &tauri::AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        match win.cookies() {
            Ok(all) => {
                let n = cookies::save(&all);
                println!("[desk-cookies] persisted {} cookies", n);
            }
            Err(e) => eprintln!("[desk-cookies] read store failed: {}", e),
        }
    }
}

/// 主窗口：不存在则创建（首次启动 / 关闭后从托盘再次拉起）
fn ensure_main_window(app: &tauri::AppHandle) -> tauri::Result<()> {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.show();
        let _ = win.set_focus();
        return Ok(());
    }
    let base = env_base(&current_env(app));
    let version = env!("CARGO_PKG_VERSION");
    WebviewWindowBuilder::new(
        app,
        "main",
        WebviewUrl::External(
            format!("{}/workspace?client=desktop&_v={}", base, version).parse().unwrap(),
        ),
    )
    .title(&format!("Z-Bot v{}", version))
    .inner_size(1200.0, 800.0)
    .min_inner_size(800.0, 600.0)
    .resizable(true)
    .initialization_script("window.__FT_DESKTOP__ = true;")
    .build()?;
    Ok(())
}

/// 快捷窗口（群聊 / 笔记）：存在则前置（保留会话），否则创建。
/// 关闭行为为隐藏（见 on_window_event），因此「存在」包含已隐藏窗口。
/// fill_height=true 时竖向贴满显示器工作区（菜单栏下方 → 屏幕/Dock 上方），水平居中。
fn ensure_quick_window(
    app: &tauri::AppHandle,
    label: &str,
    path: &str,
    title: &str,
    width: f64,
    height: f64,
    fill_height: bool,
) -> tauri::Result<()> {
    if let Some(win) = app.get_webview_window(label) {
        let _ = win.show();
        let _ = win.set_focus();
        return Ok(());
    }
    let base = env_base(&current_env(app));
    let url = format!("{}{}?client=desktop&_v={}", base, path, env!("CARGO_PKG_VERSION"));
    let win = WebviewWindowBuilder::new(
        app,
        label,
        WebviewUrl::External(url.parse().unwrap()),
    )
    .title(title)
    .inner_size(width, height)
    .min_inner_size(360.0, 480.0)
    .resizable(true)
    // 先隐藏，贴满调整后再显示，避免高度跳变闪烁
    .visible(false)
    .initialization_script("window.__FT_DESKTOP__ = true;")
    .build()?;

    if fill_height {
        // 工作区（自动扣除菜单栏/Dock）：竖向贴满，水平保持居中
        if let Ok(Some(monitor)) = win.current_monitor() {
            let scale = monitor.scale_factor();
            let area = monitor.work_area();
            let pos = area.position.to_logical::<f64>(scale);
            let size = area.size.to_logical::<f64>(scale);
            let x = pos.x + (size.width - width) / 2.0;
            let _ = win.set_size(LogicalSize::new(width, size.height));
            let _ = win.set_position(LogicalPosition::new(x, pos.y));
        }
    }
    let _ = win.show();
    let _ = win.set_focus();
    Ok(())
}

/// 专属伙伴窗口最小舒适宽：左列表 280 + 聊天区 ~600，聊天输入区仍可单行；
/// 实际宽度取工作区 50% 与此值较大者（大屏抽屉占半屏，小屏保可用性）
const PARTNER_WINDOW_WIDTH: f64 = 880.0;

/// 历史群聊窗口宽度：与 quick-chat 一致（ChatPanel 工具栏单行需 ~699px）
const HISTORY_WINDOW_WIDTH: f64 = 720.0;

/// 笔记窗口宽度：速记编辑器舒适阅读宽度
const NOTE_WINDOW_WIDTH: f64 = 780.0;

/// 桌面悬浮标（吉祥物）基础边长：小女孩本体显示尺寸。
/// 头顶群聊执行情况卡片栈伸缩时由前端经 badge_resize 动态调整整窗尺寸。
const FT_BADGE_SIZE: f64 = 72.0;

/// FT 功能面板窗口尺寸：DingTalk 式启动器布局（搜索框/最近使用/内置功能/市场精选）
const LAUNCHER_WINDOW_WIDTH: f64 = 780.0;
const LAUNCHER_WINDOW_HEIGHT: f64 = 680.0;

/// 右缘滑入快捷窗口（群聊 / 笔记 / 历史 / 专属伙伴）：贴工作区右缘、竖向贴满，从右边缘滑入（ease-out）。
/// 已存在且可见时仅前置；隐藏（关闭过）或新建时重播滑入动画。
/// width_ratio 传比例（如 Some(0.5)）时，实际宽度 = max(工作区宽×比例, width)：大屏抽屉占半屏，小屏保住 width 可用性。
fn ensure_right_slide_window(
    app: &tauri::AppHandle,
    label: &str,
    path: &str,
    title: &str,
    width: f64,
    width_ratio: Option<f64>,
) -> tauri::Result<()> {
    let win = match app.get_webview_window(label) {
        Some(win) => {
            if win.is_visible().unwrap_or(false) {
                let _ = win.show();
                let _ = win.set_focus();
                return Ok(());
            }
            win
        }
        None => {
            let base = env_base(&current_env(app));
            let url = format!(
                "{}{}?client=desktop&_v={}",
                base,
                path,
                env!("CARGO_PKG_VERSION")
            );
            WebviewWindowBuilder::new(
                app,
                label,
                WebviewUrl::External(url.parse().unwrap()),
            )
            .title(title)
            .inner_size(width, 760.0)
            .min_inner_size(360.0, 480.0)
            .resizable(true)
            .visible(false)
            .initialization_script("window.__FT_DESKTOP__ = true;")
            .build()?
        }
    };

    // 计算贴右目标位置：竖向贴满工作区，水平贴右缘
    let monitor = match win.current_monitor() {
        Ok(Some(m)) => m,
        _ => {
            // 拿不到显示器时退化为普通居中展示，不阻塞打开
            let _ = win.show();
            let _ = win.set_focus();
            return Ok(());
        }
    };
    let scale = monitor.scale_factor();
    let area = monitor.work_area();
    let pos = area.position.to_logical::<f64>(scale);
    let size = area.size.to_logical::<f64>(scale);
    let width = match width_ratio {
        Some(r) => ((size.width * r).max(width)).min(size.width),
        None => width.min(size.width),
    };
    let target_x = pos.x + size.width - width;
    let start_x = pos.x + size.width; // 完全移出屏幕右缘，滑入前不可见
    let y = pos.y;

    let _ = win.set_size(LogicalSize::new(width, size.height));
    let _ = win.set_position(LogicalPosition::new(start_x, y));
    let _ = win.show();

    // 滑入动画：~260ms / 16 步，ease-out cubic（先快后慢）
    std::thread::spawn(move || {
        let steps = 16u32;
        let step_ms = 16u64;
        for i in 1..=steps {
            let t = i as f64 / steps as f64;
            let e = 1.0 - (1.0 - t).powi(3);
            let x = start_x + (target_x - start_x) * e;
            let _ = win.set_position(LogicalPosition::new(x, y));
            std::thread::sleep(std::time::Duration::from_millis(step_ms));
        }
        let _ = win.set_position(LogicalPosition::new(target_x, y));
        let _ = win.set_focus();
    });
    Ok(())
}

/// 全局快捷键表：(组合键, 动作 id)。动作 id 与托盘菜单复用同一组，
/// 任何应用前置时按键均可唤起，实现“快速吊起功能”。
/// Windows 用 Ctrl+Shift 系：tauri 交叉平台解析中 "cmd" 映射为 Win 键（SUPER），
/// 而医院机常见组策略禁 Win 键/安全软件拦截 Win 组合，改用 Ctrl 系更稳
/// （与托盘菜单 accelerator "CmdOrCtrl" 在 Windows 的渲染一致）；macOS 保持 ⌘⇧ 系。
#[cfg(target_os = "windows")]
const GLOBAL_SHORTCUTS: &[(&str, &str)] = &[
    ("ctrl+shift+g", "quick-chat"),    // Ctrl+Shift+G 发起群聊（Group）
    ("ctrl+shift+n", "quick-note"),    // Ctrl+Shift+N 个人笔记（Note）
    ("ctrl+shift+h", "quick-history"), // Ctrl+Shift+H 历史群聊（History）
    ("ctrl+shift+p", "quick-partner"), // Ctrl+Shift+P 专属伙伴（Partner）
    ("ctrl+shift+m", "show-main"),     // Ctrl+Shift+M 显示主窗口（Main）
];
#[cfg(not(target_os = "windows"))]
const GLOBAL_SHORTCUTS: &[(&str, &str)] = &[
    ("cmd+shift+g", "quick-chat"),    // ⌘⇧G 发起群聊（Group）
    ("cmd+shift+n", "quick-note"),    // ⌘⇧N 个人笔记（Note）
    ("cmd+shift+h", "quick-history"), // ⌘⇧H 历史群聊（History）
    ("cmd+shift+p", "quick-partner"), // ⌘⇧P 专属伙伴（Partner）
    ("cmd+shift+m", "show-main"),     // ⌘⇧M 显示主窗口（Main）
];

/// 注册全局快捷键。注册失败（如组合键被其他全局热键占用）只记日志降级，
/// 不阻塞应用启动；菜单内仍可通过托盘手动唤起。
fn register_global_shortcuts(app: &tauri::AppHandle) {
    for (combo, action) in GLOBAL_SHORTCUTS {
        let action = *action;
        if let Err(e) = app.global_shortcut().on_shortcut(*combo, move |app, _shortcut, event| {
            // 只响应按下（ Released 忽略），避免一次按键触发两次
            if event.state == ShortcutState::Pressed {
                #[cfg(target_os = "windows")]
                {
                    // 同 handle_launcher_action：WM_HOTKEY 分发栈内建窗会与
                    // 事件循环重入冲突，弹到事件循环空闲点再执行。
                    let app2 = app.clone();
                    std::thread::spawn(move || {
                        let app3 = app2.clone();
                        let _ = app2.run_on_main_thread(move || {
                            handle_menu_action(&app3, action);
                        });
                    });
                }
                #[cfg(not(target_os = "windows"))]
                handle_menu_action(app, action);
            }
        }) {
            eprintln!("[desk] global shortcut {} ({}) register failed: {}", combo, action, e);
        } else {
            println!("[desk] global shortcut {} -> {}", combo, action);
        }
    }

    // ⌥Space（Alt+Space）：Spotlight 式唤起/隐藏 FT 功能面板（悬浮标同款 toggle）。
    // 显式 Shortcut 构造："alt+space" 字符串解析兼容性不确定，Code::Space 直取最稳。
    let alt_space = Shortcut::new(Some(Modifiers::ALT), Code::Space);
    let hs_app = app.clone();
    if let Err(e) = app.global_shortcut().on_shortcut(alt_space, move |app, _shortcut, event| {
        if event.state == ShortcutState::Pressed {
            handle_launcher_action(app, "toggle", None);
        }
    }) {
        eprintln!("[desk] global shortcut alt+space register failed: {}（可能被其他软件占用，可用备用键 mac ⌃⌘K / Windows Ctrl+Win+K）", e);
    } else {
        println!("[desk] global shortcut alt+space -> ft-launcher toggle");
    }
    let _ = hs_app;

    // 备用快捷键 ⌃⌘K：⌥Space 被抢占时的 fallback（参考 z-biz-tool-box）
    let ctrl_cmd_k = Shortcut::new(Some(Modifiers::CONTROL | Modifiers::SUPER), Code::KeyK);
    if let Err(e) = app.global_shortcut().on_shortcut(ctrl_cmd_k, move |app, _shortcut, event| {
        if event.state == ShortcutState::Pressed {
            handle_launcher_action(app, "toggle", None);
        }
    }) {
        eprintln!("[desk] global shortcut ctrl+cmd+k register failed: {}", e);
    } else {
        println!("[desk] global shortcut ctrl+cmd+k -> ft-launcher toggle (fallback)");
    }
}

/// 桌面悬浮标（小副标）：贴屏幕右缘、垂直居中的圆形徽标，点击开关 FT 功能面板。
/// 内容为本地静态页 ft-badge.html（不走外网，秒开）；按住可拖动（data-tauri-drag-region）。
fn ensure_ft_badge(app: &tauri::AppHandle) -> tauri::Result<()> {
    if app.get_webview_window("ft-badge").is_some() {
        return Ok(());
    }
    let win = WebviewWindowBuilder::new(
        app,
        "ft-badge",
        WebviewUrl::App("ft-badge.html".into()),
    )
    .title("Z-Team")
    .inner_size(FT_BADGE_SIZE, FT_BADGE_SIZE)
    .resizable(false)
    .decorations(false)
    .transparent(true)
    .shadow(false)
    .always_on_top(true)
    .skip_taskbar(true)
    .visible(false)
    .build()?;

    // 贴工作区右缘、垂直居中（拿不到显示器信息时保持默认位置，不阻塞启动）
    if let Ok(Some(monitor)) = win.current_monitor() {
        let scale = monitor.scale_factor();
        let area = monitor.work_area();
        let pos = area.position.to_logical::<f64>(scale);
        let size = area.size.to_logical::<f64>(scale);
        let x = pos.x + size.width - FT_BADGE_SIZE - 8.0;
        let y = pos.y + (size.height - FT_BADGE_SIZE) / 2.0;
        let _ = win.set_position(LogicalPosition::new(x, y));
    }
    let _ = win.show();
    Ok(())
}

/// FT 功能面板：不存在则创建（隐藏）；存在则直接复用。
/// 页面为 DMG 内置本地资源（public/z-launcher.html），不依赖远程前端发版；
/// 市场数据由 market_resources command 在 Rust 侧代理拉取（带 SSO token）。
/// ⚠ 文件名是 z-launcher.html —— 旧名 ft-launcher.html 已随去 c2f/galaxy 命名残留
/// 的那轮改名一起改掉，Rust 侧引用没跟，导致按 ⌥Space 打开的是**空白页**。
fn ensure_ft_launcher(app: &tauri::AppHandle) -> tauri::Result<tauri::WebviewWindow<tauri::Wry>> {
    if let Some(win) = app.get_webview_window("ft-launcher") {
        return Ok(win);
    }
    let win = WebviewWindowBuilder::new(
        app,
        "ft-launcher",
        WebviewUrl::App("z-launcher.html".into()),
    )
    .title("FT 功能面板")
    .inner_size(LAUNCHER_WINDOW_WIDTH, LAUNCHER_WINDOW_HEIGHT)
    .resizable(false)
    .decorations(false)
    .transparent(true)
    .always_on_top(true)
    .skip_taskbar(true)
    .visible(false)
    .initialization_script("window.__FT_DESKTOP__ = true;")
    .build()?;
    Ok(win)
}

/// 打开 FT 功能面板：仅首次创建时居中于工作区；已存在的窗口（可能被用户拖到
/// 顺手位置）保持原位，直接显示并聚焦。
fn show_ft_launcher(app: &tauri::AppHandle) {
    let existed = app.get_webview_window("ft-launcher").is_some();
    let win = match ensure_ft_launcher(app) {
        Ok(w) => w,
        Err(e) => {
            eprintln!("open ft-launcher failed: {}", e);
            return;
        }
    };
    if !existed {
        if let Ok(Some(monitor)) = win.current_monitor() {
            let scale = monitor.scale_factor();
            let area = monitor.work_area();
            let pos = area.position.to_logical::<f64>(scale);
            let size = area.size.to_logical::<f64>(scale);
            let x = pos.x + (size.width - LAUNCHER_WINDOW_WIDTH) / 2.0;
            let y = pos.y + (size.height - LAUNCHER_WINDOW_HEIGHT) / 2.0;
            let _ = win.set_position(LogicalPosition::new(x, y));
        } else {
            let _ = win.center();
        }
    }
    let _ = win.show();
    // 延迟聚焦：点击悬浮标的激活流程（系统把 key 交给徽标窗口）与本处 set_focus
    // 存在竞态，立即聚焦会被系统夺回 → 面板闪现即收起。稍等激活流程落定再聚焦。
    // ⌥Space 场景 app 未激活，首次 set_focus 可能失败，500ms 后重试一次；
    // 聚焦成功（面板成为 key window）是失焦收起体验的前提。
    let focus_win = win.clone();
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(80));
        let _ = focus_win.set_focus();
        std::thread::sleep(std::time::Duration::from_millis(420));
        if focus_win.is_visible().unwrap_or(false) && !focus_win.is_focused().unwrap_or(true) {
            let _ = focus_win.set_focus();
        }
    });
}

/// 收起 FT 功能面板并清除失焦时间戳。
fn hide_ft_launcher(app: &tauri::AppHandle) {
    if let Some(win) = app.get_webview_window("ft-launcher") {
        let _ = win.hide();
    }
    *app
        .state::<AgentState>()
        .launcher_focus_lost_at
        .lock()
        .unwrap() = None;
}

/// FT 功能面板动作（悬浮标 / 面板页 / 全局快捷键共用）：
/// - toggle:     ⌥Space/悬浮标点击 → 开/关面板（抑制刚因失焦收起的闪关，见 Focused(false) 处理）
/// - hide:       面板页关闭按钮 → 收起面板
/// - quick-chat / quick-note / quick-history / show-main: 打开对应窗口（与托盘/全局快捷键同源）
/// - open-path:  主窗口导航到指定路由（支持携带 query，如 /workspace?mode=life&lifeTab=browser-plugin；/admin 仅 admin 放行）
fn handle_launcher_action(app: &tauri::AppHandle, action: &str, path: Option<&str>) {
    #[cfg(target_os = "windows")]
    {
        // Windows：WM_HOTKEY 在主线程 WndProc 分发内同步回调，IPC 命令也在
        // 主线程事件回调栈内执行；首次 toggle / quick-* 需要创建 webview 窗口，
        // 在分发栈内同步建窗会与事件循环重入冲突，可概率性永久卡死主线程
        // （面板不再出现、后续 IPC 全部无响应，与 on_page_load 内同步 cookies()
        // 的死锁同族）。与启动期 run_on_main_thread 建窗同款：弹到事件循环
        // 空闲点再执行。
        let app2 = app.clone();
        let action = action.to_string();
        let path = path.map(str::to_string);
        std::thread::spawn(move || {
            let app3 = app2.clone();
            let _ = app2.run_on_main_thread(move || {
                handle_launcher_action_impl(&app3, &action, path.as_deref());
            });
        });
    }
    #[cfg(not(target_os = "windows"))]
    handle_launcher_action_impl(app, action, path);
}

fn handle_launcher_action_impl(app: &tauri::AppHandle, action: &str, path: Option<&str>) {
    match action {
        "toggle" => {
            if let Some(win) = app.get_webview_window("ft-launcher") {
                if win.is_visible().unwrap_or(false) {
                    hide_ft_launcher(app);
                    return;
                }
            }
            // 刚因失焦收起（很可能是本次点击悬浮标所致）：维持关闭态，避免「点关反开」
            let recently_auto_hidden = app
                .state::<AgentState>()
                .launcher_focus_lost_at
                .lock()
                .unwrap()
                .map(|t| t.elapsed() < std::time::Duration::from_millis(700))
                .unwrap_or(false);
            if recently_auto_hidden {
                return;
            }
            show_ft_launcher(app);
        }
        "hide" => hide_ft_launcher(app),
        "quick-chat" | "quick-note" | "quick-history" | "show-main" => {
            handle_menu_action(app, action);
        }
        "open-path" => {
            let p = path.unwrap_or("").trim().to_string();
            if p.is_empty() {
                return;
            }
            // /admin 仅 admin 放行（前端同时按登录态隐藏入口）
            if p.starts_with("/admin") {
                let is_admin = *app.state::<AgentState>().is_admin.lock().unwrap();
                if !is_admin {
                    return;
                }
            }
            let base = env_base(&current_env(app));
            if let Some(win) = app.get_webview_window("main") {
                // path 可自带 query（如 /workspace?mode=life&lifeTab=browser-plugin）：有 ? 用 & 追加，否则用 ?
                let sep = if p.contains('?') { '&' } else { '?' };
                let _ = win.eval(&format!(
                    "window.location.href = '{}{}{}client=desktop&_v={}'",
                    base,
                    p,
                    sep,
                    env!("CARGO_PKG_VERSION")
                ));
                let _ = win.show();
                let _ = win.set_focus();
            }
        }
        _ => {}
    }
    // 除 toggle 外，任何动作完成后收起面板
    if action != "toggle" {
        if let Some(win) = app.get_webview_window("ft-launcher") {
            let _ = win.hide();
        }
    }
}

#[tauri::command]
fn launcher_action(
    app: tauri::AppHandle,
    action: String,
    path: Option<String>,
) -> Result<(), String> {
    handle_launcher_action(&app, &action, path.as_deref());
    Ok(())
}

/// 市场资源代理：面板本地页无跨域/无 Cookie 能力，由 Rust 侧代拉。
/// 鉴权沿用后端 SSO filter 的 ?token= query 机制，token 取自启动时注入/
/// 运行时落盘的 SSO Cookie 快照（生产 .zopc.top 域，如 sso_token）。
/// 返回后端原始 JSON（Result 包装），由面板页自行解析；未登录/失败时面板隐藏市场区。
#[tauri::command]
async fn market_resources(app: tauri::AppHandle) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let base = env_base(&current_env(&app));
        // z-team servlet context：本地与生产同路径（生产域不要加 /kapi 前缀）
        let api = format!("{}/z-team/api/market/resources", base);
        let token = cookies::load()
            .iter()
            .find(|c| is_sso_cookie_name(c.name()))
            .map(|c| c.value().to_string())
            .unwrap_or_default();
        let url = if token.is_empty() {
            api
        } else {
            format!("{}?token={}", api, token)
        };
        let resp = ureq::get(&url)
            .timeout(std::time::Duration::from_secs(8))
            .call()
            .map_err(|e| format!("market request failed: {}", e))?;
        resp.into_string()
            .map_err(|e| format!("market body read failed: {}", e))
    })
    .await
    .map_err(|e| format!("market task join failed: {}", e))?
}

/// SSO token 拼 URL query 前 percent-encode：token 为 base64 值，含 '+'/'/'/'='，
/// 部分网关按 form-urlencoded 解码时 '+' 会被吞成空格导致鉴权失败；
/// 全量编码对两种解码行为（RFC3986 / form-urlencoded）都安全
fn enc_token(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 16);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// 悬浮标「群聊执行情况」数据源：当前用户参与的场景实例（群聊）精简列表。
/// 与 market_resources 同模式：徽标本地页无 Cookie/跨域能力，Rust 侧带 SSO
/// token 代拉后端接口，精简为 [{id,name,status,createdAt}]。
/// 必须走 /page 分页接口：全量列表接口在参与数千群聊时响应 20MB+（30s 都拉不完），
/// 任何有限超时都必然失败、悬浮标永远显示"未登录"；
/// /page 默认 create_time 倒序且 ~70KB 亚秒返回。
#[tauri::command]
async fn badge_briefs(app: tauri::AppHandle) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let base = env_base(&current_env(&app));
        // z-team servlet context：本地与生产同路径（与 market_resources 一致）
        let api = format!("{}/z-team/api/scenario-instances/page", base);
        let token = cookies::load()
            .iter()
            .find(|c| is_sso_cookie_name(c.name()))
            .map(|c| c.value().to_string())
            .unwrap_or_default();
        let mut url = format!("{}?page=1&size=20", api);
        if !token.is_empty() {
            url.push_str(&format!("&token={}", enc_token(&token)));
        }
        let resp = ureq::get(&url)
            .timeout(std::time::Duration::from_secs(15))
            .call()
            .map_err(|e| format!("badge briefs request failed: {}", e))?;
        let body = resp
            .into_string()
            .map_err(|e| format!("badge briefs body read failed: {}", e))?;
        let v: serde_json::Value = serde_json::from_str(&body)
            .map_err(|e| format!("badge briefs parse failed: {}", e))?;
        // /page 响应结构 data.list；分页条目时间字段为 created_at
        let rows = v
            .get("data")
            .and_then(|d| d.get("list"))
            .and_then(|l| l.as_array())
            .cloned()
            .unwrap_or_default();
        let briefs: Vec<serde_json::Value> = rows
            .iter()
            .map(|r| {
                serde_json::json!({
                    "id": r.get("id").cloned().unwrap_or(serde_json::json!(0)),
                    "name": r.get("name").cloned().unwrap_or(serde_json::json!("")),
                    "status": r.get("status").cloned().unwrap_or(serde_json::json!("")),
                    "createdAt": r
                        .get("createdAt")
                        .or_else(|| r.get("created_at"))
                        .or_else(|| r.get("create_time"))
                        .cloned()
                        .unwrap_or(serde_json::json!("")),
                })
            })
            .collect();
        serde_json::to_string(&briefs)
            .map_err(|e| format!("badge briefs encode failed: {}", e))
    })
    .await
    .map_err(|e| format!("badge briefs task join failed: {}", e))?
}

/// 悬浮标卡片一键关单：POST /api/scenario-instances/{id}/close（body 空）。
/// 成功返回后端原始 JSON；失败（未登录/无权限/网络）返回 Err 供前端提示。
#[tauri::command]
async fn badge_close(app: tauri::AppHandle, id: i64) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let base = env_base(&current_env(&app));
        let api = format!("{}/z-team/api/scenario-instances/{}/close", base, id);
        let token = cookies::load()
            .iter()
            .find(|c| is_sso_cookie_name(c.name()))
            .map(|c| c.value().to_string())
            .unwrap_or_default();
        let url = if token.is_empty() {
            api
        } else {
            format!("{}?token={}", api, enc_token(&token))
        };
        let resp = ureq::post(&url)
            .set("Content-Type", "application/json")
            .timeout(std::time::Duration::from_secs(8))
            .send_string("{}")
            .map_err(|e| format!("badge close request failed: {}", e))?;
        resp.into_string()
            .map_err(|e| format!("badge close body read failed: {}", e))
    })
    .await
    .map_err(|e| format!("badge close task join failed: {}", e))?
}

/// 悬浮标窗口随卡片栈伸缩：右缘贴屏幕右缘不变、底端位置不变（高度向上生长）。
/// 前端按卡片数量算出目标逻辑尺寸后调用；窗口不存在时静默忽略。
#[tauri::command]
fn badge_resize(app: tauri::AppHandle, width: f64, height: f64) -> Result<(), String> {
    let Some(win) = app.get_webview_window("ft-badge") else {
        return Ok(());
    };
    let scale = win.scale_factor().unwrap_or(1.0);
    let pos = win.outer_position().map_err(|e| e.to_string())?;
    let size = win.outer_size().map_err(|e| e.to_string())?;
    let lx = pos.x as f64 / scale;
    let ly = pos.y as f64 / scale;
    let lw = size.width as f64 / scale;
    let lh = size.height as f64 / scale;
    // 下限即吉祥物本体尺寸（72），避免被缩到不可见
    let w = width.max(FT_BADGE_SIZE);
    let h = height.max(FT_BADGE_SIZE);
    let nx = lx + (lw - w);
    let ny = ly + (lh - h);
    // Windows 实测（1.0.45）：无边框窗的 outer_position/outer_size 读数与可见区域有偏差，
    // 且 set_size/set_position 异步进事件循环，页面频繁 fitWindow 会让链式推算把窗口
    // 逐步推出屏幕（右缘 1976 > 1920）。推算结果一律按工作区夹取，保证永不出屏。
    let (mut fx, mut fy) = (nx, ny);
    if let Ok(Some(monitor)) = win.current_monitor() {
        let ms = monitor.scale_factor();
        let area = monitor.work_area();
        let apos = area.position.to_logical::<f64>(ms);
        let asize = area.size.to_logical::<f64>(ms);
        let max_right = apos.x + asize.width - 8.0;
        let max_bottom = apos.y + asize.height - 8.0;
        let min_top = apos.y + 8.0;
        if fx + w > max_right {
            fx = max_right - w;
        }
        if fx < apos.x {
            fx = apos.x;
        }
        if fy + h > max_bottom {
            fy = max_bottom - h;
        }
        if fy < min_top {
            fy = min_top;
        }
    }
    win.set_size(LogicalSize::new(w, h)).map_err(|e| e.to_string())?;
    win.set_position(LogicalPosition::new(fx, fy))
        .map_err(|e| e.to_string())?;
    Ok(())
}

/// 标准 base64 编码（无依赖，供 logo 内联 data URL 使用）。
fn b64_encode(data: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = chunk.get(1).copied().unwrap_or(0) as u32;
        let b2 = chunk.get(2).copied().unwrap_or(0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

/// 解压总大小上限（防 zip 炸弹）：资源包上传上限 200MB，解压后超过该阈值即拒绝安装
const ZTOOL_UNZIP_MAX_BYTES: u64 = 512 * 1024 * 1024;

/// 本地插件安装根目录：~/.z-biz-tools/plugins/（z-biz-tool-box 的外部插件目录，
/// 与其 scanner 同源：装完打开工具箱即识别；子目录约定 com.z-team.ztools.{marketId}）。
fn ztools_dir() -> Result<std::path::PathBuf, String> {
    let home = user_home().ok_or_else(|| "cannot resolve user home".to_string())?;
    Ok(std::path::PathBuf::from(home).join(".z-biz-tools").join("plugins"))
}

/// 已安装本地工具列表：扫描各 */plugin.json，取 id 前缀为 com.z-team.ztools. 的
/// 插件并映射回市场资源 id（marketId），供面板绘制已安装/未安装角标（与工具箱同源）。
/// 同时内联：logoDataUrl（logo.png 的 base64 data URL，无则空串；面板页无法直接
/// 加载 file:// 图片）与 mainPath（入口 HTML 绝对路径，取 plugin.json 的 main 字段
/// 缺省 main.html；面板经 asset:// 协议 iframe 直接运行插件，与 z-biz-tool-box
/// PluginIframe 同模式，无需拉起工具箱）。
#[tauri::command]
fn ztool_installed_list() -> Result<String, String> {
    let dir = ztools_dir()?;
    let mut items: Vec<serde_json::Value> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for entry in rd.flatten() {
            let mf = entry.path().join("plugin.json");
            if let Ok(txt) = std::fs::read_to_string(&mf) {
                if let Ok(mut v) = serde_json::from_str::<serde_json::Value>(&txt) {
                    let pid = v.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
                    if let Some(mid) = pid.strip_prefix("com.z-team.ztools.") {
                        if let Ok(num) = mid.parse::<i64>() {
                            v["marketId"] = serde_json::json!(num);
                            let logo = std::fs::read(entry.path().join("logo.png")).ok()
                                .map(|b| format!("data:image/png;base64,{}", b64_encode(&b)));
                            v["logoDataUrl"] = serde_json::json!(logo.unwrap_or_default());
                            let main_rel = v
                                .get("main")
                                .and_then(|x| x.as_str())
                                .map(|s| s.trim())
                                .filter(|s| !s.is_empty())
                                .unwrap_or("main.html")
                                .to_string();
                            let main_path = entry.path().join(&main_rel);
                            if main_path.is_file() {
                                v["mainPath"] = serde_json::json!(main_path.to_string_lossy());
                            }
                            items.push(v);
                        }
                    }
                }
            }
        }
    }
    Ok(serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string()))
}

/// HTTP 测试工具后端（供 fttools/http.html 使用，契约与 z-biz-tool-box HttpTester 一致）：
/// 任意方法转发 + 忽略证书校验 + 30s 超时，返回 status/headers/body/elapsed_ms。
#[tauri::command]
async fn http_request(
    url: String,
    method: String,
    body: Option<String>,
    headers: Option<std::collections::HashMap<String, String>>,
) -> Result<serde_json::Value, String> {
    let client = reqwest::Client::builder()
        .danger_accept_invalid_certs(true)
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| format!("构建 HTTP 客户端失败: {}", e))?;
    let m = method.to_uppercase();
    let mut req = match m.as_str() {
        "GET" => client.get(&url),
        "POST" => client.post(&url),
        "PUT" => client.put(&url),
        "DELETE" => client.delete(&url),
        "PATCH" => client.patch(&url),
        "HEAD" => client.head(&url),
        "OPTIONS" => client.request(reqwest::Method::OPTIONS, &url),
        _ => return Err(format!("不支持的方法: {}", method)),
    };
    if let Some(hs) = &headers {
        for (k, v) in hs {
            req = req.header(k, v);
        }
    }
    if let Some(b) = body {
        if !matches!(m.as_str(), "GET" | "HEAD") {
            req = req.body(b);
        }
    }
    let start = std::time::Instant::now();
    match req.send().await {
        Ok(resp) => {
            let status = resp.status().as_u16();
            let status_text = resp.status().canonical_reason().unwrap_or("").to_string();
            let mut hdrs = serde_json::Map::new();
            for (k, v) in resp.headers().iter() {
                if let Ok(vv) = v.to_str() {
                    hdrs.insert(k.to_string(), serde_json::json!(vv));
                }
            }
            let body_text = resp.text().await.unwrap_or_default();
            let elapsed_ms = start.elapsed().as_millis() as u64;
            Ok(serde_json::json!({
                "status": status, "statusText": status_text,
                "headers": hdrs, "body": body_text,
                "elapsed_ms": elapsed_ms, "success": true
            }))
        }
        Err(e) => Ok(serde_json::json!({
            "status": 0, "statusText": "Error", "headers": {},
            "body": e.to_string(), "elapsed_ms": start.elapsed().as_millis() as u64,
            "success": false, "error": e.to_string()
        })),
    }
}

/// 安装本地插件（幂等，重复点击即覆盖更新）：
/// 1) POST market download API（后端记录下载次数并返回 OSS fileUrl）
/// 2) 下载资源包：zip（PK 魔数）→ 解压为完整插件目录（多文件插件）；裸 HTML（存量旧资源）→ 直写 main.html
/// 3) plugin.json = 包内自带优先（缺失时按市场信息生成）；id 强制对齐 com.z-team.ztools.{marketId}
/// 4) 入口 = plugin.json 的 main 字段（缺省 main.html），包内必须真实存在该文件
/// 与 z-biz-tool-box scanner 约定一致：装完打开工具箱即可用；未登录时后端拒绝，报错透传 toast。
#[tauri::command]
async fn ztool_install(
    app: tauri::AppHandle,
    id: String,
    name: String,
    version: Option<String>,
    summary: Option<String>,
    author: Option<String>,
    cover_url: Option<String>,
    manifest_json: Option<String>,
) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        // 市场 id 前端以字符串传递（市场 API 返回字符串数字），统一校验转 i64
        let id_num: i64 = id
            .trim()
            .parse()
            .map_err(|_| format!("非法插件 id: {}", id))?;
        let base = env_base(&current_env(&app));
        let api = format!("{}/z-team/api", base);
        let token = cookies::load()
            .iter()
            .find(|c| is_sso_cookie_name(c.name()))
            .map(|c| c.value().to_string())
            .unwrap_or_default();

        // 1) 下载 API → {fileUrl, fileName}
        let dl_url = if token.is_empty() {
            format!("{}/market/resources/{}/download", api, id_num)
        } else {
            format!("{}/market/resources/{}/download?token={}", api, id_num, token)
        };
        let resp = ureq::post(&dl_url)
            .timeout(std::time::Duration::from_secs(15))
            .call()
            .map_err(|e| format!("下载接口请求失败: {}", e))?;
        let body_text = resp
            .into_string()
            .map_err(|e| format!("下载接口响应读取失败: {}", e))?;
        let body: serde_json::Value =
            serde_json::from_str(&body_text).map_err(|e| format!("下载接口响应解析失败: {}", e))?;
        if !(body.get("success").and_then(|v| v.as_bool()).unwrap_or(false)
            || body.get("code").and_then(|v| v.as_i64()).map(|c| c == 0 || c == 200).unwrap_or(false))
        {
            let msg = body
                .get("message")
                .and_then(|v| v.as_str())
                .unwrap_or("未登录或下载失败");
            return Err(msg.to_string());
        }
        let data = body.get("data").cloned().unwrap_or(serde_json::Value::Null);
        let file_url = data
            .get("fileUrl")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .ok_or_else(|| "资源包地址为空".to_string())?
            .to_string();

        // 2) 下载二进制（OSS 直链，可能较大）
        let pkg = ureq::get(&file_url)
            .timeout(std::time::Duration::from_secs(120))
            .call()
            .map_err(|e| format!("资源包下载失败: {}", e))?;
        let mut bytes = Vec::new();
        pkg.into_reader()
            .read_to_end(&mut bytes)
            .map_err(|e| format!("资源包读取失败: {}", e))?;

        // 3) 安装目录：覆盖更新前先清空旧目录（避免升级后旧文件残留仍被加载）
        let tool_dir = ztools_dir()?.join(format!("com.z-team.ztools.{}", id_num));
        if tool_dir.exists() {
            std::fs::remove_dir_all(&tool_dir)
                .map_err(|e| format!("清理旧插件目录失败: {}", e))?;
        }
        std::fs::create_dir_all(&tool_dir).map_err(|e| format!("创建安装目录失败: {}", e))?;

        // 4) 包形态分流：zip（PK 魔数）→ 解压出完整插件目录；裸 HTML（存量旧资源）→ 直写 main.html
        let is_zip = bytes.len() >= 4
            && bytes[0] == b'P'
            && bytes[1] == b'K'
            && matches!(bytes[2], 0x03 | 0x05 | 0x07)
            && matches!(bytes[3], 0x04 | 0x06 | 0x08);
        let mut pkg_manifest: Option<serde_json::Value> = None;
        if is_zip {
            let reader = std::io::Cursor::new(&bytes);
            let mut archive =
                zip::ZipArchive::new(reader).map_err(|e| format!("插件包解压失败: {}", e))?;
            let mut total: u64 = 0;
            for i in 0..archive.len() {
                let mut entry = archive
                    .by_index(i)
                    .map_err(|e| format!("插件包读取失败: {}", e))?;
                // enclosed_name=None 即路径穿越条目（zip-slip），整包拒绝安装
                let rel = entry
                    .enclosed_name()
                    .ok_or_else(|| "插件包含非法路径条目，已拒绝安装".to_string())?
                    .to_path_buf();
                let rel_name = rel.to_string_lossy();
                if rel_name.starts_with("__MACOSX") || rel_name.ends_with(".DS_Store") {
                    continue;
                }
                let dest = tool_dir.join(&rel);
                if entry.is_dir() {
                    std::fs::create_dir_all(&dest)
                        .map_err(|e| format!("创建插件子目录失败: {}", e))?;
                    continue;
                }
                total += entry.size();
                if total > ZTOOL_UNZIP_MAX_BYTES {
                    return Err("插件包解压后超出大小限制".to_string());
                }
                if let Some(parent) = dest.parent() {
                    std::fs::create_dir_all(parent)
                        .map_err(|e| format!("创建插件子目录失败: {}", e))?;
                }
                let mut buf = Vec::with_capacity(entry.size() as usize);
                entry
                    .read_to_end(&mut buf)
                    .map_err(|e| format!("插件包文件读取失败: {}", e))?;
                if rel == std::path::Path::new("plugin.json") {
                    pkg_manifest = serde_json::from_slice(&buf).ok();
                }
                std::fs::write(&dest, &buf).map_err(|e| format!("写入插件文件失败: {}", e))?;
            }
            // 兼容“套壳打包”（整目录压缩成一层 wrapper，macOS 右键压缩常见形态）：
            // 包根没有 plugin.json，但唯一一个一级子目录里有 → 摊平到包根
            if pkg_manifest.is_none() {
                let mut candidates: Vec<std::path::PathBuf> = Vec::new();
                if let Ok(rd) = std::fs::read_dir(&tool_dir) {
                    for e in rd.flatten() {
                        let p = e.path();
                        if p.is_dir() && p.join("plugin.json").is_file() {
                            candidates.push(p);
                        }
                    }
                }
                if candidates.len() == 1 {
                    let src = candidates.remove(0);
                    for e in std::fs::read_dir(&src)
                        .map_err(|e| format!("读取插件子目录失败: {}", e))?
                        .flatten()
                    {
                        let dest = tool_dir.join(e.file_name());
                        if dest.exists() {
                            return Err(format!(
                                "插件包文件冲突: {}",
                                e.file_name().to_string_lossy()
                            ));
                        }
                        std::fs::rename(e.path(), &dest)
                            .map_err(|e| format!("摊平插件目录失败: {}", e))?;
                    }
                    std::fs::remove_dir(&src)
                        .map_err(|e| format!("清理插件子目录失败: {}", e))?;
                    pkg_manifest = std::fs::read_to_string(tool_dir.join("plugin.json"))
                        .ok()
                        .and_then(|t| serde_json::from_str(&t).ok());
                }
            }
        } else {
            std::fs::write(tool_dir.join("main.html"), &bytes)
                .map_err(|e| format!("写入插件文件失败: {}", e))?;
        }

        // 5) plugin.json：包内自带优先（缺失时按市场信息生成）；id 强制对齐市场映射，
        //    保证 ztool_installed_list 能映射回 marketId（角标判定/卸载/运行定位全靠它）
        let mut manifest = pkg_manifest
            .and_then(|v| v.as_object().cloned())
            .unwrap_or_default();
        if !manifest.contains_key("features") {
            let feats = manifest_json
                .as_deref()
                .and_then(|mj| serde_json::from_str::<serde_json::Value>(mj).ok())
                .and_then(|v| v.get("features").cloned())
                .unwrap_or_else(|| serde_json::json!([]));
            manifest.insert("features".to_string(), feats);
        }
        manifest.insert(
            "id".to_string(),
            serde_json::json!(format!("com.z-team.ztools.{}", id_num)),
        );
        manifest
            .entry("name".to_string())
            .or_insert(serde_json::json!(name));
        if let Some(s) = summary.as_deref().filter(|s| !s.is_empty()) {
            manifest
                .entry("description".to_string())
                .or_insert(serde_json::json!(s));
        }
        if let Some(v) = version.as_deref().filter(|s| !s.is_empty()) {
            manifest
                .entry("version".to_string())
                .or_insert(serde_json::json!(v));
        }
        if let Some(a) = author.as_deref().filter(|s| !s.is_empty()) {
            manifest
                .entry("author".to_string())
                .or_insert(serde_json::json!(a));
        }
        // 入口：plugin.json 的 main 字段 → 缺省 main.html；包内必须真实存在该文件
        let main_rel = manifest
            .get("main")
            .and_then(|v| v.as_str())
            .map(|s| s.trim().trim_start_matches("./").to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "main.html".to_string());
        if !tool_dir.join(&main_rel).is_file() {
            return Err(format!("插件包缺少入口文件: {}", main_rel));
        }
        manifest.insert("main".to_string(), serde_json::json!(main_rel));
        // logo：包内已带 logo.png 直接登记；否则下载市场封面兜底（可选，失败不致命）
        if tool_dir.join("logo.png").is_file() {
            manifest
                .entry("logo".to_string())
                .or_insert(serde_json::json!("logo.png"));
        } else if let Some(cu) = cover_url.as_deref().filter(|s| !s.is_empty()) {
            // 封面下载失败不致命（logo 可选，占位由工具箱处理）
            if let Ok(resp) = ureq::get(cu).timeout(std::time::Duration::from_secs(20)).call() {
                let mut buf = Vec::new();
                if resp.into_reader().read_to_end(&mut buf).is_ok() && !buf.is_empty() {
                    if std::fs::write(tool_dir.join("logo.png"), &buf).is_ok() {
                        manifest.insert("logo".to_string(), serde_json::json!("logo.png"));
                    }
                }
            }
        }
        let manifest_out = serde_json::to_string_pretty(&serde_json::Value::Object(manifest))
            .unwrap_or_else(|_| "{}".to_string());
        std::fs::write(tool_dir.join("plugin.json"), &manifest_out)
            .map_err(|e| format!("写入 plugin.json 失败: {}", e))?;
        Ok(manifest_out)
    })
    .await
    .map_err(|e| format!("ztool install task join failed: {}", e))?
}

/// 独立窗口多开本地插件：面板 iframe 里点上传（file input）会触发 WKWebView
/// 原生文件选择框（runOpenPanel），导致插件页重载消失，故改为每次点击开一个
/// 独立 WebviewWindow，asset:// 加载已装插件入口（与 iframe 同源信任规则），
/// label 带毫秒时间戳，同一插件可多开互不影响。
#[tauri::command]
async fn ztool_open(
    app: tauri::AppHandle,
    main_path: String,
    name: String,
) -> Result<String, String> {
    // Windows：同步命令在主线程 WebView2 事件分发栈内执行，此处同步
    // WebviewWindowBuilder::build 会与事件循环重入冲突，概率性永久卡死主线程
    // （插件窗点 X 关不掉、面板失焦不收、Alt+Space 失效、后续 IPC 全部无响应，
    // 与 handle_launcher_action 的死锁同族）。转 async 到异步运行时线程执行，
    // 建窗走事件循环代理路径（与启动期建窗同款安全机制）。
    let p = std::path::PathBuf::from(main_path.trim());
    if !p.is_file() {
        return Err(format!("插件入口文件不存在: {}", p.display()));
    }
    // 入口必须位于已安装插件目录内（防任意路径渲染）
    let canon = p.canonicalize().map_err(|e| e.to_string())?;
    let root = ztools_dir()?.canonicalize().map_err(|e| e.to_string())?;
    if !canon.starts_with(&root) {
        return Err("入口文件不在已安装插件目录内".to_string());
    }
    // 构造 asset:// URL：保留字面量 '/'（多段 path），否则入口页里的相对子资源
    // （xlsx.full.min.js 等）会被解析到根目录导致加载失败（Can't find variable: XLSX）；
    // 其余字符按 encodeURIComponent 规则全量百分号编码。
    let mut enc = String::new();
    for b in canon.to_string_lossy().as_bytes() {
        let c = *b as char;
        let keep = c.is_ascii_alphanumeric()
            || matches!(c, '/' | '-' | '_' | '.' | '!' | '~' | '*' | '\'' | '(' | ')');
        if keep {
            enc.push(c);
        } else {
            enc.push_str(&format!("%{:02X}", b));
        }
    }
    let url = tauri::Url::parse(&format!("asset://localhost/{}", enc))
        .map_err(|e| e.to_string())?;
    let label = format!(
        "ztool-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0)
    );
    tauri::webview::WebviewWindowBuilder::new(&app, &label, tauri::WebviewUrl::External(url))
        .title(if name.trim().is_empty() {
            "本地插件".to_string()
        } else {
            name.trim().to_string()
        })
        .inner_size(1180.0, 800.0)
        .min_inner_size(900.0, 640.0)
        .center()
        .build()
        .map_err(|e| format!("打开插件窗口失败: {}", e))?;
    Ok(label)
}

/// 卸载本地工具：删除 ~/.z-biz-tools/plugins/com.z-team.ztools.{id}/。
/// id 仅允许纯数字（防路径穿越）；目录不存在视为已卸载，幂等成功。
#[tauri::command]
fn ztool_uninstall(id: String) -> Result<String, String> {
    let id = id.trim().to_string();
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_digit()) {
        return Err(format!("非法插件 id: {}", id));
    }
    let dir = ztools_dir()?.join(format!("com.z-team.ztools.{}", id));
    if dir.exists() {
        std::fs::remove_dir_all(&dir).map_err(|e| format!("卸载失败: {}", e))?;
    }
    Ok(id)
}

/// 从前端首页 HTML 提取 publicPath 版本前缀（/z-team/{纯数字与点组成的版本}/）
fn extract_version_prefix(html: &str) -> Option<String> {
    let key = "/z-team/";
    let start = html.find(key)? + key.len();
    let rest = &html[start..];
    let end = rest
        .find(|c: char| !(c.is_ascii_digit() || c == '.'))
        .unwrap_or(rest.len());
    let ver = &rest[..end];
    if ver.is_empty() || !ver.contains('.') || ver.ends_with('.') {
        return None;
    }
    Some(format!("{}{}", key, ver))
}

/// 系统默认浏览器打开 URL（面板 webview 内直接导航会劫持窗口，必须外跳）
fn open_in_browser(url: &str) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(url)
            .spawn()
            .map_err(|e| format!("打开浏览器失败: {}", e))?;
    }
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("cmd")
            .args(["/C", "start", "", url])
            .spawn()
            .map_err(|e| format!("打开浏览器失败: {}", e))?;
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        std::process::Command::new("xdg-open")
            .arg(url)
            .spawn()
            .map_err(|e| format!("打开浏览器失败: {}", e))?;
    }
    Ok(())
}

/// 打开「插件制造 Skill」zip 下载（系统浏览器）：生产前端 publicPath 带构建
/// 时间戳版本号（/z-team/{VERSION}/skills/...），DMG 内无法预知最新版本号，
/// 运行时拉首页解析版本前缀；解析失败（本地 dev 根路径部署）退回根路径。
#[tauri::command]
async fn open_skill_package(app: tauri::AppHandle) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let base = env_base(&current_env(&app));
        let mut prefix = String::new();
        if let Ok(page) = ureq::get(base)
            .timeout(std::time::Duration::from_secs(10))
            .call()
        {
            if let Ok(html) = page.into_string() {
                if let Some(p) = extract_version_prefix(&html) {
                    prefix = p;
                }
            }
        }
        let url = format!("{}{}skills/ft-local-plugin-skill.zip", base, prefix);
        open_in_browser(&url)?;
        Ok(url)
    })
    .await
    .map_err(|e| format!("open skill package task join failed: {}", e))?
}

/// 构建顶部 App 菜单（环境切换 / Agent）。show_env=false 时隐藏「环境切换」，
/// 非admin用户固定生产环境，无入口可切。
fn build_app_menu(app: &tauri::AppHandle, show_env: bool) -> tauri::Result<()> {
    let version = env!("CARGO_PKG_VERSION");
    let sep = PredefinedMenuItem::separator(app)?;
    let version_item =
        MenuItem::with_id(app, "version", &format!("版本 v{}", version), false, None::<&str>)?;
    let update_item = MenuItem::with_id(app, "update", "检查更新...", false, None::<&str>)?;
    let agent_status =
        MenuItem::with_id(app, "agent-status", "Agent: 检测中...", false, None::<&str>)?;
    let kill_agent = MenuItem::with_id(app, "kill-agent", "重启 Agent", true, None::<&str>)?;
    let agent_submenu = Submenu::with_items(
        app,
        "Agent",
        true,
        &[&version_item, &update_item, &sep, &agent_status, &kill_agent],
    )?;

    let env_submenu = if show_env {
        let local_item = MenuItem::with_id(app, "env-local", "本地环境", true, None::<&str>)?;
        let prod_item = MenuItem::with_id(app, "env-prod", "生产环境", true, None::<&str>)?;
        Some(Submenu::with_items(
            app,
            "环境切换",
            true,
            &[&local_item, &prod_item],
        )?)
    } else {
        None
    };

    // 编辑子菜单：macOS WKWebView 的 ⌘C/⌘V/⌘A 依赖 App 菜单里的 Edit 项，
    // 缺失时 webview 输入框无法粘贴文本（桌面群聊粘贴失效根因）
    let edit_sep1 = PredefinedMenuItem::separator(app)?;
    let edit_sep2 = PredefinedMenuItem::separator(app)?;
    let edit_undo = PredefinedMenuItem::undo(app, None::<&str>)?;
    let edit_redo = PredefinedMenuItem::redo(app, None::<&str>)?;
    let edit_cut = PredefinedMenuItem::cut(app, None::<&str>)?;
    let edit_copy = PredefinedMenuItem::copy(app, None::<&str>)?;
    let edit_paste = PredefinedMenuItem::paste(app, None::<&str>)?;
    let edit_select_all = PredefinedMenuItem::select_all(app, None::<&str>)?;
    let edit_submenu = Submenu::with_items(
        app,
        "编辑",
        true,
        &[
            &edit_undo,
            &edit_redo,
            &edit_sep1,
            &edit_cut,
            &edit_copy,
            &edit_paste,
            &edit_sep2,
            &edit_select_all,
        ],
    )?;

    let mut items: Vec<&dyn tauri::menu::IsMenuItem<tauri::Wry>> = Vec::new();
    if let Some(ref env) = env_submenu {
        items.push(env);
    }
    items.push(&agent_submenu);
    items.push(&edit_submenu);
    app.set_menu(Menu::with_items(app, &items)?)?;
    Ok(())
}

/// 构建托盘菜单。show_env=false 时隐藏「环境切换」子菜单。
/// 唤起类菜单项展示对应的全局快捷键（见 GLOBAL_SHORTCUTS），纯提示用。
fn build_tray_menu(app: &tauri::AppHandle, show_env: bool) -> tauri::Result<tauri::menu::Menu<tauri::Wry>> {
    let tray_chat = MenuItem::with_id(app, "quick-chat", "发起群聊", true, Some("CmdOrCtrl+Shift+G"))?;
    let tray_note = MenuItem::with_id(app, "quick-note", "个人笔记", true, Some("CmdOrCtrl+Shift+N"))?;
    let tray_partner = MenuItem::with_id(app, "quick-partner", "专属伙伴", true, Some("CmdOrCtrl+Shift+P"))?;
    let tray_history = MenuItem::with_id(app, "quick-history", "历史群聊", true, Some("CmdOrCtrl+Shift+H"))?;
    let tray_sep1 = PredefinedMenuItem::separator(app)?;
    let tray_main = MenuItem::with_id(app, "show-main", "显示主窗口", true, Some("CmdOrCtrl+Shift+M"))?;
    let tray_sep2 = PredefinedMenuItem::separator(app)?;

    let tray_kill_agent = MenuItem::with_id(app, "kill-agent", "重启 Agent", true, None::<&str>)?;
    let tray_agent_submenu = Submenu::with_items(app, "Agent", true, &[&tray_kill_agent])?;

    let tray_sep3 = PredefinedMenuItem::separator(app)?;
    let tray_quit =
        MenuItem::with_id(app, "quit", "退出 Z-Bot", true, None::<&str>)?;

    let env_submenu = if show_env {
        let tray_local = MenuItem::with_id(app, "env-local", "本地环境", true, None::<&str>)?;
        let tray_prod = MenuItem::with_id(app, "env-prod", "生产环境", true, None::<&str>)?;
        Some(Submenu::with_items(
            app,
            "环境切换",
            true,
            &[&tray_local, &tray_prod],
        )?)
    } else {
        None
    };

    let mut items: Vec<&dyn tauri::menu::IsMenuItem<tauri::Wry>> = vec![
        &tray_chat,
        &tray_note,
        &tray_partner,
        &tray_history,
        &tray_sep1,
        &tray_main,
        &tray_sep2,
    ];
    if let Some(ref env) = env_submenu {
        items.push(env);
    }
    items.push(&tray_agent_submenu);
    items.push(&tray_sep3);
    items.push(&tray_quit);
    Menu::with_items(app, &items)
}

/// 前端登录后上报用户身份：admin 才显示「环境切换」菜单（默认隐藏，固定生产环境）。
/// 幂等：状态未变化时跳过菜单重建。
#[tauri::command]
fn set_admin_mode(app: tauri::AppHandle, is_admin: bool) -> Result<(), String> {
    {
        let state = app.state::<AgentState>();
        let mut guard = state.is_admin.lock().unwrap();
        if *guard == is_admin {
            return Ok(());
        }
        *guard = is_admin;
    }
    println!("[desk] admin mode: {}", is_admin);
    let menu_app = app.clone();
    app.run_on_main_thread(move || {
        if let Err(e) = build_app_menu(&menu_app, is_admin) {
            eprintln!("[desk] rebuild app menu failed: {}", e);
        }
        if let Some(tray) = menu_app.tray_by_id("main-tray") {
            match build_tray_menu(&menu_app, is_admin) {
                Ok(menu) => {
                    let _ = tray.set_menu(Some(menu));
                }
                Err(e) => eprintln!("[desk] rebuild tray menu failed: {}", e),
            }
        }
    })
    .map_err(|e| format!("run_on_main_thread failed: {}", e))?;
    Ok(())
}

/// 统一菜单动作（App 顶部菜单与托盘菜单共用同一组 id）
fn handle_menu_action(app: &tauri::AppHandle, id: &str) {
    match id {
        "quick-chat" => {
            // 720 宽：实测 ChatPanel 工具栏（Build/指令/默认模型/工具/导出/学习/图片/展开全部）
            // 单行需 ~699px（675 内容 + 24 padding），720 保证单行不换行；
            // 右缘滑入（与笔记/历史一致），竖向贴满工作区
            if let Err(e) = ensure_right_slide_window(
                app,
                "quick-chat",
                "/quickchat",
                "Z-Team 群聊",
                HISTORY_WINDOW_WIDTH,
                None,
            ) {
                eprintln!("open quick-chat failed: {}", e);
            }
        }
        "quick-partner" => {
            // 专属伙伴：个人 Agent 列表 + 点击即聊，右缘滑入；版面较大，抽屉占工作区 50% 宽
            if let Err(e) = ensure_right_slide_window(
                app,
                "quick-partner",
                "/quickpartner",
                "专属伙伴",
                PARTNER_WINDOW_WIDTH,
                Some(0.5),
            ) {
                eprintln!("open quick-partner failed: {}", e);
            }
        }
        "quick-history" => {
            if let Err(e) = ensure_right_slide_window(
                app,
                "quick-history",
                "/quickhistory",
                "历史群聊",
                HISTORY_WINDOW_WIDTH,
                None,
            ) {
                eprintln!("open quick-history failed: {}", e);
            }
        }
        "quick-note" => {
            // 笔记与历史群聊同为右缘滑入：竖向贴满工作区、贴右缘，保持桌面工具面板的一致感
            if let Err(e) = ensure_right_slide_window(
                app,
                "quick-note",
                "/quicknote",
                "Z-Team 笔记",
                NOTE_WINDOW_WIDTH,
                None,
            ) {
                eprintln!("open quick-note failed: {}", e);
            }
        }
        "show-main" => {
            if let Err(e) = ensure_main_window(app) {
                eprintln!("open main window failed: {}", e);
            }
        }
        "env-local" | "env-prod" => {
            let env = if id == "env-local" { "local" } else { "production" };
            {
                let state = app.state::<AgentState>();
                *state.current_env.lock().unwrap() = env.to_string();
            }
            let base = env_base(env);
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.eval(&format!(
                    "window.location.href = '{}/workspace?client=desktop&_v={}'",
                    base, env!("CARGO_PKG_VERSION")
                ));
                window
                    .set_title(&format!(
                        "Z-Bot v{} - {}",
                        env!("CARGO_PKG_VERSION"),
                        env_label(env)
                    ))
                    .ok();
                println!("Switched to {}: {}", env_label(env), base);
            }
            // 已打开的快捷窗口跟随环境重新加载（ft-launcher 为本地页，仅 reload 以
            // 便市场代理按新环境 base 拉取）
            for (label, path) in [
                ("quick-chat", "/quickchat"),
                ("quick-note", "/quicknote"),
                ("quick-history", "/quickhistory"),
            ] {
                if let Some(win) = app.get_webview_window(label) {
                    let _ = win.eval(&format!(
                        "window.location.href = '{}{}?client=desktop&_v={}'",
                        base, path, env!("CARGO_PKG_VERSION")
                    ));
                }
            }
            if let Some(win) = app.get_webview_window("ft-launcher") {
                let _ = win.eval("window.location.reload()");
            }
        }
        "kill-agent" => {
            println!("Restarting Agent...");
            // 后台执行：wait_for_health 最长可阻塞 ~2 分钟，跑在主线程会冻住
            // 事件循环（窗口点 X / 所有菜单全部无响应）
            let app = app.clone();
            std::thread::spawn(move || {
                // 先杀 state 持有的旧子进程句柄再 kill_port：agent 处于启动中
                // （尚未监听 28080）时 kill_port 会漏杀，直接重启会叠出双 agent，
                // 旧进程沦为孤儿并锁死 resources\jre\bin\java.exe（覆盖安装报
                // "Error opening file for writing" 的根因之一）
                let state = app.state::<AgentState>();
                if let Ok(mut child_guard) = state.child.lock() {
                    if let Some(mut child) = child_guard.take() {
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                }
                agent_process::kill_port(28080);
                if let Some(jar) = find_jar() {
                    // 新句柄存回 state：原先 let _ 丢弃导致重启后 state 里
                    // 一直是已死的旧句柄，后续退出兜底杀不到新 agent
                    if let Some(new_child) = agent_process::start_agent_jar(&jar, 28080) {
                        if let Ok(mut child_guard) = app.state::<AgentState>().child.lock() {
                            *child_guard = Some(new_child);
                        }
                    }
                    agent_process::wait_for_health(28080);
                }
            });
        }
        "quit" => {
            println!("Quitting Z-Bot...");
            // 退出前保存 SSO 登录态（下次启动注入恢复，免重复登录）。
            // 这里同样**不能**同步调 cookies()：主窗口正在导航提交时，
            // 同步 cookies() 的嵌套 run loop 会把那条提交也派发掉 →
            // wry 借机重建 webview → 再进一次 cookies() → 自递归卡死主线程，
            // 症状是「点退出没反应、进程还在」。
            // 改成：整体延后到事件循环空闲点，在那里快照 + 退出。
            let app_quit = app.clone();
            let app_snap = app.clone();
            std::thread::spawn(move || {
                std::thread::sleep(std::time::Duration::from_millis(500));
                let _ = app_quit.run_on_main_thread(move || {
                    persist_sso_cookies(&app_snap);
                    agent_process::kill_port(28080);
                    // 兜底：agent 处于启动中（尚未监听 28080）时 kill_port 会漏杀，
                    // 直接杀 state 持有的子进程句柄；take 后 wait 收尸防句柄泄漏
                    let state = app_snap.state::<AgentState>();
                    let mut child_guard = state.child.lock().unwrap();
                    if let Some(mut child) = child_guard.take() {
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                    app_snap.exit(0);
                });
            });
        }
        "update" => {
            let state = app.state::<AgentState>();
            let guard = state.update_url.lock().unwrap();
            if let Some(url) = guard.as_ref() {
                let _ = open_in_browser(url).map_err(|e| eprintln!("open update url failed: {}", e));
            }
        }
        _ => {}
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let version = env!("CARGO_PKG_VERSION");
    println!("Z Team Agent v{} starting...", version);

    // 1. 先清理旧进程
    agent_process::kill_port(28080);

    // 2. 查找并启动 Agent JAR
    let agent_child = if let Some(ref jar) = find_jar() {
        println!("Using z-bot JAR: {}", jar);
        agent_process::start_agent_jar(jar, 28080)
    } else {
        eprintln!("z-bot JAR not found!");
        None
    };

    // 3. 等待 Agent 启动（后台等待）：wait_for_health 最长可阻塞 ~2 分钟，
    //    放主线程会推迟事件循环启动（窗口迟迟不出现，客户机会连点图标
    //    叠开多个进程）。主窗口加载远程前端，不依赖本地 Agent 健康。
    if agent_child.is_some() {
        println!("Waiting for Agent to start...");
        std::thread::spawn(|| {
            if agent_process::wait_for_health(28080) {
                println!("Agent is healthy!");
            } else {
                eprintln!("Agent failed to become healthy within 30s");
            }
        });
    }

    // 4. 启动 Tauri 应用
    tauri::Builder::default()
        // 单实例守护：必须最先注册。二次启动不再拉起新进程（此前每点一次
        // 图标就叠一个主窗口，且各进程启动时互杀 28080 端口的 Agent），
        // 改为前置已有主窗口
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Err(e) = ensure_main_window(app) {
                eprintln!("[desk] second-instance focus main failed: {}", e);
            }
        }))
        .plugin(tauri_plugin_deep_link::init())
        // 全局快捷键插件：必须注册，否则 setup 里 global_shortcut() 取不到
        // 托管状态会直接 panic（启动即崩），编译期无法发现
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .build(),
        )
        .manage(AgentState {
            child: Mutex::new(agent_child),
            current_env: Mutex::new("production".to_string()),
            update_url: Mutex::new(None),
            is_admin: Mutex::new(false),
            launcher_focus_lost_at: Mutex::new(None),
        })
        .invoke_handler(tauri::generate_handler![
            download_and_install_dmg,
            check_for_update,
            set_admin_mode,
            launcher_action,
            market_resources,
            badge_briefs,
            badge_close,
            badge_resize,
            ztool_installed_list,
            ztool_install,
            ztool_uninstall,
            ztool_open,
            http_request,
            open_skill_package
        ])
        .setup(move |app| {
            // 清理 WebView HTTP 磁盘缓存：防止升级后旧版 index.html 残留导致新路由（/quicknote 等）404。
            // 此时首个 WebView 尚未创建（窗口均在下方 run_on_main_thread 动态建立），磁盘缓存目录无进程锁，
            // 删除安全。仅清 HTTP 缓存，不动 cookie/localStorage（SSO 登录态保留）。
            // macOS：WKWebView 的 Caches/<bundle_id> 整目录。
            #[cfg(target_os = "macos")]
            if let Ok(home) = std::env::var("HOME") {
                // 新 identifier 的缓存目录 + 旧 z-team 壳的遗留目录都清一遍
                for bundle in ["com.zifang.z-bot", "com.zifang.z-team-agent"] {
                    let cache_dir = std::path::Path::new(&home)
                        .join("Library/Caches")
                        .join(bundle);
                    if cache_dir.exists() {
                        match std::fs::remove_dir_all(&cache_dir) {
                            Ok(_) => println!("[desk] cleared webview disk cache: {}", bundle),
                            Err(e) => eprintln!("[desk] clear cache failed ({}): {}", bundle, e),
                        }
                    }
                }
            }
            // Windows：WebView2 的 userDataFolder（默认 exe 旁 <exe>.WebView2，或
            // %LOCALAPPDATA% 下的回退位置）只清其 HTTP 缓存子目录（Cache/Code Cache），
            // 不动 Cookies/Local Storage/IndexedDB（登录态保留）。
            #[cfg(target_os = "windows")]
            {
                let mut roots: Vec<std::path::PathBuf> = Vec::new();
                if let Ok(exe) = std::env::current_exe() {
                    if let (Some(dir), Some(stem)) = (exe.parent(), exe.file_stem()) {
                        roots.push(dir.join(format!("{}.WebView2", stem.to_string_lossy())));
                    }
                }
                if let Ok(local) = std::env::var("LOCALAPPDATA") {
                    let local = std::path::PathBuf::from(local);
                    roots.push(local.join("Z-Bot.exe.WebView2"));
                    roots.push(local.join("com.zifang.z-team-agent"));
                }
                for root in roots {
                    let cache_root = root.join("EBWebView/Default");
                    for sub in ["Cache", "Code Cache"] {
                        let dir = cache_root.join(sub);
                        if dir.exists() {
                            match std::fs::remove_dir_all(&dir) {
                                Ok(_) => println!("[desk] cleared webview2 cache: {}", dir.display()),
                                Err(e) => eprintln!("[desk] clear cache failed ({}): {}", dir.display(), e),
                            }
                        }
                    }
                }
            }
            // ── SSO 登录态恢复：主窗口创建前注入上次保存的 Cookie
            //    （Apex 会话 Cookie 不落盘，靠 ~/.z-team/desk-cookies.json 镜像）──
            inject_persisted_cookies(app.handle());

            // 主窗口：延后到注入任务落地之后再创建。set_cookie 经 event loop
            // 异步派发，若在此同步创建主窗口，前端首个鉴权请求可能早于 Cookie
            // 落地 → 后端 101012001 → 前端清 localStorage 并跳登录页（"每次
            // 打开都要登录"）。run_on_main_thread 的任务排在已入队的 set_cookie
            // 之后执行，保证首请求就带上恢复的登录态。
            let main_app = app.handle().clone();
            app.run_on_main_thread(move || {
                if let Err(e) = ensure_main_window(&main_app) {
                    eprintln!("[desk] open main window failed: {}", e);
                }
            })
            .map_err(|e| format!("defer main window failed: {}", e))?;

            // ── 顶部菜单 + 托盘菜单：环境切换默认隐藏（默认生产环境），
            //    前端登录后 set_admin_mode(true) 才对 admin 显示
            let is_admin = *app.state::<AgentState>().is_admin.lock().unwrap();
            build_app_menu(app.handle(), is_admin)?;
            let tray_menu = build_tray_menu(app.handle(), is_admin)?;

            // ── 全局快捷键：⌘⇧G 群聊 / ⌘⇧N 笔记 / ⌘⇧H 历史群聊 / ⌘⇧M 主窗口，
            //    任何应用前置时按键即可唤起，与托盘菜单动作完全一致 ──
            register_global_shortcuts(app.handle());

            TrayIconBuilder::with_id("main-tray")
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("Z-Bot")
                .menu(&tray_menu)
                .on_menu_event(|app, event| {
                    handle_menu_action(app, event.id().as_ref());
                })
                .build(app)?;

            // ── 桌面悬浮标（小副标）：贴屏幕右缘，点击开关 FT 功能面板 ──
            if let Err(e) = ensure_ft_badge(app.handle()) {
                eprintln!("[desk] create ft-badge failed: {}", e);
            }

            // ── 定时快照 SSO Cookie（60s）：防 crash/强杀导致登录态存档落后 ──
            let cookie_app = app.handle().clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(std::time::Duration::from_secs(60));
                if let Some(win) = cookie_app.get_webview_window("main") {
                    if let Ok(all) = win.cookies() {
                        cookies::save(&all);
                    }
                }
            });

            // ── Rust-side update check: update native menu bar (no JS needed) ──
            let app_handle = app.handle().clone();
            let current_version = version.to_string();
            std::thread::spawn(move || {
                std::thread::sleep(std::time::Duration::from_secs(5));
                println!("[UPDATE] checking for updates...");
                let url = "http://oss.z-team.local-cn-hangzhou.aliyuncs.com/z-team/sandbox/z_dmg/latest.json";
                let output = std::process::Command::new("/usr/bin/curl")
                    .args(["-s", "--max-time", "10", "-H", "Cache-Control: no-cache", url])
                    .output();
                if let Ok(output) = output {
                    if output.status.success() {
                        if let Ok(json) = serde_json::from_slice::<serde_json::Value>(&output.stdout) {
                            if let Some(remote_ver) = json["version"].as_str() {
                                println!("[UPDATE] remote={}, local={}", remote_ver, current_version);
                                let cur: Vec<u32> =
                                    current_version.split('.').filter_map(|s| s.parse().ok()).collect();
                                let rem: Vec<u32> =
                                    remote_ver.split('.').filter_map(|s| s.parse().ok()).collect();
                                let is_newer = rem.iter().enumerate().any(|(i, &rv)| {
                                    i >= cur.len() || rv > cur.get(i).copied().unwrap_or(0)
                                });
                                if is_newer {
                                    if let Some(dl_url) = json["downloadUrl"].as_str() {
                                        println!("[UPDATE] update available: {}", dl_url);
                                        let state = app_handle.state::<AgentState>();
                                        *state.update_url.lock().unwrap() = Some(dl_url.to_string());
                                        let label = format!(
                                            "\u{2728} 发现新版本 v{}\u{ff0c}点击更新",
                                            remote_ver
                                        );
                                        let remote_ver_owned = remote_ver.to_string();
                                        let ah = app_handle.clone();
                                        let _ = app_handle.run_on_main_thread(move || {
                                            if let Some(window) = ah.get_webview_window("main") {
                                                let _ = window.set_title(&format!(
                                                    "Z-Bot v{}  ✨ 发现新版本 v{} → 点 Agent 菜单更新",
                                                    current_version, remote_ver_owned
                                                ));
                                            }
                                            if let Some(menu) = ah.menu() {
                                                if let Ok(items) = menu.items() {
                                                    for kind in &items {
                                                        if let Some(submenu) = kind.as_submenu() {
                                                            if let Some(update_kind) = submenu.get("update") {
                                                                if let Some(item) = update_kind.as_menuitem() {
                                                                    let _ = item.set_text(&label);
                                                                    let _ = item.set_enabled(true);
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        });
                                    }
                                }
                            }
                        }
                    }
                }
            });

            Ok(())
        })
        .on_menu_event(|app, event| {
            handle_menu_action(app, event.id().as_ref());
        })
        .on_page_load(|webview, _payload| {
            let _ = webview.eval("window.__FT_DESKTOP__ = true;");
            // 页面加载（登录成功 redirect 回来 / 普通导航）后快照 SSO Cookie；
            // cookie-bootstrap 是注入用的临时隐藏窗口，其空 store 不落盘。
            // Windows 下 on_page_load 处于 WebView2 事件回调栈内，同步 cookies()
            // 内部 wait_with_pump 等待的 GetCookies 完成回调无法重入投递，会
            // 永久卡死 UI 线程——此后所有 NavigationStarting 不再触发，页面内
            // 脚本发起的跳转（Apex 登录 302）全部被静默中止，表现为"永远停在
            // 正在接入 Apex 统一登录"。因此延迟到事件循环空闲点再快照。
            if webview.label() != "cookie-bootstrap" {
                // 页面加载回调里**不能**同步调 cookies()：macOS 上它是
                // 「嵌套 CFRunLoop 等异步回调」，而这个嵌套循环会继续派发待处理
                // IPC —— 恰好撞上 didCommitLoad（wry 借机重建 webview）就自递归：
                // create_webview → cookies() → 嵌套循环 → didCommitLoad →
                // create_webview → …… 主线程死锁。
                // 症状：主窗口一直白屏、启动日志停在「窗口创建」那一步不动。
                // 实测 2026-10-20：主窗口指向**可访问**的 https 域（生产 OPC
                // https://opc.zopc.top/workspace）时必现 —— 域名解析不了时反而不
                // 触发（没有导航提交，就没有 didCommitLoad）。
                //
                // 修法：把快照动作挪到事件循环空闲点（延后 + run_on_main_thread），
                // 此时没有待处理的导航提交，不会重入。Windows 侧原本就是这个修法
                // （见上一分支注释：WebView2 回调栈内同步 cookies() 会卡死 UI 线程），
                // 这里统一为两平台同一套实现。
                let app = webview.app_handle().clone();
                std::thread::spawn(move || {
                    std::thread::sleep(std::time::Duration::from_millis(800));
                    let app2 = app.clone();
                    let _ = app.run_on_main_thread(move || {
                        if let Some(win) = app2.get_webview_window("main") {
                            if let Ok(all) = win.cookies() {
                                cookies::save(&all);
                                println!("[desk-cookies] persisted {} cookies (deferred)", all.len());
                            }
                        }
                    });
                });
            }
        })
        .on_window_event(|window, event| {
            match event {
                WindowEvent::CloseRequested { api, .. } => {
                    #[cfg(target_os = "macos")]
                    {
                        // macOS 标准行为：点叉仅隐藏窗口面板，不退出应用、不杀 Agent。
                        // 主窗口 / 群聊 / 笔记 一律隐藏，托盘菜单点击对应项即可恢复。
                        // 真正退出走托盘「退出 Z-Bot」（quit），由该处统一杀 Agent + exit。
                        api.prevent_close();
                        let _ = window.hide();
                    }
                    #[cfg(not(target_os = "macos"))]
                    {
                        // Windows 用户预期：点 X 关闭主窗口 = 退出应用（复用托盘
                        // quit：存登录态 → 杀 Agent → exit）；快捷面板（群聊/笔记/
                        // 历史/伙伴）点 X 仍仅隐藏，保留会话。
                        if window.label() == "main" {
                            handle_menu_action(window.app_handle(), "quit");
                        } else {
                            api.prevent_close();
                            let _ = window.hide();
                        }
                    }
                }
                WindowEvent::Focused(false) if window.label() == "ft-launcher" => {
                    // DingTalk 式体验：功能面板失焦即收起；记录时间戳供 toggle 识别
                    // 「本次点击悬浮标/⌥Space 引起的失焦收起」，避免点关面板时被重新打开。
                    // 延迟复查：show 后的瞬时焦点抖动（系统激活流程来回切换）也会触发本事件，
                    // 若立即隐藏会出现「面板闪现即收起」。150ms 后确实无焦点才收起。
                    let app = window.app_handle().clone();
                    std::thread::spawn(move || {
                        std::thread::sleep(std::time::Duration::from_millis(150));
                        let Some(win) = app.get_webview_window("ft-launcher") else {
                            return;
                        };
                        if win.is_visible().unwrap_or(false) && !win.is_focused().unwrap_or(false) {
                            *app
                                .state::<AgentState>()
                                .launcher_focus_lost_at
                                .lock()
                                .unwrap() = Some(std::time::Instant::now());
                            let _ = win.hide();
                        }
                    });
                }
                _ => {}
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
