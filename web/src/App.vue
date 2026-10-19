<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Activity, AlertTriangle, ArrowLeft, BellRing, CalendarClock, CheckCircle2, ChevronRight, CircleGauge, Clock3, CloudCog, Database, ExternalLink, FolderKanban, HardDrive, ListChecks, LogOut, Pencil, Play, Plus, Power, RefreshCw, Search, Server, Settings, ShieldCheck, Trash2, XCircle } from '@lucide/vue'
import { api, setAuthToken, type Alert, type Asset, type Inspection, type InspectionItem, type InspectionPlan, type MonitoringDetails, type MonitoringSyncRun, type Project, type Summary } from './api'

type ViewKey = 'dashboard' | 'projects' | 'assets' | 'inspections' | 'alerts'
type AssetCategoryKey = 'server' | 'database' | 'middleware' | 'interfaceStorage'

const activeView = ref<ViewKey>('dashboard')
const authenticated = ref(false)
const authLoading = ref(true)
const loginLoading = ref(false)
const currentUser = reactive({ username: '', displayName: '', canManageIntegrations: false })
const loginForm = reactive({ username: 'admin', password: '' })
const loading = ref(true)
const error = ref('')
const keyword = ref('')
const lastUpdated = ref('')
const summary = ref<Summary>({ projectCount: 0, assetCount: 0, abnormalAssetCount: 0, openAlertCount: 0, inspectionPassRate: 0 })
const projects = ref<Project[]>([])
const assets = ref<Asset[]>([])
const inspections = ref<Inspection[]>([])
const inspectionPlans = ref<InspectionPlan[]>([])
const inspectionItems = ref<InspectionItem[]>([])
const inspectionSection = ref<'plans' | 'results'>('plans')
const selectedInspectionPlan = ref<InspectionPlan | null>(null)
const inspectionPage = ref(1)
const inspectionPageSize = 10
const inspectionDrawerVisible = ref(false)
const selectedInspection = ref<Inspection | null>(null)
const inspectionDetailLoading = ref(false)
const alerts = ref<Alert[]>([])
const selectedProjectName = ref('')
const globalListSource = ref(false)
const serviceListMode = ref<'all' | 'abnormal'>('all')
const serviceCategory = ref<AssetCategoryKey | null>(null)
const pendingAlertsOnly = ref(false)
const hertzBeatConnected = ref(false)
const editorVisible = ref(false)
const editorMode = ref<'project' | 'asset' | 'plan'>('project')
const editingId = ref<number | null>(null)
const saving = ref(false)
const runningPlanId = ref<number | null>(null)
const syncingAssetId = ref<number | null>(null)
const syncingAllAssets = ref(false)
const syncingMonitoring = ref(false)
const monitoringRuns = ref<MonitoringSyncRun[]>([])
const monitoringDrawerVisible = ref(false)
const monitoringDetailLoading = ref(false)
const monitoringDetails = ref<MonitoringDetails | null>(null)
const projectForm = reactive({ name: '', owner: '', description: '', status: 'RUNNING' })
const assetForm = reactive({ name: '', projectName: '', environmentName: '生产', type: 'SERVER', endpoint: '', status: 'UNKNOWN' })
const planForm = reactive({ name: '', projectName: '', scopeText: '', scheduleText: '每 5 分钟', status: 'ENABLED', nextRunAt: '' })
const scheduleForm = reactive({ mode: 'INTERVAL', intervalValue: 5, intervalUnit: '分钟', dailyTime: '09:00', cronExpression: '0 0 9 * * *' })
const environments = ['生产', '预发布', '测试', '开发']
const assetTypes = ['SERVER', 'MYSQL', 'POSTGRESQL', 'REDIS', 'KAFKA', 'RABBITMQ', 'ROCKETMQ', 'MINIO', 'HTTP']
const assetCategories = [
  { key: 'server' as const, label: '服务器', title: '服务器服务', types: ['SERVER'], icon: Server },
  { key: 'database' as const, label: '数据库', title: '数据库服务', types: ['MYSQL', 'POSTGRESQL'], icon: Database },
  { key: 'middleware' as const, label: '缓存与队列', title: '缓存与队列服务', types: ['REDIS', 'KAFKA', 'RABBITMQ', 'ROCKETMQ'], icon: Activity },
  { key: 'interfaceStorage' as const, label: '接口与存储', title: '接口与存储服务', types: ['HTTP', 'MINIO'], icon: CloudCog },
]

const navItems = [
  { key: 'dashboard' as ViewKey, label: '运行总览', icon: CircleGauge },
  { key: 'projects' as ViewKey, label: '项目中心', icon: FolderKanban },
  { key: 'inspections' as ViewKey, label: '巡检中心', icon: ListChecks },
  { key: 'alerts' as ViewKey, label: '告警事件', icon: BellRing },
]

const titles: Record<ViewKey, { title: string; description: string }> = {
  dashboard: { title: '运行总览', description: '跨项目基础设施状态与风险概览' },
  projects: { title: '项目中心', description: '统一管理业务系统、负责人和运行状态' },
  assets: { title: '服务管理', description: '统一管理项目下的服务器、数据库、中间件和接口服务' },
  inspections: { title: '巡检中心', description: '跟踪自动巡检执行结果和检查覆盖率' },
  alerts: { title: '告警事件', description: '集中处理异常事件并持续跟踪恢复状态' },
}

