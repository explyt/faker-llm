#!/usr/bin/env bash
# Хвост лог-файла Faker LLM в реальном времени с подсветкой уровней.
# Полезен для background-папы: в foreground режиме (scripts/run.sh) stdout уже цветной
# благодаря logback %highlight().
#
# Использование:
#   scripts/logs.sh                       # tail -F logs/faker-llm.log (logback file appender)
#   scripts/logs.sh /path/to/log          # tail -F конкретного файла
#   LOG_FILE=... scripts/logs.sh          # то же через env
#
# Цвета:
#   ERROR — красный, WARN — жёлтый, INFO — зелёный, DEBUG — циан.
set -euo pipefail

cd "$(dirname "$0")/.."

LOG_FILE="${1:-${LOG_FILE:-logs/faker-llm.log}}"

if [ ! -f "$LOG_FILE" ]; then
  echo "[logs.sh] $LOG_FILE not found."
  echo "[logs.sh] Start server first: scripts/run-background.sh"
  echo "[logs.sh] Or pass a custom path: scripts/logs.sh /path/to/log"
  exit 1
fi

echo "[logs.sh] tailing $LOG_FILE — Ctrl-C to stop"
echo

# tail -F follows the file across rotations (vs -f which sticks to inode).
# awk-colorizer matches the SLF4J/logback level tokens emitted by logback.xml:
#   %-5level → ERROR / WARN  / INFO  / DEBUG (5-char left-padded)
tail -F "$LOG_FILE" 2>/dev/null | awk '
  BEGIN {
    RED  = "\033[1;31m"; YEL = "\033[1;33m"; GRN = "\033[1;32m"
    CYN  = "\033[1;36m"; GRY = "\033[0;90m"; RST = "\033[0m"
  }
  {
    # подсветка level-токенов (учитываем 5-символьное выравнивание logback)
    gsub(/ ERROR /, " " RED "ERROR" RST " ")
    gsub(/ WARN  /, " " YEL "WARN " RST " ")
    gsub(/ INFO  /, " " GRN "INFO " RST " ")
    gsub(/ DEBUG /, " " CYN "DEBUG" RST " ")
    # приглушаем timestamp в начале строки (первые 12 символов HH:mm:ss.SSS)
    if (match($0, /^[0-9][0-9]:[0-9][0-9]:[0-9][0-9]\.[0-9][0-9][0-9]/)) {
      ts = substr($0, RSTART, RLENGTH)
      rest = substr($0, RSTART + RLENGTH)
      print GRY ts RST rest
    } else {
      print
    }
  }
'
