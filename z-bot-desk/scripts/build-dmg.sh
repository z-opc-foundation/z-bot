#!/usr/bin/env bash
#
# 用 Tauri 构建 macOS .dmg 安装包。
#
# 流程：
#   1. npm run build → 构建前端到 dist/
#   2. 拷贝 dist/ 到 starter classpath（JAR 内嵌前端）
#   3. mvn package → 生成 fat JAR
#   4. jlink → 构建嵌入式 JRE（免安装 Java 即可运行）
#   5. cargo tauri build → 生成 .dmg
#   6. ditto → 生成含安装引导脚本的 zip（解决 zip 传输 quarantine 问题）
#
# 用法：
#   bash scripts/build-dmg.sh
#
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DESK_DIR="$(dirname "$SCRIPT_DIR")"
AGENT_DIR="$(dirname "$DESK_DIR")"
REPO_ROOT="$(cd "$AGENT_DIR/../.." && pwd)"

# 加载 Rust 环境
source "$HOME/.cargo/env"

echo "=========================================="
echo "  Building ZTeamAgent (.dmg) [Tauri]"
echo "=========================================="

# ── 1. 构建前端 ──
echo ""
echo "[1/6] Building frontend..."
cd "$DESK_DIR"
npm run build 2>&1 | tail -5

# ── 2. 拷贝前端到 JAR classpath ──
echo ""
echo "[2/6] Copying frontend to JAR classpath..."
STATIC_DIR="$AGENT_DIR/z-team-agent-starter/src/main/resources/static/workspace"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -R dist/* "$STATIC_DIR/"
echo "  Copied dist/ → $STATIC_DIR"

# ── 3. 构建 fat JAR ──
echo ""
echo "[3/6] Building fat JAR..."
cd "$AGENT_DIR"
mvn package -pl z-team-agent-starter -am -DskipTests 2>&1 | tail -10

# fat JAR 名字带版本号，不写死版本 —— 取 target 下最新的非 original-* 的主构件
JAR_PATH=$(ls -t "$AGENT_DIR"/z-team-agent-starter/target/z-team-agent-starter-*.jar 2>/dev/null \
    | grep -v '/original-' | head -1)
if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: fat JAR not found under $AGENT_DIR/z-team-agent-starter/target/" >&2
    exit 1
fi
JAR_SIZE=$(ls -lh "$JAR_PATH" | awk '{print $5}')
echo "  JAR: $JAR_PATH ($JAR_SIZE)"

# 拷贝 JAR 到 src-tauri/resources/（Tauri 打包需要）
# 名字必须与 tauri.conf.json 的 bundle.resources 一致（resources/z-team-agent.jar），
# 且 Rust 侧 find_jar() 也按这个名字找
mkdir -p "$DESK_DIR/src-tauri/resources"
cp "$JAR_PATH" "$DESK_DIR/src-tauri/resources/z-team-agent.jar"
echo "  Copied JAR → src-tauri/resources/z-team-agent.jar"

# ── 4. 构建嵌入式 JRE ──
echo ""
echo "[4/6] Building embedded JRE with jlink..."

# 查找含 jlink 的 JDK（jlink 需要 JDK 9+，JAVA_HOME 可能指向 JDK 8）
JLINK_JDK=""
# 4a. 优先使用 JAVA_HOME（如果包含 jlink）
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jlink" ] && [ -d "$JAVA_HOME/jmods" ]; then
    JLINK_JDK="$JAVA_HOME"
fi
# 4b. Homebrew OpenJDK
if [ -z "$JLINK_JDK" ]; then
    for hb in /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home /usr/local/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home; do
        if [ -x "$hb/bin/jlink" ] && [ -d "$hb/jmods" ]; then JLINK_JDK="$hb"; break; fi
    done
fi
# 4c. /usr/libexec/java_home -v 9+
if [ -z "$JLINK_JDK" ]; then
    JLINK_JDK=$(/usr/libexec/java_home -v 9+ 2>/dev/null || true)
fi
if [ -z "$JLINK_JDK" ]; then
    echo "ERROR: No JDK with jlink (JDK 9+) found. Install OpenJDK 17+ or set JAVA_HOME." >&2
    exit 1
fi
echo "  Using JDK for jlink: $JLINK_JDK"

cd "$DESK_DIR"
bash "$SCRIPT_DIR/build-jre.sh" "$JLINK_JDK" 2>&1
JRE_DIR="$DESK_DIR/src-tauri/resources/jre"
if [ ! -d "$JRE_DIR" ] || [ ! -x "$JRE_DIR/bin/java" ]; then
    echo "ERROR: embedded JRE not found at $JRE_DIR" >&2
    exit 1
fi
JRE_SIZE=$(du -sh "$JRE_DIR" | awk '{print $1}')
echo "  JRE: $JRE_DIR ($JRE_SIZE)"

# ── 5. 图标：缺失时从吉祥物生成 ──
echo ""
echo "[5/6] Ensuring Tauri icon set..."
if [ ! -f "$DESK_DIR/src-tauri/icons/icon.icns" ]; then
    ICON_SRC="$DESK_DIR/public/mascot/mascot-happy.png"
    if [ ! -f "$ICON_SRC" ]; then
        echo "ERROR: icons missing and no icon source found at $ICON_SRC" >&2
        exit 1
    fi
    TMP_ICON="$(mktemp -t zteam-icon).png"
    sips -z 1024 1024 "$ICON_SRC" --out "$TMP_ICON" >/dev/null
    (cd "$DESK_DIR" && npx --no-install tauri icon "$TMP_ICON")
    rm -f "$TMP_ICON"
fi
echo "  Icons: $DESK_DIR/src-tauri/icons"

# ── 6. Tauri 构建 DMG ──
echo ""
echo "[6/6] Building DMG with Tauri..."
cd "$DESK_DIR"
# cargo-tauri 未装（cargo tauri 不可用），用 node_modules 里的 @tauri-apps/cli；
# 退化到 PATH 上的全局 tauri
if [ -x "$DESK_DIR/node_modules/.bin/tauri" ]; then
    "$DESK_DIR/node_modules/.bin/tauri" build 2>&1 | tail -20
else
    tauri build 2>&1 | tail -20
fi

# 产品名以 tauri.conf.json 的 productName 为准（bundle 出来的 .app/.dmg 都用它）
PRODUCT_NAME=$(sed -n 's/.*"productName": "\([^"]*\)".*/\1/p' "$DESK_DIR/src-tauri/tauri.conf.json" | head -1)
VERSION=$(sed -n 's/.*"version": "\([^"]*\)".*/\1/p' "$DESK_DIR/src-tauri/tauri.conf.json" | head -1)
ARCH=$(uname -m)

