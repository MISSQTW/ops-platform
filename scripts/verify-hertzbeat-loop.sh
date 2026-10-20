#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8088}"
HERTZBEAT_URL="${HERTZBEAT_URL:-http://localhost:1157}"
OPS_USERNAME="${OPS_USERNAME:-admin}"
OPS_PASSWORD="${OPS_PASSWORD:-admin123}"
HERTZBEAT_USERNAME="${HERTZBEAT_USERNAME:-admin}"
HERTZBEAT_PASSWORD="${HERTZBEAT_PASSWORD:-hertzbeat}"
HERTZBEAT_CONTAINER="${HERTZBEAT_CONTAINER:-ops-platform-hertzbeat-1}"
API_CONTAINER="${API_CONTAINER:-ops-platform-api-1}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-ops-platform-postgres-1}"
VERIFY_OUTAGE="${VERIFY_OUTAGE:-1}"
SERVICE_TYPE="${SERVICE_TYPE:-HTTP}"
FAILURE_THRESHOLD="${FAILURE_THRESHOLD:-1}"

suffix="$(date +%Y%m%d%H%M%S)"
target_container="ops-loop-target-${suffix}"
target_created=0
project_name="闭环验证-${suffix}"
asset_name="闭环服务-${suffix}"
plan_name="闭环计划-${suffix}"
project_id=""
asset_id=""
plan_id=""
alert_id=""
hertzbeat_paused=0

log() {
  printf '[e2e] %s\n' "$*"
}

curl() {
  command curl --connect-timeout 5 --max-time 60 "$@"
}

api() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  if [[ -n "$body" ]] && jq -e '.mode == "MONITORING"' <<<"$body" >/dev/null; then
    body="$(jq --argjson threshold "$FAILURE_THRESHOLD" '.failureThreshold = $threshold' <<<"$body")"
  fi
  if [[ -n "$body" ]]; then
    curl -fsS --max-time 60 -X "$method" "${BASE_URL}/api${path}" \
      -H "Authorization: Bearer ${ops_token}" \
      -H 'Content-Type: application/json' \
      -d "$body"
  else
    curl -fsS --max-time 60 -X "$method" "${BASE_URL}/api${path}" \
      -H "Authorization: Bearer ${ops_token}"
  fi
}

wait_for() {
  local description="$1"
  local attempts="$2"
  shift 2
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if "$@"; then
      log "通过：${description}"
      return 0
    fi
    sleep 5
  done
  log "超时：${description}"
  return 1
}

plan_synced() {
  [[ "$(api GET "/inspection-plans/${plan_id}" | jq -r '.syncStatus')" == 'SYNCED' ]]
}

alert_firing() {
  local alert
  alert="$(api GET "/alerts?assetId=${asset_id}&externalLifecycle=FIRING" | jq -c 'map(select(.handlingStatus != "CLOSED")) | if length > 0 then .[0] else empty end')"
  [[ -n "$alert" && "$alert" != 'null' ]] || return 1
  alert_id="$(jq -r '.id' <<<"$alert")"
  [[ "$alert_id" =~ ^[0-9]+$ ]]
}

alert_resolved() {
  [[ "$(api GET "/alerts/${alert_id}" | jq -r '.externalLifecycle')" == 'RESOLVED' ]]
}

plan_deleted() {
  [[ "$(api GET "/inspection-plans/${plan_id}" | jq -r '.lifecycleStatus')" == 'DELETED' ]]
}

cleanup() {
  local cleanup_failed=0
  if [[ "$hertzbeat_paused" == '1' ]]; then
    docker unpause "$HERTZBEAT_CONTAINER" >/dev/null 2>&1 || cleanup_failed=1
  fi
  if [[ "$target_created" == '1' ]]; then
    docker rm -f "$target_container" >/dev/null 2>&1 || cleanup_failed=1
  fi
  if [[ "$cleanup_failed" != '0' ]]; then return 1; fi
  if [[ -n "$plan_id" ]]; then
    api DELETE "/monitoring-plans/${plan_id}" >/dev/null 2>&1
    for _ in {1..12}; do
      api POST "/monitoring-plans/${plan_id}/sync" >/dev/null 2>&1
      plan_deleted && break
      sleep 2
    done
    if ! plan_deleted; then
      log "清理未完成，保留重试载体：plan=${plan_id} asset=${asset_id} project=${project_id}"
      return 1
    fi
  fi
  if [[ -n "$asset_id" ]]; then
    api DELETE "/assets/${asset_id}" >/dev/null 2>&1 || return 1
  fi
  if [[ -n "$project_id" ]]; then
    local db_name
    db_name="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$API_CONTAINER" 2>/dev/null | sed -n 's#^DB_URL=.*/##p' | head -1)"
    if [[ -n "$db_name" ]]; then
      docker exec -i "$POSTGRES_CONTAINER" psql -U ops -d "$db_name" -v ON_ERROR_STOP=1 \
        -v plan_id="${plan_id:-0}" -v asset_id="${asset_id:-0}" -v project_id="$project_id" \
        >/dev/null <<'SQL'