const filteredProjects = computed(() => projects.value.filter(item => includesKeyword(item.name, item.owner, item.description)))
const activeServiceCategory = computed(() => assetCategories.find(item => item.key === serviceCategory.value) || null)
const assetCategoryStats = computed(() => assetCategories.map(category => {
  const categoryAssets = assets.value.filter(item => category.types.includes(item.type))
  const healthy = categoryAssets.filter(item => item.status === 'HEALTHY').length
  return { ...category, count: categoryAssets.length, healthy, percentage: categoryAssets.length ? (healthy / categoryAssets.length) * 100 : 0 }
}))
const currentTitle = computed(() => {
  if (activeView.value === 'assets' && selectedProjectName.value) return { title: `${selectedProjectName.value} · 服务管理`, description: '查看和维护当前项目接入的运行服务' }
  if (activeView.value === 'alerts' && selectedProjectName.value) return { title: `${selectedProjectName.value} · 告警事件`, description: '查看当前项目产生的运行告警' }
  if (activeView.value === 'assets' && serviceListMode.value === 'abnormal') return { title: '异常服务', description: '查看需要持续关注的运行服务' }
  if (activeView.value === 'assets' && activeServiceCategory.value) return { title: activeServiceCategory.value.title, description: `查看所有项目中的${activeServiceCategory.value.label}服务` }
  if (activeView.value === 'alerts' && pendingAlertsOnly.value) return { title: '待处理告警', description: '查看尚未恢复的运行告警' }
  return titles[activeView.value]
})
const filteredAssets = computed(() => assets.value.filter(item => (!selectedProjectName.value || item.projectName === selectedProjectName.value) && (serviceListMode.value === 'all' || item.status !== 'HEALTHY') && (!activeServiceCategory.value || activeServiceCategory.value.types.includes(item.type)) && includesKeyword(item.name, item.projectName, item.endpoint, item.type)))
const filteredInspections = computed(() => inspections.value.filter(item => includesKeyword(item.name, item.projectName)))
const selectedPlanInspections = computed(() => filteredInspections.value.filter(item => !selectedInspectionPlan.value || (item.name === selectedInspectionPlan.value.name && item.projectName === selectedInspectionPlan.value.projectName)))
const pagedPlanInspections = computed(() => selectedPlanInspections.value.slice((inspectionPage.value - 1) * inspectionPageSize, inspectionPage.value * inspectionPageSize))
const filteredAlerts = computed(() => {
  const severityOrder: Record<string, number> = { CRITICAL: 0, WARNING: 1, INFO: 2 }
  return alerts.value
    .filter(item => (!selectedProjectName.value || item.projectName === selectedProjectName.value) && (!pendingAlertsOnly.value || item.status === 'OPEN') && includesKeyword(item.message, item.projectName, item.targetName))
    .sort((left, right) => new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime() || (severityOrder[left.level] ?? 9) - (severityOrder[right.level] ?? 9))
})

function navigateTo(view: ViewKey) {
  activeView.value = view
  selectedProjectName.value = ''
  globalListSource.value = false
  serviceListMode.value = 'all'
  serviceCategory.value = null
  pendingAlertsOnly.value = false
  if (view === 'inspections') {
    selectedInspectionPlan.value = null
    inspectionPage.value = 1
    inspectionSection.value = 'plans'
  }
  keyword.value = ''
}

function openProjectServices(item: Project) {
  selectedProjectName.value = item.name
  globalListSource.value = false
  serviceListMode.value = 'all'
  serviceCategory.value = null
  activeView.value = 'assets'
  keyword.value = ''
}

function openProjectAlerts(item: Project) {
  selectedProjectName.value = item.name
  globalListSource.value = false
  pendingAlertsOnly.value = false
  activeView.value = 'alerts'
  keyword.value = ''
}

function backToProjects() {
  selectedProjectName.value = ''
  activeView.value = 'projects'
  keyword.value = ''
}

function backFromList() {
  if (activeView.value === 'assets' && serviceCategory.value) {
    backToDashboard()
    return
  }
  backToProjects()
}

function openGlobalProjects() {
  navigateTo('projects')
}

function openGlobalServices(mode: 'all' | 'abnormal') {
  selectedProjectName.value = ''
  globalListSource.value = true
  serviceListMode.value = mode
  serviceCategory.value = null
  activeView.value = 'assets'
  keyword.value = ''
}

function openServiceCategory(category: AssetCategoryKey) {
  selectedProjectName.value = ''
  globalListSource.value = true
  serviceListMode.value = 'all'
  serviceCategory.value = category
  activeView.value = 'assets'
  keyword.value = ''
}

function openGlobalAlerts() {
  selectedProjectName.value = ''
  globalListSource.value = true
  pendingAlertsOnly.value = true
  activeView.value = 'alerts'
  keyword.value = ''
}

function openGlobalAlertList() {
  selectedProjectName.value = ''
  globalListSource.value = true
  pendingAlertsOnly.value = false
  activeView.value = 'alerts'
  keyword.value = ''
}

function openGlobalInspections() {
  selectedInspectionPlan.value = null
  inspectionPage.value = 1
  inspectionSection.value = 'results'
  globalListSource.value = true
  activeView.value = 'inspections'
  keyword.value = ''
}

function backToDashboard() {
  selectedProjectName.value = ''
  selectedInspectionPlan.value = null
  inspectionPage.value = 1
  inspectionSection.value = 'plans'
  globalListSource.value = false
  serviceListMode.value = 'all'
  serviceCategory.value = null
  pendingAlertsOnly.value = false
  activeView.value = 'dashboard'
  keyword.value = ''
}

function includesKeyword(...values: string[]) {
  const value = keyword.value.trim().toLowerCase()
  return !value || values.some(item => item.toLowerCase().includes(value))
}

function badge(status: string) {
  const mapping: Record<string, string> = {
    RUNNING: 'success', HEALTHY: 'success', PASSED: 'success', RESOLVED: 'info',
    MAINTENANCE: 'warning', WARNING: 'warning', OPEN: 'danger', CRITICAL: 'danger', FAILED: 'danger', INFO: 'info',
  }
  return mapping[status] ?? 'info'
}

function statusText(status: string) {
  const mapping: Record<string, string> = {
    RUNNING: '运行中', MAINTENANCE: '维护中', HEALTHY: '正常', WARNING: '注意', CRITICAL: '异常',
    PASSED: '通过', FAILED: '失败', OPEN: '待处理', ACKNOWLEDGED: '已确认', FIRING: '告警中', RESOLVED: '已恢复', INFO: '提示', UNKNOWN: '未知',
    ENABLED: '已启用', DISABLED: '已停用',
  }
  return mapping[status] ?? status
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}

function formatRecordTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
}