# 查找生成的 DMG（排除 create-dmg 失败残留的 rw.*.dmg 母盘）
DMG_PATH=$(find "$DESK_DIR/src-tauri/target/release/bundle/dmg" -name "${PRODUCT_NAME}_*.dmg" 2>/dev/null | head -1)

# macOS 26+ 上 create-dmg 的 Finder AppleScript（statusbar visible）必败，
# tauri build 到 bundle_dmg.sh 阶段报错但 .app 已生成：
# 此时用 hdiutil UDZO 直接从 .app 生成等价 DMG（无 Finder 背景美化，拖拽安装不受影响）
if [ -z "$DMG_PATH" ]; then
    APP_PATH="$DESK_DIR/src-tauri/target/release/bundle/macos/${PRODUCT_NAME}.app"
    if [ ! -d "$APP_PATH" ]; then
        echo "ERROR: neither DMG nor .app bundle found — Tauri build failed." >&2
        exit 1
    fi
    echo "  bundle_dmg.sh failed (known create-dmg/AppleScript issue on macOS 26+), fallback to hdiutil UDZO..."
    rm -f "$DESK_DIR"/src-tauri/target/release/bundle/dmg/rw.*.dmg 2>/dev/null || true
    hdiutil create -volname "$PRODUCT_NAME" -srcfolder "$APP_PATH" -ov -format UDZO \
        "$DESK_DIR/src-tauri/target/release/bundle/dmg/${PRODUCT_NAME}_${VERSION}_${ARCH}.dmg" >/dev/null
    DMG_PATH="$DESK_DIR/src-tauri/target/release/bundle/dmg/${PRODUCT_NAME}_${VERSION}_${ARCH}.dmg"