BEGIN;
DELETE FROM ops_alert_handling_event WHERE alert_id IN (
  SELECT id FROM ops_alert WHERE asset_id = :'asset_id'
);
DELETE FROM ops_alert WHERE asset_id = :'asset_id';
DELETE FROM ops_monitoring_config_task WHERE plan_id = :'plan_id';
DELETE FROM ops_inspection_plan_binding WHERE plan_id = :'plan_id' OR asset_id = :'asset_id';
DELETE FROM ops_inspection_plan WHERE id = :'plan_id';
DELETE FROM ops_asset WHERE id = :'asset_id';
DELETE FROM ops_project WHERE id = :'project_id';
COMMIT;
SQL
      return $?
    fi
    return 1
  fi
  return 0
}

finish() {
  local result=$?
  trap - EXIT
  if ! cleanup; then
    log "清理失败，补偿信息：project=${project_id:-未创建} asset=${asset_id:-未创建} plan=${plan_id:-未创建} target=${target_container} hertzbeat=${HERTZBEAT_CONTAINER}"
    exit 1
  fi
  if [[ "$result" == '0' ]]; then log '真实 Docker 端到端闭环与清理验证通过'; fi
  exit "$result"
}
trap finish EXIT

log '登录平台与 HertzBeat'
ops_token="$(curl -fsS -X POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg username "$OPS_USERNAME" --arg password "$OPS_PASSWORD" '{username:$username,password:$password}')" \
  | jq -r '.token')"
hertzbeat_token="$(curl -fsS -X POST "${HERTZBEAT_URL}/api/account/auth/form" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg identifier "$HERTZBEAT_USERNAME" --arg credential "$HERTZBEAT_PASSWORD" '{identifier:$identifier,credential:$credential}')" \
  | jq -r '.data.token')"
[[ -n "$ops_token" && "$ops_token" != 'null' && -n "$hertzbeat_token" && "$hertzbeat_token" != 'null' ]]

log '创建项目、服务和 10 秒巡检计划'
docker run -d --name "$target_container" --network ops-platform_default --entrypoint nginx ops-platform-web -g 'daemon off;' >/dev/null
target_created=1
project_id="$(api POST '/projects' "$(jq -nc --arg name "$project_name" '{name:$name,projectType:"验证系统",owner:"Codex",contactPhone:"",maintenanceExpiresAt:null,description:"HertzBeat 闭环自动验证",status:"RUNNING"}')" | jq -r '.id')"
asset_id="$(api POST '/assets' "$(jq -nc --arg name "$asset_name" --arg project "$project_name" --arg type "$SERVICE_TYPE" --arg endpoint "http://${target_container}:80/" '{name:$name,projectName:$project,environmentName:"测试",type:$type,endpoint:$endpoint,status:"UNKNOWN"}')" | jq -r '.id')"
plan_id="$(api POST '/inspection-plans' "$(jq -nc --arg name "$plan_name" --arg project "$project_name" --argjson projectId "$project_id" --argjson assetId "$asset_id" '{name:$name,projectId:$projectId,projectName:$project,status:"ENABLED",intervalSeconds:10,failureThreshold:1,alertLevel:"CRITICAL",assetIds:[$assetId],mode:"MONITORING"}')" | jq -r '.id')"
wait_for '保存计划后自动同步成功' 18 plan_synced

asset="$(api GET '/assets' | jq -c --argjson id "$asset_id" 'map(select(.id == $id)) | first')"
monitor_id="$(jq -r '.hertzbeatMonitorId' <<<"$asset")"
[[ "$monitor_id" != 'null' ]]

log '校验 HertzBeat Monitor 频率与受管告警规则'
monitor="$(curl -fsS "${HERTZBEAT_URL}/api/monitor/${monitor_id}" -H "Authorization: Bearer ${hertzbeat_token}")"
[[ "$(jq -r '.data.monitor.intervals' <<<"$monitor")" == '10' ]]
[[ "$(jq -r '.data.monitor.status' <<<"$monitor")" == '1' ]]
rule="$(curl -fsS "${HERTZBEAT_URL}/api/alert/defines?pageIndex=0&pageSize=100" -H "Authorization: Bearer ${hertzbeat_token}" \
  | jq -c --arg planId "$plan_id" --arg assetId "$asset_id" '.data.content | map(select(.labels.managedBy == "ops-platform" and .labels.planId == $planId and .labels.assetId == $assetId)) | first')"
[[ "$(jq -r '.period' <<<"$rule")" == '10' ]]
[[ "$(jq -r '.times' <<<"$rule")" == "$FAILURE_THRESHOLD" ]]
[[ "$(jq -r '.enable' <<<"$rule")" == 'true' ]]

log '修改频率并校验 HertzBeat 漂移修复'
api PUT "/monitoring-plans/${plan_id}" "$(jq -nc --arg name "$plan_name" --arg project "$project_name" --argjson projectId "$project_id" --argjson assetId "$asset_id" '{name:$name,projectId:$projectId,projectName:$project,status:"ENABLED",intervalSeconds:20,failureThreshold:1,alertLevel:"CRITICAL",assetIds:[$assetId],mode:"MONITORING"}')" >/dev/null
api POST "/monitoring-plans/${plan_id}/sync" >/dev/null
wait_for '20 秒频率下发成功' 12 plan_synced
monitor="$(curl -fsS "${HERTZBEAT_URL}/api/monitor/${monitor_id}" -H "Authorization: Bearer ${hertzbeat_token}")"
[[ "$(jq -r '.data.monitor.intervals' <<<"$monitor")" == '20' ]]