function defaultNextRunAt() {
  const value = new Date(Date.now() + 5 * 60 * 1000)
  const offset = value.getTimezoneOffset() * 60_000
  return new Date(value.getTime() - offset).toISOString().slice(0, 19)
}

function applyScheduleText() {
  if (scheduleForm.mode === 'INTERVAL') planForm.scheduleText = `每 ${scheduleForm.intervalValue} ${scheduleForm.intervalUnit}`
  else if (scheduleForm.mode === 'DAILY') planForm.scheduleText = `每天 ${scheduleForm.dailyTime}`
  else planForm.scheduleText = `Cron: ${scheduleForm.cronExpression}`
}

function loadScheduleForm(scheduleText: string) {
  const interval = scheduleText.match(/^每\s*(\d+)\s*(分钟|小时)$/)
  if (interval) Object.assign(scheduleForm, { mode: 'INTERVAL', intervalValue: Number(interval[1]), intervalUnit: interval[2] })
  else if (scheduleText.startsWith('每天 ')) Object.assign(scheduleForm, { mode: 'DAILY', dailyTime: scheduleText.slice(3) })
  else if (scheduleText.startsWith('Cron: ')) Object.assign(scheduleForm, { mode: 'CRON', cronExpression: scheduleText.slice(6) })
  else Object.assign(scheduleForm, { mode: 'INTERVAL', intervalValue: 1, intervalUnit: '小时' })
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [summaryData, projectData, assetData, inspectionData, planData, alertData] = await Promise.all([
      api.summary(), api.projects(), api.assets(), api.inspections(), api.inspectionPlans(), api.alerts(),
    ])
    summary.value = summaryData
    projects.value = projectData
    assets.value = assetData
    inspections.value = inspectionData
    inspectionPlans.value = planData
    alerts.value = alertData
    try {
      const [hertzBeatStatus, runData] = await Promise.all([api.hertzBeatStatus(), api.monitoringRuns()])
      hertzBeatConnected.value = hertzBeatStatus.connected
      monitoringRuns.value = runData
    } catch {
      hertzBeatConnected.value = false
    }
    lastUpdated.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date())
  } catch (reason) {
    if (reason instanceof Error && reason.message === 'AUTH_REQUIRED') {
      setAuthToken('')
      authenticated.value = false
      return
    }
    error.value = reason instanceof Error ? reason.message : '数据加载失败'
  } finally {
    loading.value = false
  }
}

async function initializeAuth() {
  try {
    const user = await api.me()
    Object.assign(currentUser, user)
    authenticated.value = true
    await loadData()
  } catch {
    setAuthToken('')
    authenticated.value = false
  } finally {
    authLoading.value = false
  }
}

async function login() {
  loginLoading.value = true
  try {
    const session = await api.login(loginForm.username, loginForm.password)
    setAuthToken(session.token)
    Object.assign(currentUser, session)
    authenticated.value = true
    loginForm.password = ''
    await loadData()
  } catch (reason) {
    ElMessage.error(reason instanceof Error && reason.message !== 'AUTH_REQUIRED' ? reason.message : '用户名或密码错误')
  } finally {
    loginLoading.value = false
  }
}

async function logout() {
  try { await api.logout() } catch { /* 会话失效时直接清理本地状态 */ }
  setAuthToken('')
  authenticated.value = false
}

async function openInspectionDetail(item: Inspection) {
  selectedInspection.value = item
  inspectionDrawerVisible.value = true
  inspectionDetailLoading.value = true
  try {
    inspectionItems.value = await api.inspectionItems(item.id)
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '巡检详情加载失败')
  } finally {
    inspectionDetailLoading.value = false
  }
}

function inspectionCount(status: string) {
  return inspectionItems.value.filter(item => item.status === status).length
}

function openCreate() {
  editingId.value = null
  editorMode.value = activeView.value === 'projects' ? 'project' : activeView.value === 'assets' ? 'asset' : 'plan'
  if (editorMode.value === 'project') Object.assign(projectForm, { name: '', owner: '', description: '', status: 'RUNNING' })
  else if (editorMode.value === 'asset') Object.assign(assetForm, { name: '', projectName: selectedProjectName.value || projects.value[0]?.name || '', environmentName: '生产', type: 'SERVER', endpoint: '', status: 'UNKNOWN' })
  else {
    Object.assign(planForm, { name: '', projectName: projects.value[0]?.name || '', scopeText: '', scheduleText: '每 5 分钟', status: 'ENABLED', nextRunAt: defaultNextRunAt() })
    Object.assign(scheduleForm, { mode: 'INTERVAL', intervalValue: 5, intervalUnit: '分钟', dailyTime: '09:00', cronExpression: '0 0 9 * * *' })
  }
  editorVisible.value = true
}

function openProjectEdit(item: Project) {
  editorMode.value = 'project'
  editingId.value = item.id
  Object.assign(projectForm, { name: item.name, owner: item.owner, description: item.description, status: item.status })
  editorVisible.value = true
}

function openAssetEdit(item: Asset) {
  editorMode.value = 'asset'
  editingId.value = item.id
  Object.assign(assetForm, { name: item.name, projectName: item.projectName, environmentName: item.environmentName, type: item.type, endpoint: item.endpoint, status: item.status })
  editorVisible.value = true
}

function openPlanEdit(item: InspectionPlan) {
  editorMode.value = 'plan'
  editingId.value = item.id
  Object.assign(planForm, { name: item.name, projectName: item.projectName, scopeText: item.scopeText, scheduleText: item.scheduleText, status: item.status, nextRunAt: item.nextRunAt.slice(0, 19) })
  loadScheduleForm(item.scheduleText)
  editorVisible.value = true
}

