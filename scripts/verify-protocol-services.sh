#!/usr/bin/env bash
# 仅创建和清理本次验收的独立资源，不读取或修改业务容器。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_id="ops-protocol-it-$(date +%Y%m%d%H%M%S)-$$-${RANDOM}"
report_dir="${PROTOCOL_IT_REPORT_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/ops-protocol-it.XXXXXX")}"
mkdir -p "$report_dir"
exec > >(tee -a "$report_dir/run.log") 2>&1

export PROBE_IT_ENABLED=true
export PROBE_IT_USER=inspector
export PROBE_IT_PASSWORD=opsProbeTestOnly2026X
export PROBE_IT_WRONG_PASSWORD=incorrectProbeOnly2026X
export PROBE_IT_MINIO_USER=inspectionadmin
export PROBE_IT_BUCKET=inspection-verification
network_created=0
unavailable=0
containers=()

log() { printf '[真实产品验收] %s\n' "$*"; }
redact() { sed -e "s/${PROBE_IT_PASSWORD}/[已隐藏测试凭据]/g" -e "s/${PROBE_IT_WRONG_PASSWORD}/[已隐藏错误测试凭据]/g"; }

cleanup() {
  local original_status=$?
  trap - EXIT INT TERM
  set +e
  log "清理本次资源：${run_id}"
  for container in "${containers[@]}"; do
    local actual_label container_id
    read -r actual_label container_id <<< "$(docker inspect -f '{{index .Config.Labels "ops.protocol.verify.run"}} {{.Id}}' "$container" 2>/dev/null)"
    if [[ "$actual_label" != "$run_id" || -z "$container_id" ]]; then continue; fi
    # 双重核对本次标签，再按不可变容器 ID 清理，避免名称冲突误删其他资源。
    docker logs "$container_id" 2>&1 | redact > "$report_dir/${container}.log"
    docker rm -fv "$container_id" >> "$report_dir/cleanup.log" 2>&1
  done
  if [[ "$network_created" == 1 ]]; then docker network rm "$run_id" >> "$report_dir/cleanup.log" 2>&1; fi
  local remaining_containers remaining_networks
  local evidence_status=0
  remaining_containers="$(docker ps -aq --filter "label=ops.protocol.verify.run=${run_id}")" || evidence_status=1
  remaining_networks="$(docker network ls -q --filter "label=ops.protocol.verify.run=${run_id}")" || evidence_status=1
  if [[ "$evidence_status" == 0 && -z "$remaining_containers" && -z "$remaining_networks" ]]; then
    log "清理证据：本次标签的剩余容器=0，剩余网络=0" | tee -a "$report_dir/cleanup.log"
  else
    log "清理未完成；见 ${report_dir}/cleanup.log"
    original_status=1
  fi
  log "验收日志目录：${report_dir}"
  exit "$original_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 使用本地镜像并显式禁止拉取；各容器端口只发布到回环地址。
start_product() {
  local service=$1 image=$2
  shift 2
  local name="${run_id}-${service}"
  containers+=("$name")
  if ! docker run -d --pull never --name "$name" --network "$run_id" \
      --label "ops.protocol.verify.run=${run_id}" "$@" "$image" \
      > "$report_dir/${service}-start.log" 2>&1; then
    log "${service} 启动失败，详见 ${service}-start.log"
    return 1
  fi
}

start_with_command() {
  local service=$1 image=$2
  shift 2
  local name="${run_id}-${service}"
  containers+=("$name")
  if ! docker run -d --pull never --name "$name" --network "$run_id" \
      --label "ops.protocol.verify.run=${run_id}" "$@" \
      > "$report_dir/${service}-start.log" 2>&1; then
    log "${service} 启动失败，详见 ${service}-start.log"
    return 1
  fi
}

host_endpoint() {
  local service=$1 port=$2 published
  published="$(docker inspect -f "{{(index (index .NetworkSettings.Ports \"${port}/tcp\") 0).HostPort}}" "${run_id}-${service}")"
  printf '127.0.0.1:%s' "$published"
}

wait_ready() {
  local service=$1
  shift
  # 每项就绪最多等待约 90 秒；已启动的其他产品同时初始化。
  for ((attempt=0; attempt<45; attempt++)); do
    if "$@" > /dev/null 2>&1; then log "${service} 已就绪"; return 0; fi
    if [[ "$(docker inspect -f '{{.State.Running}}' "${run_id}-${service}" 2>/dev/null || true)" != true ]]; then break; fi
    sleep 2
  done
  log "${service} 未在预算内就绪；对应产品测试将跳过，脚本最终返回失败"
  unavailable=$((unavailable+1))
  return 1
}

has_log() { docker logs "${run_id}-$1" 2>&1 | rg -q "$2"; }

log "运行标识：${run_id}"
log "报告目录：${report_dir}"
docker info > /dev/null
docker network create --label "ops.protocol.verify.run=${run_id}" "$run_id" > "$report_dir/network.log"
network_created=1

start_product postgres postgres:16-alpine -p 127.0.0.1::5432 \
  -e POSTGRES_DB=inspection -e "POSTGRES_USER=$PROBE_IT_USER" -e "POSTGRES_PASSWORD=$PROBE_IT_PASSWORD" || true
start_with_command redis redis:latest -p 127.0.0.1::6379 -e "PROBE_IT_PASSWORD=$PROBE_IT_PASSWORD" \
  redis:latest redis-server --requirepass "$PROBE_IT_PASSWORD" --save '' --appendonly no || true
start_product mysql mysql:5.7 --platform linux/amd64 -p 127.0.0.1::3306 \
  -e "MYSQL_ROOT_PASSWORD=$PROBE_IT_PASSWORD" -e MYSQL_DATABASE=inspection \
  -e "MYSQL_USER=$PROBE_IT_USER" -e "MYSQL_PASSWORD=$PROBE_IT_PASSWORD" || true
start_product rabbit rabbitmq:3.13-management -p 127.0.0.1::5672 \
  -e "RABBITMQ_DEFAULT_USER=$PROBE_IT_USER" -e "RABBITMQ_DEFAULT_PASS=$PROBE_IT_PASSWORD" -e RABBITMQ_DEFAULT_VHOST=/inspection || true
start_with_command minio minio/minio:latest -p 127.0.0.1::9000 \
  -e "MINIO_ROOT_USER=$PROBE_IT_MINIO_USER" -e "MINIO_ROOT_PASSWORD=$PROBE_IT_PASSWORD" \
  minio/minio:latest server /data || true

# Kafka 必须公布宿主机可达端口；先获取一个回环随机端口，绑定冲突时明确失败。
kafka_port="$(python3 - <<'PY'
import socket
with socket.socket() as listener:
    listener.bind(('127.0.0.1', 0))
    print(listener.getsockname()[1])
PY
)"
start_product kafka m.daocloud.io/docker.io/apache/kafka:3.9.1 -p "127.0.0.1:${kafka_port}:9092" \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:${kafka_port}" \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e 'KAFKA_HEAP_OPTS=-Xms128m -Xmx384m' || true
start_with_command rocket m.daocloud.io/docker.io/apache/rocketmq:5.3.2 -p 127.0.0.1::9876 \
  -e 'JAVA_OPT_EXT=-Xms128m -Xmx256m -Xmn128m' \
  m.daocloud.io/docker.io/apache/rocketmq:5.3.2 sh mqnamesrv || true

if wait_ready postgres docker exec "${run_id}-postgres" pg_isready -U "$PROBE_IT_USER" -d inspection; then
  export PROBE_IT_POSTGRES_ENDPOINT="$(host_endpoint postgres 5432)"
fi
if wait_ready mysql docker exec "${run_id}-mysql" sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u"$MYSQL_USER" "$MYSQL_DATABASE" -Nse "SELECT 1"'; then
  export PROBE_IT_MYSQL_ENDPOINT="$(host_endpoint mysql 3306)"
fi
if wait_ready redis docker exec "${run_id}-redis" sh -c 'redis-cli --no-auth-warning -a "$PROBE_IT_PASSWORD" PING'; then
  export PROBE_IT_REDIS_ENDPOINT="$(host_endpoint redis 6379)"
fi
if wait_ready rabbit has_log rabbit 'Server startup complete'; then
  export PROBE_IT_RABBIT_ENDPOINT="$(host_endpoint rabbit 5672)"
fi
minio_url="http://$(host_endpoint minio 9000 2>/dev/null || true)"
if wait_ready minio curl -fsS --connect-timeout 1 --max-time 2 "${minio_url}/minio/health/live"; then
  # 仅向本次新建 MinIO 实例创建专用空桶；不触及已有对象或业务实例。
  setup_name="${run_id}-minio-setup"
  containers+=("$setup_name")
  if docker run --pull never --name "$setup_name" --network "$run_id" \
      --label "ops.protocol.verify.run=${run_id}" --entrypoint /bin/sh \
      -e "PROBE_IT_MINIO_USER=$PROBE_IT_MINIO_USER" -e "PROBE_IT_PASSWORD=$PROBE_IT_PASSWORD" \
      -e "PROBE_IT_BUCKET=$PROBE_IT_BUCKET" -e "PROBE_IT_MINIO_HOST=${run_id}-minio" \
      minio/mc:latest -c 'mc alias set product "http://${PROBE_IT_MINIO_HOST}:9000" "$PROBE_IT_MINIO_USER" "$PROBE_IT_PASSWORD" >/dev/null && mc mb "product/${PROBE_IT_BUCKET}" >/dev/null' \
      2>&1 | redact > "$report_dir/minio-setup.log"; then
    export PROBE_IT_MINIO_ENDPOINT="$(host_endpoint minio 9000)"
  else
    log 'MinIO 测试桶创建失败；见 minio-setup.log'
    unavailable=$((unavailable+1))
  fi
fi
if wait_ready kafka has_log kafka 'Kafka Server started'; then
  export PROBE_IT_KAFKA_ENDPOINT="$(host_endpoint kafka 9092)"
fi
if wait_ready rocket has_log rocket 'Name Server boot success'; then
  export PROBE_IT_ROCKET_ENDPOINT="$(host_endpoint rocket 9876)"
fi

if [[ -z "${JAVA_HOME:-}" && -d /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home ]]; then
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
fi
log '开始运行真实产品 IT（正确与错误认证），脱敏 Maven 输出保存到 maven.log'
set +e
# PgJDBC 与 Java 的 SOCKS 绕过规则只识别主机通配符；避免继承本机 CIDR 代理配置误伤回环测试。
mvn -f "$repo_root/api/pom.xml" '-DsocksNonProxyHosts=localhost|127.*|[::1]' -Dtest=RealProductProbeIT test 2>&1 | redact > "$report_dir/maven.log"
maven_status=${PIPESTATUS[0]}
set -e
rg 'Tests run:|BUILD SUCCESS|BUILD FAILURE|Total time:' "$report_dir/maven.log" || true
if [[ "$maven_status" != 0 || "$unavailable" != 0 ]]; then
  log "验收未全部通过：Maven退出码=${maven_status}，未就绪产品数=${unavailable}"
  exit 1
fi
log '所有真实产品测试通过'