log '恢复 10 秒频率并制造真实不可用告警'
api PUT "/monitoring-plans/${plan_id}" "$(jq -nc --arg name "$plan_name" --arg project "$project_name" --argjson projectId "$project_id" --argjson assetId "$asset_id" '{name:$name,projectId:$projectId,projectName:$project,status:"ENABLED",intervalSeconds:10,failureThreshold:1,alertLevel:"CRITICAL",assetIds:[$assetId],mode:"MONITORING"}')" >/dev/null
docker stop "$target_container" >/dev/null
api POST "/monitoring-plans/${plan_id}/sync" >/dev/null
found_alert=0
for _ in {1..36}; do
  api POST '/integrations/hertzbeat/monitoring/sync' >/dev/null || true
  if alert_firing; then
    found_alert=1
    break
  fi
  sleep 5
done
if [[ "$found_alert" != '1' || ! "$alert_id" =~ ^[0-9]+$ ]]; then
  log '未在等待窗口内捕获告警，输出诊断信息'
  api GET '/assets' | jq --argjson id "$asset_id" 'map(select(.id == $id)) | first'
  curl -fsS "${HERTZBEAT_URL}/api/alerts?status=firing&pageIndex=0&pageSize=100" -H "Authorization: Bearer ${hertzbeat_token}" | jq '.' || true
  exit 1
fi
log "捕获真实告警 ${alert_id}，执行人工确认"
api POST "/alerts/${alert_id}/acknowledge" '{"note":"端到端验证确认"}' >/dev/null

log '恢复服务并等待 HertzBeat 返回 resolved'
docker start "$target_container" >/dev/null
api POST "/monitoring-plans/${plan_id}/sync" >/dev/null
for _ in {1..24}; do
  api POST '/integrations/hertzbeat/monitoring/sync' >/dev/null || true
  alert_resolved && break
  sleep 5
done
alert_resolved
api POST "/alerts/${alert_id}/close" '{"note":"端到端验证关闭"}' >/dev/null
[[ "$(api GET "/alerts/${alert_id}" | jq -r '.handlingStatus')" == 'CLOSED' ]]

if [[ "$VERIFY_OUTAGE" == '1' ]]; then
  log '暂停 HertzBeat，验证同步失败与恢复重试'
  docker pause "$HERTZBEAT_CONTAINER" >/dev/null
  hertzbeat_paused=1
  api PUT "/monitoring-plans/${plan_id}" "$(jq -nc --arg name "$plan_name" --arg project "$project_name" --argjson projectId "$project_id" --argjson assetId "$asset_id" '{name:$name,projectId:$projectId,projectName:$project,status:"ENABLED",intervalSeconds:12,failureThreshold:1,alertLevel:"CRITICAL",assetIds:[$assetId],mode:"MONITORING"}')" >/dev/null
  outage_result="$(api POST "/monitoring-plans/${plan_id}/sync")"
  [[ "$(jq -r '.status' <<<"$outage_result")" == 'FAILED' ]]
  docker unpause "$HERTZBEAT_CONTAINER" >/dev/null
  hertzbeat_paused=0
  sleep 18
  api POST "/monitoring-plans/${plan_id}/sync" >/dev/null
  wait_for 'HertzBeat 恢复后同步成功' 12 plan_synced
fi

log '校验迁移报告与受管状态'
migration_item="$(api GET '/inspection-plans/migration-report' | jq -c --argjson id "$asset_id" '.items | map(select(.serviceId == $id)) | first')"
[[ "$(jq -r '.status' <<<"$migration_item")" == 'ALREADY_MANAGED' ]]

log '请求删除计划并验证外部资源清理'
api DELETE "/monitoring-plans/${plan_id}" >/dev/null
for _ in {1..18}; do
  api POST "/monitoring-plans/${plan_id}/sync" >/dev/null || true
  plan_deleted && break
  sleep 3
done
plan_deleted
for resource in "monitor/${monitor_id}" "alert/define/$(jq -r '.id' <<<"$rule")"; do
  response="$(curl -sS -w '\n%{http_code}' "${HERTZBEAT_URL}/api/${resource}" -H "Authorization: Bearer ${hertzbeat_token}")"
  http_status="${response##*$'\n'}"
  response_body="${response%$'\n'*}"
  if [[ "$http_status" == '404' ]]; then continue; fi
  if [[ "$http_status" != '200' ]] || ! jq -e '.code != 0 and ((.msg // "") | test("not exist"; "i"))' <<<"$response_body" >/dev/null; then
    log "外部资源删除未获确认：${resource} HTTP ${http_status}"
    exit 1
  fi
done

log '闭环主体通过，开始清理并核验结果'