async function saveEditor() {
  saving.value = true
  try {
    if (editorMode.value === 'project') {
      if (editingId.value) await api.updateProject(editingId.value, projectForm)
      else await api.createProject(projectForm)
    } else if (editorMode.value === 'asset') {
      if (editingId.value) await api.updateAsset(editingId.value, assetForm)
      else await api.createAsset(assetForm)
    } else {
      applyScheduleText()
      if (editingId.value) await api.updateInspectionPlan(editingId.value, planForm)
      else await api.createInspectionPlan(planForm)
    }
    ElMessage.success(editingId.value ? '修改已保存' : '新增成功')
    editorVisible.value = false
    await loadData()
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeProject(item: Project) {
  await ElMessageBox.confirm(`确认删除项目“${item.name}”？`, '删除项目', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await api.deleteProject(item.id)
  ElMessage.success('项目已删除')
  await loadData()
}

async function removeAsset(item: Asset) {
  const productionHint = item.environmentName === '生产' ? '这是生产环境服务，删除后无法恢复。' : '删除后无法恢复。'
  await ElMessageBox.confirm(`${productionHint}确认删除“${item.name}”？`, '删除服务', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' })
  await api.deleteAsset(item.id)
  ElMessage.success('服务已删除')
  await loadData()
}

async function syncAsset(item: Asset) {
  syncingAssetId.value = item.id
  try {
    const synced = await api.syncAssetToHertzBeat(item.id)
    const index = assets.value.findIndex(asset => asset.id === item.id)
    if (index >= 0) assets.value[index] = synced
    if (synced.syncStatus === 'SYNCED') ElMessage.success(`已同步到 HertzBeat，监控 ID：${synced.hertzbeatMonitorId}`)
    else ElMessage.error(synced.syncMessage || '同步失败')
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '同步失败')
  } finally {
    syncingAssetId.value = null
  }
}

async function syncAllAssets() {
  syncingAllAssets.value = true
  try {
    const result = await api.syncAllAssetsToHertzBeat()
    assets.value = result.assets
    if (result.failed === 0) ElMessage.success(`已同步 ${result.succeeded} 项资产到 HertzBeat`)
    else ElMessage.warning(`同步完成：成功 ${result.succeeded} 项，失败 ${result.failed} 项`)
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '一键同步失败')
  } finally {
    syncingAllAssets.value = false
  }
}

async function syncMonitoringData() {
  syncingMonitoring.value = true
  try {
    const run = await api.syncMonitoringData()
    ElMessage[run.status === 'SUCCESS' ? 'success' : run.status === 'SKIPPED' ? 'warning' : 'error'](
      run.status === 'SUCCESS' ? `监控数据已更新：状态 ${run.assetsUpdated} 项，告警 ${run.alertsUpdated} 项` : run.errorSummary || '已有同步任务正在运行',
    )
    await loadData()
  } finally {
    syncingMonitoring.value = false
  }
}

async function openMonitoringDetails(item: Asset) {
  monitoringDrawerVisible.value = true
  monitoringDetailLoading.value = true
  monitoringDetails.value = null
  try { monitoringDetails.value = await api.monitoringDetails(item.id) }
  catch (reason) { ElMessage.error(reason instanceof Error ? reason.message : '监控详情加载失败') }
  finally { monitoringDetailLoading.value = false }
}

function metricLabel(name: string) {
  return name === 'availability' ? '可用性' : name === 'responseTime' ? '响应时间' : name === 'httpStatus' ? 'HTTP 状态码' : name
}

function metricValue(metric: MonitoringDetails['latestMetrics'][number]) {
  if (metric.booleanValue !== null) return metric.booleanValue ? '可用' : '不可用'
  return `${metric.value ?? '--'}${metric.unit ? ` ${metric.unit}` : ''}`
}

async function togglePlan(item: InspectionPlan) {
  const status = item.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await api.updateInspectionPlan(item.id, { ...item, status })
  ElMessage.success(status === 'ENABLED' ? '巡检计划已启用' : '巡检计划已停用')
  await loadData()
}

async function removePlan(item: InspectionPlan) {
  await ElMessageBox.confirm(`确认删除巡检计划“${item.name}”？`, '删除巡检计划', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await api.deleteInspectionPlan(item.id)
  ElMessage.success('巡检计划已删除')
  await loadData()
}

async function executePlan(item: InspectionPlan) {
  runningPlanId.value = item.id
  try {
    const result = await api.executeInspectionPlan(item.id)
    ElMessage.success(result.status === 'PASSED' ? '巡检执行完成，全部检查通过' : '巡检执行完成，发现异常项')
    selectedInspectionPlan.value = item
    inspectionPage.value = 1
    inspectionSection.value = 'results'
    await loadData()
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '巡检执行失败')
  } finally {
    runningPlanId.value = null
  }
}

function openPlanRecords(item: InspectionPlan) {
  selectedInspectionPlan.value = item
  inspectionPage.value = 1
  inspectionSection.value = 'results'
}

function backToPlans() {
  selectedInspectionPlan.value = null
  inspectionPage.value = 1
  inspectionSection.value = 'plans'
}

watch(keyword, () => {
  if (activeView.value === 'inspections' && inspectionSection.value === 'results') inspectionPage.value = 1
})

onMounted(initializeAuth)
</script>

<template>
  <div v-if="authLoading" class="auth-loading"><Activity :size="26" class="spinning" /></div>
  <div v-else-if="!authenticated" class="login-page">
    <section class="login-panel">
      <div class="login-brand"><div class="brand-mark"><Activity :size="22" /></div><div><strong>联乾运维平台</strong><span>OPS PLATFORM</span></div></div>
      <h1>管理员登录</h1><p>登录后管理项目、服务、巡检计划与告警。</p>
      <el-form label-position="top" @submit.prevent="login"><el-form-item label="用户名"><el-input v-model="loginForm.username" autocomplete="username" /></el-form-item><el-form-item label="密码"><el-input v-model="loginForm.password" type="password" show-password autocomplete="current-password" @keyup.enter="login" /></el-form-item><el-button type="primary" :loading="loginLoading" class="login-submit" @click="login">登录</el-button></el-form>
    </section>
  </div>
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark"><Activity :size="21" /></div><div><strong>联乾</strong><span>OPS PLATFORM</span></div></div>
      <div class="scope-label">运维工作台</div>
      <nav>
        <button v-for="item in navItems" :key="item.key" class="nav-item" :class="{ active: activeView === item.key }" @click="navigateTo(item.key)">
          <component :is="item.icon" :size="18" /><span>{{ item.label }}</span><ChevronRight v-if="activeView === item.key" :size="15" class="nav-arrow" />
        </button>
      </nav>
      <div class="sidebar-spacer" />
      <div v-if="currentUser.canManageIntegrations" class="integration-list">
        <div class="scope-label">监控组件</div>
        <a href="http://localhost:1157" target="_blank"><CloudCog :size="17" /><span>HertzBeat <i class="integration-dot" :class="{ connected: hertzBeatConnected }" /></span><ExternalLink :size="13" /></a>
        <a href="http://localhost:8428/vmui" target="_blank"><Activity :size="17" /><span>VictoriaMetrics</span><ExternalLink :size="13" /></a>
      </div>
      <div class="operator"><div class="avatar">运</div><div><strong>{{ currentUser.displayName }}</strong><span>{{ currentUser.username }}@local</span></div><button title="退出登录" @click="logout"><LogOut :size="17" /></button></div>
    </aside>

    <main class="main-area">
      <header class="topbar">
        <div class="topbar-title"><button v-if="((activeView === 'assets' || activeView === 'alerts') && selectedProjectName) || (activeView === 'assets' && serviceCategory)" class="records-back" :title="serviceCategory ? '返回运行总览' : '返回项目中心'" @click="backFromList"><ArrowLeft :size="18" /></button><div><h1>{{ currentTitle.title }}</h1><p>{{ currentTitle.description }}</p></div></div>
        <div class="topbar-actions">
          <div class="search"><Search :size="17" /><input v-model="keyword" placeholder="搜索当前视图" /></div>
          <span class="updated">更新于 {{ lastUpdated || '--:--:--' }}</span>
          <button class="icon-button" title="刷新数据" :disabled="loading" @click="loadData"><RefreshCw :size="17" :class="{ spinning: loading }" /></button>
          <button v-if="activeView === 'assets'" class="secondary-button" :disabled="syncingAllAssets" @click="syncAllAssets"><RefreshCw :size="16" :class="{ 'spin-icon': syncingAllAssets }" />同步资产</button>
          <button v-if="activeView === 'assets' || activeView === 'alerts'" class="secondary-button" :disabled="syncingMonitoring" @click="syncMonitoringData"><Activity :size="16" :class="{ 'spin-icon': syncingMonitoring }" />同步监控数据</button>
          <button v-if="activeView === 'projects' || activeView === 'assets' || (activeView === 'inspections' && inspectionSection === 'plans')" class="primary-button" @click="openCreate"><Plus :size="16" />新增{{ activeView === 'projects' ? '项目' : activeView === 'assets' ? '服务' : '计划' }}</button>
        </div>
      </header>

      <section v-if="error" class="error-state"><ShieldCheck :size="20" /><span>{{ error }}</span><button @click="loadData">重新加载</button></section>

      <section v-if="activeView === 'dashboard'" class="content dashboard-view">
        <div class="metrics-grid">
          <button class="metric" @click="openGlobalProjects"><div class="metric-icon blue"><FolderKanban :size="20" /></div><div><span>接入项目</span><strong>{{ summary.projectCount }}</strong><small>统一业务视图</small></div><ChevronRight :size="16" class="metric-arrow" /></button>
          <button class="metric" @click="openGlobalServices('all')"><div class="metric-icon green"><Server :size="20" /></div><div><span>纳管服务</span><strong>{{ summary.assetCount }}</strong><small>统一服务视图</small></div><ChevronRight :size="16" class="metric-arrow" /></button>
          <button class="metric" @click="openGlobalServices('abnormal')"><div class="metric-icon amber"><HardDrive :size="20" /></div><div><span>异常服务</span><strong>{{ summary.abnormalAssetCount }}</strong><small>需要持续关注</small></div><ChevronRight :size="16" class="metric-arrow" /></button>
          <button class="metric" @click="openGlobalAlerts"><div class="metric-icon red"><BellRing :size="20" /></div><div><span>待处理告警</span><strong>{{ summary.openAlertCount }}</strong><small>尚未恢复</small></div><ChevronRight :size="16" class="metric-arrow" /></button>
          <button class="metric" @click="openGlobalInspections"><div class="metric-icon cyan"><ListChecks :size="20" /></div><div><span>巡检通过率</span><strong>{{ summary.inspectionPassRate }}%</strong><small>执行记录</small></div><ChevronRight :size="16" class="metric-arrow" /></button>
        </div>

        <div class="dashboard-grid">
          <section class="panel span-two"><div class="panel-heading"><div><h2>服务运行状态</h2><p>按类型查看当前基础设施健康度</p></div><button class="text-button" @click="navigateTo('projects')">进入项目中心 <ChevronRight :size="15" /></button></div>
            <div class="asset-overview">
              <button v-for="item in assetCategoryStats" :key="item.key" type="button" class="asset-stat" @click="openServiceCategory(item.key)">
                <component :is="item.icon" :size="19" /><div><strong>{{ item.label }}</strong><span>{{ item.healthy }}/{{ item.count }} 健康</span></div><div class="health-bar"><i :style="{ width: `${item.percentage}%` }" /></div>
              </button>
            </div>
          </section>

          <section class="panel"><div class="panel-heading"><div><h2>巡检质量</h2><p>最近三次任务</p></div></div>
            <div class="pass-ring"><div><strong>{{ summary.inspectionPassRate }}%</strong><span>通过率</span></div></div>
            <div class="mini-list"><div v-for="item in inspections.slice(0, 3)" :key="item.id"><span class="dot" :class="item.status.toLowerCase()" /><div><strong>{{ item.name }}</strong><small>{{ item.passedChecks }}/{{ item.totalChecks }} 项通过</small></div></div></div>
          </section>

          <section class="panel span-three"><div class="panel-heading"><div><h2>最新告警</h2><p>按发生时间排序的运行风险</p></div><button class="icon-button" title="进入告警事件" @click="navigateTo('alerts')"><ChevronRight :size="17" /></button></div>
            <div class="alert-stream"><div v-for="item in alerts" :key="item.id" class="alert-row"><span class="severity" :class="item.level.toLowerCase()">{{ statusText(item.level) }}</span><div class="alert-main"><strong>{{ item.message }}</strong><span>{{ item.projectName }} · {{ item.targetName }}</span></div><time>{{ formatTime(item.occurredAt) }}</time><el-tag :type="badge(item.status)" effect="light" size="small">{{ statusText(item.status) }}</el-tag></div></div>
          </section>
        </div>
      </section>

      <section v-else class="content list-view">
        <div v-if="activeView !== 'inspections'" class="list-summary"><span>当前共 {{ activeView === 'projects' ? filteredProjects.length : activeView === 'assets' ? filteredAssets.length : filteredAlerts.length }} 条记录</span><div class="legend"><i class="ok" />正常<i class="warn" />注意<i class="bad" />异常</div></div>

        <div v-if="activeView === 'projects'" class="project-grid">
          <article v-for="item in filteredProjects" :key="item.id" class="project-card"><div class="project-head"><div class="project-symbol">{{ item.name.slice(0, 1) }}</div><div class="card-actions"><el-tag :type="badge(item.status)" effect="light">{{ statusText(item.status) }}</el-tag><button title="编辑项目" @click="openProjectEdit(item)"><Pencil :size="15" /></button><button title="删除项目" class="danger-action" @click="removeProject(item)"><Trash2 :size="15" /></button></div></div><h3>{{ item.name }}</h3><p>{{ item.description }}</p><div class="project-meta"><span>负责人</span><strong>{{ item.owner }}</strong></div><div class="project-footer"><button @click="openProjectServices(item)"><Server :size="14" />{{ assets.filter(asset => asset.projectName === item.name).length }} 项服务<ChevronRight :size="14" /></button><button @click="openProjectAlerts(item)"><AlertTriangle :size="14" />{{ alerts.filter(alert => alert.projectName === item.name && alert.status === 'OPEN').length }} 个告警<ChevronRight :size="14" /></button></div></article>
        </div>

        <div v-if="activeView === 'assets'" class="table-panel"><el-table :data="filteredAssets" stripe><el-table-column prop="name" label="服务名称" min-width="170" /><el-table-column prop="projectName" label="所属项目" min-width="150" /><el-table-column prop="environmentName" label="环境" width="90" /><el-table-column prop="type" label="服务类型" width="120" /><el-table-column prop="endpoint" label="连接地址" min-width="190" /><el-table-column label="运行状态" width="120"><template #default="scope"><div class="asset-status"><el-tag :type="badge(scope.row.status)" effect="light">{{ statusText(scope.row.status) }}</el-tag><span v-if="scope.row.monitoringStale">数据已陈旧</span></div></template></el-table-column><el-table-column label="监控同步" width="150"><template #default="scope"><el-tooltip :content="scope.row.monitoringError || scope.row.syncMessage || '尚未同步到 HertzBeat'" placement="top"><div class="sync-state"><el-tag :type="scope.row.mappingStatus === 'MANAGED' ? 'success' : scope.row.mappingStatus === 'INVALIDATED' ? 'danger' : 'info'" effect="light">{{ scope.row.mappingStatus === 'MANAGED' ? 'HertzBeat 管理' : scope.row.mappingStatus === 'INVALIDATED' ? '映射失效' : '本地巡检' }}</el-tag><span>{{ scope.row.lastSuccessfulSyncAt ? formatTime(scope.row.lastSuccessfulSyncAt) : '未回写' }}</span></div></el-tooltip></template></el-table-column><el-table-column label="操作" width="170" fixed="right"><template #default="scope"><div class="row-actions"><button title="监控详情" @click="openMonitoringDetails(scope.row)"><Activity :size="15" /></button><button :title="scope.row.syncStatus === 'SYNCED' ? '重新同步到 HertzBeat' : '同步到 HertzBeat'" :disabled="syncingAssetId === scope.row.id" @click="syncAsset(scope.row)"><RefreshCw :size="15" :class="{ 'spin-icon': syncingAssetId === scope.row.id }" /></button><button title="编辑服务" @click="openAssetEdit(scope.row)"><Pencil :size="15" /></button><button title="删除服务" class="danger-action" @click="removeAsset(scope.row)"><Trash2 :size="15" /></button></div></template></el-table-column></el-table></div>

        <div v-if="activeView === 'inspections'" class="inspection-center">
          <div class="inspection-view-heading"><div class="inspection-heading-title"><button v-if="inspectionSection === 'results' && selectedInspectionPlan" class="records-back" title="返回巡检计划" @click="backToPlans"><ArrowLeft :size="18" /></button><div><h2>{{ inspectionSection === 'plans' ? '巡检计划列表' : selectedInspectionPlan ? `${selectedInspectionPlan.name} · 执行记录` : '执行记录' }}</h2><p v-if="inspectionSection === 'plans'">配置巡检周期并管理自动执行任务</p></div></div></div>
          <div v-if="inspectionSection === 'plans'" class="plan-grid">
            <article v-for="plan in inspectionPlans" :key="plan.id" class="plan-card"><div class="plan-card-head"><div class="plan-icon"><CalendarClock :size="19" /></div><el-tag :type="plan.status === 'ENABLED' ? 'success' : 'info'" effect="light">{{ statusText(plan.status) }}</el-tag></div><h3>{{ plan.name }}</h3><p>{{ plan.scopeText }}</p><dl><div><dt>所属项目</dt><dd>{{ plan.projectName }}</dd></div><div><dt>执行周期</dt><dd>{{ plan.scheduleText }}</dd></div><div><dt>下次执行</dt><dd>{{ formatTime(plan.nextRunAt) }}</dd></div></dl><div class="plan-actions"><div class="plan-commands"><button title="立即执行" :disabled="runningPlanId === plan.id" @click="executePlan(plan)"><RefreshCw v-if="runningPlanId === plan.id" :size="15" class="spin-icon" /><Play v-else :size="15" /></button><button title="执行记录" @click="openPlanRecords(plan)"><Clock3 :size="15" /></button><button class="plan-toggle-button" :class="plan.status === 'ENABLED' ? 'stop' : 'start'" :title="plan.status === 'ENABLED' ? '停用计划' : '启用计划'" @click="togglePlan(plan)"><Power :size="15" /></button></div><div><button title="编辑计划" @click="openPlanEdit(plan)"><Pencil :size="15" /></button><button title="删除计划" class="danger-action" @click="removePlan(plan)"><Trash2 :size="15" /></button></div></div></article>
            <div v-if="inspectionPlans.length === 0" class="empty-state plan-empty"><CalendarClock :size="26" /><strong>暂无巡检计划</strong><span>新增计划后可统一管理巡检范围和执行周期。</span></div>
          </div>
          <div v-else class="result-list">
            <article v-for="item in pagedPlanInspections" :key="item.id" class="result-card" @click="openInspectionDetail(item)">
              <div class="result-status" :class="item.status.toLowerCase()"><CheckCircle2 v-if="item.status === 'PASSED'" :size="22" /><XCircle v-else :size="22" /></div>
              <div class="result-main"><div><h3>{{ item.name }}</h3><el-tag :type="badge(item.status)" effect="light">{{ statusText(item.status) }}</el-tag></div></div>
              <div class="result-score"><strong>{{ item.passedChecks }}/{{ item.totalChecks }}</strong></div>
              <div class="result-meta"><time class="result-executed-at">{{ formatRecordTime(item.executedAt) }}</time><span><Clock3 :size="13" />执行耗时 {{ item.durationSeconds }} 秒</span></div>
              <ChevronRight :size="18" class="result-arrow" />
            </article>
            <div v-if="selectedPlanInspections.length === 0" class="empty-state"><Clock3 :size="26" /><strong>暂无执行记录</strong><span>点击该计划的“立即执行”后会在这里生成记录。</span></div>
            <div v-else class="inspection-pagination"><span>共 {{ selectedPlanInspections.length }} 条</span><el-pagination v-model:current-page="inspectionPage" :page-size="inspectionPageSize" :total="selectedPlanInspections.length" layout="prev, pager, next" background /></div>
          </div>
        </div>

        <div v-if="activeView === 'alerts'" class="table-panel"><el-table :data="filteredAlerts" stripe><el-table-column label="级别" width="100"><template #default="scope"><span class="severity" :class="scope.row.level.toLowerCase()">{{ statusText(scope.row.level) }}</span></template></el-table-column><el-table-column prop="message" label="告警内容" min-width="250" /><el-table-column prop="projectName" label="所属项目" min-width="160" /><el-table-column prop="targetName" label="告警对象" min-width="160" /><el-table-column label="发生时间" width="150"><template #default="scope">{{ formatTime(scope.row.occurredAt) }}</template></el-table-column><el-table-column label="外部状态" width="100"><template #default="scope"><el-tag :type="scope.row.externalLifecycle === 'RESOLVED' ? 'success' : 'danger'" effect="plain">{{ statusText(scope.row.externalLifecycle || 'FIRING') }}</el-tag></template></el-table-column><el-table-column label="处置状态" width="110"><template #default="scope"><el-tag :type="badge(scope.row.status)" effect="light">{{ statusText(scope.row.status) }}</el-tag></template></el-table-column></el-table></div>
      </section>
    </main>

    <el-drawer v-model="editorVisible" :title="`${editingId ? '编辑' : '新增'}${editorMode === 'project' ? '项目' : editorMode === 'asset' ? '服务' : '巡检计划'}`" size="440px" destroy-on-close>
      <el-form v-if="editorMode === 'project'" label-position="top" class="editor-form" @submit.prevent="saveEditor">
        <el-form-item label="项目名称" required><el-input v-model="projectForm.name" maxlength="80" placeholder="例如：订单履约平台" /></el-form-item>
        <el-form-item label="负责人" required><el-input v-model="projectForm.owner" maxlength="40" placeholder="请输入负责人" /></el-form-item>
        <el-form-item label="项目描述"><el-input v-model="projectForm.description" type="textarea" :rows="4" maxlength="240" show-word-limit /></el-form-item>
        <el-form-item label="运行状态"><el-segmented v-model="projectForm.status" :options="[{ label: '运行中', value: 'RUNNING' }, { label: '维护中', value: 'MAINTENANCE' }]" /></el-form-item>
      </el-form>
      <el-form v-else-if="editorMode === 'asset'" label-position="top" class="editor-form" @submit.prevent="saveEditor">
        <el-form-item label="服务名称" required><el-input v-model="assetForm.name" maxlength="100" placeholder="例如：payment-prod-mysql" /></el-form-item>
        <el-form-item label="所属项目" required><el-select v-model="assetForm.projectName" placeholder="选择项目" style="width: 100%"><el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.name" /></el-select></el-form-item>
        <div class="form-grid"><el-form-item label="运行环境" required><el-select v-model="assetForm.environmentName" style="width: 100%"><el-option v-for="item in environments" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="服务类型" required><el-select v-model="assetForm.type" style="width: 100%"><el-option v-for="item in assetTypes" :key="item" :label="item" :value="item" /></el-select></el-form-item></div>
        <el-form-item label="连接地址" required><el-input v-model="assetForm.endpoint" maxlength="240" placeholder="IP:端口或 HTTP 地址" /></el-form-item>
        <el-form-item v-if="editingId" label="当前状态"><el-select v-model="assetForm.status" style="width: 100%"><el-option label="未知" value="UNKNOWN" /><el-option label="正常" value="HEALTHY" /><el-option label="注意" value="WARNING" /><el-option label="异常" value="CRITICAL" /></el-select></el-form-item>
        <div class="production-note"><ShieldCheck :size="17" /><span>新建服务默认归入生产环境，可在此切换。</span></div>
      </el-form>
      <el-form v-else label-position="top" class="editor-form" @submit.prevent="saveEditor">
        <el-form-item label="计划名称" required><el-input v-model="planForm.name" maxlength="100" placeholder="例如：礼事随记生产巡检" /></el-form-item>
        <el-form-item label="所属项目" required><el-select v-model="planForm.projectName" placeholder="选择项目" style="width: 100%"><el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.name" /></el-select></el-form-item>
        <el-form-item label="巡检范围" required><el-input v-model="planForm.scopeText" type="textarea" :rows="3" maxlength="240" show-word-limit placeholder="例如：后端健康接口、PostgreSQL、H5 页面" /></el-form-item>
        <el-form-item label="执行周期" required><el-segmented v-model="scheduleForm.mode" :options="[{ label: '间隔执行', value: 'INTERVAL' }, { label: '每天定时', value: 'DAILY' }, { label: 'Cron', value: 'CRON' }]" /></el-form-item>
        <div v-if="scheduleForm.mode === 'INTERVAL'" class="form-grid"><el-form-item label="间隔数值" required><el-input-number v-model="scheduleForm.intervalValue" :min="1" :max="1440" style="width: 100%" /></el-form-item><el-form-item label="间隔单位" required><el-select v-model="scheduleForm.intervalUnit" style="width: 100%"><el-option label="分钟" value="分钟" /><el-option label="小时" value="小时" /></el-select></el-form-item></div>
        <el-form-item v-else-if="scheduleForm.mode === 'DAILY'" label="每天执行时间" required><el-time-picker v-model="scheduleForm.dailyTime" value-format="HH:mm" format="HH:mm" style="width: 100%" /></el-form-item>
        <el-form-item v-else label="Cron 表达式" required><el-input v-model="scheduleForm.cronExpression" placeholder="例如：0 0/10 * * * *" /><div class="field-hint">使用 6 位 Spring Cron：秒 分 时 日 月 周</div></el-form-item>
        <el-form-item label="计划状态"><el-segmented v-model="planForm.status" :options="[{ label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }]" /></el-form-item>
      </el-form>
      <template #footer><div class="drawer-footer"><el-button @click="editorVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEditor">保存</el-button></div></template>
    </el-drawer>

    <el-drawer v-model="inspectionDrawerVisible" :title="selectedInspection ? `巡检详细结果 · ${selectedInspection.name}` : '巡检详细结果'" size="520px">
      <div v-if="selectedInspection" class="inspection-detail" v-loading="inspectionDetailLoading">
        <div class="detail-summary"><div class="detail-score"><strong>{{ selectedInspection.passedChecks }}/{{ selectedInspection.totalChecks }}</strong><span>检查通过</span></div><div class="detail-counts"><span class="passed"><CheckCircle2 :size="15" />通过 {{ inspectionCount('PASSED') }}</span><span class="warning"><AlertTriangle :size="15" />警告 {{ inspectionCount('WARNING') }}</span><span class="failed"><XCircle :size="15" />失败 {{ inspectionCount('FAILED') }}</span></div></div>
        <div class="detail-context"><span>{{ selectedInspection.projectName }}</span><time>{{ formatRecordTime(selectedInspection.executedAt) }}</time><span>执行耗时 {{ selectedInspection.durationSeconds }} 秒</span></div>
        <div class="check-list">
          <article v-for="check in inspectionItems" :key="check.id" class="check-item" :class="check.status.toLowerCase()"><div class="check-icon"><CheckCircle2 v-if="check.status === 'PASSED'" :size="18" /><AlertTriangle v-else-if="check.status === 'WARNING'" :size="18" /><XCircle v-else :size="18" /></div><div class="check-content"><div><strong>{{ check.checkName }}</strong><span>{{ check.targetName }}</span></div><p>{{ check.message }}</p></div><time>{{ check.durationMillis }}ms</time></article>
        </div>
      </div>
    </el-drawer>

    <el-drawer v-model="monitoringDrawerVisible" :title="monitoringDetails ? `监控详情 · ${monitoringDetails.asset.name}` : '监控详情'" size="560px">
      <div class="monitoring-detail" v-loading="monitoringDetailLoading">
        <template v-if="monitoringDetails">
          <div class="monitoring-summary"><div><span>运行状态</span><strong>{{ statusText(monitoringDetails.asset.status) }}</strong></div><div><span>监控 ID</span><strong>{{ monitoringDetails.asset.hertzbeatMonitorId || '--' }}</strong></div><div><span>最近采集</span><strong>{{ monitoringDetails.asset.lastCollectedAt ? formatTime(monitoringDetails.asset.lastCollectedAt) : '--' }}</strong></div></div>
          <el-alert v-if="monitoringDetails.metricsStale" title="当前指标超过 180 秒未更新，数据已陈旧" type="warning" :closable="false" show-icon />
          <el-alert v-if="monitoringDetails.metricsMessage" :title="monitoringDetails.metricsMessage" type="info" :closable="false" show-icon />
          <div class="monitoring-metrics"><article v-for="metric in monitoringDetails.latestMetrics" :key="metric.name"><span>{{ metricLabel(metric.name) }}</span><strong>{{ metricValue(metric) }}</strong><small>{{ metric.collectedAt ? formatTime(metric.collectedAt) : '暂无采集时间' }}</small></article></div>
          <div class="trend-section"><h3>最近一小时响应时间</h3><div v-if="monitoringDetails.history.length" class="trend-bars"><i v-for="(point, index) in monitoringDetails.history" :key="index" :style="{ height: `${Math.max(8, Math.min(100, Number(point.value || 0) / 3))}%` }" :title="metricValue(point)" /></div><div v-else class="empty-trend">暂无趋势数据</div></div>
          <div class="sync-history"><h3>最近同步运行</h3><div v-for="run in monitoringRuns.slice(0, 5)" :key="run.id"><el-tag :type="run.status === 'SUCCESS' ? 'success' : run.status === 'PARTIAL' ? 'warning' : 'danger'" size="small">{{ run.status }}</el-tag><span>{{ formatRecordTime(run.startedAt) }}</span><span>状态 {{ run.assetsUpdated }} / 告警 {{ run.alertsUpdated }}</span></div></div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>
