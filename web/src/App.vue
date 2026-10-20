<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Activity, AlertTriangle, ArrowLeft, BellRing, CalendarClock, CheckCircle2, ChevronRight, CircleGauge, Clock3, CloudCog, Database, ExternalLink, FolderKanban, HardDrive, ListChecks, LogOut, Pencil, Play, Plus, Power, RefreshCw, Search, Server, Settings, ShieldCheck, Trash2, XCircle } from '@lucide/vue'
import { api, setAuthToken, type Alert, type AlertHandlingEvent, type Asset, type Inspection, type InspectionItem, type InspectionPlan, type MonitoringDetails, type MonitoringSyncRun, type Project, type Summary } from './api'

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
const alertExternalFilter = ref('ALL')
const alertHandlingFilter = ref('ALL')
const alertLevelFilter = ref('')
const alertProjectFilter = ref('')
const alertPlanFilter = ref('')
const alertAssetFilter = ref('')
const alertTimeRange = ref<string[] | null>(null)
const alertProjectOptions = computed(() => [...new Set(alerts.value.map(item => item.projectNameSnapshot || item.projectName))])
const alertPlanOptions = computed(() => alertObjectOptions('plan'))
const alertAssetOptions = computed(() => alertObjectOptions('asset'))
const alertDrawerVisible = ref(false)
const alertDetailLoading = ref(false)
const alertActionLoading = ref(false)
const selectedAlert = ref<Alert | null>(null)
const alertEvents = ref<AlertHandlingEvent[]>([])
const viewportWidth = ref(window.innerWidth)
const alertDrawerSize = computed(() => viewportWidth.value < 720 ? '100%' : '560px')
const updateViewportWidth = () => { viewportWidth.value = window.innerWidth }
const selectedProjectName = ref('')
const projectDrawerVisible = ref(false)
const selectedProject = ref<Project | null>(null)
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
const syncingPlanId = ref<number | null>(null)
const syncingAllPlans = ref(false)
const syncingMonitoring = ref(false)
const monitoringRuns = ref<MonitoringSyncRun[]>([])
const monitoringDrawerVisible = ref(false)
const monitoringDetailLoading = ref(false)
const monitoringDetails = ref<MonitoringDetails | null>(null)
const projectForm = reactive({ name: '', projectType: '业务系统', owner: '', contactPhone: '', maintenanceExpiresAt: '', description: '', status: 'RUNNING' })
const assetForm = reactive({ name: '', projectName: '', environmentName: '生产', type: 'SERVER', endpoint: '', status: 'UNKNOWN' })
const planForm = reactive({ name: '', projectId: 0, projectName: '', status: 'ENABLED', intervalValue: 5, intervalUnit: '分钟', failureThreshold: 2, alertLevel: 'CRITICAL', assetIds: [] as number[] })
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
const healthyStatuses = new Set(['UP', 'HEALTHY'])
const abnormalStatuses = new Set(['DOWN', 'CRITICAL', 'FAILED'])
const isHealthyStatus = (status: string) => healthyStatuses.has(status)
const isAbnormalStatus = (status: string) => abnormalStatuses.has(status)
const assetCategoryStats = computed(() => assetCategories.map(category => {
  const categoryAssets = assets.value.filter(item => category.types.includes(item.type))
  const healthy = categoryAssets.filter(item => isHealthyStatus(item.status)).length
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
const filteredAssets = computed(() => assets.value.filter(item => (!selectedProjectName.value || item.projectName === selectedProjectName.value) && (serviceListMode.value === 'all' || isAbnormalStatus(item.status)) && (!activeServiceCategory.value || activeServiceCategory.value.types.includes(item.type)) && includesKeyword(item.name, item.projectName, item.endpoint, item.type)))
const selectedProjectAssets = computed(() => selectedProject.value ? assets.value.filter(item => item.projectName === selectedProject.value?.name) : [])
const selectedProjectPlans = computed(() => selectedProject.value ? inspectionPlans.value.filter(item => item.projectName === selectedProject.value?.name) : [])
const selectedProjectAlerts = computed(() => selectedProject.value ? alerts.value.filter(item => (item.projectNameSnapshot || item.projectName) === selectedProject.value?.name) : [])
const planIntervalMin = computed(() => planForm.intervalUnit === '秒' ? 10 : 1)
const planIntervalMax = computed(() => planForm.intervalUnit === '小时' ? 24 : planForm.intervalUnit === '分钟' ? 1440 : 86400)
const filteredInspections = computed(() => inspections.value.filter(item => includesKeyword(item.name, item.projectName)))
const selectedPlanInspections = computed(() => filteredInspections.value.filter(item => !selectedInspectionPlan.value || (item.name === selectedInspectionPlan.value.name && item.projectName === selectedInspectionPlan.value.projectName)))
const pagedPlanInspections = computed(() => selectedPlanInspections.value.slice((inspectionPage.value - 1) * inspectionPageSize, inspectionPage.value * inspectionPageSize))
const filteredAlerts = computed(() => {
  const severityOrder: Record<string, number> = { CRITICAL: 0, WARNING: 1, INFO: 2 }
  return alerts.value
    .filter(item => (!selectedProjectName.value || item.projectName === selectedProjectName.value)
      && (!pendingAlertsOnly.value || item.handlingStatus === 'OPEN')
      && (alertExternalFilter.value === 'ALL' || item.externalLifecycle === alertExternalFilter.value)
      && (alertHandlingFilter.value === 'ALL' || item.handlingStatus === alertHandlingFilter.value)
      && (!alertLevelFilter.value || item.level === alertLevelFilter.value)
      && (!alertProjectFilter.value || (item.projectNameSnapshot || item.projectName) === alertProjectFilter.value)
      && (!alertPlanFilter.value || alertObjectKey(item, 'plan') === alertPlanFilter.value)
      && (!alertAssetFilter.value || alertObjectKey(item, 'asset') === alertAssetFilter.value)
      && (!alertTimeRange.value || (new Date(item.occurredAt).getTime() >= new Date(alertTimeRange.value[0]!).getTime()
        && new Date(item.occurredAt).getTime() < new Date(alertTimeRange.value[1]!).getTime() + 1000))
      && includesKeyword(item.message, item.projectNameSnapshot || item.projectName, item.targetNameSnapshot || item.targetName, item.inspectionPlanName || ''))
    .sort((left, right) => new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime() || (severityOrder[left.level] ?? 9) - (severityOrder[right.level] ?? 9))
})

watch(() => planForm.intervalUnit, () => {
  planForm.intervalValue = Math.max(planIntervalMin.value, Math.min(planForm.intervalValue, planIntervalMax.value))
})

function currentAssetAlerts(asset: Asset) {
  return alerts.value.filter(alert => {
    if (alert.handlingStatus === 'CLOSED') return false
    if (alert.assetId != null) return alert.assetId === asset.id
    return (alert.projectNameSnapshot || alert.projectName) === asset.projectName
      && (alert.targetNameSnapshot || alert.targetName) === asset.name
  })
}

function alertObjectKey(alert: Alert, kind: 'plan' | 'asset') {
  const id = kind === 'plan' ? alert.inspectionPlanId : alert.assetId
  const name = kind === 'plan' ? alert.inspectionPlanName : alert.targetNameSnapshot || alert.targetName
  return id != null ? `id:${id}` : `historical:${JSON.stringify([alert.projectNameSnapshot || alert.projectName, name || ''])}`
}

function alertObjectOptions(kind: 'plan' | 'asset') {
  return [...new Map(alerts.value.filter(alert => kind !== 'plan' || alert.inspectionPlanName).map(alert => {
    const id = kind === 'plan' ? alert.inspectionPlanId : alert.assetId
    const name = kind === 'plan' ? alert.inspectionPlanName : alert.targetNameSnapshot || alert.targetName
    const value = alertObjectKey(alert, kind)
    return [value, { value, label: `${alert.projectNameSnapshot || alert.projectName} · ${name} · ${id == null ? '历史未关联' : `#${id}`}` }]
  })).values()]
}

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
  alertProjectFilter.value = ''
  alertPlanFilter.value = ''
  alertAssetFilter.value = ''
  alertLevelFilter.value = ''
  alertExternalFilter.value = 'ALL'
  alertHandlingFilter.value = 'ALL'
  alertTimeRange.value = null
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
    RUNNING: 'success', UP: 'success', HEALTHY: 'success', PASSED: 'success', RESOLVED: 'info',
    MAINTENANCE: 'warning', WARNING: 'warning', OPEN: 'danger', DOWN: 'danger', CRITICAL: 'danger', FAILED: 'danger', INFO: 'info', CLOSED: 'success', ACKNOWLEDGED: 'warning',
  }
  return mapping[status] ?? 'info'
}

function statusText(status: string) {
  const mapping: Record<string, string> = {
    RUNNING: '运行中', UP: '可用', DOWN: '不可用', MAINTENANCE: '维护中', HEALTHY: '正常', WARNING: '注意', CRITICAL: '异常',
    PASSED: '通过', FAILED: '失败', OPEN: '待处理', ACKNOWLEDGED: '已确认', CLOSED: '已关闭', FIRING: '告警中', RESOLVED: '已恢复', INFO: '提示', UNKNOWN: '状态未知',
    ENABLED: '已启用', DISABLED: '已停用', SYNCED: '已同步', NOT_SYNCED: '未同步', SYNCING: '同步中', PARTIAL: '部分成功',
    NOTE_ADDED: '添加备注', REOPENED: '新周期重开',
  }
  return mapping[status] ?? status
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}

function formatRecordTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
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
  if (editorMode.value === 'project') Object.assign(projectForm, { name: '', projectType: '业务系统', owner: '', contactPhone: '', maintenanceExpiresAt: '', description: '', status: 'RUNNING' })
  else if (editorMode.value === 'asset') Object.assign(assetForm, { name: '', projectName: selectedProjectName.value || projects.value[0]?.name || '', environmentName: '生产', type: 'SERVER', endpoint: '', status: 'UNKNOWN' })
  else {
    const project = projects.value[0]
    Object.assign(planForm, { name: '', projectId: project?.id || 0, projectName: project?.name || '', status: 'ENABLED', intervalValue: 5, intervalUnit: '分钟', failureThreshold: 2, alertLevel: 'CRITICAL', assetIds: [] })
  }
  editorVisible.value = true
}

function openProjectEdit(item: Project) {
  editorMode.value = 'project'
  editingId.value = item.id
  Object.assign(projectForm, { name: item.name, projectType: item.projectType || '业务系统', owner: item.owner, contactPhone: item.contactPhone || '', maintenanceExpiresAt: item.maintenanceExpiresAt || '', description: item.description, status: item.status })
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
  const intervalSeconds = item.intervalSeconds || 300
  const useHours = intervalSeconds % 3600 === 0
  const useMinutes = !useHours && intervalSeconds % 60 === 0
  Object.assign(planForm, { name: item.name, projectId: item.projectId || 0, projectName: item.projectName, status: item.status, intervalValue: useHours ? intervalSeconds / 3600 : useMinutes ? intervalSeconds / 60 : intervalSeconds, intervalUnit: useHours ? '小时' : useMinutes ? '分钟' : '秒', failureThreshold: item.failureThreshold || 2, alertLevel: item.alertLevel || 'CRITICAL', assetIds: [...item.assetIds] })
  editorVisible.value = true
}

function openProjectDetail(item: Project) {
  selectedProject.value = item
  projectDrawerVisible.value = true
}

function selectPlanProject(projectId: number) {
  const project = projects.value.find(item => item.id === projectId)
  planForm.projectName = project?.name || ''
  planForm.assetIds = planForm.assetIds.filter(id => assets.value.some(asset => asset.id === id && asset.projectName === planForm.projectName))
}

function planIntervalSeconds() {
  const multiplier = planForm.intervalUnit === '小时' ? 3600 : planForm.intervalUnit === '分钟' ? 60 : 1
  return planForm.intervalValue * multiplier
}

async function saveEditor() {
  saving.value = true
  try {
    if (editorMode.value === 'project') {
      const payload = { ...projectForm, contactPhone: projectForm.contactPhone || null, maintenanceExpiresAt: projectForm.maintenanceExpiresAt || null }
      if (editingId.value) await api.updateProject(editingId.value, payload)
      else await api.createProject(payload)
    } else if (editorMode.value === 'asset') {
      if (editingId.value) await api.updateAsset(editingId.value, assetForm)
      else await api.createAsset(assetForm)
    } else {
      const payload = { name: planForm.name, projectId: planForm.projectId, projectName: planForm.projectName, status: planForm.status, intervalSeconds: planIntervalSeconds(), failureThreshold: planForm.failureThreshold, alertLevel: planForm.alertLevel, assetIds: planForm.assetIds, mode: 'MONITORING' as const }
      if (editingId.value) await api.updateInspectionPlan(editingId.value, payload)
      else await api.createInspectionPlan(payload)
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

async function syncPlan(plan: InspectionPlan) {
  syncingPlanId.value = plan.id
  try {
    const result = await api.syncInspectionPlan(plan.id)
    result.status === 'SYNCED' ? ElMessage.success('巡检计划已同步到 HertzBeat') : ElMessage.warning(result.error || `同步状态：${result.status}`)
    await loadData()
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '巡检计划同步失败')
  } finally {
    syncingPlanId.value = null
  }
}

async function syncAllPlans() {
  syncingAllPlans.value = true
  try {
    const result = await api.syncAllInspectionPlans()
    const failed = result.results.filter(item => item.status !== 'SYNCED').length
    failed === 0 ? ElMessage.success(`已同步 ${result.totalPlans} 个巡检计划`) : ElMessage.warning(`同步完成：${failed} 个计划仍需处理`)
    await loadData()
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '巡检计划批量同步失败')
  } finally {
    syncingAllPlans.value = false
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

async function openAlertDetail(item: Alert) {
  alertDrawerVisible.value = true
  alertDetailLoading.value = true
  selectedAlert.value = item
  alertEvents.value = []
  try {
    const [detail, events] = await Promise.all([api.alert(item.id), api.alertEvents(item.id)])
    selectedAlert.value = detail
    alertEvents.value = events
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '告警详情加载失败')
  } finally {
    alertDetailLoading.value = false
  }
}

async function handleAlertAction(action: 'acknowledge' | 'note' | 'close') {
  if (!selectedAlert.value) return
  const title = action === 'acknowledge' ? '确认告警' : action === 'note' ? '添加处置备注' : '关闭告警'
  try {
    const result = await ElMessageBox.prompt('请输入处置说明', title, {
      confirmButtonText: '确认', cancelButtonText: '取消', inputType: 'textarea',
      inputValidator: value => action !== 'note' || Boolean(value.trim()) || '处置备注不能为空',
    })
    alertActionLoading.value = true
    const note = result.value?.trim()
    const updated = action === 'acknowledge'
      ? await api.acknowledgeAlert(selectedAlert.value.id, note)
      : action === 'note'
        ? await api.addAlertNote(selectedAlert.value.id, note)
        : await api.closeAlert(selectedAlert.value.id, note)
    selectedAlert.value = updated
    const index = alerts.value.findIndex(item => item.id === updated.id)
    if (index >= 0) alerts.value[index] = updated
    alertEvents.value = await api.alertEvents(updated.id)
    ElMessage.success(action === 'acknowledge' ? '告警已确认' : action === 'note' ? '备注已添加' : '告警已关闭')
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(reason instanceof Error ? reason.message : '告警处置失败')
  } finally {
    alertActionLoading.value = false
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
  await api.updateInspectionPlan(item.id, {
    name: item.name,
    projectId: item.projectId || 0,
    projectName: item.projectName,
    status,
    intervalSeconds: item.intervalSeconds || 300,
    failureThreshold: item.failureThreshold || 2,
    alertLevel: item.alertLevel || 'CRITICAL',
    assetIds: item.assetIds,
    mode: 'MONITORING',
  })
  ElMessage.success(status === 'ENABLED' ? '巡检计划已启用' : '巡检计划已停用')
  await loadData()
}

async function removePlan(item: InspectionPlan) {
  await ElMessageBox.confirm(`确认删除巡检计划“${item.name}”？`, '删除巡检计划', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  if (item.projectId) await api.deleteInspectionPlan(item.id)
  else await api.deleteLegacyInspectionPlan(item.id)
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

onMounted(() => {
  window.addEventListener('resize', updateViewportWidth)
  initializeAuth()
})
onBeforeUnmount(() => window.removeEventListener('resize', updateViewportWidth))
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
          <button v-if="activeView === 'alerts'" class="secondary-button" title="从 HertzBeat 拉取最新运行状态和告警，更新平台中的监控数据" :disabled="syncingMonitoring" @click="syncMonitoringData"><Activity :size="16" :class="{ 'spin-icon': syncingMonitoring }" />同步监控数据</button>
          <button v-if="activeView === 'inspections' && inspectionSection === 'plans'" class="secondary-button" title="将全部巡检计划配置同步到 HertzBeat" :disabled="syncingAllPlans" @click="syncAllPlans"><RefreshCw :size="16" :class="{ 'spin-icon': syncingAllPlans }" />同步全部计划</button>
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
        <el-alert v-if="activeView === 'assets' && filteredAssets.some(asset => !inspectionPlans.some(plan => plan.status === 'ENABLED' && plan.assetIds.includes(asset.id)))" type="info" :closable="false">
          <template #title>部分服务未配置启用的巡检计划，请前往巡检中心配置。</template>
          <el-button link type="primary" @click="navigateTo('inspections')">配置巡检计划</el-button>
        </el-alert>

        <div v-if="activeView === 'projects'" class="project-grid">
          <article v-for="item in filteredProjects" :key="item.id" class="project-card" @click="openProjectDetail(item)"><div class="project-head"><div class="project-symbol">{{ item.name.slice(0, 1) }}</div><div class="card-actions"><el-tag :type="badge(item.status)" effect="light">{{ statusText(item.status) }}</el-tag><button title="编辑项目" @click.stop="openProjectEdit(item)"><Pencil :size="15" /></button><button title="删除项目" class="danger-action" @click.stop="removeProject(item)"><Trash2 :size="15" /></button></div></div><h3>{{ item.name }}</h3><p>{{ item.description }}</p><div class="project-meta"><span>{{ item.projectType || '业务系统' }}</span><strong>{{ item.owner }}</strong></div><div class="project-footer"><button @click.stop="openProjectServices(item)"><Server :size="14" />{{ assets.filter(asset => asset.projectName === item.name).length }} 项服务<ChevronRight :size="14" /></button><button @click.stop="openProjectAlerts(item)"><AlertTriangle :size="14" />{{ alerts.filter(alert => (alert.projectNameSnapshot || alert.projectName) === item.name && alert.handlingStatus === 'OPEN').length }} 个告警<ChevronRight :size="14" /></button></div></article>
        </div>

        <div v-if="activeView === 'assets'" class="table-panel"><el-table :data="filteredAssets" stripe><el-table-column prop="name" label="服务名称" min-width="170" /><el-table-column prop="projectName" label="所属项目" min-width="150" /><el-table-column label="所属计划" min-width="160"><template #default="scope">{{ inspectionPlans.find(plan => plan.assetIds.includes(scope.row.id) && plan.lifecycleStatus !== 'DELETED')?.name || '未配置' }}</template></el-table-column><el-table-column prop="environmentName" label="环境" width="90" /><el-table-column prop="type" label="服务类型" width="120" /><el-table-column label="运行状态" width="120"><template #default="scope"><div class="asset-status"><el-tag :type="badge(scope.row.status)" effect="light">{{ statusText(scope.row.status) }}</el-tag><span v-if="scope.row.monitoringStale">{{ scope.row.lastCollectedAt ? '数据已陈旧' : '暂无采样' }}</span></div></template></el-table-column><el-table-column label="监控状态" width="150"><template #default="scope"><el-tooltip :content="scope.row.monitoringError || scope.row.syncMessage || '请在巡检计划中配置监控'" placement="top"><div class="sync-state"><el-tag :type="scope.row.mappingStatus === 'MANAGED' ? 'success' : scope.row.mappingStatus === 'INVALIDATED' ? 'danger' : 'info'" effect="light">{{ scope.row.mappingStatus === 'MANAGED' ? 'HertzBeat 管理' : scope.row.mappingStatus === 'INVALIDATED' ? '映射失效' : '未配置' }}</el-tag><span>{{ currentAssetAlerts(scope.row).length }} 个当前告警</span></div></el-tooltip></template></el-table-column><el-table-column label="操作" width="130" fixed="right"><template #default="scope"><div class="row-actions"><button title="监控详情" @click="openMonitoringDetails(scope.row)"><Activity :size="15" /></button><button title="编辑服务" @click="openAssetEdit(scope.row)"><Pencil :size="15" /></button><button title="删除服务" class="danger-action" @click="removeAsset(scope.row)"><Trash2 :size="15" /></button></div></template></el-table-column></el-table></div>

        <div v-if="activeView === 'inspections'" class="inspection-center">
          <div class="inspection-view-heading"><div class="inspection-heading-title"><button v-if="inspectionSection === 'results' && selectedInspectionPlan" class="records-back" title="返回巡检计划" @click="backToPlans"><ArrowLeft :size="18" /></button><div><h2>{{ inspectionSection === 'plans' ? '巡检计划列表' : selectedInspectionPlan ? `${selectedInspectionPlan.name} · 执行记录` : '执行记录' }}</h2><p v-if="inspectionSection === 'plans'">配置巡检周期并管理自动执行任务</p></div></div></div>
          <div v-if="inspectionSection === 'plans'" class="plan-grid">
            <article v-for="plan in inspectionPlans" :key="plan.id" class="plan-card"><div class="plan-card-head"><div class="plan-icon"><CalendarClock :size="19" /></div><div class="plan-state-tags"><el-tag :type="plan.status === 'ENABLED' ? 'success' : 'info'" effect="light">{{ statusText(plan.status) }}</el-tag><el-tag :type="plan.projectId ? plan.syncStatus === 'SYNCED' ? 'success' : plan.syncStatus === 'FAILED' || plan.syncStatus === 'PARTIAL' ? 'danger' : 'warning' : 'info'" effect="plain">{{ plan.projectId ? statusText(plan.syncStatus || 'NOT_SYNCED') : '待迁移' }}</el-tag></div></div><h3>{{ plan.name }}</h3><p>{{ plan.projectId ? `${plan.assetIds.length} 项服务 · 连续失败 ${plan.failureThreshold || 1} 次触发${statusText(plan.alertLevel || 'CRITICAL')}告警` : '旧巡检计划只保留历史执行兼容，迁移后由 HertzBeat 执行' }}</p><dl><div><dt>所属项目</dt><dd>{{ plan.projectName }}</dd></div><div><dt>监控频率</dt><dd>{{ plan.scheduleText }}</dd></div><div><dt>最近同步</dt><dd>{{ plan.lastSyncSucceededAt ? formatTime(plan.lastSyncSucceededAt) : '尚未成功' }}</dd></div><div><dt>最近校验</dt><dd>{{ plan.lastVerifiedAt ? formatTime(plan.lastVerifiedAt) : '尚未校验' }}</dd></div></dl><el-alert v-if="plan.syncError" :title="plan.syncError" type="warning" :closable="false" /><div class="plan-actions"><div class="plan-commands"><button v-if="plan.projectId" title="手动同步到 HertzBeat" :disabled="syncingPlanId === plan.id" @click="syncPlan(plan)"><RefreshCw :size="15" :class="{ 'spin-icon': syncingPlanId === plan.id }" /></button><button title="执行记录" @click="openPlanRecords(plan)"><Clock3 :size="15" /></button><button v-if="plan.projectId" class="plan-toggle-button" :class="plan.status === 'ENABLED' ? 'stop' : 'start'" :title="plan.status === 'ENABLED' ? '停用计划' : '启用计划'" @click="togglePlan(plan)"><Power :size="15" /></button></div><div><button v-if="plan.projectId" title="编辑计划" @click="openPlanEdit(plan)"><Pencil :size="15" /></button><button title="删除计划" class="danger-action" @click="removePlan(plan)"><Trash2 :size="15" /></button></div></div></article>
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

        <div v-if="activeView === 'alerts'" class="table-panel">
          <div class="alert-filters" style="flex-wrap: wrap">
            <el-select v-model="alertProjectFilter" :disabled="Boolean(selectedProjectName)" clearable placeholder="全部项目" aria-label="项目筛选" style="width: 160px"><el-option v-for="name in alertProjectOptions" :key="name" :label="name" :value="name" /></el-select>
            <el-select v-model="alertPlanFilter" clearable placeholder="全部计划" aria-label="计划筛选" style="width: 160px"><el-option v-for="option in alertPlanOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select>
            <el-select v-model="alertAssetFilter" clearable placeholder="全部服务" aria-label="服务筛选" style="width: 160px"><el-option v-for="option in alertAssetOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select>
            <el-select v-model="alertLevelFilter" clearable placeholder="全部级别" aria-label="级别筛选" style="width: 130px"><el-option label="异常" value="CRITICAL" /><el-option label="注意" value="WARNING" /><el-option label="提示" value="INFO" /></el-select>
            <el-date-picker v-model="alertTimeRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" aria-label="告警时间范围" style="max-width: 100%" />
          </div>
          <div class="alert-filters"><el-select v-model="alertExternalFilter" aria-label="外部状态筛选" style="width: 140px"><el-option label="全部外部状态" value="ALL" /><el-option label="告警中" value="FIRING" /><el-option label="已恢复" value="RESOLVED" /></el-select><el-select v-model="alertHandlingFilter" aria-label="处置状态筛选" style="width: 140px"><el-option label="全部处置状态" value="ALL" /><el-option label="待处理" value="OPEN" /><el-option label="已确认" value="ACKNOWLEDGED" /><el-option label="已关闭" value="CLOSED" /></el-select></div>
          <el-table :data="filteredAlerts" stripe @row-click="openAlertDetail"><el-table-column label="级别" width="100"><template #default="scope"><span class="severity" :class="scope.row.level.toLowerCase()">{{ statusText(scope.row.level) }}</span></template></el-table-column><el-table-column prop="message" label="告警内容" min-width="240" /><el-table-column label="所属项目" min-width="150"><template #default="scope">{{ scope.row.projectNameSnapshot || scope.row.projectName }}</template></el-table-column><el-table-column prop="inspectionPlanName" label="巡检计划" min-width="150"><template #default="scope">{{ scope.row.inspectionPlanName || '--' }}</template></el-table-column><el-table-column label="告警对象" min-width="150"><template #default="scope">{{ scope.row.targetNameSnapshot || scope.row.targetName }}</template></el-table-column><el-table-column label="发生时间" width="150"><template #default="scope">{{ formatTime(scope.row.occurredAt) }}</template></el-table-column><el-table-column label="外部状态" width="100"><template #default="scope"><el-tag :type="scope.row.externalLifecycle === 'RESOLVED' ? 'success' : 'danger'" effect="plain">{{ statusText(scope.row.externalLifecycle || 'FIRING') }}</el-tag></template></el-table-column><el-table-column label="处置状态" width="110"><template #default="scope"><el-tag :type="badge(scope.row.handlingStatus)" effect="light">{{ statusText(scope.row.handlingStatus) }}</el-tag></template></el-table-column><el-table-column label="操作" width="72" fixed="right"><template #default="scope"><button class="icon-button" title="查看告警详情" @click.stop="openAlertDetail(scope.row)"><ChevronRight :size="16" /></button></template></el-table-column></el-table>
        </div>
      </section>
    </main>

    <el-drawer v-model="editorVisible" :title="`${editingId ? '编辑' : '新增'}${editorMode === 'project' ? '项目' : editorMode === 'asset' ? '服务' : '巡检计划'}`" size="440px" destroy-on-close>
      <el-form v-if="editorMode === 'project'" label-position="top" class="editor-form" @submit.prevent="saveEditor">
        <el-form-item label="项目名称" required><el-input v-model="projectForm.name" maxlength="80" placeholder="例如：订单履约平台" /></el-form-item>
        <div class="form-grid"><el-form-item label="项目类型"><el-input v-model="projectForm.projectType" maxlength="40" /></el-form-item><el-form-item label="负责人" required><el-input v-model="projectForm.owner" maxlength="40" placeholder="请输入负责人" /></el-form-item></div>
        <div class="form-grid"><el-form-item label="联系方式"><el-input v-model="projectForm.contactPhone" maxlength="40" /></el-form-item><el-form-item label="运维到期日"><el-date-picker v-model="projectForm.maintenanceExpiresAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item></div>
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
        <el-form-item label="所属项目" required><el-select v-model="planForm.projectId" :disabled="Boolean(editingId)" placeholder="选择项目" style="width: 100%" @change="selectPlanProject"><el-option v-for="item in projects" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="纳管服务" required><el-select v-model="planForm.assetIds" multiple collapse-tags placeholder="选择相同频率的服务" style="width: 100%"><el-option v-for="item in assets.filter(asset => asset.projectName === planForm.projectName)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <div class="form-grid"><el-form-item label="监控频率" required><el-input-number v-model="planForm.intervalValue" :min="planIntervalMin" :max="planIntervalMax" style="width: 100%" /></el-form-item><el-form-item label="频率单位" required><el-select v-model="planForm.intervalUnit" style="width: 100%"><el-option label="秒" value="秒" /><el-option label="分钟" value="分钟" /><el-option label="小时" value="小时" /></el-select></el-form-item></div>
        <div class="form-grid"><el-form-item label="连续失败次数" required><el-input-number v-model="planForm.failureThreshold" :min="1" :max="10" style="width: 100%" /></el-form-item><el-form-item label="告警级别" required><el-select v-model="planForm.alertLevel" style="width: 100%"><el-option label="异常" value="CRITICAL" /><el-option label="注意" value="WARNING" /><el-option label="提示" value="INFO" /></el-select></el-form-item></div>
        <el-form-item label="计划状态"><el-segmented v-model="planForm.status" :options="[{ label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }]" /></el-form-item>
      </el-form>
      <template #footer><div class="drawer-footer"><el-button @click="editorVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEditor">保存</el-button></div></template>
    </el-drawer>

    <el-drawer v-model="projectDrawerVisible" :title="selectedProject ? `项目详情 · ${selectedProject.name}` : '项目详情'" size="520px" append-to-body>
      <div v-if="selectedProject" class="project-detail">
        <p>服务状态：{{ selectedProjectAssets.filter(asset => !isAbnormalStatus(asset.status)).length }} 项未确认异常，{{ selectedProjectAssets.filter(asset => isAbnormalStatus(asset.status)).length }} 项异常；历史告警 {{ selectedProjectAlerts.length }} 条，已关闭 {{ selectedProjectAlerts.filter(alert => alert.handlingStatus === 'CLOSED').length }} 条。</p>
        <div class="project-detail-summary"><div><span>项目类型</span><strong>{{ selectedProject.projectType || '业务系统' }}</strong></div><div><span>运行状态</span><el-tag :type="badge(selectedProject.status)">{{ statusText(selectedProject.status) }}</el-tag></div></div>
        <dl class="alert-context"><div><dt>负责人</dt><dd>{{ selectedProject.owner }}</dd></div><div><dt>联系方式</dt><dd>{{ selectedProject.contactPhone || '--' }}</dd></div><div><dt>运维到期日</dt><dd>{{ selectedProject.maintenanceExpiresAt || '--' }}</dd></div><div><dt>项目说明</dt><dd>{{ selectedProject.description || '--' }}</dd></div></dl>
        <div class="project-detail-metrics"><button @click="projectDrawerVisible = false; openProjectServices(selectedProject)"><strong>{{ selectedProjectAssets.length }}</strong><span>纳管服务</span></button><button @click="projectDrawerVisible = false; navigateTo('inspections')"><strong>{{ selectedProjectPlans.length }}</strong><span>巡检计划</span></button><button @click="projectDrawerVisible = false; openProjectAlerts(selectedProject)"><strong>{{ selectedProjectAlerts.filter(item => item.handlingStatus !== 'CLOSED').length }}</strong><span>当前告警</span></button></div>
      </div>
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
          <div class="monitoring-summary"><div><span>监控类型</span><strong>{{ monitoringDetails.asset.type }}</strong></div><div><span>最近同步尝试</span><strong>{{ monitoringDetails.asset.lastSyncAttemptAt ? formatTime(monitoringDetails.asset.lastSyncAttemptAt) : '尚未尝试' }}</strong></div><div><span>最近同步成功</span><strong>{{ monitoringDetails.asset.lastSuccessfulSyncAt ? formatTime(monitoringDetails.asset.lastSuccessfulSyncAt) : '尚未成功' }}</strong></div></div>
          <div class="monitoring-summary"><div><span>所属巡检计划</span><strong>{{ inspectionPlans.find(plan => plan.assetIds.includes(monitoringDetails!.asset.id) && plan.lifecycleStatus !== 'DELETED')?.name || '未配置' }}</strong></div><div><span>当前告警</span><strong>{{ currentAssetAlerts(monitoringDetails!.asset).length }} 个</strong></div><div><span>配置入口</span><strong>巡检中心</strong></div></div>
          <el-alert v-if="monitoringDetails.asset.monitoringError" :title="monitoringDetails.asset.monitoringError" type="warning" :closable="false" show-icon />
          <el-alert v-if="!monitoringDetails.metricsCollectedAt" title="暂无指标采样；服务可用性以监控状态为准" type="info" :closable="false" show-icon />
          <el-alert v-else-if="monitoringDetails.metricsStale" title="当前指标超过 180 秒未更新，数据已陈旧" type="warning" :closable="false" show-icon />
          <el-alert v-if="monitoringDetails.metricsMessage" :title="monitoringDetails.metricsMessage" type="info" :closable="false" show-icon />
          <div class="monitoring-metrics"><article v-for="metric in monitoringDetails.latestMetrics" :key="metric.name"><span>{{ metricLabel(metric.name) }}</span><strong>{{ metricValue(metric) }}</strong><small>{{ metric.collectedAt ? formatTime(metric.collectedAt) : '暂无采集时间' }}</small></article></div>
          <div class="trend-section"><h3>最近一小时响应时间</h3><div v-if="monitoringDetails.history.length" class="trend-bars"><i v-for="(point, index) in monitoringDetails.history" :key="index" :style="{ height: `${Math.max(8, Math.min(100, Number(point.value || 0) / 3))}%` }" :title="metricValue(point)" /></div><div v-else class="empty-trend">暂无趋势数据</div></div>
          <div class="sync-history"><h3>最近同步运行</h3><div v-for="run in monitoringRuns.slice(0, 5)" :key="run.id"><el-tag :type="run.status === 'SUCCESS' ? 'success' : run.status === 'PARTIAL' ? 'warning' : 'danger'" size="small">{{ run.status }}</el-tag><span>{{ formatRecordTime(run.startedAt) }}</span><span>状态 {{ run.assetsUpdated }} / 告警 {{ run.alertsUpdated }}</span></div></div>
          <div class="sync-history"><h3>当前告警</h3><div v-for="item in currentAssetAlerts(monitoringDetails!.asset)" :key="item.id"><el-tag :type="badge(item.level)" size="small">{{ statusText(item.level) }}</el-tag><span>{{ item.message }}</span><span>{{ statusText(item.externalLifecycle || 'FIRING') }}</span></div><div v-if="currentAssetAlerts(monitoringDetails!.asset).length === 0" class="empty-trend">暂无当前告警</div></div>
        </template>
      </div>
    </el-drawer>

    <el-drawer v-model="alertDrawerVisible" :title="selectedAlert ? `告警详情 · ${selectedAlert.targetNameSnapshot || selectedAlert.targetName}` : '告警详情'" :size="alertDrawerSize" append-to-body>
      <div v-if="selectedAlert" class="alert-detail" v-loading="alertDetailLoading">
        <div class="alert-state-summary"><div><span>外部生命周期</span><el-tag :type="selectedAlert.externalLifecycle === 'RESOLVED' ? 'success' : 'danger'" effect="plain">{{ statusText(selectedAlert.externalLifecycle || 'FIRING') }}</el-tag></div><div><span>平台处置状态</span><el-tag :type="badge(selectedAlert.handlingStatus)" effect="light">{{ statusText(selectedAlert.handlingStatus) }}</el-tag></div></div>
        <div class="alert-message"><span class="severity" :class="selectedAlert.level.toLowerCase()">{{ statusText(selectedAlert.level) }}</span><strong>{{ selectedAlert.message }}</strong></div>
        <dl class="alert-context"><div><dt>所属项目</dt><dd>{{ selectedAlert.projectNameSnapshot || selectedAlert.projectName }}</dd></div><div><dt>巡检计划</dt><dd>{{ selectedAlert.inspectionPlanName || '--' }}</dd></div><div><dt>告警对象</dt><dd>{{ selectedAlert.targetNameSnapshot || selectedAlert.targetName }}</dd></div><div><dt>首次发生</dt><dd>{{ formatRecordTime(selectedAlert.firstOccurredAt || selectedAlert.occurredAt) }}</dd></div><div><dt>本次发生</dt><dd>{{ formatRecordTime(selectedAlert.occurredAt) }}</dd></div><div><dt>恢复时间</dt><dd>{{ selectedAlert.resolvedAt ? formatRecordTime(selectedAlert.resolvedAt) : '--' }}</dd></div></dl>
        <div class="alert-actions"><el-button v-if="selectedAlert.handlingStatus === 'OPEN'" type="primary" :loading="alertActionLoading" @click="handleAlertAction('acknowledge')">确认告警</el-button><el-button :loading="alertActionLoading" @click="handleAlertAction('note')">添加备注</el-button><el-button v-if="selectedAlert.externalLifecycle === 'RESOLVED' && selectedAlert.handlingStatus !== 'CLOSED'" type="success" :loading="alertActionLoading" @click="handleAlertAction('close')">关闭告警</el-button></div>
        <section class="alert-history"><h3>处置历史</h3><el-timeline v-if="alertEvents.length"><el-timeline-item v-for="event in alertEvents" :key="event.id" :timestamp="formatRecordTime(event.occurredAt)" placement="top"><strong>{{ statusText(event.action) }}</strong><p>{{ event.operatorName }}<template v-if="event.note"> · {{ event.note }}</template></p><small>{{ statusText(event.fromStatus) }} → {{ statusText(event.toStatus) }}</small></el-timeline-item></el-timeline><div v-else class="empty-trend">暂无处置记录</div></section>
      </div>
    </el-drawer>
  </div>
</template>
