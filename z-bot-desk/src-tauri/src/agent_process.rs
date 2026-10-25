use std::process::{Child, Command, Stdio};
use std::time::Duration;
use std::thread;

/// Windows 子进程默认携带 CREATE_NO_WINDOW，避免后台命令闪黑框
fn hidden_command(program: &str) -> Command {
    let mut cmd = Command::new(program);
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    #[allow(unused_mut)]
    let _ = &mut cmd;
    cmd
}

/// Windows：解析打包内嵌 JRE 的 java.exe（客户机不要求装 Java）。
/// jar 与 jre 同在 resources 目录下（resources/jar + resources/jre/bin/java.exe）；
/// 兼容交叉打包时 resources 平铺到安装根的已知行为（exe 同级 jre/）。
#[cfg(target_os = "windows")]
fn bundled_java_windows(jar_path: &str) -> Option<String> {
    if let Some(parent) = std::path::Path::new(jar_path).parent() {
        let java = parent.join("jre/bin/java.exe");
        if java.exists() {
            return Some(java.to_string_lossy().to_string());
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(exe_dir) = exe.parent() {
            let java = exe_dir.join("jre/bin/java.exe");
            if java.exists() {
                return Some(java.to_string_lossy().to_string());
            }
        }
    }
    None
}

/// 解析 java 可执行文件：Windows 优先内嵌 JRE → JAVA_HOME → PATH；
/// macOS 维持原有 JAVA_HOME → PATH 顺序不变（存量 DMG 行为零影响）。
/// jar_path 仅 Windows 分支使用（据此定位同目录内嵌 JRE）。
#[allow(unused_variables)]
fn resolve_java_bin(jar_path: &str) -> String {
    #[cfg(target_os = "windows")]
    if let Some(java) = bundled_java_windows(jar_path) {
        return java;
    }
    if let Ok(java_home) = std::env::var("JAVA_HOME") {
        let java = if cfg!(windows) {
            format!("{}\\bin\\java.exe", java_home)
        } else {
            format!("{}/bin/java", java_home)
        };
        if std::path::Path::new(&java).exists() {
            return java;
        }
    }
    if cfg!(windows) { "java.exe".to_string() } else { "java".to_string() }
}

/// 查找并杀掉占用指定端口的进程
pub fn kill_port(port: u16) {
    #[cfg(target_os = "windows")]
    {
        // netstat -ano 找 LISTENING 状态的本地端口 → taskkill 强杀进程树
        let port_suffix = format!(":{}", port);
        if let Ok(out) = hidden_command("netstat").args(["-ano"]).output() {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let mut killed: Vec<i32> = Vec::new();
            for line in stdout.lines() {
                let cols: Vec<&str> = line.split_whitespace().collect();
                // TCP  本地地址  远程地址  LISTENING  PID
                if cols.len() >= 5
                    && cols[0] == "TCP"
                    && cols[1].ends_with(&port_suffix)
                    && cols[3] == "LISTENING"
                {
                    if let Ok(pid) = cols[4].parse::<i32>() {
                        if !killed.contains(&pid) {
                            let _ = hidden_command("taskkill")
                                .args(["/F", "/T", "/PID", &pid.to_string()])
                                .output();
                            killed.push(pid);
                            thread::sleep(Duration::from_millis(500));
                        }
                    }
                }
            }
        }
        return;
    }
    #[cfg(not(target_os = "windows"))]
    {
        // macOS / Linux: 用 lsof 查端口
        let output = Command::new("lsof")
            .args(["-ti", &format!(":{}", port)])
            .output();

        if let Ok(out) = output {
            let stdout = String::from_utf8_lossy(&out.stdout);
            for pid_str in stdout.trim().lines() {
                if let Ok(pid) = pid_str.trim().parse::<i32>() {
                    // 先 SIGTERM
                    let _ = Command::new("kill").arg(pid.to_string()).output();
                    thread::sleep(Duration::from_millis(500));
                    // 如果还在，SIGKILL
                    let _ = Command::new("kill")
                        .args(["-9", &pid.to_string()])
                        .output();
                }
            }
        }
    }
}

/// Windows：把 java 子进程挂入 KILL_ON_JOB_CLOSE 的 Job Object——desk 进程死亡
/// （安装器杀旧版、任务管理器强杀、崩溃）时内核自动终止 java，杜绝 28080 孤儿
/// 进程与安装目录 jre/jar 文件锁导致的卸载残留。分配失败（如父链 job 禁止）
/// 则静默退回旧行为：孤儿由下次启动 kill_port 自愈。
#[cfg(target_os = "windows")]
fn bind_kill_on_close_job(child: &Child) {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };
    unsafe {
        let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if job.is_null() {
            return;
        }
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const core::ffi::c_void,
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        ) == 0
        {
            return;
        }
        let _ = AssignProcessToJobObject(job, child.as_raw_handle());
        // 刻意不 CloseHandle：desk 持有 job 句柄，进程退出句柄关闭 → java 被回收
    }
}

/// 启动 z-bot JAR 子进程（`serve` 子命令，HTTP 面在 `--port` 上）。
pub fn start_agent_jar(jar_path: &str, port: u16) -> Option<Child> {
    let java_bin = resolve_java_bin(jar_path);

    let child = hidden_command(&java_bin)
        .args(["-jar", jar_path, "serve", "--port", &port.to_string()])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();

    match child {
        Ok(c) => {
            #[cfg(target_os = "windows")]
            bind_kill_on_close_job(&c);
            println!("z-bot JAR started: port={}", port);
            Some(c)
        }
        Err(e) => {
            eprintln!("Failed to start Agent JAR: {}", e);
            None
        }
    }
}

/// 等待 health 检查通过（最多 30 秒）；ureq 原生 HTTP，跨平台不依赖 curl
pub fn wait_for_health(port: u16) -> bool {
    let url = format!("http://127.0.0.1:{}/health", port);
    for _ in 0..60 {
        let ok = ureq::get(&url)
            .timeout(Duration::from_secs(2))
            .call()
            .map(|resp| resp.status() == 200)
            .unwrap_or(false);
        if ok {
            return true;
        }
        thread::sleep(Duration::from_millis(500));
    }
    false
}
