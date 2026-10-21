<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type MigrationItem, type MigrationProposal, type Project } from './api'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ projects: Project[]; serviceId: number | null }>()
const emit = defineEmits<{ changed: [] }>()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const items = ref<MigrationItem[]>([])
const proposals = ref<MigrationProposal[]>([])
const focusServiceId = ref<number | null>(null)
const intervals = reactive<Record<number, number | undefined>>({})
const candidates = computed(() => items.value.filter(item => item.status !== 'ALREADY_MANAGED'
  && (!focusServiceId.value || item.serviceId === focusServiceId.value)))
const pending = computed(() => proposals.value.filter(item => item.status === 'PENDING_CONFIRMATION'
  && (!focusServiceId.value || item.assetId === focusServiceId.value)))
const eligibleReasons = new Set(['UNIQUE_MONITOR_WITHOUT_PLAN', 'LEGACY_PLAN_WITHOUT_MONITOR',
  'FREQUENCY_CONFLICT', 'LEGACY_MONITOR_MATCHED', 'MONITOR_MISSING', 'UNSUPPORTED_LEGACY_SCHEDULE'])
const reasons: Record<string, string> = {
  UNIQUE_MONITOR_WITHOUT_PLAN: '已有独占监控，尚无计划', LEGACY_PLAN_WITHOUT_MONITOR: '旧计划尚未接入监控',
  FREQUENCY_CONFLICT: '计划与监控频率冲突', LEGACY_MONITOR_MATCHED: '旧计划与监控频率一致',
  MONITOR_MISSING: '外部监控已不存在', UNSUPPORTED_LEGACY_SCHEDULE: '旧计划频率需重新选择',
  MULTIPLE_LEGACY_PLANS: '命中多个启用旧计划，请先处理归属', DUPLICATE_MONITOR: '多个服务共用监控，禁止认领',
  EXTERNAL_UNAVAILABLE: '暂时无法核实外部配置', UNCONFIGURED: '未配置，请创建普通巡检计划',
}

watch(visible, async value => {
  if (!value) return
  focusServiceId.value = props.serviceId
  await refresh()
})

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [report, saved] = await Promise.all([api.migrationReport(), api.migrationProposals()])
    items.value = report.items
    proposals.value = saved
    for (const item of report.items) {
      intervals[item.serviceId] = ['FREQUENCY_CONFLICT', 'UNSUPPORTED_LEGACY_SCHEDULE'].includes(item.reason)
        ? undefined : item.monitorIntervalSeconds ?? item.legacyIntervalSeconds ?? undefined
    }
  } catch (reason) {
    items.value = []
    proposals.value = []
    error.value = reason instanceof Error ? reason.message : '迁移报告加载失败'
  } finally { loading.value = false }
}

function canPrepare(item: MigrationItem) {
  return eligibleReasons.has(item.reason)
    && !proposals.value.some(proposal => proposal.assetId === item.serviceId && proposal.status === 'PENDING_CONFIRMATION')
}

async function prepare(item: MigrationItem) {
  const project = props.projects.find(project => project.name === item.projectName)
  const interval = intervals[item.serviceId]
  if (!project) { ElMessage.warning('请先补齐项目档案'); return }
  if (!Number.isInteger(interval) || !interval || interval < 10 || interval > 86400) {
    ElMessage.warning('请明确选择10秒至24小时之间的整数频率'); return
  }
  busy.value = true
  try {
    await api.prepareMigration(item.serviceId, project.id, interval)
    ElMessage.success('已创建待确认计划，尚未修改监控配置')
    await refresh()
    emit('changed')
  } catch (reason) { ElMessage.error(reason instanceof Error ? reason.message : '迁移准备失败') }
  finally { busy.value = false }
}

async function act(proposal: MigrationProposal, action: 'confirm' | 'cancel') {
  try {
    await ElMessageBox.confirm(action === 'confirm'
      ? `确认以每${proposal.intervalSeconds}秒的频率接管“${proposal.planName}”？将自动同步监控和告警规则，成功前保留旧调度。`
      : '取消待确认计划并释放服务预留，不删除现有外部监控。', action === 'confirm' ? '确认迁移' : '取消迁移', { type: 'warning' })
  } catch { return }
  busy.value = true
  try {
    if (action === 'confirm') await api.confirmMigration(proposal.id)
    else await api.cancelMigration(proposal.id)
    ElMessage.success(action === 'confirm' ? '迁移已确认，请在计划卡片查看同步结果' : '迁移已取消，可重新准备')
    await refresh()
    emit('changed')
  } catch (reason) { ElMessage.error(reason instanceof Error ? reason.message : '迁移操作失败') }
  finally { busy.value = false }
}
</script>

<template>
  <el-drawer v-model="visible" title="存量监控迁移" size="min(760px, 100%)" append-to-body>
    <div v-loading="loading" class="migration-content">
      <el-alert title="先分析、再准备、最后确认。确认前保留原监控频率与旧调度；频率冲突必须明确选择。" type="info" :closable="false" />
      <div class="migration-toolbar"><el-button :disabled="loading || busy" @click="refresh">重新分析</el-button><el-button v-if="focusServiceId" link @click="focusServiceId = null">查看全部服务</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <section v-if="pending.length"><h3>待确认计划</h3><article v-for="proposal in pending" :key="proposal.id" class="migration-card"><strong>{{ proposal.planName }}</strong><p>每 {{ proposal.intervalSeconds }} 秒 · 尚未下发配置</p><el-button type="primary" :disabled="busy || loading" @click="act(proposal, 'confirm')">确认接管</el-button><el-button :disabled="busy || loading" @click="act(proposal, 'cancel')">取消迁移</el-button></article></section>
      <section><h3>迁移分析</h3><article v-for="item in candidates" :key="item.serviceId" class="migration-card"><strong>{{ item.projectName }} · {{ item.serviceName }}</strong><p>{{ reasons[item.reason] || item.reason }}</p><p>旧计划：{{ item.legacyIntervalSeconds ? `${item.legacyIntervalSeconds} 秒` : '无固定频率' }} · Monitor：{{ item.monitorIntervalSeconds ? `${item.monitorIntervalSeconds} 秒` : '无可验证频率' }}</p><p>{{ item.action }}</p><template v-if="canPrepare(item)"><div class="migration-interval"><el-input-number v-model="intervals[item.serviceId]" :min="10" :max="86400" :precision="0" placeholder="明确选择频率" /><span>秒</span><el-button :disabled="busy || loading" @click="prepare(item)">准备待确认计划</el-button></div><div v-if="item.reason === 'FREQUENCY_CONFLICT'" class="migration-toolbar"><el-button link :disabled="busy" @click="intervals[item.serviceId] = item.legacyIntervalSeconds ?? undefined">选用旧计划频率</el-button><el-button link :disabled="busy" @click="intervals[item.serviceId] = item.monitorIntervalSeconds ?? undefined">选用监控频率</el-button></div></template><el-tag v-else-if="eligibleReasons.has(item.reason)" type="warning">已有待确认计划</el-tag></article><el-empty v-if="!loading && !error && !candidates.length" description="没有待迁移服务" /></section>
    </div>
  </el-drawer>
</template>

<style scoped>
.migration-content { display: grid; gap: 16px; }
.migration-toolbar, .migration-interval { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.migration-card { padding: 16px; margin: 12px 0; border: 1px solid #e5e9f0; border-radius: 10px; }
.migration-card p { color: #68778a; line-height: 1.6; overflow-wrap: anywhere; }
</style>
