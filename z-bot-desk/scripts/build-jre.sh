#!/usr/bin/env bash
#
# 使用 jlink 构建最小化 JRE，嵌入 Tauri 应用包中。
#
# 用法：
#   bash scripts/build-jre.sh [jdk-home] [output-dir]
#
# 默认 JDK_HOME = $JAVA_HOME
# 默认 OUTPUT_DIR = src-tauri/resources/jre
set -euo pipefail

JDK_HOME="${1:-${JAVA_HOME:-}}"
OUTPUT_DIR="${2:-src-tauri/resources/jre}"

if [ -z "$JDK_HOME" ]; then
  echo "ERROR: JAVA_HOME not set and no jdk-home argument provided." >&2
  exit 1
fi

if [ ! -x "$JDK_HOME/bin/jlink" ]; then
  echo "ERROR: jlink not found at $JDK_HOME/bin/jlink" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT_DIR")"

# jlink --output 不允许目录已存在，先清理
if [ -d "$OUTPUT_DIR" ]; then
  rm -rf "$OUTPUT_DIR"
fi

echo "Building minimal JRE with jlink..."
echo "  JDK_HOME:   $JDK_HOME"
echo "  OUTPUT_DIR:  $OUTPUT_DIR"

# 确定平台后缀
OS="$(uname -s)"
case "$OS" in
  Darwin*) PLATFORM="macos" ;;
  Linux*)  PLATFORM="linux" ;;
  MINGW*|MSYS*|CYGWIN*) PLATFORM="windows" ;;
  *) echo "Unknown OS: $OS"; exit 1 ;;
esac

"$JDK_HOME/bin/jlink" \
  --module-path "$JDK_HOME/jmods" \
  --add-modules java.base,java.logging,java.net.http,java.naming,java.management,java.desktop,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported,jdk.httpserver \
  --output "$OUTPUT_DIR" \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --strip-debug

# jlink 在 legal/ 目录下生成符号链接，Tauri 打包时无法处理导致 Permission denied
# legal 目录仅为许可证文件，运行时不需要，直接删除
rm -rf "$OUTPUT_DIR/legal"

echo "Done. JRE built at: $OUTPUT_DIR"
