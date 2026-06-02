#!/usr/bin/env bash
# Host-level setup для Ubuntu сервера под Faker LLM нагрузку 3k+ RPS.
#
# Запуск: sudo bash scripts/prod-host-setup.sh
#
# Что делает:
#   1. /etc/sysctl.d/99-faker.conf  — sysctl: FD limits, somaxconn, TCP buffers, TIME_WAIT reuse
#   2. /etc/security/limits.d/99-faker.conf — ulimit для login-сессий
#   3. /etc/systemd/system/docker.service.d/override.conf — ulimit для docker daemon
#   4. systemctl restart docker — применить ulimit override
#
# Идемпотентен: повторный запуск только перезатирает файлы и рестартит docker.
#
# Что НЕ делает (это твоя ответственность):
#   - не ставит docker — должен быть уже установлен
#   - не выполняет re-login юзеров для применения /etc/security/limits.d
#   - не настраивает firewall / network interfaces

set -euo pipefail

if [ "$EUID" -ne 0 ]; then
  echo "[setup] this script must be run as root (try: sudo bash $0)" >&2
  exit 1
fi

echo "[setup] === Faker LLM host setup for high-RPS load ==="

# --- 1) sysctl ----------------------------------------------------------------
echo "[setup] writing /etc/sysctl.d/99-faker.conf..."
cat > /etc/sysctl.d/99-faker.conf <<'EOF'
# Faker LLM: kernel knobs for 3k+ RPS streaming workload.
# Применяется через: sudo sysctl --system

# Лимиты файловых дескрипторов на уровне ядра
fs.file-max = 2000000
fs.nr_open = 2000000

# Очередь accept() — без этого под burst'ами получим connection refused
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535

# Эфемерные порты под исходящие соединения
net.ipv4.ip_local_port_range = 1024 65535

# TIME_WAIT recycle — на 3k+ RPS забивает портовое пространство
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 15

# TCP буферы под long-running SSE streams (deepseek-пул держит коннект 5-15s)
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 65536 16777216
EOF

echo "[setup] applying sysctl..."
sysctl --system >/dev/null

# --- 2) limits.d --------------------------------------------------------------
echo "[setup] writing /etc/security/limits.d/99-faker.conf..."
cat > /etc/security/limits.d/99-faker.conf <<'EOF'
# Faker LLM: file-descriptor / process limits для login-сессий
# Требуется re-login или reboot для применения к существующим сессиям.

* soft nofile 1048576
* hard nofile 1048576
* soft nproc 65535
* hard nproc 65535
root soft nofile 1048576
root hard nofile 1048576
EOF

# --- 3) docker.service override ----------------------------------------------
echo "[setup] writing /etc/systemd/system/docker.service.d/override.conf..."
mkdir -p /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/docker.service.d/override.conf <<'EOF'
# Faker LLM: docker daemon должен иметь высокий FD limit, иначе он обрежет
# ulimits.nofile в docker-compose.yml до своего значения.
[Service]
LimitNOFILE=1048576
LimitNPROC=65535
EOF

# --- 4) Restart docker --------------------------------------------------------
echo "[setup] reloading systemd + restarting docker..."
systemctl daemon-reload
if systemctl is-active --quiet docker; then
  systemctl restart docker
  echo "[setup] docker restarted"
else
  echo "[setup] docker is not running — start it manually after setup (systemctl start docker)"
fi

# --- Summary ------------------------------------------------------------------
echo ""
echo "[setup] === final state ==="
echo "[setup] ulimit -n (root shell): $(ulimit -n)"
echo "[setup] fs.file-max: $(cat /proc/sys/fs/file-max)"
echo "[setup] net.core.somaxconn: $(cat /proc/sys/net/core/somaxconn)"
echo "[setup] docker status: $(systemctl is-active docker 2>/dev/null || echo 'not running')"
echo ""
echo "[setup] === DONE. Next steps ==="
echo "[setup]   1) Re-login or reboot to apply /etc/security/limits.d to non-systemd processes"
echo "[setup]   2) Verify in container:"
echo "[setup]        docker run --rm faker-llm:0.1.0 sh -c 'ulimit -n'"
echo "[setup]      Expected output: 1048576"
echo "[setup]   3) Build & start:"
echo "[setup]        FAKER_POOL_DIR=pool-deepseek scripts/docker.sh build"
echo "[setup]        FAKER_POOL_DIR=pool-deepseek scripts/docker.sh up"
echo "[setup]   4) Smoke test:"
echo "[setup]        scripts/docker.sh smoke"