fi
if [ -n "$DMG_PATH" ]; then
    DMG_SIZE=$(ls -lh "$DMG_PATH" | awk '{print $5}')
    # 清除扩展属性
    xattr -cr "$DMG_PATH" 2>/dev/null || true

    # ── 6. 生成分发用 zip 包（含安装引导脚本） ──
    # 解决 zip 传输后 DMG 被 macOS quarantine 拦截的问题
    DMG_FILENAME=$(basename "$DMG_PATH")
    ZIP_NAME="${DMG_FILENAME%.dmg}.zip"
    STAGING_DIR=$(mktemp -d)
    cp "$DMG_PATH" "$STAGING_DIR/$DMG_FILENAME"

    # 生成安装引导脚本：清除 quarantine + 打开 DMG
    # 使用英文文件名避免 zip 中文编码问题
    # 产品名不写死：运行时从同目录的 dmg 文件名反推（ZTeamAgent_1.0.12_aarch64.dmg → ZTeamAgent.app）
    cat > "$STAGING_DIR/install.command" << 'INSTALL_EOF'
#!/bin/bash
# 安装引导脚本
# 自动清除 macOS quarantine 隔离属性并打开 DMG

set -e
cd "$(dirname "$0")"

echo "=========================================="
echo "  安装引导"
echo "=========================================="
echo ""
echo "正在清除隔离属性..."
xattr -cr . 2>/dev/null || true
echo "完成！"
echo ""
echo "正在打开安装包..."
open *.dmg
echo ""
DMG_FILE=$(ls *.dmg 2>/dev/null | head -1)
if [ -n "$DMG_FILE" ]; then
    APP_NAME="${DMG_FILE%.dmg}.app"
    echo "请在弹出的窗口中，将 ${APP_NAME} 拖到 Applications 文件夹。"
fi
# 保持终端窗口打开 3 秒
sleep 3
INSTALL_EOF
    chmod +x "$STAGING_DIR/install.command"

    # 在 staging dir 内部打包，避免目录嵌套
    # zip 落在 bundle 目录而不是 dist/：vite build 的 emptyOutDir 会清空 dist/，
    # 下一次 tauri build（beforeBuildCommand 跑 npm run build）会把这里的 zip 一起抹掉
    BUNDLE_DIR="$DESK_DIR/src-tauri/target/release/bundle"
    mkdir -p "$BUNDLE_DIR"
    rm -f "$BUNDLE_DIR/$ZIP_NAME"
    (cd "$STAGING_DIR" && zip -r -X "$BUNDLE_DIR/$ZIP_NAME" . 2>/dev/null)
    # 清除 zip 自身的扩展属性
    xattr -cr "$BUNDLE_DIR/$ZIP_NAME" 2>/dev/null || true
    rm -rf "$STAGING_DIR"

    ZIP_SIZE=$(ls -lh "$BUNDLE_DIR/$ZIP_NAME" | awk '{print $5}')
    echo ""
    echo "=========================================="
    echo "  SUCCESS!"
    echo "  DMG: $DMG_PATH"
    echo "  Size: $DMG_SIZE"
    echo "  ZIP: $BUNDLE_DIR/$ZIP_NAME"
    echo "  ZipSize: $ZIP_SIZE"
    echo "=========================================="
    echo ""
    echo "  直接分发 DMG：双击 dmg → 拖拽 app 到 Applications"
    echo "  通过 zip 分发：解压后双击 install.command 即可"
    echo "  运行方式：双击 ${PRODUCT_NAME}.app"
    echo "  Tauri 窗口自动打开 http://127.0.0.1:28080/workspace"
else
    echo ""
    echo "  DMG not found in src-tauri/target/release/bundle/dmg/"
    echo "  Check build output above for errors."
    exit 1
fi
