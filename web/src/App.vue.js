import InspectionProfilePanel from './InspectionProfilePanel.vue';
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Activity, AlertTriangle, ArrowLeft, BellRing, CalendarClock, CheckCircle2, ChevronRight, CircleGauge, Clock3, CloudCog, Database, ExternalLink, FolderKanban, HardDrive, ListChecks, LogOut, Pencil, Play, Plus, Power, RefreshCw, Search, Server, ShieldCheck, Trash2, XCircle } from '@lucide/vue';
import { api, setAuthToken } from './api';
import { useRoute, useRouter } from 'vue-router';
import { presentServiceState } from './serviceState';
const route = useRoute();
const router = useRouter();
const activeView = ref(route.name === 'inspection-records' ? 'inspections' : route.name || 'dashboard');
const authenticated = ref(false);
const authLoading = ref(true);
const loginLoading = ref(false);
const currentUser = reactive({ username: '', displayName: '', canManageIntegrations: false });
const loginForm = reactive({ username: 'admin', password: '' });
const loading = ref(true);
const error = ref('');
const keyword = ref('');
const summary = ref({ projectCount: 0, assetCount: 0, abnormalAssetCount: 0, openAlertCount: 0, inspectionPassRate: 0 });
const projects = ref([]);
const assets = ref([]);
const inspections = ref([]);
const inspectionPlans = ref([]);
const visibleInspectionPlans = computed(() => inspectionPlans.value.filter(item => item.lifecycleStatus !== 'DELETED'));
const inspectionItems = ref([]);
const inspectionSection = ref('plans');
const selectedInspectionPlanId = ref(null);
const selectedInspectionPlan = computed(() => inspectionPlans.value.find(plan => plan.id === selectedInspectionPlanId.value) || null);
const inspectionPage = ref(1);
const inspectionPageSize = 10;
const inspectionDrawerVisible = ref(false);
const selectedInspection = ref(null);
const inspectionDetailLoading = ref(false);
const alerts = ref([]);
const alertExternalFilter = ref('ALL');
const alertHandlingFilter = ref('ALL');
const alertLevelFilter = ref('');
const alertProjectFilter = ref('');
const alertPlanFilter = ref('');
const alertAssetFilter = ref('');
const alertTimeRange = ref(null);
const alertProjectOptions = computed(() => [...new Set(alerts.value.map(item => item.projectNameSnapshot || item.projectName))]);
const alertPlanOptions = computed(() => alertObjectOptions('plan'));
const alertAssetOptions = computed(() => alertObjectOptions('asset'));
const alertDrawerVisible = ref(false);
const alertDetailLoading = ref(false);
const alertActionLoading = ref(false);
const selectedAlert = ref(null);
const alertEvents = ref([]);
const viewportWidth = ref(window.innerWidth);
const alertDrawerSize = computed(() => viewportWidth.value < 720 ? '100%' : '560px');
const updateViewportWidth = () => { viewportWidth.value = window.innerWidth; };
const selectedProjectName = ref('');
const projectDrawerVisible = ref(false);
const selectedProject = ref(null);
const globalListSource = ref(false);
const serviceListMode = ref('all');
const serviceCategory = ref(null);
const pendingAlertsOnly = ref(false);
const hertzBeatConnected = ref(false);
const editorVisible = ref(false);
const resumeEditorAfterLogin = ref(false);
const editorMode = ref('project');
const editingId = ref(null);
const saving = ref(false);
const runningPlanId = ref(null);
const syncingPlanId = ref(null);
const syncingAllPlans = ref(false);
const syncingMonitoring = ref(false);
const monitoringDrawerVisible = ref(false);
const monitoringDetailLoading = ref(false);
const monitoringDetails = ref(null);
const projectForm = reactive({ name: '', projectType: '业务系统', owner: '', contactPhone: '', maintenanceExpiresAt: '', description: '', status: 'RUNNING' });
const assetForm = reactive({ name: '', projectName: '', environmentName: '生产', type: 'SERVER', endpoint: '', status: 'UNKNOWN' });
const planForm = reactive({ name: '', projectId: 0, projectName: '', status: 'ENABLED', intervalValue: 5, intervalUnit: '分钟', failureThreshold: 2, alertLevel: 'CRITICAL', assetIds: [] });
const environments = ['生产', '预发布', '测试', '开发'];
const assetTypes = ['SERVER', 'MYSQL', 'POSTGRESQL', 'REDIS', 'KAFKA', 'RABBITMQ', 'ROCKETMQ', 'MINIO', 'HTTP'];
const assetCategories = [
    { key: 'server', label: '服务器', title: '服务器服务', types: ['SERVER'], icon: Server },
    { key: 'database', label: '数据库', title: '数据库服务', types: ['MYSQL', 'POSTGRESQL'], icon: Database },
    { key: 'middleware', label: '缓存与队列', title: '缓存与队列服务', types: ['REDIS', 'KAFKA', 'RABBITMQ', 'ROCKETMQ'], icon: Activity },
    { key: 'interfaceStorage', label: '接口与存储', title: '接口与存储服务', types: ['HTTP', 'MINIO'], icon: CloudCog },
];
const navItems = [
    { key: 'dashboard', label: '运行总览', icon: CircleGauge },
    { key: 'projects', label: '项目中心', icon: FolderKanban },
    { key: 'inspections', label: '巡检中心', icon: ListChecks },
    { key: 'alerts', label: '告警事件', icon: BellRing },
];
const titles = {
    dashboard: { title: '运行总览', description: '跨项目基础设施状态与风险概览' },
    projects: { title: '项目中心', description: '统一管理业务系统、负责人和运行状态' },
    assets: { title: '服务管理', description: '统一管理项目下的服务器、数据库、中间件和接口服务' },
    inspections: { title: '巡检中心', description: '跟踪自动巡检执行结果和检查覆盖率' },
    alerts: { title: '告警事件', description: '集中处理异常事件并持续跟踪恢复状态' },
};
const searchPlaceholder = computed(() => {
    if (activeView.value === 'projects')
        return '搜索项目';
    if (activeView.value === 'assets')
        return '搜索服务';
    if (activeView.value === 'inspections' && inspectionSection.value === 'results')
        return '搜索执行记录';
    if (activeView.value === 'alerts')
        return '搜索告警';
    return '';
});
const filteredProjects = computed(() => projects.value.filter(item => includesKeyword(item.name, item.owner, item.description)));
const activeServiceCategory = computed(() => assetCategories.find(item => item.key === serviceCategory.value) || null);
const healthyStatuses = new Set(['UP', 'HEALTHY']);
const abnormalStatuses = new Set(['DOWN', 'CRITICAL', 'FAILED']);
const isHealthyStatus = (status) => healthyStatuses.has(status);
const isAbnormalStatus = (status) => abnormalStatuses.has(status);
const assetCategoryStats = computed(() => assetCategories.map(category => {
    const categoryAssets = assets.value.filter(item => category.types.includes(item.type));
    const healthy = categoryAssets.filter(item => isHealthyStatus(item.status)).length;
    return { ...category, count: categoryAssets.length, healthy, percentage: categoryAssets.length ? (healthy / categoryAssets.length) * 100 : 0 };
}));
const currentTitle = computed(() => {
    if (activeView.value === 'inspections' && inspectionSection.value === 'results')
        return {
            title: selectedInspectionPlan.value ? `${selectedInspectionPlan.value.name} · 执行记录` : '执行记录',
            description: '查看巡检结果与检查详情',
        };
    if (activeView.value === 'assets' && selectedProjectName.value)
        return { title: `${selectedProjectName.value} · 服务管理`, description: '查看和维护当前项目接入的运行服务' };
    if (activeView.value === 'alerts' && selectedProjectName.value)
        return { title: `${selectedProjectName.value} · 告警事件`, description: '查看当前项目产生的运行告警' };
    if (activeView.value === 'assets' && serviceListMode.value === 'abnormal')
        return { title: '异常服务', description: '查看需要持续关注的运行服务' };
    if (activeView.value === 'assets' && activeServiceCategory.value)
        return { title: activeServiceCategory.value.title, description: `查看所有项目中的${activeServiceCategory.value.label}服务` };
    if (activeView.value === 'alerts' && pendingAlertsOnly.value)
        return { title: '待处理告警', description: '查看尚未恢复的运行告警' };
    return titles[activeView.value];
});
const filteredAssets = computed(() => assets.value.filter(item => (!selectedProjectName.value || item.projectName === selectedProjectName.value) && (serviceListMode.value === 'all' || isAbnormalStatus(item.status)) && (!activeServiceCategory.value || activeServiceCategory.value.types.includes(item.type)) && includesKeyword(item.name, item.projectName, item.endpoint, item.type)));
const selectedProjectAssets = computed(() => selectedProject.value ? assets.value.filter(item => item.projectName === selectedProject.value?.name) : []);
const selectedProjectPlans = computed(() => selectedProject.value ? inspectionPlans.value.filter(item => item.projectName === selectedProject.value?.name) : []);
const selectedProjectAlerts = computed(() => selectedProject.value ? alerts.value.filter(item => (item.projectNameSnapshot || item.projectName) === selectedProject.value?.name) : []);
const planIntervalMin = computed(() => planForm.intervalUnit === '秒' ? 10 : 1);
const planIntervalMax = computed(() => planForm.intervalUnit === '小时' ? 24 : planForm.intervalUnit === '分钟' ? 1440 : 86400);
const filteredInspections = computed(() => inspections.value.filter(item => includesKeyword(item.name, item.projectName)));
const selectedPlanInspections = computed(() => filteredInspections.value.filter(item => !selectedInspectionPlanId.value || (selectedInspectionPlan.value && item.name === selectedInspectionPlan.value.name && item.projectName === selectedInspectionPlan.value.projectName)));
const pagedPlanInspections = computed(() => selectedPlanInspections.value.slice((inspectionPage.value - 1) * inspectionPageSize, inspectionPage.value * inspectionPageSize));
const filteredAlerts = computed(() => {
    const severityOrder = { CRITICAL: 0, WARNING: 1, INFO: 2 };
    return alerts.value
        .filter(item => (!selectedProjectName.value || item.projectName === selectedProjectName.value)
        && (!pendingAlertsOnly.value || item.handlingStatus === 'OPEN')
        && (alertExternalFilter.value === 'ALL' || item.externalLifecycle === alertExternalFilter.value)
        && (alertHandlingFilter.value === 'ALL' || item.handlingStatus === alertHandlingFilter.value)
        && (!alertLevelFilter.value || item.level === alertLevelFilter.value)
        && (!alertProjectFilter.value || (item.projectNameSnapshot || item.projectName) === alertProjectFilter.value)
        && (!alertPlanFilter.value || alertObjectKey(item, 'plan') === alertPlanFilter.value)
        && (!alertAssetFilter.value || alertObjectKey(item, 'asset') === alertAssetFilter.value)
        && (!alertTimeRange.value || (new Date(item.occurredAt).getTime() >= new Date(alertTimeRange.value[0]).getTime()
            && new Date(item.occurredAt).getTime() < new Date(alertTimeRange.value[1]).getTime() + 1000))
        && includesKeyword(item.message, item.projectNameSnapshot || item.projectName, item.targetNameSnapshot || item.targetName, item.inspectionPlanName || ''))
        .sort((left, right) => new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime() || (severityOrder[left.level] ?? 9) - (severityOrder[right.level] ?? 9));
});
watch(() => planForm.intervalUnit, () => {
    planForm.intervalValue = Math.max(planIntervalMin.value, Math.min(planForm.intervalValue, planIntervalMax.value));
});
function currentAssetAlerts(asset) {
    return alerts.value.filter(alert => {
        if (alert.handlingStatus === 'CLOSED')
            return false;
        if (alert.assetId != null)
            return alert.assetId === asset.id;
        return (alert.projectNameSnapshot || alert.projectName) === asset.projectName
            && (alert.targetNameSnapshot || alert.targetName) === asset.name;
    });
}
function alertObjectKey(alert, kind) {
    const id = kind === 'plan' ? alert.inspectionPlanId : alert.assetId;
    const name = kind === 'plan' ? alert.inspectionPlanName : alert.targetNameSnapshot || alert.targetName;
    return id != null ? `id:${id}` : `historical:${JSON.stringify([alert.projectNameSnapshot || alert.projectName, name || ''])}`;
}
function alertObjectOptions(kind) {
    return [...new Map(alerts.value.filter(alert => kind !== 'plan' || alert.inspectionPlanName).map(alert => {
            const id = kind === 'plan' ? alert.inspectionPlanId : alert.assetId;
            const name = kind === 'plan' ? alert.inspectionPlanName : alert.targetNameSnapshot || alert.targetName;
            const value = alertObjectKey(alert, kind);
            return [value, { value, label: `${alert.projectNameSnapshot || alert.projectName} · ${name} · ${id == null ? '历史未关联' : `#${id}`}` }];
        })).values()];
}
function navigateTo(view) {
    activeView.value = view;
    selectedProjectName.value = '';
    globalListSource.value = false;
    serviceListMode.value = 'all';
    serviceCategory.value = null;
    pendingAlertsOnly.value = false;
    if (view === 'inspections') {
        selectedInspectionPlanId.value = null;
        inspectionPage.value = 1;
        inspectionSection.value = 'plans';
    }
    keyword.value = '';
}
function openProjectServices(item) {
    selectedProjectName.value = item.name;
    globalListSource.value = false;
    serviceListMode.value = 'all';
    serviceCategory.value = null;
    activeView.value = 'assets';
    keyword.value = '';
}
function openProjectAlerts(item) {
    alertProjectFilter.value = '';
    alertPlanFilter.value = '';
    alertAssetFilter.value = '';
    alertLevelFilter.value = '';
    alertExternalFilter.value = 'ALL';
    alertHandlingFilter.value = 'ALL';
    alertTimeRange.value = null;
    selectedProjectName.value = item.name;
    globalListSource.value = false;
    pendingAlertsOnly.value = false;
    activeView.value = 'alerts';
    keyword.value = '';
}
function backToProjects() {
    selectedProjectName.value = '';
    activeView.value = 'projects';
    keyword.value = '';
}
function backFromList() {
    if (globalListSource.value || (activeView.value === 'assets' && serviceCategory.value)) {
        backToDashboard();
        return;
    }
    backToProjects();
}
function openGlobalProjects() {
    navigateTo('projects');
}
function openGlobalServices(mode) {
    selectedProjectName.value = '';
    globalListSource.value = true;
    serviceListMode.value = mode;
    serviceCategory.value = null;
    activeView.value = 'assets';
    keyword.value = '';
}
function openServiceCategory(category) {
    selectedProjectName.value = '';
    globalListSource.value = true;
    serviceListMode.value = 'all';
    serviceCategory.value = category;
    activeView.value = 'assets';
    keyword.value = '';
}
function openGlobalAlerts() {
    selectedProjectName.value = '';
    globalListSource.value = true;
    pendingAlertsOnly.value = true;
    activeView.value = 'alerts';
    keyword.value = '';
}
function openGlobalAlertList() {
    selectedProjectName.value = '';
    globalListSource.value = true;
    pendingAlertsOnly.value = false;
    activeView.value = 'alerts';
    keyword.value = '';
}
function openGlobalInspections() {
    selectedInspectionPlanId.value = null;
    inspectionPage.value = 1;
    inspectionSection.value = 'results';
    globalListSource.value = true;
    activeView.value = 'inspections';
    keyword.value = '';
}
function backToDashboard() {
    selectedProjectName.value = '';
    selectedInspectionPlanId.value = null;
    inspectionPage.value = 1;
    inspectionSection.value = 'plans';
    globalListSource.value = false;
    serviceListMode.value = 'all';
    serviceCategory.value = null;
    pendingAlertsOnly.value = false;
    activeView.value = 'dashboard';
    keyword.value = '';
}
function includesKeyword(...values) {
    const value = keyword.value.trim().toLowerCase();
    return !value || values.some(item => item.toLowerCase().includes(value));
}
function badge(status) {
    const mapping = {
        RUNNING: 'success', UP: 'success', HEALTHY: 'success', PASSED: 'success', RESOLVED: 'info',
        MAINTENANCE: 'warning', WARNING: 'warning', OPEN: 'danger', DOWN: 'danger', CRITICAL: 'danger', FAILED: 'danger', INFO: 'info', CLOSED: 'success', ACKNOWLEDGED: 'warning',
    };
    return mapping[status] ?? 'info';
}
function statusText(status) {
    const mapping = {
        RUNNING: '运行中', UP: '可用', DOWN: '不可用', MAINTENANCE: '维护中', HEALTHY: '正常', WARNING: '注意', CRITICAL: '异常',
        PASSED: '通过', FAILED: '失败', OPEN: '待处理', ACKNOWLEDGED: '已确认', CLOSED: '已关闭', FIRING: '告警中', RESOLVED: '已恢复', INFO: '提示', UNKNOWN: '状态未知',
        ENABLED: '已启用', DISABLED: '已停用', SYNCED: '已同步', NOT_SYNCED: '未同步', SYNCING: '同步中', PARTIAL: '部分成功',
        NOTE_ADDED: '添加备注', REOPENED: '新周期重开',
    };
    return mapping[status] ?? status;
}
function formatTime(value) {
    return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value));
}
function formatRecordTime(value) {
    return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value));
}
async function loadData() {
    loading.value = true;
    error.value = '';
    try {
        const [summaryData, projectData, assetData, inspectionData, planData, alertData] = await Promise.all([
            api.summary(), api.projects(), api.assets(), api.inspections(), api.inspectionPlans(), api.alerts(),
        ]);
        summary.value = summaryData;
        projects.value = projectData;
        assets.value = assetData;
        inspections.value = inspectionData;
        inspectionPlans.value = planData;
        alerts.value = alertData;
        try {
            const hertzBeatStatus = await api.hertzBeatStatus();
            hertzBeatConnected.value = hertzBeatStatus.connected;
        }
        catch {
            hertzBeatConnected.value = false;
        }
    }
    catch (reason) {
        if (reason instanceof Error && reason.message === 'AUTH_REQUIRED') {
            setAuthToken('');
            authenticated.value = false;
            return;
        }
        error.value = reason instanceof Error ? reason.message : '数据加载失败';
    }
    finally {
        loading.value = false;
    }
}
async function initializeAuth() {
    try {
        const user = await api.me();
        Object.assign(currentUser, user);
        authenticated.value = true;
        await loadData();
    }
    catch {
        setAuthToken('');
        authenticated.value = false;
    }
    finally {
        authLoading.value = false;
    }
}
async function login() {
    loginLoading.value = true;
    try {
        const session = await api.login(loginForm.username, loginForm.password);
        setAuthToken(session.token);
        Object.assign(currentUser, session);
        authenticated.value = true;
        loginForm.password = '';
        await loadData();
        if (authenticated.value && resumeEditorAfterLogin.value) {
            editorVisible.value = true;
            resumeEditorAfterLogin.value = false;
        }
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error && reason.message !== 'AUTH_REQUIRED' ? reason.message : '用户名或密码错误');
    }
    finally {
        loginLoading.value = false;
    }
}
async function logout() {
    try {
        await api.logout();
    }
    catch { /* 会话失效时直接清理本地状态 */ }
    setAuthToken('');
    authenticated.value = false;
}
async function openInspectionDetail(item) {
    selectedInspection.value = item;
    inspectionDrawerVisible.value = true;
    inspectionDetailLoading.value = true;
    try {
        inspectionItems.value = await api.inspectionItems(item.id);
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '巡检详情加载失败');
    }
    finally {
        inspectionDetailLoading.value = false;
    }
}
function inspectionCount(status) {
    return inspectionItems.value.filter(item => item.status === status).length;
}
function openCreate() {
    editingId.value = null;
    editorMode.value = activeView.value === 'projects' ? 'project' : activeView.value === 'assets' ? 'asset' : 'plan';
    if (editorMode.value === 'project')
        Object.assign(projectForm, { name: '', projectType: '业务系统', owner: '', contactPhone: '', maintenanceExpiresAt: '', description: '', status: 'RUNNING' });
    else if (editorMode.value === 'asset')
        Object.assign(assetForm, { name: '', projectName: selectedProjectName.value || projects.value[0]?.name || '', environmentName: '生产', type: 'SERVER', endpoint: '', status: 'UNKNOWN' });
    else {
        const project = projects.value[0];
        Object.assign(planForm, { name: '', projectId: project?.id || 0, projectName: project?.name || '', status: 'ENABLED', intervalValue: 5, intervalUnit: '分钟', failureThreshold: 2, alertLevel: 'CRITICAL', assetIds: [] });
    }
    editorVisible.value = true;
}
function currentServicePlan(asset) {
    return inspectionPlans.value.find(plan => plan.lifecycleStatus === 'ACTIVE'
        && plan.projectName === asset.projectName && plan.assetIds.includes(asset.id));
}
function serviceState(asset) {
    return presentServiceState(asset, currentServicePlan(asset)?.intervalSeconds || 0);
}
function planCheckSummary(plan) {
    const services = assets.value.filter(asset => plan.assetIds.includes(asset.id));
    const record = inspections.value.find(item => item.name === plan.name && item.projectName === plan.projectName);
    const sampleTimes = services.map(asset => asset.lastCollectedAt).filter((time) => Boolean(time));
    const latestSample = sampleTimes.sort((first, second) => new Date(second).getTime() - new Date(first).getTime())[0];
    const useRecord = record && (!latestSample || new Date(record.executedAt).getTime() >= new Date(latestSample).getTime());
    const lastCheckedAt = useRecord ? record.executedAt : latestSample;
    const states = services.map(serviceState);
    const result = useRecord ? (record.status === 'PASSED' ? '正常' : '异常')
        : states.some(state => state.text === '异常') ? '异常'
            : states.length && states.every(state => state.text === '正常') ? '正常' : lastCheckedAt ? '待更新' : '尚未检查';
    const interval = plan.intervalSeconds;
    const frequency = interval ? interval % 3600 === 0 ? `每 ${interval / 3600} 小时`
        : interval % 60 === 0 ? `每 ${interval / 60} 分钟` : `每 ${interval} 秒` : plan.scheduleText;
    let nextCheck = '等待首次检查';
    if (plan.status !== 'ENABLED')
        nextCheck = '已停用';
    else if (plan.syncStatus === 'FAILED' || plan.syncStatus === 'PARTIAL')
        nextCheck = '配置异常';
    else if (interval) {
        const candidates = services.filter(asset => asset.lastCollectedAt)
            .map(asset => new Date(asset.lastCollectedAt).getTime() + interval * 1000);
        const nextTime = candidates.length ? Math.min(...candidates) : null;
        if (nextTime)
            nextCheck = nextTime > Date.now() ? `预计 ${formatRecordTime(new Date(nextTime).toISOString())}` : '等待执行';
    }
    else if (plan.nextRunAt)
        nextCheck = formatRecordTime(plan.nextRunAt);
    return { frequency, lastChecked: lastCheckedAt ? formatRecordTime(lastCheckedAt) : '尚未检查', result, nextCheck };
}
function serviceInspectionConfiguration(asset) {
    if (asset.mappingStatus === 'INVALIDATED' || asset.syncStatus === 'FAILED' || asset.monitoringError)
        return { text: '配置异常', type: 'danger' };
    const plan = visibleInspectionPlans.value.find(item => item.projectName === asset.projectName && item.assetIds.includes(asset.id));
    if (!plan)
        return { text: '未配置', type: 'info' };
    if (plan.syncStatus === 'FAILED' || plan.syncStatus === 'PARTIAL')
        return { text: '配置异常', type: 'danger' };
    return { text: '已配置', type: 'success' };
}
function openAssetPlanConfiguration(asset) {
    const project = projects.value.find(item => item.name === asset.projectName);
    if (!project) {
        ElMessage.warning('请先补齐服务所属项目档案');
        return;
    }
    const current = currentServicePlan(asset);
    monitoringDrawerVisible.value = false;
    navigateTo('inspections');
    if (current)
        openPlanEdit(current);
    else {
        openCreate();
        Object.assign(planForm, { projectId: project.id, projectName: project.name, assetIds: [asset.id] });
    }
}
function openProjectEdit(item) {
    editorMode.value = 'project';
    editingId.value = item.id;
    Object.assign(projectForm, { name: item.name, projectType: item.projectType || '业务系统', owner: item.owner, contactPhone: item.contactPhone || '', maintenanceExpiresAt: item.maintenanceExpiresAt || '', description: item.description, status: item.status });
    editorVisible.value = true;
}
function openAssetEdit(item) {
    editorMode.value = 'asset';
    editingId.value = item.id;
    Object.assign(assetForm, { name: item.name, projectName: item.projectName, environmentName: item.environmentName, type: item.type, endpoint: item.endpoint, status: item.status });
    editorVisible.value = true;
}
function openPlanEdit(item) {
    editorMode.value = 'plan';
    editingId.value = item.id;
    const intervalSeconds = item.intervalSeconds || 300;
    const useHours = intervalSeconds % 3600 === 0;
    const useMinutes = !useHours && intervalSeconds % 60 === 0;
    Object.assign(planForm, { name: item.name, projectId: item.projectId || 0, projectName: item.projectName, status: item.status, intervalValue: useHours ? intervalSeconds / 3600 : useMinutes ? intervalSeconds / 60 : intervalSeconds, intervalUnit: useHours ? '小时' : useMinutes ? '分钟' : '秒', failureThreshold: item.failureThreshold || 2, alertLevel: item.alertLevel || 'CRITICAL', assetIds: [...item.assetIds] });
    editorVisible.value = true;
}
function openProjectDetail(item) {
    selectedProject.value = item;
    projectDrawerVisible.value = true;
}
function selectPlanProject(projectId) {
    const project = projects.value.find(item => item.id === projectId);
    planForm.projectName = project?.name || '';
    planForm.assetIds = planForm.assetIds.filter(id => assets.value.some(asset => asset.id === id && asset.projectName === planForm.projectName));
}
function planIntervalSeconds() {
    const multiplier = planForm.intervalUnit === '小时' ? 3600 : planForm.intervalUnit === '分钟' ? 60 : 1;
    return planForm.intervalValue * multiplier;
}
async function saveEditor() {
    saving.value = true;
    try {
        if (editorMode.value === 'project') {
            const payload = { ...projectForm, contactPhone: projectForm.contactPhone || null, maintenanceExpiresAt: projectForm.maintenanceExpiresAt || null };
            if (editingId.value)
                await api.updateProject(editingId.value, payload);
            else
                await api.createProject(payload);
        }
        else if (editorMode.value === 'asset') {
            if (editingId.value)
                await api.updateAsset(editingId.value, assetForm);
            else
                await api.createAsset(assetForm);
        }
        else {
            const payload = { name: planForm.name, projectId: planForm.projectId, projectName: planForm.projectName, status: planForm.status, intervalSeconds: planIntervalSeconds(), failureThreshold: planForm.failureThreshold, alertLevel: planForm.alertLevel, assetIds: planForm.assetIds, mode: 'MONITORING' };
            if (editingId.value)
                await api.updateInspectionPlan(editingId.value, payload);
            else
                await api.createInspectionPlan(payload);
        }
        ElMessage.success(editingId.value ? '修改已保存' : '新增成功');
        editorVisible.value = false;
        await loadData();
    }
    catch (reason) {
        if (reason instanceof Error && reason.message === 'AUTH_REQUIRED') {
            resumeEditorAfterLogin.value = true;
            editorVisible.value = false;
            setAuthToken('');
            authenticated.value = false;
            ElMessage.warning('登录已失效，请重新登录；已保留填写内容，登录后可继续保存');
        }
        else {
            ElMessage.error(reason instanceof Error ? reason.message : '保存失败');
        }
    }
    finally {
        saving.value = false;
    }
}
async function removeProject(item) {
    await ElMessageBox.confirm(`确认删除项目“${item.name}”？`, '删除项目', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
    await api.deleteProject(item.id);
    ElMessage.success('项目已删除');
    await loadData();
}
async function removeAsset(item) {
    const productionHint = item.environmentName === '生产' ? '这是生产环境服务，删除后无法恢复。' : '删除后无法恢复。';
    await ElMessageBox.confirm(`${productionHint}确认删除“${item.name}”？`, '删除服务', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' });
    await api.deleteAsset(item.id);
    ElMessage.success('服务已删除');
    await loadData();
}
async function syncPlan(plan) {
    syncingPlanId.value = plan.id;
    try {
        const result = await api.syncInspectionPlan(plan.id);
        result.status === 'SYNCED' ? ElMessage.success('巡检配置已更新') : ElMessage.warning('配置更新未完成，请检查计划配置');
        await loadData();
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '巡检计划同步失败');
    }
    finally {
        syncingPlanId.value = null;
    }
}
async function syncAllPlans() {
    syncingAllPlans.value = true;
    try {
        const result = await api.syncAllInspectionPlans();
        const failed = result.results.filter(item => item.status !== 'SYNCED').length;
        failed === 0 ? ElMessage.success(`已同步 ${result.totalPlans} 个巡检计划`) : ElMessage.warning(`同步完成：${failed} 个计划仍需处理`);
        await loadData();
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '巡检计划批量同步失败');
    }
    finally {
        syncingAllPlans.value = false;
    }
}
async function syncMonitoringData() {
    syncingMonitoring.value = true;
    try {
        const run = await api.syncMonitoringData();
        ElMessage[run.status === 'SUCCESS' ? 'success' : run.status === 'SKIPPED' ? 'warning' : 'error'](run.status === 'SUCCESS' ? `监控数据已更新：状态 ${run.assetsUpdated} 项，告警 ${run.alertsUpdated} 项` : run.errorSummary || '已有同步任务正在运行');
        await loadData();
    }
    finally {
        syncingMonitoring.value = false;
    }
}
async function openAlertDetail(item) {
    alertDrawerVisible.value = true;
    alertDetailLoading.value = true;
    selectedAlert.value = item;
    alertEvents.value = [];
    try {
        const [detail, events] = await Promise.all([api.alert(item.id), api.alertEvents(item.id)]);
        selectedAlert.value = detail;
        alertEvents.value = events;
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '告警详情加载失败');
    }
    finally {
        alertDetailLoading.value = false;
    }
}
async function handleAlertAction(action) {
    if (!selectedAlert.value)
        return;
    const title = action === 'acknowledge' ? '确认告警' : action === 'note' ? '添加处置备注' : '关闭告警';
    try {
        const result = await ElMessageBox.prompt('请输入处置说明', title, {
            confirmButtonText: '确认', cancelButtonText: '取消', inputType: 'textarea',
            inputValidator: value => action !== 'note' || Boolean(value.trim()) || '处置备注不能为空',
        });
        alertActionLoading.value = true;
        const note = result.value?.trim();
        const updated = action === 'acknowledge'
            ? await api.acknowledgeAlert(selectedAlert.value.id, note)
            : action === 'note'
                ? await api.addAlertNote(selectedAlert.value.id, note)
                : await api.closeAlert(selectedAlert.value.id, note);
        selectedAlert.value = updated;
        const index = alerts.value.findIndex(item => item.id === updated.id);
        if (index >= 0)
            alerts.value[index] = updated;
        alertEvents.value = await api.alertEvents(updated.id);
        ElMessage.success(action === 'acknowledge' ? '告警已确认' : action === 'note' ? '备注已添加' : '告警已关闭');
    }
    catch (reason) {
        if (reason !== 'cancel' && reason !== 'close')
            ElMessage.error(reason instanceof Error ? reason.message : '告警处置失败');
    }
    finally {
        alertActionLoading.value = false;
    }
}
async function openMonitoringDetails(item) {
    monitoringDrawerVisible.value = true;
    monitoringDetailLoading.value = true;
    monitoringDetails.value = null;
    try {
        monitoringDetails.value = await api.monitoringDetails(item.id);
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '监控详情加载失败');
    }
    finally {
        monitoringDetailLoading.value = false;
    }
}
function metricLabel(name) {
    return name === 'availability' ? '可用性' : name === 'responseTime' ? '响应时间' : name === 'httpStatus' ? 'HTTP 状态码' : name;
}
function metricValue(metric) {
    if (metric.booleanValue !== null)
        return metric.booleanValue ? '可用' : '不可用';
    return `${metric.value ?? '--'}${metric.unit ? ` ${metric.unit}` : ''}`;
}
async function togglePlan(item) {
    const status = item.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
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
    });
    ElMessage.success(status === 'ENABLED' ? '巡检计划已启用' : '巡检计划已停用');
    await loadData();
}
async function removePlan(item) {
    await ElMessageBox.confirm(`确认删除巡检计划“${item.name}”？`, '删除巡检计划', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
    await api.deleteInspectionPlan(item.id);
    ElMessage.success('巡检计划已删除');
    await loadData();
}
async function executePlan(item) {
    if (runningPlanId.value !== null)
        return;
    runningPlanId.value = item.id;
    try {
        const result = await api.executeInspectionPlan(item.id);
        ElMessage.success(result.status === 'PASSED' ? '巡检执行完成，全部检查通过' : '巡检执行完成，发现异常项');
        await loadData();
        openPlanRecords(item);
    }
    catch (reason) {
        if (reason instanceof Error && reason.message === 'AUTH_REQUIRED') {
            setAuthToken('');
            authenticated.value = false;
            ElMessage.warning('登录已失效，请重新登录后执行');
        }
        else {
            ElMessage.error(reason instanceof Error ? reason.message : '巡检执行失败');
        }
    }
    finally {
        runningPlanId.value = null;
    }
}
function openPlanRecords(item) {
    keyword.value = '';
    selectedInspectionPlanId.value = item.id;
    inspectionPage.value = 1;
    inspectionSection.value = 'results';
}
function backToPlans() {
    selectedInspectionPlanId.value = null;
    inspectionPage.value = 1;
    inspectionSection.value = 'plans';
    keyword.value = '';
}
let restoringPage = false;
let restoredPageKey = '';
function pageLocation() {
    const records = activeView.value === 'inspections' && inspectionSection.value === 'results';
    const query = {};
    const add = (key, value, fallback = '') => { if (value && value !== fallback)
        query[key] = value; };
    add('q', keyword.value);
    if (activeView.value === 'assets' || activeView.value === 'alerts') {
        add('project', selectedProjectName.value);
        add('from', globalListSource.value ? 'overview' : '');
    }
    if (activeView.value === 'assets') {
        add('status', serviceListMode.value, 'all');
        add('category', serviceCategory.value || '');
    }
    if (activeView.value === 'alerts') {
        add('pending', pendingAlertsOnly.value ? '1' : '');
        add('external', alertExternalFilter.value, 'ALL');
        add('handling', alertHandlingFilter.value, 'ALL');
        add('level', alertLevelFilter.value);
        add('alertProject', alertProjectFilter.value);
        add('plan', alertPlanFilter.value);
        add('asset', alertAssetFilter.value);
        add('start', alertTimeRange.value?.[0] || '');
        add('end', alertTimeRange.value?.[1] || '');
    }
    if (records && inspectionPage.value > 1)
        add('page', String(inspectionPage.value));
    return {
        name: records ? 'inspection-records' : activeView.value,
        params: records && selectedInspectionPlanId.value ? { planId: String(selectedInspectionPlanId.value) } : {},
        query,
    };
}
function restorePageFromRoute() {
    restoringPage = true;
    const value = (key) => typeof route.query[key] === 'string' ? route.query[key] : '';
    activeView.value = route.name === 'inspection-records' ? 'inspections' : route.name || 'dashboard';
    inspectionSection.value = route.name === 'inspection-records' ? 'results' : 'plans';
    const planId = Number(route.params.planId);
    selectedInspectionPlanId.value = Number.isSafeInteger(planId) && planId > 0 ? planId : null;
    const page = Number(value('page'));
    inspectionPage.value = Number.isSafeInteger(page) && page > 0 ? page : 1;
    keyword.value = value('q');
    selectedProjectName.value = value('project');
    globalListSource.value = value('from') === 'overview';
    serviceListMode.value = value('status') === 'abnormal' ? 'abnormal' : 'all';
    serviceCategory.value = assetCategories.find(item => item.key === value('category'))?.key || null;
    pendingAlertsOnly.value = value('pending') === '1';
    alertExternalFilter.value = value('external') || 'ALL';
    alertHandlingFilter.value = value('handling') || 'ALL';
    alertLevelFilter.value = value('level');
    alertProjectFilter.value = value('alertProject');
    alertPlanFilter.value = value('plan');
    alertAssetFilter.value = value('asset');
    alertTimeRange.value = value('start') && value('end') ? [value('start'), value('end')] : null;
    restoredPageKey = JSON.stringify(pageLocation());
    restoringPage = false;
}
watch(() => route.fullPath, restorePageFromRoute, { immediate: true, flush: 'sync' });
watch(pageLocation, location => {
    const key = JSON.stringify(location);
    if (key === restoredPageKey)
        return;
    restoredPageKey = key;
    const contextChanged = location.name !== route.name || (location.params.planId || '') !== (route.params.planId || '')
        || ['project', 'from', 'status', 'category', 'pending'].some(field => (location.query[field] || '') !== (route.query[field] || ''));
    if (contextChanged)
        void router.push(location);
    else
        void router.replace(location);
}, { flush: 'post' });
watch(keyword, () => {
    if (!restoringPage && activeView.value === 'inspections' && inspectionSection.value === 'results')
        inspectionPage.value = 1;
}, { flush: 'sync' });
onMounted(() => {
    window.addEventListener('resize', updateViewportWidth);
    initializeAuth();
});
onBeforeUnmount(() => {
    window.removeEventListener('resize', updateViewportWidth);
}); /* PartiallyEnd: #3632/scriptSetup.vue */
function __VLS_template() {
    const __VLS_ctx = {};
    let __VLS_components;
    let __VLS_directives;
    if (__VLS_ctx.authLoading) {
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("auth-loading") },
        });
        const __VLS_0 = {}.Activity;
        /** @type { [typeof __VLS_components.Activity, ] } */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            size: ((26)),
            ...{ class: ("spinning") },
        }));
        const __VLS_2 = __VLS_1({
            size: ((26)),
            ...{ class: ("spinning") },
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    }
    else if (!__VLS_ctx.authenticated) {
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("login-page") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
            ...{ class: ("login-panel") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("login-brand") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("brand-mark") },
        });
        const __VLS_6 = {}.Activity;
        /** @type { [typeof __VLS_components.Activity, ] } */ ;
        // @ts-ignore
        const __VLS_7 = __VLS_asFunctionalComponent(__VLS_6, new __VLS_6({
            size: ((22)),
        }));
        const __VLS_8 = __VLS_7({
            size: ((22)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_7));
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
        const __VLS_12 = {}.ElForm;
        /** @type { [typeof __VLS_components.ElForm, typeof __VLS_components.elForm, typeof __VLS_components.ElForm, typeof __VLS_components.elForm, ] } */ ;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
            ...{ 'onSubmit': {} },
            labelPosition: ("top"),
        }));
        const __VLS_14 = __VLS_13({
            ...{ 'onSubmit': {} },
            labelPosition: ("top"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
        let __VLS_18;
        const __VLS_19 = {
            onSubmit: (__VLS_ctx.login)
        };
        let __VLS_15;
        let __VLS_16;
        const __VLS_20 = {}.ElFormItem;
        /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
        // @ts-ignore
        const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
            label: ("用户名"),
        }));
        const __VLS_22 = __VLS_21({
            label: ("用户名"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_21));
        const __VLS_26 = {}.ElInput;
        /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
        // @ts-ignore
        const __VLS_27 = __VLS_asFunctionalComponent(__VLS_26, new __VLS_26({
            modelValue: ((__VLS_ctx.loginForm.username)),
            autocomplete: ("username"),
        }));
        const __VLS_28 = __VLS_27({
            modelValue: ((__VLS_ctx.loginForm.username)),
            autocomplete: ("username"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_27));
        __VLS_25.slots.default;
        var __VLS_25;
        const __VLS_32 = {}.ElFormItem;
        /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
        // @ts-ignore
        const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
            label: ("密码"),
        }));
        const __VLS_34 = __VLS_33({
            label: ("密码"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_33));
        const __VLS_38 = {}.ElInput;
        /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
        // @ts-ignore
        const __VLS_39 = __VLS_asFunctionalComponent(__VLS_38, new __VLS_38({
            ...{ 'onKeyup': {} },
            modelValue: ((__VLS_ctx.loginForm.password)),
            type: ("password"),
            showPassword: (true),
            autocomplete: ("current-password"),
        }));
        const __VLS_40 = __VLS_39({
            ...{ 'onKeyup': {} },
            modelValue: ((__VLS_ctx.loginForm.password)),
            type: ("password"),
            showPassword: (true),
            autocomplete: ("current-password"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_39));
        let __VLS_44;
        const __VLS_45 = {
            onKeyup: (__VLS_ctx.login)
        };
        let __VLS_41;
        let __VLS_42;
        var __VLS_43;
        __VLS_37.slots.default;
        var __VLS_37;
        const __VLS_46 = {}.ElButton;
        /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
        // @ts-ignore
        const __VLS_47 = __VLS_asFunctionalComponent(__VLS_46, new __VLS_46({
            ...{ 'onClick': {} },
            type: ("primary"),
            loading: ((__VLS_ctx.loginLoading)),
            ...{ class: ("login-submit") },
        }));
        const __VLS_48 = __VLS_47({
            ...{ 'onClick': {} },
            type: ("primary"),
            loading: ((__VLS_ctx.loginLoading)),
            ...{ class: ("login-submit") },
        }, ...__VLS_functionalComponentArgsRest(__VLS_47));
        let __VLS_52;
        const __VLS_53 = {
            onClick: (__VLS_ctx.login)
        };
        let __VLS_49;
        let __VLS_50;
        __VLS_51.slots.default;
        var __VLS_51;
        __VLS_17.slots.default;
        var __VLS_17;
    }
    else {
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("app-shell") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
            ...{ class: ("sidebar") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("brand") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("brand-mark") },
        });
        const __VLS_54 = {}.Activity;
        /** @type { [typeof __VLS_components.Activity, ] } */ ;
        // @ts-ignore
        const __VLS_55 = __VLS_asFunctionalComponent(__VLS_54, new __VLS_54({
            size: ((21)),
        }));
        const __VLS_56 = __VLS_55({
            size: ((21)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_55));
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("scope-label") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({});
        for (const [item] of __VLS_getVForSourceType((__VLS_ctx.navItems))) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        __VLS_ctx.navigateTo(item.key);
                    } },
                key: ((item.key)),
                ...{ class: ("nav-item") },
                ...{ class: (({ active: __VLS_ctx.activeView === item.key })) },
            });
            const __VLS_60 = ((item.icon));
            // @ts-ignore
            const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
                size: ((18)),
            }));
            const __VLS_62 = __VLS_61({
                size: ((18)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_61));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (item.label);
            if (__VLS_ctx.activeView === item.key) {
                const __VLS_66 = {}.ChevronRight;
                /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
                // @ts-ignore
                const __VLS_67 = __VLS_asFunctionalComponent(__VLS_66, new __VLS_66({
                    size: ((15)),
                    ...{ class: ("nav-arrow") },
                }));
                const __VLS_68 = __VLS_67({
                    size: ((15)),
                    ...{ class: ("nav-arrow") },
                }, ...__VLS_functionalComponentArgsRest(__VLS_67));
            }
        }
        __VLS_elementAsFunction(__VLS_intrinsicElements.div)({
            ...{ class: ("sidebar-spacer") },
        });
        if (__VLS_ctx.currentUser.canManageIntegrations) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("integration-list") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("scope-label") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
                href: ("http://localhost:1157"),
                target: ("_blank"),
            });
            const __VLS_72 = {}.CloudCog;
            /** @type { [typeof __VLS_components.CloudCog, ] } */ ;
            // @ts-ignore
            const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
                size: ((17)),
            }));
            const __VLS_74 = __VLS_73({
                size: ((17)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_73));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.i)({
                ...{ class: ("integration-dot") },
                ...{ class: (({ connected: __VLS_ctx.hertzBeatConnected })) },
            });
            const __VLS_78 = {}.ExternalLink;
            /** @type { [typeof __VLS_components.ExternalLink, ] } */ ;
            // @ts-ignore
            const __VLS_79 = __VLS_asFunctionalComponent(__VLS_78, new __VLS_78({
                size: ((13)),
            }));
            const __VLS_80 = __VLS_79({
                size: ((13)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_79));
            __VLS_elementAsFunction(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
                href: ("http://localhost:8428/vmui"),
                target: ("_blank"),
            });
            const __VLS_84 = {}.Activity;
            /** @type { [typeof __VLS_components.Activity, ] } */ ;
            // @ts-ignore
            const __VLS_85 = __VLS_asFunctionalComponent(__VLS_84, new __VLS_84({
                size: ((17)),
            }));
            const __VLS_86 = __VLS_85({
                size: ((17)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_85));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            const __VLS_90 = {}.ExternalLink;
            /** @type { [typeof __VLS_components.ExternalLink, ] } */ ;
            // @ts-ignore
            const __VLS_91 = __VLS_asFunctionalComponent(__VLS_90, new __VLS_90({
                size: ((13)),
            }));
            const __VLS_92 = __VLS_91({
                size: ((13)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_91));
        }
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("operator") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("avatar") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        (__VLS_ctx.currentUser.displayName);
        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.currentUser.username);
        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.logout) },
            title: ("退出登录"),
        });
        const __VLS_96 = {}.LogOut;
        /** @type { [typeof __VLS_components.LogOut, ] } */ ;
        // @ts-ignore
        const __VLS_97 = __VLS_asFunctionalComponent(__VLS_96, new __VLS_96({
            size: ((17)),
        }));
        const __VLS_98 = __VLS_97({
            size: ((17)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_97));
        __VLS_elementAsFunction(__VLS_intrinsicElements.main, __VLS_intrinsicElements.main)({
            ...{ class: ("main-area") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
            ...{ class: ("topbar") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("topbar-title") },
        });
        if (__VLS_ctx.activeView === 'inspections' && __VLS_ctx.inspectionSection === 'results') {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.backToPlans) },
                ...{ class: ("records-back") },
                title: ("返回巡检计划"),
                'aria-label': ("返回巡检计划"),
            });
            const __VLS_102 = {}.ArrowLeft;
            /** @type { [typeof __VLS_components.ArrowLeft, ] } */ ;
            // @ts-ignore
            const __VLS_103 = __VLS_asFunctionalComponent(__VLS_102, new __VLS_102({
                size: ((18)),
            }));
            const __VLS_104 = __VLS_103({
                size: ((18)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_103));
        }
        if (((__VLS_ctx.activeView === 'assets' || __VLS_ctx.activeView === 'alerts') && __VLS_ctx.selectedProjectName) || (__VLS_ctx.activeView === 'assets' && __VLS_ctx.globalListSource)) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.backFromList) },
                ...{ class: ("records-back") },
                title: ((__VLS_ctx.globalListSource ? '返回运行总览' : '返回项目中心')),
                'aria-label': ((__VLS_ctx.globalListSource ? '返回运行总览' : '返回项目中心')),
            });
            const __VLS_108 = {}.ArrowLeft;
            /** @type { [typeof __VLS_components.ArrowLeft, ] } */ ;
            // @ts-ignore
            const __VLS_109 = __VLS_asFunctionalComponent(__VLS_108, new __VLS_108({
                size: ((18)),
            }));
            const __VLS_110 = __VLS_109({
                size: ((18)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_109));
        }
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({});
        (__VLS_ctx.currentTitle.title);
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("topbar-actions") },
        });
        if (__VLS_ctx.searchPlaceholder) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("search") },
            });
            const __VLS_114 = {}.Search;
            /** @type { [typeof __VLS_components.Search, ] } */ ;
            // @ts-ignore
            const __VLS_115 = __VLS_asFunctionalComponent(__VLS_114, new __VLS_114({
                size: ((17)),
            }));
            const __VLS_116 = __VLS_115({
                size: ((17)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_115));
            __VLS_elementAsFunction(__VLS_intrinsicElements.input)({
                placeholder: ((__VLS_ctx.searchPlaceholder)),
            });
            (__VLS_ctx.keyword);
        }
        if (__VLS_ctx.activeView === 'alerts') {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.syncMonitoringData) },
                ...{ class: ("secondary-button") },
                title: ("更新服务状态和告警"),
                disabled: ((__VLS_ctx.syncingMonitoring)),
            });
            const __VLS_120 = {}.Activity;
            /** @type { [typeof __VLS_components.Activity, ] } */ ;
            // @ts-ignore
            const __VLS_121 = __VLS_asFunctionalComponent(__VLS_120, new __VLS_120({
                size: ((16)),
                ...{ class: (({ 'spin-icon': __VLS_ctx.syncingMonitoring })) },
            }));
            const __VLS_122 = __VLS_121({
                size: ((16)),
                ...{ class: (({ 'spin-icon': __VLS_ctx.syncingMonitoring })) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_121));
        }
        if (__VLS_ctx.activeView === 'inspections' && __VLS_ctx.inspectionSection === 'plans' && __VLS_ctx.visibleInspectionPlans.length > 0) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.syncAllPlans) },
                ...{ class: ("secondary-button") },
                title: ("更新全部巡检配置"),
                disabled: ((__VLS_ctx.syncingAllPlans)),
            });
            const __VLS_126 = {}.RefreshCw;
            /** @type { [typeof __VLS_components.RefreshCw, ] } */ ;
            // @ts-ignore
            const __VLS_127 = __VLS_asFunctionalComponent(__VLS_126, new __VLS_126({
                size: ((16)),
                ...{ class: (({ 'spin-icon': __VLS_ctx.syncingAllPlans })) },
            }));
            const __VLS_128 = __VLS_127({
                size: ((16)),
                ...{ class: (({ 'spin-icon': __VLS_ctx.syncingAllPlans })) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_127));
        }
        if (__VLS_ctx.activeView === 'projects' || __VLS_ctx.activeView === 'assets' || (__VLS_ctx.activeView === 'inspections' && __VLS_ctx.inspectionSection === 'plans')) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.openCreate) },
                ...{ class: ("primary-button") },
            });
            const __VLS_132 = {}.Plus;
            /** @type { [typeof __VLS_components.Plus, ] } */ ;
            // @ts-ignore
            const __VLS_133 = __VLS_asFunctionalComponent(__VLS_132, new __VLS_132({
                size: ((16)),
            }));
            const __VLS_134 = __VLS_133({
                size: ((16)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_133));
            (__VLS_ctx.activeView === 'projects' ? '项目' : __VLS_ctx.activeView === 'assets' ? '服务' : '巡检计划');
        }
        if (__VLS_ctx.error) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
                ...{ class: ("error-state") },
            });
            const __VLS_138 = {}.ShieldCheck;
            /** @type { [typeof __VLS_components.ShieldCheck, ] } */ ;
            // @ts-ignore
            const __VLS_139 = __VLS_asFunctionalComponent(__VLS_138, new __VLS_138({
                size: ((20)),
            }));
            const __VLS_140 = __VLS_139({
                size: ((20)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_139));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.error);
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.loadData) },
            });
        }
        if (__VLS_ctx.activeView === 'dashboard') {
            __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
                ...{ class: ("content dashboard-view") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("metrics-grid") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.openGlobalProjects) },
                ...{ class: ("metric") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("metric-icon blue") },
            });
            const __VLS_144 = {}.FolderKanban;
            /** @type { [typeof __VLS_components.FolderKanban, ] } */ ;
            // @ts-ignore
            const __VLS_145 = __VLS_asFunctionalComponent(__VLS_144, new __VLS_144({
                size: ((20)),
            }));
            const __VLS_146 = __VLS_145({
                size: ((20)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_145));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.summary.projectCount);
            const __VLS_150 = {}.ChevronRight;
            /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
            // @ts-ignore
            const __VLS_151 = __VLS_asFunctionalComponent(__VLS_150, new __VLS_150({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }));
            const __VLS_152 = __VLS_151({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_151));
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.activeView === 'dashboard')))
                            return;
                        __VLS_ctx.openGlobalServices('all');
                    } },
                ...{ class: ("metric") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("metric-icon green") },
            });
            const __VLS_156 = {}.Server;
            /** @type { [typeof __VLS_components.Server, ] } */ ;
            // @ts-ignore
            const __VLS_157 = __VLS_asFunctionalComponent(__VLS_156, new __VLS_156({
                size: ((20)),
            }));
            const __VLS_158 = __VLS_157({
                size: ((20)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_157));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.summary.assetCount);
            const __VLS_162 = {}.ChevronRight;
            /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
            // @ts-ignore
            const __VLS_163 = __VLS_asFunctionalComponent(__VLS_162, new __VLS_162({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }));
            const __VLS_164 = __VLS_163({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_163));
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.activeView === 'dashboard')))
                            return;
                        __VLS_ctx.openGlobalServices('abnormal');
                    } },
                ...{ class: ("metric") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("metric-icon amber") },
            });
            const __VLS_168 = {}.HardDrive;
            /** @type { [typeof __VLS_components.HardDrive, ] } */ ;
            // @ts-ignore
            const __VLS_169 = __VLS_asFunctionalComponent(__VLS_168, new __VLS_168({
                size: ((20)),
            }));
            const __VLS_170 = __VLS_169({
                size: ((20)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_169));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.summary.abnormalAssetCount);
            const __VLS_174 = {}.ChevronRight;
            /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
            // @ts-ignore
            const __VLS_175 = __VLS_asFunctionalComponent(__VLS_174, new __VLS_174({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }));
            const __VLS_176 = __VLS_175({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_175));
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.openGlobalAlerts) },
                ...{ class: ("metric") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("metric-icon red") },
            });
            const __VLS_180 = {}.BellRing;
            /** @type { [typeof __VLS_components.BellRing, ] } */ ;
            // @ts-ignore
            const __VLS_181 = __VLS_asFunctionalComponent(__VLS_180, new __VLS_180({
                size: ((20)),
            }));
            const __VLS_182 = __VLS_181({
                size: ((20)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_181));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.summary.openAlertCount);
            const __VLS_186 = {}.ChevronRight;
            /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
            // @ts-ignore
            const __VLS_187 = __VLS_asFunctionalComponent(__VLS_186, new __VLS_186({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }));
            const __VLS_188 = __VLS_187({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_187));
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.openGlobalInspections) },
                ...{ class: ("metric") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("metric-icon cyan") },
            });
            const __VLS_192 = {}.ListChecks;
            /** @type { [typeof __VLS_components.ListChecks, ] } */ ;
            // @ts-ignore
            const __VLS_193 = __VLS_asFunctionalComponent(__VLS_192, new __VLS_192({
                size: ((20)),
            }));
            const __VLS_194 = __VLS_193({
                size: ((20)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_193));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.inspections.length ? `${__VLS_ctx.summary.inspectionPassRate}%` : '--');
            __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
            const __VLS_198 = {}.ChevronRight;
            /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
            // @ts-ignore
            const __VLS_199 = __VLS_asFunctionalComponent(__VLS_198, new __VLS_198({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }));
            const __VLS_200 = __VLS_199({
                size: ((16)),
                ...{ class: ("metric-arrow") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_199));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("dashboard-grid") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
                ...{ class: ("panel span-two") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("panel-heading") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.activeView === 'dashboard')))
                            return;
                        __VLS_ctx.navigateTo('projects');
                    } },
                ...{ class: ("text-button") },
            });
            const __VLS_204 = {}.ChevronRight;
            /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
            // @ts-ignore
            const __VLS_205 = __VLS_asFunctionalComponent(__VLS_204, new __VLS_204({
                size: ((15)),
            }));
            const __VLS_206 = __VLS_205({
                size: ((15)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_205));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("asset-overview") },
            });
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.assetCategoryStats))) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onClick: (...[$event]) => {
                            if (!(!((__VLS_ctx.authLoading))))
                                return;
                            if (!(!((!__VLS_ctx.authenticated))))
                                return;
                            if (!((__VLS_ctx.activeView === 'dashboard')))
                                return;
                            __VLS_ctx.openServiceCategory(item.key);
                        } },
                    key: ((item.key)),
                    type: ("button"),
                    ...{ class: ("asset-stat") },
                });
                const __VLS_210 = ((item.icon));
                // @ts-ignore
                const __VLS_211 = __VLS_asFunctionalComponent(__VLS_210, new __VLS_210({
                    size: ((19)),
                }));
                const __VLS_212 = __VLS_211({
                    size: ((19)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_211));
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                (item.label);
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (item.healthy);
                (item.count);
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("health-bar") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.i)({
                    ...{ style: (({ width: `${item.percentage}%` })) },
                });
            }
            __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
                ...{ class: ("panel") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("panel-heading") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("pass-ring") },
                ...{ style: (({ background: `conic-gradient(#27ad79 0 ${__VLS_ctx.inspections.length ? __VLS_ctx.summary.inspectionPassRate : 0}%, #e8edeb 0 100%)` })) },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.inspections.length ? `${__VLS_ctx.summary.inspectionPassRate}%` : '--');
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.inspections.length ? '通过率' : '暂无执行记录');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("mini-list") },
            });
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.inspections.slice(0, 3)))) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    key: ((item.id)),
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.span)({
                    ...{ class: ("dot") },
                    ...{ class: ((item.status.toLowerCase())) },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                (item.name);
                __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
                (item.passedChecks);
                (item.totalChecks);
            }
            __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
                ...{ class: ("panel span-three") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("panel-heading") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.activeView === 'dashboard')))
                            return;
                        __VLS_ctx.navigateTo('alerts');
                    } },
                ...{ class: ("icon-button") },
                title: ("进入告警事件"),
            });
            const __VLS_216 = {}.ChevronRight;
            /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
            // @ts-ignore
            const __VLS_217 = __VLS_asFunctionalComponent(__VLS_216, new __VLS_216({
                size: ((17)),
            }));
            const __VLS_218 = __VLS_217({
                size: ((17)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_217));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("alert-stream") },
            });
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.alerts))) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    key: ((item.id)),
                    ...{ class: ("alert-row") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: ("severity") },
                    ...{ class: ((item.level.toLowerCase())) },
                });
                (__VLS_ctx.statusText(item.level));
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("alert-main") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                (item.message);
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (item.projectName);
                (item.targetName);
                __VLS_elementAsFunction(__VLS_intrinsicElements.time, __VLS_intrinsicElements.time)({});
                (__VLS_ctx.formatTime(item.occurredAt));
                const __VLS_222 = {}.ElTag;
                /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                // @ts-ignore
                const __VLS_223 = __VLS_asFunctionalComponent(__VLS_222, new __VLS_222({
                    type: ((__VLS_ctx.badge(item.status))),
                    effect: ("light"),
                    size: ("small"),
                }));
                const __VLS_224 = __VLS_223({
                    type: ((__VLS_ctx.badge(item.status))),
                    effect: ("light"),
                    size: ("small"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_223));
                (__VLS_ctx.statusText(item.status));
                __VLS_227.slots.default;
                var __VLS_227;
            }
        }
        else {
            __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
                ...{ class: ("content list-view") },
            });
            if (__VLS_ctx.activeView !== 'inspections') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("list-summary") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.activeView === 'projects' ? __VLS_ctx.filteredProjects.length : __VLS_ctx.activeView === 'assets' ? __VLS_ctx.filteredAssets.length : __VLS_ctx.filteredAlerts.length);
            }
            if (__VLS_ctx.activeView === 'projects' && !__VLS_ctx.filteredProjects.length) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("page-empty") },
                });
                const __VLS_228 = {}.FolderKanban;
                /** @type { [typeof __VLS_components.FolderKanban, ] } */ ;
                // @ts-ignore
                const __VLS_229 = __VLS_asFunctionalComponent(__VLS_228, new __VLS_228({
                    size: ((26)),
                }));
                const __VLS_230 = __VLS_229({
                    size: ((26)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_229));
                __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                (__VLS_ctx.keyword ? '未找到项目' : '暂无项目');
            }
            if (__VLS_ctx.activeView === 'projects') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("project-grid") },
                });
                for (const [item] of __VLS_getVForSourceType((__VLS_ctx.filteredProjects))) {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.article, __VLS_intrinsicElements.article)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'projects')))
                                    return;
                                __VLS_ctx.openProjectDetail(item);
                            } },
                        key: ((item.id)),
                        ...{ class: ("project-card") },
                    });
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("project-head") },
                    });
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("project-symbol") },
                    });
                    (item.name.slice(0, 1));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("card-actions") },
                    });
                    const __VLS_234 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_235 = __VLS_asFunctionalComponent(__VLS_234, new __VLS_234({
                        type: ((__VLS_ctx.badge(item.status))),
                        effect: ("light"),
                    }));
                    const __VLS_236 = __VLS_235({
                        type: ((__VLS_ctx.badge(item.status))),
                        effect: ("light"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_235));
                    (__VLS_ctx.statusText(item.status));
                    __VLS_239.slots.default;
                    var __VLS_239;
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'projects')))
                                    return;
                                __VLS_ctx.openProjectEdit(item);
                            } },
                        title: ("编辑项目"),
                    });
                    const __VLS_240 = {}.Pencil;
                    /** @type { [typeof __VLS_components.Pencil, ] } */ ;
                    // @ts-ignore
                    const __VLS_241 = __VLS_asFunctionalComponent(__VLS_240, new __VLS_240({
                        size: ((15)),
                    }));
                    const __VLS_242 = __VLS_241({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_241));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'projects')))
                                    return;
                                __VLS_ctx.removeProject(item);
                            } },
                        title: ("删除项目"),
                        ...{ class: ("danger-action") },
                    });
                    const __VLS_246 = {}.Trash2;
                    /** @type { [typeof __VLS_components.Trash2, ] } */ ;
                    // @ts-ignore
                    const __VLS_247 = __VLS_asFunctionalComponent(__VLS_246, new __VLS_246({
                        size: ((15)),
                    }));
                    const __VLS_248 = __VLS_247({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_247));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
                    (item.name);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
                    (item.description);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("project-meta") },
                    });
                    __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (item.projectType || '业务系统');
                    __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                    (item.owner);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("project-footer") },
                    });
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'projects')))
                                    return;
                                __VLS_ctx.openProjectServices(item);
                            } },
                    });
                    const __VLS_252 = {}.Server;
                    /** @type { [typeof __VLS_components.Server, ] } */ ;
                    // @ts-ignore
                    const __VLS_253 = __VLS_asFunctionalComponent(__VLS_252, new __VLS_252({
                        size: ((14)),
                    }));
                    const __VLS_254 = __VLS_253({
                        size: ((14)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_253));
                    (__VLS_ctx.assets.filter(asset => asset.projectName === item.name).length);
                    const __VLS_258 = {}.ChevronRight;
                    /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
                    // @ts-ignore
                    const __VLS_259 = __VLS_asFunctionalComponent(__VLS_258, new __VLS_258({
                        size: ((14)),
                    }));
                    const __VLS_260 = __VLS_259({
                        size: ((14)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_259));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'projects')))
                                    return;
                                __VLS_ctx.openProjectAlerts(item);
                            } },
                    });
                    const __VLS_264 = {}.AlertTriangle;
                    /** @type { [typeof __VLS_components.AlertTriangle, ] } */ ;
                    // @ts-ignore
                    const __VLS_265 = __VLS_asFunctionalComponent(__VLS_264, new __VLS_264({
                        size: ((14)),
                    }));
                    const __VLS_266 = __VLS_265({
                        size: ((14)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_265));
                    (__VLS_ctx.alerts.filter(alert => (alert.projectNameSnapshot || alert.projectName) === item.name && alert.handlingStatus === 'OPEN').length);
                    const __VLS_270 = {}.ChevronRight;
                    /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
                    // @ts-ignore
                    const __VLS_271 = __VLS_asFunctionalComponent(__VLS_270, new __VLS_270({
                        size: ((14)),
                    }));
                    const __VLS_272 = __VLS_271({
                        size: ((14)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_271));
                }
            }
            if (__VLS_ctx.activeView === 'assets') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("table-panel") },
                });
                const __VLS_276 = {}.ElTable;
                /** @type { [typeof __VLS_components.ElTable, typeof __VLS_components.elTable, typeof __VLS_components.ElTable, typeof __VLS_components.elTable, ] } */ ;
                // @ts-ignore
                const __VLS_277 = __VLS_asFunctionalComponent(__VLS_276, new __VLS_276({
                    data: ((__VLS_ctx.filteredAssets)),
                    emptyText: ("暂无服务"),
                    stripe: (true),
                }));
                const __VLS_278 = __VLS_277({
                    data: ((__VLS_ctx.filteredAssets)),
                    emptyText: ("暂无服务"),
                    stripe: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_277));
                const __VLS_282 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_283 = __VLS_asFunctionalComponent(__VLS_282, new __VLS_282({
                    prop: ("name"),
                    label: ("服务名称"),
                    minWidth: ("170"),
                }));
                const __VLS_284 = __VLS_283({
                    prop: ("name"),
                    label: ("服务名称"),
                    minWidth: ("170"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_283));
                const __VLS_288 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_289 = __VLS_asFunctionalComponent(__VLS_288, new __VLS_288({
                    prop: ("projectName"),
                    label: ("所属项目"),
                    minWidth: ("150"),
                }));
                const __VLS_290 = __VLS_289({
                    prop: ("projectName"),
                    label: ("所属项目"),
                    minWidth: ("150"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_289));
                const __VLS_294 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_295 = __VLS_asFunctionalComponent(__VLS_294, new __VLS_294({
                    label: ("所属计划"),
                    minWidth: ("160"),
                }));
                const __VLS_296 = __VLS_295({
                    label: ("所属计划"),
                    minWidth: ("160"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_295));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_299.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    (__VLS_ctx.currentServicePlan(scope.row)?.name || '未加入计划');
                }
                var __VLS_299;
                const __VLS_300 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_301 = __VLS_asFunctionalComponent(__VLS_300, new __VLS_300({
                    prop: ("environmentName"),
                    label: ("环境"),
                    width: ("90"),
                }));
                const __VLS_302 = __VLS_301({
                    prop: ("environmentName"),
                    label: ("环境"),
                    width: ("90"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_301));
                const __VLS_306 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_307 = __VLS_asFunctionalComponent(__VLS_306, new __VLS_306({
                    prop: ("type"),
                    label: ("服务类型"),
                    width: ("120"),
                }));
                const __VLS_308 = __VLS_307({
                    prop: ("type"),
                    label: ("服务类型"),
                    width: ("120"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_307));
                const __VLS_312 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_313 = __VLS_asFunctionalComponent(__VLS_312, new __VLS_312({
                    label: ("服务状态"),
                    width: ("120"),
                }));
                const __VLS_314 = __VLS_313({
                    label: ("服务状态"),
                    width: ("120"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_313));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_317.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    const __VLS_318 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_319 = __VLS_asFunctionalComponent(__VLS_318, new __VLS_318({
                        type: ((__VLS_ctx.serviceState(scope.row).type)),
                        effect: ("light"),
                    }));
                    const __VLS_320 = __VLS_319({
                        type: ((__VLS_ctx.serviceState(scope.row).type)),
                        effect: ("light"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_319));
                    (__VLS_ctx.serviceState(scope.row).text);
                    __VLS_323.slots.default;
                    var __VLS_323;
                }
                var __VLS_317;
                const __VLS_324 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_325 = __VLS_asFunctionalComponent(__VLS_324, new __VLS_324({
                    label: ("巡检配置"),
                    width: ("130"),
                }));
                const __VLS_326 = __VLS_325({
                    label: ("巡检配置"),
                    width: ("130"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_325));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_329.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    const __VLS_330 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_331 = __VLS_asFunctionalComponent(__VLS_330, new __VLS_330({
                        type: ((__VLS_ctx.serviceInspectionConfiguration(scope.row).type)),
                        effect: ("light"),
                    }));
                    const __VLS_332 = __VLS_331({
                        type: ((__VLS_ctx.serviceInspectionConfiguration(scope.row).type)),
                        effect: ("light"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_331));
                    (__VLS_ctx.serviceInspectionConfiguration(scope.row).text);
                    __VLS_335.slots.default;
                    var __VLS_335;
                }
                var __VLS_329;
                const __VLS_336 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_337 = __VLS_asFunctionalComponent(__VLS_336, new __VLS_336({
                    label: ("操作"),
                    width: ("250"),
                    fixed: ("right"),
                }));
                const __VLS_338 = __VLS_337({
                    label: ("操作"),
                    width: ("250"),
                    fixed: ("right"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_337));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_341.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("service-row-actions") },
                    });
                    const __VLS_342 = {}.ElButton;
                    /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
                    // @ts-ignore
                    const __VLS_343 = __VLS_asFunctionalComponent(__VLS_342, new __VLS_342({
                        ...{ 'onClick': {} },
                        link: (true),
                        type: ("primary"),
                    }));
                    const __VLS_344 = __VLS_343({
                        ...{ 'onClick': {} },
                        link: (true),
                        type: ("primary"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_343));
                    let __VLS_348;
                    const __VLS_349 = {
                        onClick: (...[$event]) => {
                            if (!(!((__VLS_ctx.authLoading))))
                                return;
                            if (!(!((!__VLS_ctx.authenticated))))
                                return;
                            if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                return;
                            if (!((__VLS_ctx.activeView === 'assets')))
                                return;
                            __VLS_ctx.openAssetPlanConfiguration(scope.row);
                        }
                    };
                    let __VLS_345;
                    let __VLS_346;
                    (__VLS_ctx.currentServicePlan(scope.row) ? '查看巡检计划' : '新增巡检计划');
                    __VLS_347.slots.default;
                    var __VLS_347;
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("row-actions") },
                    });
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'assets')))
                                    return;
                                __VLS_ctx.openMonitoringDetails(scope.row);
                            } },
                        title: ("服务详情"),
                    });
                    const __VLS_350 = {}.Activity;
                    /** @type { [typeof __VLS_components.Activity, ] } */ ;
                    // @ts-ignore
                    const __VLS_351 = __VLS_asFunctionalComponent(__VLS_350, new __VLS_350({
                        size: ((15)),
                    }));
                    const __VLS_352 = __VLS_351({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_351));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'assets')))
                                    return;
                                __VLS_ctx.openAssetEdit(scope.row);
                            } },
                        title: ("编辑服务"),
                    });
                    const __VLS_356 = {}.Pencil;
                    /** @type { [typeof __VLS_components.Pencil, ] } */ ;
                    // @ts-ignore
                    const __VLS_357 = __VLS_asFunctionalComponent(__VLS_356, new __VLS_356({
                        size: ((15)),
                    }));
                    const __VLS_358 = __VLS_357({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_357));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'assets')))
                                    return;
                                __VLS_ctx.removeAsset(scope.row);
                            } },
                        title: ("删除服务"),
                        ...{ class: ("danger-action") },
                    });
                    const __VLS_362 = {}.Trash2;
                    /** @type { [typeof __VLS_components.Trash2, ] } */ ;
                    // @ts-ignore
                    const __VLS_363 = __VLS_asFunctionalComponent(__VLS_362, new __VLS_362({
                        size: ((15)),
                    }));
                    const __VLS_364 = __VLS_363({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_363));
                }
                var __VLS_341;
                __VLS_281.slots.default;
                var __VLS_281;
            }
            if (__VLS_ctx.activeView === 'inspections') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("inspection-center") },
                });
                if (__VLS_ctx.inspectionSection === 'plans') {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("plan-grid") },
                        ...{ class: (({ 'is-empty': __VLS_ctx.visibleInspectionPlans.length === 0 })) },
                    });
                    for (const [plan] of __VLS_getVForSourceType((__VLS_ctx.visibleInspectionPlans))) {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.article, __VLS_intrinsicElements.article)({
                            key: ((plan.id)),
                            ...{ class: ("plan-card") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("plan-card-head") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("plan-icon") },
                        });
                        const __VLS_368 = {}.CalendarClock;
                        /** @type { [typeof __VLS_components.CalendarClock, ] } */ ;
                        // @ts-ignore
                        const __VLS_369 = __VLS_asFunctionalComponent(__VLS_368, new __VLS_368({
                            size: ((19)),
                        }));
                        const __VLS_370 = __VLS_369({
                            size: ((19)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_369));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("plan-state-tags") },
                        });
                        const __VLS_374 = {}.ElTag;
                        /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                        // @ts-ignore
                        const __VLS_375 = __VLS_asFunctionalComponent(__VLS_374, new __VLS_374({
                            type: ((plan.status === 'ENABLED' ? 'success' : 'info')),
                            effect: ("light"),
                        }));
                        const __VLS_376 = __VLS_375({
                            type: ((plan.status === 'ENABLED' ? 'success' : 'info')),
                            effect: ("light"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_375));
                        (__VLS_ctx.statusText(plan.status));
                        __VLS_379.slots.default;
                        var __VLS_379;
                        if (plan.syncStatus === 'FAILED' || plan.syncStatus === 'PARTIAL') {
                            const __VLS_380 = {}.ElTag;
                            /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                            // @ts-ignore
                            const __VLS_381 = __VLS_asFunctionalComponent(__VLS_380, new __VLS_380({
                                type: ("danger"),
                                effect: ("plain"),
                            }));
                            const __VLS_382 = __VLS_381({
                                type: ("danger"),
                                effect: ("plain"),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_381));
                            __VLS_385.slots.default;
                            var __VLS_385;
                        }
                        __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
                        (plan.name);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                            ...{ class: ("plan-service-summary") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (plan.assetIds.length);
                        const __VLS_386 = {}.ElTooltip;
                        /** @type { [typeof __VLS_components.ElTooltip, typeof __VLS_components.elTooltip, typeof __VLS_components.ElTooltip, typeof __VLS_components.elTooltip, ] } */ ;
                        // @ts-ignore
                        const __VLS_387 = __VLS_asFunctionalComponent(__VLS_386, new __VLS_386({
                            content: ((`告警规则：连续检查失败 ${plan.failureThreshold || 1} 次后触发${__VLS_ctx.statusText(plan.alertLevel || 'CRITICAL')}告警`)),
                            trigger: ((['hover', 'focus'])),
                            placement: ("top"),
                        }));
                        const __VLS_388 = __VLS_387({
                            content: ((`告警规则：连续检查失败 ${plan.failureThreshold || 1} 次后触发${__VLS_ctx.statusText(plan.alertLevel || 'CRITICAL')}告警`)),
                            trigger: ((['hover', 'focus'])),
                            placement: ("top"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_387));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                            type: ("button"),
                            ...{ class: ("plan-rule-trigger") },
                            'aria-label': ("查看告警规则"),
                        });
                        const __VLS_392 = {}.BellRing;
                        /** @type { [typeof __VLS_components.BellRing, ] } */ ;
                        // @ts-ignore
                        const __VLS_393 = __VLS_asFunctionalComponent(__VLS_392, new __VLS_392({
                            size: ((14)),
                        }));
                        const __VLS_394 = __VLS_393({
                            size: ((14)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_393));
                        __VLS_391.slots.default;
                        var __VLS_391;
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (plan.projectName);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (__VLS_ctx.planCheckSummary(plan).frequency);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (__VLS_ctx.planCheckSummary(plan).lastChecked);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (__VLS_ctx.planCheckSummary(plan).result);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (__VLS_ctx.planCheckSummary(plan).nextCheck);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("plan-actions") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("plan-commands") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                            ...{ onClick: (...[$event]) => {
                                    if (!(!((__VLS_ctx.authLoading))))
                                        return;
                                    if (!(!((!__VLS_ctx.authenticated))))
                                        return;
                                    if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                        return;
                                    if (!((__VLS_ctx.activeView === 'inspections')))
                                        return;
                                    if (!((__VLS_ctx.inspectionSection === 'plans')))
                                        return;
                                    __VLS_ctx.executePlan(plan);
                                } },
                            title: ("立即执行"),
                            'aria-label': ("立即执行"),
                            disabled: ((__VLS_ctx.runningPlanId !== null || plan.status !== 'ENABLED' || plan.syncStatus !== 'SYNCED')),
                        });
                        if (__VLS_ctx.runningPlanId === plan.id) {
                            const __VLS_398 = {}.RefreshCw;
                            /** @type { [typeof __VLS_components.RefreshCw, ] } */ ;
                            // @ts-ignore
                            const __VLS_399 = __VLS_asFunctionalComponent(__VLS_398, new __VLS_398({
                                size: ((15)),
                                ...{ class: ("spin-icon") },
                            }));
                            const __VLS_400 = __VLS_399({
                                size: ((15)),
                                ...{ class: ("spin-icon") },
                            }, ...__VLS_functionalComponentArgsRest(__VLS_399));
                        }
                        else {
                            const __VLS_404 = {}.Play;
                            /** @type { [typeof __VLS_components.Play, ] } */ ;
                            // @ts-ignore
                            const __VLS_405 = __VLS_asFunctionalComponent(__VLS_404, new __VLS_404({
                                size: ((15)),
                            }));
                            const __VLS_406 = __VLS_405({
                                size: ((15)),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_405));
                        }
                        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                            ...{ onClick: (...[$event]) => {
                                    if (!(!((__VLS_ctx.authLoading))))
                                        return;
                                    if (!(!((!__VLS_ctx.authenticated))))
                                        return;
                                    if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                        return;
                                    if (!((__VLS_ctx.activeView === 'inspections')))
                                        return;
                                    if (!((__VLS_ctx.inspectionSection === 'plans')))
                                        return;
                                    __VLS_ctx.syncPlan(plan);
                                } },
                            title: ("更新巡检配置"),
                            disabled: ((__VLS_ctx.syncingPlanId === plan.id)),
                        });
                        const __VLS_410 = {}.RefreshCw;
                        /** @type { [typeof __VLS_components.RefreshCw, ] } */ ;
                        // @ts-ignore
                        const __VLS_411 = __VLS_asFunctionalComponent(__VLS_410, new __VLS_410({
                            size: ((15)),
                            ...{ class: (({ 'spin-icon': __VLS_ctx.syncingPlanId === plan.id })) },
                        }));
                        const __VLS_412 = __VLS_411({
                            size: ((15)),
                            ...{ class: (({ 'spin-icon': __VLS_ctx.syncingPlanId === plan.id })) },
                        }, ...__VLS_functionalComponentArgsRest(__VLS_411));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                            ...{ onClick: (...[$event]) => {
                                    if (!(!((__VLS_ctx.authLoading))))
                                        return;
                                    if (!(!((!__VLS_ctx.authenticated))))
                                        return;
                                    if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                        return;
                                    if (!((__VLS_ctx.activeView === 'inspections')))
                                        return;
                                    if (!((__VLS_ctx.inspectionSection === 'plans')))
                                        return;
                                    __VLS_ctx.openPlanRecords(plan);
                                } },
                            title: ("执行记录"),
                        });
                        const __VLS_416 = {}.Clock3;
                        /** @type { [typeof __VLS_components.Clock3, ] } */ ;
                        // @ts-ignore
                        const __VLS_417 = __VLS_asFunctionalComponent(__VLS_416, new __VLS_416({
                            size: ((15)),
                        }));
                        const __VLS_418 = __VLS_417({
                            size: ((15)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_417));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                            ...{ onClick: (...[$event]) => {
                                    if (!(!((__VLS_ctx.authLoading))))
                                        return;
                                    if (!(!((!__VLS_ctx.authenticated))))
                                        return;
                                    if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                        return;
                                    if (!((__VLS_ctx.activeView === 'inspections')))
                                        return;
                                    if (!((__VLS_ctx.inspectionSection === 'plans')))
                                        return;
                                    __VLS_ctx.togglePlan(plan);
                                } },
                            ...{ class: ("plan-toggle-button") },
                            ...{ class: ((plan.status === 'ENABLED' ? 'stop' : 'start')) },
                            title: ((plan.status === 'ENABLED' ? '停用计划' : '启用计划')),
                        });
                        const __VLS_422 = {}.Power;
                        /** @type { [typeof __VLS_components.Power, ] } */ ;
                        // @ts-ignore
                        const __VLS_423 = __VLS_asFunctionalComponent(__VLS_422, new __VLS_422({
                            size: ((15)),
                        }));
                        const __VLS_424 = __VLS_423({
                            size: ((15)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_423));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                            ...{ onClick: (...[$event]) => {
                                    if (!(!((__VLS_ctx.authLoading))))
                                        return;
                                    if (!(!((!__VLS_ctx.authenticated))))
                                        return;
                                    if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                        return;
                                    if (!((__VLS_ctx.activeView === 'inspections')))
                                        return;
                                    if (!((__VLS_ctx.inspectionSection === 'plans')))
                                        return;
                                    __VLS_ctx.openPlanEdit(plan);
                                } },
                            title: ("编辑计划"),
                        });
                        const __VLS_428 = {}.Pencil;
                        /** @type { [typeof __VLS_components.Pencil, ] } */ ;
                        // @ts-ignore
                        const __VLS_429 = __VLS_asFunctionalComponent(__VLS_428, new __VLS_428({
                            size: ((15)),
                        }));
                        const __VLS_430 = __VLS_429({
                            size: ((15)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_429));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                            ...{ onClick: (...[$event]) => {
                                    if (!(!((__VLS_ctx.authLoading))))
                                        return;
                                    if (!(!((!__VLS_ctx.authenticated))))
                                        return;
                                    if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                        return;
                                    if (!((__VLS_ctx.activeView === 'inspections')))
                                        return;
                                    if (!((__VLS_ctx.inspectionSection === 'plans')))
                                        return;
                                    __VLS_ctx.removePlan(plan);
                                } },
                            title: ("删除计划"),
                            ...{ class: ("danger-action") },
                        });
                        const __VLS_434 = {}.Trash2;
                        /** @type { [typeof __VLS_components.Trash2, ] } */ ;
                        // @ts-ignore
                        const __VLS_435 = __VLS_asFunctionalComponent(__VLS_434, new __VLS_434({
                            size: ((15)),
                        }));
                        const __VLS_436 = __VLS_435({
                            size: ((15)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_435));
                    }
                    if (__VLS_ctx.visibleInspectionPlans.length === 0) {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("empty-state plan-empty") },
                        });
                        const __VLS_440 = {}.CalendarClock;
                        /** @type { [typeof __VLS_components.CalendarClock, ] } */ ;
                        // @ts-ignore
                        const __VLS_441 = __VLS_asFunctionalComponent(__VLS_440, new __VLS_440({
                            size: ((26)),
                        }));
                        const __VLS_442 = __VLS_441({
                            size: ((26)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_441));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                    }
                }
                else {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("records-content") },
                    });
                    if (__VLS_ctx.selectedPlanInspections.length) {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("table-panel") },
                        });
                        const __VLS_446 = {}.ElTable;
                        /** @type { [typeof __VLS_components.ElTable, typeof __VLS_components.elTable, typeof __VLS_components.ElTable, typeof __VLS_components.elTable, ] } */ ;
                        // @ts-ignore
                        const __VLS_447 = __VLS_asFunctionalComponent(__VLS_446, new __VLS_446({
                            data: ((__VLS_ctx.pagedPlanInspections)),
                            rowKey: ("id"),
                            stripe: (true),
                            emptyText: ("暂无执行记录"),
                        }));
                        const __VLS_448 = __VLS_447({
                            data: ((__VLS_ctx.pagedPlanInspections)),
                            rowKey: ("id"),
                            stripe: (true),
                            emptyText: ("暂无执行记录"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_447));
                        const __VLS_452 = {}.ElTableColumn;
                        /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                        // @ts-ignore
                        const __VLS_453 = __VLS_asFunctionalComponent(__VLS_452, new __VLS_452({
                            prop: ("name"),
                            label: ("巡检计划"),
                            minWidth: ("180"),
                        }));
                        const __VLS_454 = __VLS_453({
                            prop: ("name"),
                            label: ("巡检计划"),
                            minWidth: ("180"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_453));
                        const __VLS_458 = {}.ElTableColumn;
                        /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                        // @ts-ignore
                        const __VLS_459 = __VLS_asFunctionalComponent(__VLS_458, new __VLS_458({
                            prop: ("projectName"),
                            label: ("所属项目"),
                            minWidth: ("150"),
                        }));
                        const __VLS_460 = __VLS_459({
                            prop: ("projectName"),
                            label: ("所属项目"),
                            minWidth: ("150"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_459));
                        const __VLS_464 = {}.ElTableColumn;
                        /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                        // @ts-ignore
                        const __VLS_465 = __VLS_asFunctionalComponent(__VLS_464, new __VLS_464({
                            label: ("执行结果"),
                            width: ("110"),
                        }));
                        const __VLS_466 = __VLS_465({
                            label: ("执行结果"),
                            width: ("110"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_465));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                        {
                            const { default: __VLS_thisSlot } = __VLS_469.slots;
                            const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                            const __VLS_470 = {}.ElTag;
                            /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                            // @ts-ignore
                            const __VLS_471 = __VLS_asFunctionalComponent(__VLS_470, new __VLS_470({
                                type: ((__VLS_ctx.badge(scope.row.status))),
                                effect: ("light"),
                            }));
                            const __VLS_472 = __VLS_471({
                                type: ((__VLS_ctx.badge(scope.row.status))),
                                effect: ("light"),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_471));
                            (__VLS_ctx.statusText(scope.row.status));
                            __VLS_475.slots.default;
                            var __VLS_475;
                        }
                        var __VLS_469;
                        const __VLS_476 = {}.ElTableColumn;
                        /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                        // @ts-ignore
                        const __VLS_477 = __VLS_asFunctionalComponent(__VLS_476, new __VLS_476({
                            label: ("检查通过"),
                            width: ("120"),
                        }));
                        const __VLS_478 = __VLS_477({
                            label: ("检查通过"),
                            width: ("120"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_477));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                        {
                            const { default: __VLS_thisSlot } = __VLS_481.slots;
                            const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                            (scope.row.passedChecks);
                            (scope.row.totalChecks);
                        }
                        var __VLS_481;
                        const __VLS_482 = {}.ElTableColumn;
                        /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                        // @ts-ignore
                        const __VLS_483 = __VLS_asFunctionalComponent(__VLS_482, new __VLS_482({
                            label: ("执行时间"),
                            minWidth: ("180"),
                        }));
                        const __VLS_484 = __VLS_483({
                            label: ("执行时间"),
                            minWidth: ("180"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_483));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                        {
                            const { default: __VLS_thisSlot } = __VLS_487.slots;
                            const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                            (__VLS_ctx.formatRecordTime(scope.row.executedAt));
                        }
                        var __VLS_487;
                        const __VLS_488 = {}.ElTableColumn;
                        /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                        // @ts-ignore
                        const __VLS_489 = __VLS_asFunctionalComponent(__VLS_488, new __VLS_488({
                            label: ("执行耗时"),
                            width: ("110"),
                        }));
                        const __VLS_490 = __VLS_489({
                            label: ("执行耗时"),
                            width: ("110"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_489));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                        {
                            const { default: __VLS_thisSlot } = __VLS_493.slots;
                            const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                            (scope.row.durationSeconds);
                        }
                        var __VLS_493;
                        const __VLS_494 = {}.ElTableColumn;
                        /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                        // @ts-ignore
                        const __VLS_495 = __VLS_asFunctionalComponent(__VLS_494, new __VLS_494({
                            label: ("操作"),
                            width: ("100"),
                            fixed: ("right"),
                        }));
                        const __VLS_496 = __VLS_495({
                            label: ("操作"),
                            width: ("100"),
                            fixed: ("right"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_495));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                        {
                            const { default: __VLS_thisSlot } = __VLS_499.slots;
                            const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                            const __VLS_500 = {}.ElButton;
                            /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
                            // @ts-ignore
                            const __VLS_501 = __VLS_asFunctionalComponent(__VLS_500, new __VLS_500({
                                ...{ 'onClick': {} },
                                link: (true),
                                type: ("primary"),
                            }));
                            const __VLS_502 = __VLS_501({
                                ...{ 'onClick': {} },
                                link: (true),
                                type: ("primary"),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_501));
                            let __VLS_506;
                            const __VLS_507 = {
                                onClick: (...[$event]) => {
                                    if (!(!((__VLS_ctx.authLoading))))
                                        return;
                                    if (!(!((!__VLS_ctx.authenticated))))
                                        return;
                                    if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                        return;
                                    if (!((__VLS_ctx.activeView === 'inspections')))
                                        return;
                                    if (!(!((__VLS_ctx.inspectionSection === 'plans'))))
                                        return;
                                    if (!((__VLS_ctx.selectedPlanInspections.length)))
                                        return;
                                    __VLS_ctx.openInspectionDetail(scope.row);
                                }
                            };
                            let __VLS_503;
                            let __VLS_504;
                            __VLS_505.slots.default;
                            var __VLS_505;
                        }
                        var __VLS_499;
                        __VLS_451.slots.default;
                        var __VLS_451;
                    }
                    else {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("plan-empty records-empty") },
                        });
                        const __VLS_508 = {}.Clock3;
                        /** @type { [typeof __VLS_components.Clock3, ] } */ ;
                        // @ts-ignore
                        const __VLS_509 = __VLS_asFunctionalComponent(__VLS_508, new __VLS_508({
                            size: ((26)),
                        }));
                        const __VLS_510 = __VLS_509({
                            size: ((26)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_509));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                        (__VLS_ctx.selectedInspectionPlanId && !__VLS_ctx.selectedInspectionPlan && !__VLS_ctx.loading ? '巡检计划不存在或已删除' : '暂无执行记录');
                    }
                    if (__VLS_ctx.selectedPlanInspections.length) {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.footer, __VLS_intrinsicElements.footer)({
                            ...{ class: ("inspection-pagination") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.selectedPlanInspections.length);
                        const __VLS_514 = {}.ElPagination;
                        /** @type { [typeof __VLS_components.ElPagination, typeof __VLS_components.elPagination, ] } */ ;
                        // @ts-ignore
                        const __VLS_515 = __VLS_asFunctionalComponent(__VLS_514, new __VLS_514({
                            currentPage: ((__VLS_ctx.inspectionPage)),
                            pageSize: ((__VLS_ctx.inspectionPageSize)),
                            total: ((__VLS_ctx.selectedPlanInspections.length)),
                            layout: ("prev, pager, next"),
                            background: (true),
                        }));
                        const __VLS_516 = __VLS_515({
                            currentPage: ((__VLS_ctx.inspectionPage)),
                            pageSize: ((__VLS_ctx.inspectionPageSize)),
                            total: ((__VLS_ctx.selectedPlanInspections.length)),
                            layout: ("prev, pager, next"),
                            background: (true),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_515));
                    }
                }
            }
            if (__VLS_ctx.activeView === 'alerts') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("alert-page") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
                    ...{ class: ("page-toolbar") },
                    'aria-label': ("告警筛选"),
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("alert-filters") },
                    ...{ style: ({}) },
                });
                const __VLS_520 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_521 = __VLS_asFunctionalComponent(__VLS_520, new __VLS_520({
                    modelValue: ((__VLS_ctx.alertProjectFilter)),
                    disabled: ((Boolean(__VLS_ctx.selectedProjectName))),
                    clearable: (true),
                    placeholder: ("全部项目"),
                    'aria-label': ("项目筛选"),
                    ...{ style: ({}) },
                }));
                const __VLS_522 = __VLS_521({
                    modelValue: ((__VLS_ctx.alertProjectFilter)),
                    disabled: ((Boolean(__VLS_ctx.selectedProjectName))),
                    clearable: (true),
                    placeholder: ("全部项目"),
                    'aria-label': ("项目筛选"),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_521));
                for (const [name] of __VLS_getVForSourceType((__VLS_ctx.alertProjectOptions))) {
                    const __VLS_526 = {}.ElOption;
                    /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                    // @ts-ignore
                    const __VLS_527 = __VLS_asFunctionalComponent(__VLS_526, new __VLS_526({
                        key: ((name)),
                        label: ((name)),
                        value: ((name)),
                    }));
                    const __VLS_528 = __VLS_527({
                        key: ((name)),
                        label: ((name)),
                        value: ((name)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_527));
                }
                __VLS_525.slots.default;
                var __VLS_525;
                const __VLS_532 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_533 = __VLS_asFunctionalComponent(__VLS_532, new __VLS_532({
                    modelValue: ((__VLS_ctx.alertPlanFilter)),
                    clearable: (true),
                    placeholder: ("全部计划"),
                    'aria-label': ("计划筛选"),
                    ...{ style: ({}) },
                }));
                const __VLS_534 = __VLS_533({
                    modelValue: ((__VLS_ctx.alertPlanFilter)),
                    clearable: (true),
                    placeholder: ("全部计划"),
                    'aria-label': ("计划筛选"),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_533));
                for (const [option] of __VLS_getVForSourceType((__VLS_ctx.alertPlanOptions))) {
                    const __VLS_538 = {}.ElOption;
                    /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                    // @ts-ignore
                    const __VLS_539 = __VLS_asFunctionalComponent(__VLS_538, new __VLS_538({
                        key: ((option.value)),
                        label: ((option.label)),
                        value: ((option.value)),
                    }));
                    const __VLS_540 = __VLS_539({
                        key: ((option.value)),
                        label: ((option.label)),
                        value: ((option.value)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_539));
                }
                __VLS_537.slots.default;
                var __VLS_537;
                const __VLS_544 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_545 = __VLS_asFunctionalComponent(__VLS_544, new __VLS_544({
                    modelValue: ((__VLS_ctx.alertAssetFilter)),
                    clearable: (true),
                    placeholder: ("全部服务"),
                    'aria-label': ("服务筛选"),
                    ...{ style: ({}) },
                }));
                const __VLS_546 = __VLS_545({
                    modelValue: ((__VLS_ctx.alertAssetFilter)),
                    clearable: (true),
                    placeholder: ("全部服务"),
                    'aria-label': ("服务筛选"),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_545));
                for (const [option] of __VLS_getVForSourceType((__VLS_ctx.alertAssetOptions))) {
                    const __VLS_550 = {}.ElOption;
                    /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                    // @ts-ignore
                    const __VLS_551 = __VLS_asFunctionalComponent(__VLS_550, new __VLS_550({
                        key: ((option.value)),
                        label: ((option.label)),
                        value: ((option.value)),
                    }));
                    const __VLS_552 = __VLS_551({
                        key: ((option.value)),
                        label: ((option.label)),
                        value: ((option.value)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_551));
                }
                __VLS_549.slots.default;
                var __VLS_549;
                const __VLS_556 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_557 = __VLS_asFunctionalComponent(__VLS_556, new __VLS_556({
                    modelValue: ((__VLS_ctx.alertLevelFilter)),
                    clearable: (true),
                    placeholder: ("全部级别"),
                    'aria-label': ("级别筛选"),
                    ...{ style: ({}) },
                }));
                const __VLS_558 = __VLS_557({
                    modelValue: ((__VLS_ctx.alertLevelFilter)),
                    clearable: (true),
                    placeholder: ("全部级别"),
                    'aria-label': ("级别筛选"),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_557));
                const __VLS_562 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_563 = __VLS_asFunctionalComponent(__VLS_562, new __VLS_562({
                    label: ("异常"),
                    value: ("CRITICAL"),
                }));
                const __VLS_564 = __VLS_563({
                    label: ("异常"),
                    value: ("CRITICAL"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_563));
                const __VLS_568 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_569 = __VLS_asFunctionalComponent(__VLS_568, new __VLS_568({
                    label: ("注意"),
                    value: ("WARNING"),
                }));
                const __VLS_570 = __VLS_569({
                    label: ("注意"),
                    value: ("WARNING"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_569));
                const __VLS_574 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_575 = __VLS_asFunctionalComponent(__VLS_574, new __VLS_574({
                    label: ("提示"),
                    value: ("INFO"),
                }));
                const __VLS_576 = __VLS_575({
                    label: ("提示"),
                    value: ("INFO"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_575));
                __VLS_561.slots.default;
                var __VLS_561;
                const __VLS_580 = {}.ElDatePicker;
                /** @type { [typeof __VLS_components.ElDatePicker, typeof __VLS_components.elDatePicker, ] } */ ;
                // @ts-ignore
                const __VLS_581 = __VLS_asFunctionalComponent(__VLS_580, new __VLS_580({
                    modelValue: ((__VLS_ctx.alertTimeRange)),
                    type: ("datetimerange"),
                    valueFormat: ("YYYY-MM-DDTHH:mm:ss"),
                    startPlaceholder: ("开始时间"),
                    endPlaceholder: ("结束时间"),
                    'aria-label': ("告警时间范围"),
                    ...{ style: ({}) },
                }));
                const __VLS_582 = __VLS_581({
                    modelValue: ((__VLS_ctx.alertTimeRange)),
                    type: ("datetimerange"),
                    valueFormat: ("YYYY-MM-DDTHH:mm:ss"),
                    startPlaceholder: ("开始时间"),
                    endPlaceholder: ("结束时间"),
                    'aria-label': ("告警时间范围"),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_581));
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("alert-filters") },
                });
                const __VLS_586 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_587 = __VLS_asFunctionalComponent(__VLS_586, new __VLS_586({
                    modelValue: ((__VLS_ctx.alertExternalFilter)),
                    'aria-label': ("告警状态筛选"),
                    ...{ style: ({}) },
                }));
                const __VLS_588 = __VLS_587({
                    modelValue: ((__VLS_ctx.alertExternalFilter)),
                    'aria-label': ("告警状态筛选"),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_587));
                const __VLS_592 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_593 = __VLS_asFunctionalComponent(__VLS_592, new __VLS_592({
                    label: ("全部告警状态"),
                    value: ("ALL"),
                }));
                const __VLS_594 = __VLS_593({
                    label: ("全部告警状态"),
                    value: ("ALL"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_593));
                const __VLS_598 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_599 = __VLS_asFunctionalComponent(__VLS_598, new __VLS_598({
                    label: ("告警中"),
                    value: ("FIRING"),
                }));
                const __VLS_600 = __VLS_599({
                    label: ("告警中"),
                    value: ("FIRING"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_599));
                const __VLS_604 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_605 = __VLS_asFunctionalComponent(__VLS_604, new __VLS_604({
                    label: ("已恢复"),
                    value: ("RESOLVED"),
                }));
                const __VLS_606 = __VLS_605({
                    label: ("已恢复"),
                    value: ("RESOLVED"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_605));
                __VLS_591.slots.default;
                var __VLS_591;
                const __VLS_610 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_611 = __VLS_asFunctionalComponent(__VLS_610, new __VLS_610({
                    modelValue: ((__VLS_ctx.alertHandlingFilter)),
                    'aria-label': ("处理状态筛选"),
                    ...{ style: ({}) },
                }));
                const __VLS_612 = __VLS_611({
                    modelValue: ((__VLS_ctx.alertHandlingFilter)),
                    'aria-label': ("处理状态筛选"),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_611));
                const __VLS_616 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_617 = __VLS_asFunctionalComponent(__VLS_616, new __VLS_616({
                    label: ("全部处理状态"),
                    value: ("ALL"),
                }));
                const __VLS_618 = __VLS_617({
                    label: ("全部处理状态"),
                    value: ("ALL"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_617));
                const __VLS_622 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_623 = __VLS_asFunctionalComponent(__VLS_622, new __VLS_622({
                    label: ("待处理"),
                    value: ("OPEN"),
                }));
                const __VLS_624 = __VLS_623({
                    label: ("待处理"),
                    value: ("OPEN"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_623));
                const __VLS_628 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_629 = __VLS_asFunctionalComponent(__VLS_628, new __VLS_628({
                    label: ("已确认"),
                    value: ("ACKNOWLEDGED"),
                }));
                const __VLS_630 = __VLS_629({
                    label: ("已确认"),
                    value: ("ACKNOWLEDGED"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_629));
                const __VLS_634 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_635 = __VLS_asFunctionalComponent(__VLS_634, new __VLS_634({
                    label: ("已关闭"),
                    value: ("CLOSED"),
                }));
                const __VLS_636 = __VLS_635({
                    label: ("已关闭"),
                    value: ("CLOSED"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_635));
                __VLS_615.slots.default;
                var __VLS_615;
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("table-panel") },
                });
                const __VLS_640 = {}.ElTable;
                /** @type { [typeof __VLS_components.ElTable, typeof __VLS_components.elTable, typeof __VLS_components.ElTable, typeof __VLS_components.elTable, ] } */ ;
                // @ts-ignore
                const __VLS_641 = __VLS_asFunctionalComponent(__VLS_640, new __VLS_640({
                    ...{ 'onRowClick': {} },
                    data: ((__VLS_ctx.filteredAlerts)),
                    emptyText: ("暂无告警"),
                    stripe: (true),
                }));
                const __VLS_642 = __VLS_641({
                    ...{ 'onRowClick': {} },
                    data: ((__VLS_ctx.filteredAlerts)),
                    emptyText: ("暂无告警"),
                    stripe: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_641));
                let __VLS_646;
                const __VLS_647 = {
                    onRowClick: (__VLS_ctx.openAlertDetail)
                };
                let __VLS_643;
                let __VLS_644;
                const __VLS_648 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_649 = __VLS_asFunctionalComponent(__VLS_648, new __VLS_648({
                    label: ("级别"),
                    width: ("100"),
                }));
                const __VLS_650 = __VLS_649({
                    label: ("级别"),
                    width: ("100"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_649));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_653.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: ("severity") },
                        ...{ class: ((scope.row.level.toLowerCase())) },
                    });
                    (__VLS_ctx.statusText(scope.row.level));
                }
                var __VLS_653;
                const __VLS_654 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_655 = __VLS_asFunctionalComponent(__VLS_654, new __VLS_654({
                    prop: ("message"),
                    label: ("告警内容"),
                    minWidth: ("240"),
                }));
                const __VLS_656 = __VLS_655({
                    prop: ("message"),
                    label: ("告警内容"),
                    minWidth: ("240"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_655));
                const __VLS_660 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_661 = __VLS_asFunctionalComponent(__VLS_660, new __VLS_660({
                    label: ("所属项目"),
                    minWidth: ("150"),
                }));
                const __VLS_662 = __VLS_661({
                    label: ("所属项目"),
                    minWidth: ("150"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_661));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_665.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    (scope.row.projectNameSnapshot || scope.row.projectName);
                }
                var __VLS_665;
                const __VLS_666 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_667 = __VLS_asFunctionalComponent(__VLS_666, new __VLS_666({
                    prop: ("inspectionPlanName"),
                    label: ("巡检计划"),
                    minWidth: ("150"),
                }));
                const __VLS_668 = __VLS_667({
                    prop: ("inspectionPlanName"),
                    label: ("巡检计划"),
                    minWidth: ("150"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_667));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_671.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    (scope.row.inspectionPlanName || '--');
                }
                var __VLS_671;
                const __VLS_672 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_673 = __VLS_asFunctionalComponent(__VLS_672, new __VLS_672({
                    label: ("告警对象"),
                    minWidth: ("150"),
                }));
                const __VLS_674 = __VLS_673({
                    label: ("告警对象"),
                    minWidth: ("150"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_673));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_677.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    (scope.row.targetNameSnapshot || scope.row.targetName);
                }
                var __VLS_677;
                const __VLS_678 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_679 = __VLS_asFunctionalComponent(__VLS_678, new __VLS_678({
                    label: ("发生时间"),
                    width: ("150"),
                }));
                const __VLS_680 = __VLS_679({
                    label: ("发生时间"),
                    width: ("150"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_679));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_683.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    (__VLS_ctx.formatTime(scope.row.occurredAt));
                }
                var __VLS_683;
                const __VLS_684 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_685 = __VLS_asFunctionalComponent(__VLS_684, new __VLS_684({
                    label: ("告警状态"),
                    width: ("100"),
                }));
                const __VLS_686 = __VLS_685({
                    label: ("告警状态"),
                    width: ("100"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_685));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_689.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    const __VLS_690 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_691 = __VLS_asFunctionalComponent(__VLS_690, new __VLS_690({
                        type: ((scope.row.externalLifecycle === 'RESOLVED' ? 'success' : 'danger')),
                        effect: ("plain"),
                    }));
                    const __VLS_692 = __VLS_691({
                        type: ((scope.row.externalLifecycle === 'RESOLVED' ? 'success' : 'danger')),
                        effect: ("plain"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_691));
                    (__VLS_ctx.statusText(scope.row.externalLifecycle || 'FIRING'));
                    __VLS_695.slots.default;
                    var __VLS_695;
                }
                var __VLS_689;
                const __VLS_696 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_697 = __VLS_asFunctionalComponent(__VLS_696, new __VLS_696({
                    label: ("处置状态"),
                    width: ("110"),
                }));
                const __VLS_698 = __VLS_697({
                    label: ("处置状态"),
                    width: ("110"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_697));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_701.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    const __VLS_702 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_703 = __VLS_asFunctionalComponent(__VLS_702, new __VLS_702({
                        type: ((__VLS_ctx.badge(scope.row.handlingStatus))),
                        effect: ("light"),
                    }));
                    const __VLS_704 = __VLS_703({
                        type: ((__VLS_ctx.badge(scope.row.handlingStatus))),
                        effect: ("light"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_703));
                    (__VLS_ctx.statusText(scope.row.handlingStatus));
                    __VLS_707.slots.default;
                    var __VLS_707;
                }
                var __VLS_701;
                const __VLS_708 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_709 = __VLS_asFunctionalComponent(__VLS_708, new __VLS_708({
                    label: ("操作"),
                    width: ("72"),
                    fixed: ("right"),
                }));
                const __VLS_710 = __VLS_709({
                    label: ("操作"),
                    width: ("72"),
                    fixed: ("right"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_709));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_713.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!(!((__VLS_ctx.authLoading))))
                                    return;
                                if (!(!((!__VLS_ctx.authenticated))))
                                    return;
                                if (!(!((__VLS_ctx.activeView === 'dashboard'))))
                                    return;
                                if (!((__VLS_ctx.activeView === 'alerts')))
                                    return;
                                __VLS_ctx.openAlertDetail(scope.row);
                            } },
                        ...{ class: ("icon-button") },
                        title: ("查看告警详情"),
                    });
                    const __VLS_714 = {}.ChevronRight;
                    /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
                    // @ts-ignore
                    const __VLS_715 = __VLS_asFunctionalComponent(__VLS_714, new __VLS_714({
                        size: ((16)),
                    }));
                    const __VLS_716 = __VLS_715({
                        size: ((16)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_715));
                }
                var __VLS_713;
                __VLS_645.slots.default;
                var __VLS_645;
            }
        }
        const __VLS_720 = {}.ElDrawer;
        /** @type { [typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, ] } */ ;
        // @ts-ignore
        const __VLS_721 = __VLS_asFunctionalComponent(__VLS_720, new __VLS_720({
            modelValue: ((__VLS_ctx.editorVisible)),
            title: ((`${__VLS_ctx.editingId ? '编辑' : '新增'}${__VLS_ctx.editorMode === 'project' ? '项目' : __VLS_ctx.editorMode === 'asset' ? '服务' : '巡检计划'}`)),
            size: ("440px"),
            destroyOnClose: (true),
        }));
        const __VLS_722 = __VLS_721({
            modelValue: ((__VLS_ctx.editorVisible)),
            title: ((`${__VLS_ctx.editingId ? '编辑' : '新增'}${__VLS_ctx.editorMode === 'project' ? '项目' : __VLS_ctx.editorMode === 'asset' ? '服务' : '巡检计划'}`)),
            size: ("440px"),
            destroyOnClose: (true),
        }, ...__VLS_functionalComponentArgsRest(__VLS_721));
        if (__VLS_ctx.editorMode === 'project') {
            const __VLS_726 = {}.ElForm;
            /** @type { [typeof __VLS_components.ElForm, typeof __VLS_components.elForm, typeof __VLS_components.ElForm, typeof __VLS_components.elForm, ] } */ ;
            // @ts-ignore
            const __VLS_727 = __VLS_asFunctionalComponent(__VLS_726, new __VLS_726({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }));
            const __VLS_728 = __VLS_727({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_727));
            let __VLS_732;
            const __VLS_733 = {
                onSubmit: (__VLS_ctx.saveEditor)
            };
            let __VLS_729;
            let __VLS_730;
            const __VLS_734 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_735 = __VLS_asFunctionalComponent(__VLS_734, new __VLS_734({
                label: ("项目名称"),
                required: (true),
            }));
            const __VLS_736 = __VLS_735({
                label: ("项目名称"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_735));
            const __VLS_740 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_741 = __VLS_asFunctionalComponent(__VLS_740, new __VLS_740({
                modelValue: ((__VLS_ctx.projectForm.name)),
                maxlength: ("80"),
                placeholder: ("例如：订单履约平台"),
            }));
            const __VLS_742 = __VLS_741({
                modelValue: ((__VLS_ctx.projectForm.name)),
                maxlength: ("80"),
                placeholder: ("例如：订单履约平台"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_741));
            __VLS_739.slots.default;
            var __VLS_739;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("form-grid") },
            });
            const __VLS_746 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_747 = __VLS_asFunctionalComponent(__VLS_746, new __VLS_746({
                label: ("项目类型"),
            }));
            const __VLS_748 = __VLS_747({
                label: ("项目类型"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_747));
            const __VLS_752 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_753 = __VLS_asFunctionalComponent(__VLS_752, new __VLS_752({
                modelValue: ((__VLS_ctx.projectForm.projectType)),
                maxlength: ("40"),
            }));
            const __VLS_754 = __VLS_753({
                modelValue: ((__VLS_ctx.projectForm.projectType)),
                maxlength: ("40"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_753));
            __VLS_751.slots.default;
            var __VLS_751;
            const __VLS_758 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_759 = __VLS_asFunctionalComponent(__VLS_758, new __VLS_758({
                label: ("负责人"),
                required: (true),
            }));
            const __VLS_760 = __VLS_759({
                label: ("负责人"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_759));
            const __VLS_764 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_765 = __VLS_asFunctionalComponent(__VLS_764, new __VLS_764({
                modelValue: ((__VLS_ctx.projectForm.owner)),
                maxlength: ("40"),
                placeholder: ("请输入负责人"),
            }));
            const __VLS_766 = __VLS_765({
                modelValue: ((__VLS_ctx.projectForm.owner)),
                maxlength: ("40"),
                placeholder: ("请输入负责人"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_765));
            __VLS_763.slots.default;
            var __VLS_763;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("form-grid") },
            });
            const __VLS_770 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_771 = __VLS_asFunctionalComponent(__VLS_770, new __VLS_770({
                label: ("联系方式"),
            }));
            const __VLS_772 = __VLS_771({
                label: ("联系方式"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_771));
            const __VLS_776 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_777 = __VLS_asFunctionalComponent(__VLS_776, new __VLS_776({
                modelValue: ((__VLS_ctx.projectForm.contactPhone)),
                maxlength: ("40"),
            }));
            const __VLS_778 = __VLS_777({
                modelValue: ((__VLS_ctx.projectForm.contactPhone)),
                maxlength: ("40"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_777));
            __VLS_775.slots.default;
            var __VLS_775;
            const __VLS_782 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_783 = __VLS_asFunctionalComponent(__VLS_782, new __VLS_782({
                label: ("运维到期日"),
            }));
            const __VLS_784 = __VLS_783({
                label: ("运维到期日"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_783));
            const __VLS_788 = {}.ElDatePicker;
            /** @type { [typeof __VLS_components.ElDatePicker, typeof __VLS_components.elDatePicker, ] } */ ;
            // @ts-ignore
            const __VLS_789 = __VLS_asFunctionalComponent(__VLS_788, new __VLS_788({
                modelValue: ((__VLS_ctx.projectForm.maintenanceExpiresAt)),
                type: ("date"),
                valueFormat: ("YYYY-MM-DD"),
                ...{ style: ({}) },
            }));
            const __VLS_790 = __VLS_789({
                modelValue: ((__VLS_ctx.projectForm.maintenanceExpiresAt)),
                type: ("date"),
                valueFormat: ("YYYY-MM-DD"),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_789));
            __VLS_787.slots.default;
            var __VLS_787;
            const __VLS_794 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_795 = __VLS_asFunctionalComponent(__VLS_794, new __VLS_794({
                label: ("项目描述"),
            }));
            const __VLS_796 = __VLS_795({
                label: ("项目描述"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_795));
            const __VLS_800 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_801 = __VLS_asFunctionalComponent(__VLS_800, new __VLS_800({
                modelValue: ((__VLS_ctx.projectForm.description)),
                type: ("textarea"),
                rows: ((4)),
                maxlength: ("240"),
                showWordLimit: (true),
            }));
            const __VLS_802 = __VLS_801({
                modelValue: ((__VLS_ctx.projectForm.description)),
                type: ("textarea"),
                rows: ((4)),
                maxlength: ("240"),
                showWordLimit: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_801));
            __VLS_799.slots.default;
            var __VLS_799;
            const __VLS_806 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_807 = __VLS_asFunctionalComponent(__VLS_806, new __VLS_806({
                label: ("运行状态"),
            }));
            const __VLS_808 = __VLS_807({
                label: ("运行状态"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_807));
            const __VLS_812 = {}.ElSegmented;
            /** @type { [typeof __VLS_components.ElSegmented, typeof __VLS_components.elSegmented, ] } */ ;
            // @ts-ignore
            const __VLS_813 = __VLS_asFunctionalComponent(__VLS_812, new __VLS_812({
                modelValue: ((__VLS_ctx.projectForm.status)),
                options: (([{ label: '运行中', value: 'RUNNING' }, { label: '维护中', value: 'MAINTENANCE' }])),
            }));
            const __VLS_814 = __VLS_813({
                modelValue: ((__VLS_ctx.projectForm.status)),
                options: (([{ label: '运行中', value: 'RUNNING' }, { label: '维护中', value: 'MAINTENANCE' }])),
            }, ...__VLS_functionalComponentArgsRest(__VLS_813));
            __VLS_811.slots.default;
            var __VLS_811;
            __VLS_731.slots.default;
            var __VLS_731;
        }
        else if (__VLS_ctx.editorMode === 'asset') {
            const __VLS_818 = {}.ElForm;
            /** @type { [typeof __VLS_components.ElForm, typeof __VLS_components.elForm, typeof __VLS_components.ElForm, typeof __VLS_components.elForm, ] } */ ;
            // @ts-ignore
            const __VLS_819 = __VLS_asFunctionalComponent(__VLS_818, new __VLS_818({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }));
            const __VLS_820 = __VLS_819({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_819));
            let __VLS_824;
            const __VLS_825 = {
                onSubmit: (__VLS_ctx.saveEditor)
            };
            let __VLS_821;
            let __VLS_822;
            const __VLS_826 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_827 = __VLS_asFunctionalComponent(__VLS_826, new __VLS_826({
                label: ("服务名称"),
                required: (true),
            }));
            const __VLS_828 = __VLS_827({
                label: ("服务名称"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_827));
            const __VLS_832 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_833 = __VLS_asFunctionalComponent(__VLS_832, new __VLS_832({
                modelValue: ((__VLS_ctx.assetForm.name)),
                maxlength: ("100"),
                placeholder: ("例如：payment-prod-mysql"),
            }));
            const __VLS_834 = __VLS_833({
                modelValue: ((__VLS_ctx.assetForm.name)),
                maxlength: ("100"),
                placeholder: ("例如：payment-prod-mysql"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_833));
            __VLS_831.slots.default;
            var __VLS_831;
            const __VLS_838 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_839 = __VLS_asFunctionalComponent(__VLS_838, new __VLS_838({
                label: ("所属项目"),
                required: (true),
            }));
            const __VLS_840 = __VLS_839({
                label: ("所属项目"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_839));
            const __VLS_844 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_845 = __VLS_asFunctionalComponent(__VLS_844, new __VLS_844({
                modelValue: ((__VLS_ctx.assetForm.projectName)),
                placeholder: ("选择项目"),
                ...{ style: ({}) },
            }));
            const __VLS_846 = __VLS_845({
                modelValue: ((__VLS_ctx.assetForm.projectName)),
                placeholder: ("选择项目"),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_845));
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.projects))) {
                const __VLS_850 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_851 = __VLS_asFunctionalComponent(__VLS_850, new __VLS_850({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.name)),
                }));
                const __VLS_852 = __VLS_851({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.name)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_851));
            }
            __VLS_849.slots.default;
            var __VLS_849;
            __VLS_843.slots.default;
            var __VLS_843;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("form-grid") },
            });
            const __VLS_856 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_857 = __VLS_asFunctionalComponent(__VLS_856, new __VLS_856({
                label: ("运行环境"),
                required: (true),
            }));
            const __VLS_858 = __VLS_857({
                label: ("运行环境"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_857));
            const __VLS_862 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_863 = __VLS_asFunctionalComponent(__VLS_862, new __VLS_862({
                modelValue: ((__VLS_ctx.assetForm.environmentName)),
                ...{ style: ({}) },
            }));
            const __VLS_864 = __VLS_863({
                modelValue: ((__VLS_ctx.assetForm.environmentName)),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_863));
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.environments))) {
                const __VLS_868 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_869 = __VLS_asFunctionalComponent(__VLS_868, new __VLS_868({
                    key: ((item)),
                    label: ((item)),
                    value: ((item)),
                }));
                const __VLS_870 = __VLS_869({
                    key: ((item)),
                    label: ((item)),
                    value: ((item)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_869));
            }
            __VLS_867.slots.default;
            var __VLS_867;
            __VLS_861.slots.default;
            var __VLS_861;
            const __VLS_874 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_875 = __VLS_asFunctionalComponent(__VLS_874, new __VLS_874({
                label: ("服务类型"),
                required: (true),
            }));
            const __VLS_876 = __VLS_875({
                label: ("服务类型"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_875));
            const __VLS_880 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_881 = __VLS_asFunctionalComponent(__VLS_880, new __VLS_880({
                modelValue: ((__VLS_ctx.assetForm.type)),
                ...{ style: ({}) },
            }));
            const __VLS_882 = __VLS_881({
                modelValue: ((__VLS_ctx.assetForm.type)),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_881));
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.assetTypes))) {
                const __VLS_886 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_887 = __VLS_asFunctionalComponent(__VLS_886, new __VLS_886({
                    key: ((item)),
                    label: ((item)),
                    value: ((item)),
                }));
                const __VLS_888 = __VLS_887({
                    key: ((item)),
                    label: ((item)),
                    value: ((item)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_887));
            }
            __VLS_885.slots.default;
            var __VLS_885;
            __VLS_879.slots.default;
            var __VLS_879;
            const __VLS_892 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_893 = __VLS_asFunctionalComponent(__VLS_892, new __VLS_892({
                label: ("连接地址"),
                required: (true),
            }));
            const __VLS_894 = __VLS_893({
                label: ("连接地址"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_893));
            const __VLS_898 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_899 = __VLS_asFunctionalComponent(__VLS_898, new __VLS_898({
                modelValue: ((__VLS_ctx.assetForm.endpoint)),
                maxlength: ("240"),
                placeholder: ("IP:端口或 HTTP 地址"),
            }));
            const __VLS_900 = __VLS_899({
                modelValue: ((__VLS_ctx.assetForm.endpoint)),
                maxlength: ("240"),
                placeholder: ("IP:端口或 HTTP 地址"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_899));
            __VLS_897.slots.default;
            var __VLS_897;
            if (__VLS_ctx.editingId) {
                const __VLS_904 = {}.ElFormItem;
                /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
                // @ts-ignore
                const __VLS_905 = __VLS_asFunctionalComponent(__VLS_904, new __VLS_904({
                    label: ("当前状态"),
                }));
                const __VLS_906 = __VLS_905({
                    label: ("当前状态"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_905));
                const __VLS_910 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_911 = __VLS_asFunctionalComponent(__VLS_910, new __VLS_910({
                    modelValue: ((__VLS_ctx.assetForm.status)),
                    ...{ style: ({}) },
                }));
                const __VLS_912 = __VLS_911({
                    modelValue: ((__VLS_ctx.assetForm.status)),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_911));
                const __VLS_916 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_917 = __VLS_asFunctionalComponent(__VLS_916, new __VLS_916({
                    label: ("未知"),
                    value: ("UNKNOWN"),
                }));
                const __VLS_918 = __VLS_917({
                    label: ("未知"),
                    value: ("UNKNOWN"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_917));
                const __VLS_922 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_923 = __VLS_asFunctionalComponent(__VLS_922, new __VLS_922({
                    label: ("正常"),
                    value: ("HEALTHY"),
                }));
                const __VLS_924 = __VLS_923({
                    label: ("正常"),
                    value: ("HEALTHY"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_923));
                const __VLS_928 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_929 = __VLS_asFunctionalComponent(__VLS_928, new __VLS_928({
                    label: ("注意"),
                    value: ("WARNING"),
                }));
                const __VLS_930 = __VLS_929({
                    label: ("注意"),
                    value: ("WARNING"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_929));
                const __VLS_934 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_935 = __VLS_asFunctionalComponent(__VLS_934, new __VLS_934({
                    label: ("异常"),
                    value: ("CRITICAL"),
                }));
                const __VLS_936 = __VLS_935({
                    label: ("异常"),
                    value: ("CRITICAL"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_935));
                __VLS_915.slots.default;
                var __VLS_915;
                __VLS_909.slots.default;
                var __VLS_909;
            }
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("production-note") },
            });
            const __VLS_940 = {}.ShieldCheck;
            /** @type { [typeof __VLS_components.ShieldCheck, ] } */ ;
            // @ts-ignore
            const __VLS_941 = __VLS_asFunctionalComponent(__VLS_940, new __VLS_940({
                size: ((17)),
            }));
            const __VLS_942 = __VLS_941({
                size: ((17)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_941));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_823.slots.default;
            var __VLS_823;
        }
        else {
            const __VLS_946 = {}.ElForm;
            /** @type { [typeof __VLS_components.ElForm, typeof __VLS_components.elForm, typeof __VLS_components.ElForm, typeof __VLS_components.elForm, ] } */ ;
            // @ts-ignore
            const __VLS_947 = __VLS_asFunctionalComponent(__VLS_946, new __VLS_946({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }));
            const __VLS_948 = __VLS_947({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_947));
            let __VLS_952;
            const __VLS_953 = {
                onSubmit: (__VLS_ctx.saveEditor)
            };
            let __VLS_949;
            let __VLS_950;
            const __VLS_954 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_955 = __VLS_asFunctionalComponent(__VLS_954, new __VLS_954({
                label: ("计划名称"),
                required: (true),
            }));
            const __VLS_956 = __VLS_955({
                label: ("计划名称"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_955));
            const __VLS_960 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_961 = __VLS_asFunctionalComponent(__VLS_960, new __VLS_960({
                modelValue: ((__VLS_ctx.planForm.name)),
                maxlength: ("100"),
                placeholder: ("例如：礼事随记生产巡检"),
            }));
            const __VLS_962 = __VLS_961({
                modelValue: ((__VLS_ctx.planForm.name)),
                maxlength: ("100"),
                placeholder: ("例如：礼事随记生产巡检"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_961));
            __VLS_959.slots.default;
            var __VLS_959;
            const __VLS_966 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_967 = __VLS_asFunctionalComponent(__VLS_966, new __VLS_966({
                label: ("所属项目"),
                required: (true),
            }));
            const __VLS_968 = __VLS_967({
                label: ("所属项目"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_967));
            const __VLS_972 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_973 = __VLS_asFunctionalComponent(__VLS_972, new __VLS_972({
                ...{ 'onChange': {} },
                modelValue: ((__VLS_ctx.planForm.projectId)),
                disabled: ((Boolean(__VLS_ctx.editingId))),
                placeholder: ("选择项目"),
                ...{ style: ({}) },
            }));
            const __VLS_974 = __VLS_973({
                ...{ 'onChange': {} },
                modelValue: ((__VLS_ctx.planForm.projectId)),
                disabled: ((Boolean(__VLS_ctx.editingId))),
                placeholder: ("选择项目"),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_973));
            let __VLS_978;
            const __VLS_979 = {
                onChange: (__VLS_ctx.selectPlanProject)
            };
            let __VLS_975;
            let __VLS_976;
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.projects))) {
                const __VLS_980 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_981 = __VLS_asFunctionalComponent(__VLS_980, new __VLS_980({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.id)),
                }));
                const __VLS_982 = __VLS_981({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.id)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_981));
            }
            __VLS_977.slots.default;
            var __VLS_977;
            __VLS_971.slots.default;
            var __VLS_971;
            const __VLS_986 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_987 = __VLS_asFunctionalComponent(__VLS_986, new __VLS_986({
                label: ("纳管服务"),
                required: (true),
            }));
            const __VLS_988 = __VLS_987({
                label: ("纳管服务"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_987));
            const __VLS_992 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_993 = __VLS_asFunctionalComponent(__VLS_992, new __VLS_992({
                modelValue: ((__VLS_ctx.planForm.assetIds)),
                multiple: (true),
                collapseTags: (true),
                placeholder: ("选择相同频率的服务"),
                ...{ style: ({}) },
            }));
            const __VLS_994 = __VLS_993({
                modelValue: ((__VLS_ctx.planForm.assetIds)),
                multiple: (true),
                collapseTags: (true),
                placeholder: ("选择相同频率的服务"),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_993));
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.assets.filter(asset => asset.projectName === __VLS_ctx.planForm.projectName)))) {
                const __VLS_998 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_999 = __VLS_asFunctionalComponent(__VLS_998, new __VLS_998({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.id)),
                }));
                const __VLS_1000 = __VLS_999({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.id)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_999));
            }
            __VLS_997.slots.default;
            var __VLS_997;
            __VLS_991.slots.default;
            var __VLS_991;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("form-grid") },
            });
            const __VLS_1004 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_1005 = __VLS_asFunctionalComponent(__VLS_1004, new __VLS_1004({
                label: ("检查频率"),
                required: (true),
            }));
            const __VLS_1006 = __VLS_1005({
                label: ("检查频率"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1005));
            const __VLS_1010 = {}.ElInputNumber;
            /** @type { [typeof __VLS_components.ElInputNumber, typeof __VLS_components.elInputNumber, ] } */ ;
            // @ts-ignore
            const __VLS_1011 = __VLS_asFunctionalComponent(__VLS_1010, new __VLS_1010({
                modelValue: ((__VLS_ctx.planForm.intervalValue)),
                min: ((__VLS_ctx.planIntervalMin)),
                max: ((__VLS_ctx.planIntervalMax)),
                ...{ style: ({}) },
            }));
            const __VLS_1012 = __VLS_1011({
                modelValue: ((__VLS_ctx.planForm.intervalValue)),
                min: ((__VLS_ctx.planIntervalMin)),
                max: ((__VLS_ctx.planIntervalMax)),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_1011));
            __VLS_1009.slots.default;
            var __VLS_1009;
            const __VLS_1016 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_1017 = __VLS_asFunctionalComponent(__VLS_1016, new __VLS_1016({
                label: ("频率单位"),
                required: (true),
            }));
            const __VLS_1018 = __VLS_1017({
                label: ("频率单位"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1017));
            const __VLS_1022 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_1023 = __VLS_asFunctionalComponent(__VLS_1022, new __VLS_1022({
                modelValue: ((__VLS_ctx.planForm.intervalUnit)),
                ...{ style: ({}) },
            }));
            const __VLS_1024 = __VLS_1023({
                modelValue: ((__VLS_ctx.planForm.intervalUnit)),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_1023));
            const __VLS_1028 = {}.ElOption;
            /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
            // @ts-ignore
            const __VLS_1029 = __VLS_asFunctionalComponent(__VLS_1028, new __VLS_1028({
                label: ("秒"),
                value: ("秒"),
            }));
            const __VLS_1030 = __VLS_1029({
                label: ("秒"),
                value: ("秒"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1029));
            const __VLS_1034 = {}.ElOption;
            /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
            // @ts-ignore
            const __VLS_1035 = __VLS_asFunctionalComponent(__VLS_1034, new __VLS_1034({
                label: ("分钟"),
                value: ("分钟"),
            }));
            const __VLS_1036 = __VLS_1035({
                label: ("分钟"),
                value: ("分钟"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1035));
            const __VLS_1040 = {}.ElOption;
            /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
            // @ts-ignore
            const __VLS_1041 = __VLS_asFunctionalComponent(__VLS_1040, new __VLS_1040({
                label: ("小时"),
                value: ("小时"),
            }));
            const __VLS_1042 = __VLS_1041({
                label: ("小时"),
                value: ("小时"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1041));
            __VLS_1027.slots.default;
            var __VLS_1027;
            __VLS_1021.slots.default;
            var __VLS_1021;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("form-grid") },
            });
            const __VLS_1046 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_1047 = __VLS_asFunctionalComponent(__VLS_1046, new __VLS_1046({
                label: ("连续失败次数"),
                required: (true),
            }));
            const __VLS_1048 = __VLS_1047({
                label: ("连续失败次数"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1047));
            const __VLS_1052 = {}.ElInputNumber;
            /** @type { [typeof __VLS_components.ElInputNumber, typeof __VLS_components.elInputNumber, ] } */ ;
            // @ts-ignore
            const __VLS_1053 = __VLS_asFunctionalComponent(__VLS_1052, new __VLS_1052({
                modelValue: ((__VLS_ctx.planForm.failureThreshold)),
                min: ((1)),
                max: ((10)),
                ...{ style: ({}) },
            }));
            const __VLS_1054 = __VLS_1053({
                modelValue: ((__VLS_ctx.planForm.failureThreshold)),
                min: ((1)),
                max: ((10)),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_1053));
            __VLS_1051.slots.default;
            var __VLS_1051;
            const __VLS_1058 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_1059 = __VLS_asFunctionalComponent(__VLS_1058, new __VLS_1058({
                label: ("告警级别"),
                required: (true),
            }));
            const __VLS_1060 = __VLS_1059({
                label: ("告警级别"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1059));
            const __VLS_1064 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_1065 = __VLS_asFunctionalComponent(__VLS_1064, new __VLS_1064({
                modelValue: ((__VLS_ctx.planForm.alertLevel)),
                ...{ style: ({}) },
            }));
            const __VLS_1066 = __VLS_1065({
                modelValue: ((__VLS_ctx.planForm.alertLevel)),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_1065));
            const __VLS_1070 = {}.ElOption;
            /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
            // @ts-ignore
            const __VLS_1071 = __VLS_asFunctionalComponent(__VLS_1070, new __VLS_1070({
                label: ("异常"),
                value: ("CRITICAL"),
            }));
            const __VLS_1072 = __VLS_1071({
                label: ("异常"),
                value: ("CRITICAL"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1071));
            const __VLS_1076 = {}.ElOption;
            /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
            // @ts-ignore
            const __VLS_1077 = __VLS_asFunctionalComponent(__VLS_1076, new __VLS_1076({
                label: ("注意"),
                value: ("WARNING"),
            }));
            const __VLS_1078 = __VLS_1077({
                label: ("注意"),
                value: ("WARNING"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1077));
            const __VLS_1082 = {}.ElOption;
            /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
            // @ts-ignore
            const __VLS_1083 = __VLS_asFunctionalComponent(__VLS_1082, new __VLS_1082({
                label: ("提示"),
                value: ("INFO"),
            }));
            const __VLS_1084 = __VLS_1083({
                label: ("提示"),
                value: ("INFO"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1083));
            __VLS_1069.slots.default;
            var __VLS_1069;
            __VLS_1063.slots.default;
            var __VLS_1063;
            const __VLS_1088 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_1089 = __VLS_asFunctionalComponent(__VLS_1088, new __VLS_1088({
                label: ("计划状态"),
            }));
            const __VLS_1090 = __VLS_1089({
                label: ("计划状态"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1089));
            const __VLS_1094 = {}.ElSegmented;
            /** @type { [typeof __VLS_components.ElSegmented, typeof __VLS_components.elSegmented, ] } */ ;
            // @ts-ignore
            const __VLS_1095 = __VLS_asFunctionalComponent(__VLS_1094, new __VLS_1094({
                modelValue: ((__VLS_ctx.planForm.status)),
                options: (([{ label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }])),
            }));
            const __VLS_1096 = __VLS_1095({
                modelValue: ((__VLS_ctx.planForm.status)),
                options: (([{ label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }])),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1095));
            __VLS_1093.slots.default;
            var __VLS_1093;
            __VLS_951.slots.default;
            var __VLS_951;
        }
        __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
        {
            const { footer: __VLS_thisSlot } = __VLS_725.slots;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("drawer-footer") },
            });
            const __VLS_1100 = {}.ElButton;
            /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
            // @ts-ignore
            const __VLS_1101 = __VLS_asFunctionalComponent(__VLS_1100, new __VLS_1100({
                ...{ 'onClick': {} },
            }));
            const __VLS_1102 = __VLS_1101({
                ...{ 'onClick': {} },
            }, ...__VLS_functionalComponentArgsRest(__VLS_1101));
            let __VLS_1106;
            const __VLS_1107 = {
                onClick: (...[$event]) => {
                    if (!(!((__VLS_ctx.authLoading))))
                        return;
                    if (!(!((!__VLS_ctx.authenticated))))
                        return;
                    __VLS_ctx.editorVisible = false;
                }
            };
            let __VLS_1103;
            let __VLS_1104;
            __VLS_1105.slots.default;
            var __VLS_1105;
            const __VLS_1108 = {}.ElButton;
            /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
            // @ts-ignore
            const __VLS_1109 = __VLS_asFunctionalComponent(__VLS_1108, new __VLS_1108({
                ...{ 'onClick': {} },
                type: ("primary"),
                loading: ((__VLS_ctx.saving)),
            }));
            const __VLS_1110 = __VLS_1109({
                ...{ 'onClick': {} },
                type: ("primary"),
                loading: ((__VLS_ctx.saving)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1109));
            let __VLS_1114;
            const __VLS_1115 = {
                onClick: (__VLS_ctx.saveEditor)
            };
            let __VLS_1111;
            let __VLS_1112;
            __VLS_1113.slots.default;
            var __VLS_1113;
        }
        var __VLS_725;
        const __VLS_1116 = {}.ElDrawer;
        /** @type { [typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, ] } */ ;
        // @ts-ignore
        const __VLS_1117 = __VLS_asFunctionalComponent(__VLS_1116, new __VLS_1116({
            modelValue: ((__VLS_ctx.projectDrawerVisible)),
            title: ((__VLS_ctx.selectedProject ? `项目详情 · ${__VLS_ctx.selectedProject.name}` : '项目详情')),
            size: ("520px"),
            appendToBody: (true),
        }));
        const __VLS_1118 = __VLS_1117({
            modelValue: ((__VLS_ctx.projectDrawerVisible)),
            title: ((__VLS_ctx.selectedProject ? `项目详情 · ${__VLS_ctx.selectedProject.name}` : '项目详情')),
            size: ("520px"),
            appendToBody: (true),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1117));
        if (__VLS_ctx.selectedProject) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("project-detail") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("project-detail-summary") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.selectedProject.projectType || '业务系统');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            const __VLS_1122 = {}.ElTag;
            /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
            // @ts-ignore
            const __VLS_1123 = __VLS_asFunctionalComponent(__VLS_1122, new __VLS_1122({
                type: ((__VLS_ctx.badge(__VLS_ctx.selectedProject.status))),
            }));
            const __VLS_1124 = __VLS_1123({
                type: ((__VLS_ctx.badge(__VLS_ctx.selectedProject.status))),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1123));
            (__VLS_ctx.statusText(__VLS_ctx.selectedProject.status));
            __VLS_1127.slots.default;
            var __VLS_1127;
            __VLS_elementAsFunction(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                ...{ class: ("alert-context") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedProject.owner);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedProject.contactPhone || '--');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedProject.maintenanceExpiresAt || '--');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedProject.description || '--');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("project-detail-metrics") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.selectedProject)))
                            return;
                        __VLS_ctx.projectDrawerVisible = false;
                        __VLS_ctx.openProjectServices(__VLS_ctx.selectedProject);
                    } },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.selectedProjectAssets.length);
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.selectedProject)))
                            return;
                        __VLS_ctx.projectDrawerVisible = false;
                        __VLS_ctx.navigateTo('inspections');
                    } },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.selectedProjectPlans.length);
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.selectedProject)))
                            return;
                        __VLS_ctx.projectDrawerVisible = false;
                        __VLS_ctx.openProjectAlerts(__VLS_ctx.selectedProject);
                    } },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.selectedProjectAlerts.filter(item => item.handlingStatus !== 'CLOSED').length);
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        }
        __VLS_1121.slots.default;
        var __VLS_1121;
        const __VLS_1128 = {}.ElDrawer;
        /** @type { [typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, ] } */ ;
        // @ts-ignore
        const __VLS_1129 = __VLS_asFunctionalComponent(__VLS_1128, new __VLS_1128({
            modelValue: ((__VLS_ctx.inspectionDrawerVisible)),
            title: ((__VLS_ctx.selectedInspection ? `巡检详细结果 · ${__VLS_ctx.selectedInspection.name}` : '巡检详细结果')),
            size: ("520px"),
        }));
        const __VLS_1130 = __VLS_1129({
            modelValue: ((__VLS_ctx.inspectionDrawerVisible)),
            title: ((__VLS_ctx.selectedInspection ? `巡检详细结果 · ${__VLS_ctx.selectedInspection.name}` : '巡检详细结果')),
            size: ("520px"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1129));
        if (__VLS_ctx.selectedInspection) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("inspection-detail") },
            });
            __VLS_asFunctionalDirective(__VLS_directives.vLoading)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.inspectionDetailLoading) }, null, null);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("detail-summary") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("detail-score") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.selectedInspection.passedChecks);
            (__VLS_ctx.selectedInspection.totalChecks);
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("detail-counts") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: ("passed") },
            });
            const __VLS_1134 = {}.CheckCircle2;
            /** @type { [typeof __VLS_components.CheckCircle2, ] } */ ;
            // @ts-ignore
            const __VLS_1135 = __VLS_asFunctionalComponent(__VLS_1134, new __VLS_1134({
                size: ((15)),
            }));
            const __VLS_1136 = __VLS_1135({
                size: ((15)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1135));
            (__VLS_ctx.inspectionCount('PASSED'));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: ("warning") },
            });
            const __VLS_1140 = {}.AlertTriangle;
            /** @type { [typeof __VLS_components.AlertTriangle, ] } */ ;
            // @ts-ignore
            const __VLS_1141 = __VLS_asFunctionalComponent(__VLS_1140, new __VLS_1140({
                size: ((15)),
            }));
            const __VLS_1142 = __VLS_1141({
                size: ((15)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1141));
            (__VLS_ctx.inspectionCount('WARNING'));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: ("failed") },
            });
            const __VLS_1146 = {}.XCircle;
            /** @type { [typeof __VLS_components.XCircle, ] } */ ;
            // @ts-ignore
            const __VLS_1147 = __VLS_asFunctionalComponent(__VLS_1146, new __VLS_1146({
                size: ((15)),
            }));
            const __VLS_1148 = __VLS_1147({
                size: ((15)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1147));
            (__VLS_ctx.inspectionCount('FAILED'));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("detail-context") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.selectedInspection.projectName);
            __VLS_elementAsFunction(__VLS_intrinsicElements.time, __VLS_intrinsicElements.time)({});
            (__VLS_ctx.formatRecordTime(__VLS_ctx.selectedInspection.executedAt));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.selectedInspection.durationSeconds);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("check-list") },
            });
            for (const [check] of __VLS_getVForSourceType((__VLS_ctx.inspectionItems))) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.article, __VLS_intrinsicElements.article)({
                    key: ((check.id)),
                    ...{ class: ("check-item") },
                    ...{ class: ((check.status.toLowerCase())) },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("check-icon") },
                });
                if (check.status === 'PASSED') {
                    const __VLS_1152 = {}.CheckCircle2;
                    /** @type { [typeof __VLS_components.CheckCircle2, ] } */ ;
                    // @ts-ignore
                    const __VLS_1153 = __VLS_asFunctionalComponent(__VLS_1152, new __VLS_1152({
                        size: ((18)),
                    }));
                    const __VLS_1154 = __VLS_1153({
                        size: ((18)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_1153));
                }
                else if (check.status === 'WARNING') {
                    const __VLS_1158 = {}.AlertTriangle;
                    /** @type { [typeof __VLS_components.AlertTriangle, ] } */ ;
                    // @ts-ignore
                    const __VLS_1159 = __VLS_asFunctionalComponent(__VLS_1158, new __VLS_1158({
                        size: ((18)),
                    }));
                    const __VLS_1160 = __VLS_1159({
                        size: ((18)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_1159));
                }
                else {
                    const __VLS_1164 = {}.XCircle;
                    /** @type { [typeof __VLS_components.XCircle, ] } */ ;
                    // @ts-ignore
                    const __VLS_1165 = __VLS_asFunctionalComponent(__VLS_1164, new __VLS_1164({
                        size: ((18)),
                    }));
                    const __VLS_1166 = __VLS_1165({
                        size: ((18)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_1165));
                }
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("check-content") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                (check.checkName);
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (check.targetName);
                __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
                (check.message);
                __VLS_elementAsFunction(__VLS_intrinsicElements.time, __VLS_intrinsicElements.time)({});
                (check.durationMillis);
            }
        }
        __VLS_1133.slots.default;
        var __VLS_1133;
        const __VLS_1170 = {}.ElDrawer;
        /** @type { [typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, ] } */ ;
        // @ts-ignore
        const __VLS_1171 = __VLS_asFunctionalComponent(__VLS_1170, new __VLS_1170({
            modelValue: ((__VLS_ctx.monitoringDrawerVisible)),
            title: ((__VLS_ctx.monitoringDetails ? `服务详情 · ${__VLS_ctx.monitoringDetails.asset.name}` : '服务详情')),
            size: ("560px"),
        }));
        const __VLS_1172 = __VLS_1171({
            modelValue: ((__VLS_ctx.monitoringDrawerVisible)),
            title: ((__VLS_ctx.monitoringDetails ? `服务详情 · ${__VLS_ctx.monitoringDetails.asset.name}` : '服务详情')),
            size: ("560px"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1171));
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("monitoring-detail") },
        });
        __VLS_asFunctionalDirective(__VLS_directives.vLoading)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.monitoringDetailLoading) }, null, null);
        if (__VLS_ctx.monitoringDetails) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("monitoring-summary") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.serviceState(__VLS_ctx.monitoringDetails.asset).text);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.monitoringDetails.asset.type);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.monitoringDetails.asset.lastCollectedAt ? __VLS_ctx.formatTime(__VLS_ctx.monitoringDetails.asset.lastCollectedAt) : '尚未检查');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("monitoring-summary") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.currentServicePlan(__VLS_ctx.monitoringDetails.asset)?.name || '未加入计划');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.serviceInspectionConfiguration(__VLS_ctx.monitoringDetails.asset).text);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.currentAssetAlerts(__VLS_ctx.monitoringDetails.asset).length);
            if (__VLS_ctx.monitoringDetails.asset.monitoringError) {
                const __VLS_1176 = {}.ElAlert;
                /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
                // @ts-ignore
                const __VLS_1177 = __VLS_asFunctionalComponent(__VLS_1176, new __VLS_1176({
                    title: ("巡检配置异常，请检查计划配置"),
                    type: ("warning"),
                    closable: ((false)),
                    showIcon: (true),
                }));
                const __VLS_1178 = __VLS_1177({
                    title: ("巡检配置异常，请检查计划配置"),
                    type: ("warning"),
                    closable: ((false)),
                    showIcon: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_1177));
            }
            else if (__VLS_ctx.monitoringDetails.asset.lastCollectedAt && __VLS_ctx.serviceState(__VLS_ctx.monitoringDetails.asset).text === '待检测') {
                const __VLS_1182 = {}.ElAlert;
                /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
                // @ts-ignore
                const __VLS_1183 = __VLS_asFunctionalComponent(__VLS_1182, new __VLS_1182({
                    title: ("检测结果待更新"),
                    type: ("info"),
                    closable: ((false)),
                    showIcon: (true),
                }));
                const __VLS_1184 = __VLS_1183({
                    title: ("检测结果待更新"),
                    type: ("info"),
                    closable: ((false)),
                    showIcon: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_1183));
            }
            // @ts-ignore
            /** @type { [typeof InspectionProfilePanel, ] } */ ;
            // @ts-ignore
            const __VLS_1188 = __VLS_asFunctionalComponent(InspectionProfilePanel, new InspectionProfilePanel({
                key: ((__VLS_ctx.monitoringDetails.asset.id)),
                asset: ((__VLS_ctx.monitoringDetails.asset)),
            }));
            const __VLS_1189 = __VLS_1188({
                key: ((__VLS_ctx.monitoringDetails.asset.id)),
                asset: ((__VLS_ctx.monitoringDetails.asset)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1188));
            if (!__VLS_ctx.monitoringDetails.latestMetrics.length) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("empty-trend") },
                });
            }
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("monitoring-metrics") },
            });
            for (const [metric] of __VLS_getVForSourceType((__VLS_ctx.monitoringDetails.latestMetrics))) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.article, __VLS_intrinsicElements.article)({
                    key: ((metric.name)),
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.metricLabel(metric.name));
                __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                (__VLS_ctx.metricValue(metric));
                if (metric.collectedAt) {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
                    (__VLS_ctx.formatTime(metric.collectedAt));
                }
            }
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("trend-section") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
            if (__VLS_ctx.monitoringDetails.history.length) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("trend-bars") },
                });
                for (const [point, index] of __VLS_getVForSourceType((__VLS_ctx.monitoringDetails.history))) {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.i)({
                        key: ((index)),
                        ...{ style: (({ height: `${Math.max(8, Math.min(100, Number(point.value || 0) / 3))}%` })) },
                        title: ((__VLS_ctx.metricValue(point))),
                    });
                }
            }
            else {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("empty-trend") },
                });
            }
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("sync-history") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.currentAssetAlerts(__VLS_ctx.monitoringDetails.asset)))) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    key: ((item.id)),
                });
                const __VLS_1193 = {}.ElTag;
                /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                // @ts-ignore
                const __VLS_1194 = __VLS_asFunctionalComponent(__VLS_1193, new __VLS_1193({
                    type: ((__VLS_ctx.badge(item.level))),
                    size: ("small"),
                }));
                const __VLS_1195 = __VLS_1194({
                    type: ((__VLS_ctx.badge(item.level))),
                    size: ("small"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_1194));
                (__VLS_ctx.statusText(item.level));
                __VLS_1198.slots.default;
                var __VLS_1198;
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (item.message);
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.statusText(item.externalLifecycle || 'FIRING'));
            }
            if (__VLS_ctx.currentAssetAlerts(__VLS_ctx.monitoringDetails.asset).length === 0) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("empty-trend") },
                });
            }
        }
        if (__VLS_ctx.monitoringDetails) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
            {
                const { footer: __VLS_thisSlot } = __VLS_1175.slots;
                const __VLS_1199 = {}.ElButton;
                /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
                // @ts-ignore
                const __VLS_1200 = __VLS_asFunctionalComponent(__VLS_1199, new __VLS_1199({
                    ...{ 'onClick': {} },
                    type: ("primary"),
                }));
                const __VLS_1201 = __VLS_1200({
                    ...{ 'onClick': {} },
                    type: ("primary"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_1200));
                let __VLS_1205;
                const __VLS_1206 = {
                    onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.monitoringDetails)))
                            return;
                        __VLS_ctx.openAssetPlanConfiguration(__VLS_ctx.monitoringDetails.asset);
                    }
                };
                let __VLS_1202;
                let __VLS_1203;
                (__VLS_ctx.currentServicePlan(__VLS_ctx.monitoringDetails.asset) ? '查看巡检计划' : '新增巡检计划');
                __VLS_1204.slots.default;
                var __VLS_1204;
            }
        }
        var __VLS_1175;
        const __VLS_1207 = {}.ElDrawer;
        /** @type { [typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, ] } */ ;
        // @ts-ignore
        const __VLS_1208 = __VLS_asFunctionalComponent(__VLS_1207, new __VLS_1207({
            modelValue: ((__VLS_ctx.alertDrawerVisible)),
            title: ((__VLS_ctx.selectedAlert ? `告警详情 · ${__VLS_ctx.selectedAlert.targetNameSnapshot || __VLS_ctx.selectedAlert.targetName}` : '告警详情')),
            size: ((__VLS_ctx.alertDrawerSize)),
            appendToBody: (true),
        }));
        const __VLS_1209 = __VLS_1208({
            modelValue: ((__VLS_ctx.alertDrawerVisible)),
            title: ((__VLS_ctx.selectedAlert ? `告警详情 · ${__VLS_ctx.selectedAlert.targetNameSnapshot || __VLS_ctx.selectedAlert.targetName}` : '告警详情')),
            size: ((__VLS_ctx.alertDrawerSize)),
            appendToBody: (true),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1208));
        if (__VLS_ctx.selectedAlert) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("alert-detail") },
            });
            __VLS_asFunctionalDirective(__VLS_directives.vLoading)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.alertDetailLoading) }, null, null);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("alert-state-summary") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            const __VLS_1213 = {}.ElTag;
            /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
            // @ts-ignore
            const __VLS_1214 = __VLS_asFunctionalComponent(__VLS_1213, new __VLS_1213({
                type: ((__VLS_ctx.selectedAlert.externalLifecycle === 'RESOLVED' ? 'success' : 'danger')),
                effect: ("plain"),
            }));
            const __VLS_1215 = __VLS_1214({
                type: ((__VLS_ctx.selectedAlert.externalLifecycle === 'RESOLVED' ? 'success' : 'danger')),
                effect: ("plain"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1214));
            (__VLS_ctx.statusText(__VLS_ctx.selectedAlert.externalLifecycle || 'FIRING'));
            __VLS_1218.slots.default;
            var __VLS_1218;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            const __VLS_1219 = {}.ElTag;
            /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
            // @ts-ignore
            const __VLS_1220 = __VLS_asFunctionalComponent(__VLS_1219, new __VLS_1219({
                type: ((__VLS_ctx.badge(__VLS_ctx.selectedAlert.handlingStatus))),
                effect: ("light"),
            }));
            const __VLS_1221 = __VLS_1220({
                type: ((__VLS_ctx.badge(__VLS_ctx.selectedAlert.handlingStatus))),
                effect: ("light"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1220));
            (__VLS_ctx.statusText(__VLS_ctx.selectedAlert.handlingStatus));
            __VLS_1224.slots.default;
            var __VLS_1224;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("alert-message") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: ("severity") },
                ...{ class: ((__VLS_ctx.selectedAlert.level.toLowerCase())) },
            });
            (__VLS_ctx.statusText(__VLS_ctx.selectedAlert.level));
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.selectedAlert.message);
            __VLS_elementAsFunction(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                ...{ class: ("alert-context") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedAlert.projectNameSnapshot || __VLS_ctx.selectedAlert.projectName);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedAlert.inspectionPlanName || '--');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedAlert.targetNameSnapshot || __VLS_ctx.selectedAlert.targetName);
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.formatRecordTime(__VLS_ctx.selectedAlert.firstOccurredAt || __VLS_ctx.selectedAlert.occurredAt));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.formatRecordTime(__VLS_ctx.selectedAlert.occurredAt));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedAlert.resolvedAt ? __VLS_ctx.formatRecordTime(__VLS_ctx.selectedAlert.resolvedAt) : '--');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("alert-actions") },
            });
            if (__VLS_ctx.selectedAlert.handlingStatus === 'OPEN') {
                const __VLS_1225 = {}.ElButton;
                /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
                // @ts-ignore
                const __VLS_1226 = __VLS_asFunctionalComponent(__VLS_1225, new __VLS_1225({
                    ...{ 'onClick': {} },
                    type: ("primary"),
                    loading: ((__VLS_ctx.alertActionLoading)),
                }));
                const __VLS_1227 = __VLS_1226({
                    ...{ 'onClick': {} },
                    type: ("primary"),
                    loading: ((__VLS_ctx.alertActionLoading)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_1226));
                let __VLS_1231;
                const __VLS_1232 = {
                    onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.selectedAlert)))
                            return;
                        if (!((__VLS_ctx.selectedAlert.handlingStatus === 'OPEN')))
                            return;
                        __VLS_ctx.handleAlertAction('acknowledge');
                    }
                };
                let __VLS_1228;
                let __VLS_1229;
                __VLS_1230.slots.default;
                var __VLS_1230;
            }
            const __VLS_1233 = {}.ElButton;
            /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
            // @ts-ignore
            const __VLS_1234 = __VLS_asFunctionalComponent(__VLS_1233, new __VLS_1233({
                ...{ 'onClick': {} },
                loading: ((__VLS_ctx.alertActionLoading)),
            }));
            const __VLS_1235 = __VLS_1234({
                ...{ 'onClick': {} },
                loading: ((__VLS_ctx.alertActionLoading)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_1234));
            let __VLS_1239;
            const __VLS_1240 = {
                onClick: (...[$event]) => {
                    if (!(!((__VLS_ctx.authLoading))))
                        return;
                    if (!(!((!__VLS_ctx.authenticated))))
                        return;
                    if (!((__VLS_ctx.selectedAlert)))
                        return;
                    __VLS_ctx.handleAlertAction('note');
                }
            };
            let __VLS_1236;
            let __VLS_1237;
            __VLS_1238.slots.default;
            var __VLS_1238;
            if (__VLS_ctx.selectedAlert.externalLifecycle === 'RESOLVED' && __VLS_ctx.selectedAlert.handlingStatus !== 'CLOSED') {
                const __VLS_1241 = {}.ElButton;
                /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
                // @ts-ignore
                const __VLS_1242 = __VLS_asFunctionalComponent(__VLS_1241, new __VLS_1241({
                    ...{ 'onClick': {} },
                    type: ("success"),
                    loading: ((__VLS_ctx.alertActionLoading)),
                }));
                const __VLS_1243 = __VLS_1242({
                    ...{ 'onClick': {} },
                    type: ("success"),
                    loading: ((__VLS_ctx.alertActionLoading)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_1242));
                let __VLS_1247;
                const __VLS_1248 = {
                    onClick: (...[$event]) => {
                        if (!(!((__VLS_ctx.authLoading))))
                            return;
                        if (!(!((!__VLS_ctx.authenticated))))
                            return;
                        if (!((__VLS_ctx.selectedAlert)))
                            return;
                        if (!((__VLS_ctx.selectedAlert.externalLifecycle === 'RESOLVED' && __VLS_ctx.selectedAlert.handlingStatus !== 'CLOSED')))
                            return;
                        __VLS_ctx.handleAlertAction('close');
                    }
                };
                let __VLS_1244;
                let __VLS_1245;
                __VLS_1246.slots.default;
                var __VLS_1246;
            }
            __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
                ...{ class: ("alert-history") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
            if (__VLS_ctx.alertEvents.length) {
                const __VLS_1249 = {}.ElTimeline;
                /** @type { [typeof __VLS_components.ElTimeline, typeof __VLS_components.elTimeline, typeof __VLS_components.ElTimeline, typeof __VLS_components.elTimeline, ] } */ ;
                // @ts-ignore
                const __VLS_1250 = __VLS_asFunctionalComponent(__VLS_1249, new __VLS_1249({}));
                const __VLS_1251 = __VLS_1250({}, ...__VLS_functionalComponentArgsRest(__VLS_1250));
                for (const [event] of __VLS_getVForSourceType((__VLS_ctx.alertEvents))) {
                    const __VLS_1255 = {}.ElTimelineItem;
                    /** @type { [typeof __VLS_components.ElTimelineItem, typeof __VLS_components.elTimelineItem, typeof __VLS_components.ElTimelineItem, typeof __VLS_components.elTimelineItem, ] } */ ;
                    // @ts-ignore
                    const __VLS_1256 = __VLS_asFunctionalComponent(__VLS_1255, new __VLS_1255({
                        key: ((event.id)),
                        timestamp: ((__VLS_ctx.formatRecordTime(event.occurredAt))),
                        placement: ("top"),
                    }));
                    const __VLS_1257 = __VLS_1256({
                        key: ((event.id)),
                        timestamp: ((__VLS_ctx.formatRecordTime(event.occurredAt))),
                        placement: ("top"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_1256));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                    (__VLS_ctx.statusText(event.action));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
                    (event.operatorName);
                    if (event.note) {
                        (event.note);
                    }
                    __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
                    (__VLS_ctx.statusText(event.fromStatus));
                    (__VLS_ctx.statusText(event.toStatus));
                    __VLS_1260.slots.default;
                    var __VLS_1260;
                }
                __VLS_1254.slots.default;
                var __VLS_1254;
            }
            else {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("empty-trend") },
                });
            }
        }
        __VLS_1212.slots.default;
        var __VLS_1212;
    }
    ['auth-loading', 'spinning', 'login-page', 'login-panel', 'login-brand', 'brand-mark', 'login-submit', 'app-shell', 'sidebar', 'brand', 'brand-mark', 'scope-label', 'nav-item', 'active', 'nav-arrow', 'sidebar-spacer', 'integration-list', 'scope-label', 'integration-dot', 'connected', 'operator', 'avatar', 'main-area', 'topbar', 'topbar-title', 'records-back', 'records-back', 'topbar-actions', 'search', 'secondary-button', 'spin-icon', 'secondary-button', 'spin-icon', 'primary-button', 'error-state', 'content', 'dashboard-view', 'metrics-grid', 'metric', 'metric-icon', 'blue', 'metric-arrow', 'metric', 'metric-icon', 'green', 'metric-arrow', 'metric', 'metric-icon', 'amber', 'metric-arrow', 'metric', 'metric-icon', 'red', 'metric-arrow', 'metric', 'metric-icon', 'cyan', 'metric-arrow', 'dashboard-grid', 'panel', 'span-two', 'panel-heading', 'text-button', 'asset-overview', 'asset-stat', 'health-bar', 'panel', 'panel-heading', 'pass-ring', 'mini-list', 'dot', 'panel', 'span-three', 'panel-heading', 'icon-button', 'alert-stream', 'alert-row', 'severity', 'alert-main', 'content', 'list-view', 'list-summary', 'page-empty', 'project-grid', 'project-card', 'project-head', 'project-symbol', 'card-actions', 'danger-action', 'project-meta', 'project-footer', 'table-panel', 'service-row-actions', 'row-actions', 'danger-action', 'inspection-center', 'plan-grid', 'is-empty', 'plan-card', 'plan-card-head', 'plan-icon', 'plan-state-tags', 'plan-service-summary', 'plan-rule-trigger', 'plan-actions', 'plan-commands', 'spin-icon', 'spin-icon', 'plan-toggle-button', 'danger-action', 'empty-state', 'plan-empty', 'records-content', 'table-panel', 'plan-empty', 'records-empty', 'inspection-pagination', 'alert-page', 'page-toolbar', 'alert-filters', 'alert-filters', 'table-panel', 'severity', 'icon-button', 'editor-form', 'form-grid', 'form-grid', 'editor-form', 'form-grid', 'production-note', 'editor-form', 'form-grid', 'form-grid', 'drawer-footer', 'project-detail', 'project-detail-summary', 'alert-context', 'project-detail-metrics', 'inspection-detail', 'detail-summary', 'detail-score', 'detail-counts', 'passed', 'warning', 'failed', 'detail-context', 'check-list', 'check-item', 'check-icon', 'check-content', 'monitoring-detail', 'monitoring-summary', 'monitoring-summary', 'empty-trend', 'monitoring-metrics', 'trend-section', 'trend-bars', 'empty-trend', 'sync-history', 'empty-trend', 'alert-detail', 'alert-state-summary', 'alert-message', 'severity', 'alert-context', 'alert-actions', 'alert-history', 'empty-trend',];
    var __VLS_slots;
    var $slots;
    let __VLS_inheritedAttrs;
    var $attrs;
    const __VLS_refs = {};
    var $refs;
    var $el;
    return {
        attrs: {},
        slots: __VLS_slots,
        refs: $refs,
        rootEl: $el,
    };
}
;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            InspectionProfilePanel: InspectionProfilePanel,
            Activity: Activity,
            AlertTriangle: AlertTriangle,
            ArrowLeft: ArrowLeft,
            BellRing: BellRing,
            CalendarClock: CalendarClock,
            CheckCircle2: CheckCircle2,
            ChevronRight: ChevronRight,
            Clock3: Clock3,
            CloudCog: CloudCog,
            ExternalLink: ExternalLink,
            FolderKanban: FolderKanban,
            HardDrive: HardDrive,
            ListChecks: ListChecks,
            LogOut: LogOut,
            Pencil: Pencil,
            Play: Play,
            Plus: Plus,
            Power: Power,
            RefreshCw: RefreshCw,
            Search: Search,
            Server: Server,
            ShieldCheck: ShieldCheck,
            Trash2: Trash2,
            XCircle: XCircle,
            activeView: activeView,
            authenticated: authenticated,
            authLoading: authLoading,
            loginLoading: loginLoading,
            currentUser: currentUser,
            loginForm: loginForm,
            loading: loading,
            error: error,
            keyword: keyword,
            summary: summary,
            projects: projects,
            assets: assets,
            inspections: inspections,
            visibleInspectionPlans: visibleInspectionPlans,
            inspectionItems: inspectionItems,
            inspectionSection: inspectionSection,
            selectedInspectionPlanId: selectedInspectionPlanId,
            selectedInspectionPlan: selectedInspectionPlan,
            inspectionPage: inspectionPage,
            inspectionPageSize: inspectionPageSize,
            inspectionDrawerVisible: inspectionDrawerVisible,
            selectedInspection: selectedInspection,
            inspectionDetailLoading: inspectionDetailLoading,
            alerts: alerts,
            alertExternalFilter: alertExternalFilter,
            alertHandlingFilter: alertHandlingFilter,
            alertLevelFilter: alertLevelFilter,
            alertProjectFilter: alertProjectFilter,
            alertPlanFilter: alertPlanFilter,
            alertAssetFilter: alertAssetFilter,
            alertTimeRange: alertTimeRange,
            alertProjectOptions: alertProjectOptions,
            alertPlanOptions: alertPlanOptions,
            alertAssetOptions: alertAssetOptions,
            alertDrawerVisible: alertDrawerVisible,
            alertDetailLoading: alertDetailLoading,
            alertActionLoading: alertActionLoading,
            selectedAlert: selectedAlert,
            alertEvents: alertEvents,
            alertDrawerSize: alertDrawerSize,
            selectedProjectName: selectedProjectName,
            projectDrawerVisible: projectDrawerVisible,
            selectedProject: selectedProject,
            globalListSource: globalListSource,
            hertzBeatConnected: hertzBeatConnected,
            editorVisible: editorVisible,
            editorMode: editorMode,
            editingId: editingId,
            saving: saving,
            runningPlanId: runningPlanId,
            syncingPlanId: syncingPlanId,
            syncingAllPlans: syncingAllPlans,
            syncingMonitoring: syncingMonitoring,
            monitoringDrawerVisible: monitoringDrawerVisible,
            monitoringDetailLoading: monitoringDetailLoading,
            monitoringDetails: monitoringDetails,
            projectForm: projectForm,
            assetForm: assetForm,
            planForm: planForm,
            environments: environments,
            assetTypes: assetTypes,
            navItems: navItems,
            searchPlaceholder: searchPlaceholder,
            filteredProjects: filteredProjects,
            assetCategoryStats: assetCategoryStats,
            currentTitle: currentTitle,
            filteredAssets: filteredAssets,
            selectedProjectAssets: selectedProjectAssets,
            selectedProjectPlans: selectedProjectPlans,
            selectedProjectAlerts: selectedProjectAlerts,
            planIntervalMin: planIntervalMin,
            planIntervalMax: planIntervalMax,
            selectedPlanInspections: selectedPlanInspections,
            pagedPlanInspections: pagedPlanInspections,
            filteredAlerts: filteredAlerts,
            currentAssetAlerts: currentAssetAlerts,
            navigateTo: navigateTo,
            openProjectServices: openProjectServices,
            openProjectAlerts: openProjectAlerts,
            backFromList: backFromList,
            openGlobalProjects: openGlobalProjects,
            openGlobalServices: openGlobalServices,
            openServiceCategory: openServiceCategory,
            openGlobalAlerts: openGlobalAlerts,
            openGlobalInspections: openGlobalInspections,
            badge: badge,
            statusText: statusText,
            formatTime: formatTime,
            formatRecordTime: formatRecordTime,
            loadData: loadData,
            login: login,
            logout: logout,
            openInspectionDetail: openInspectionDetail,
            inspectionCount: inspectionCount,
            openCreate: openCreate,
            currentServicePlan: currentServicePlan,
            serviceState: serviceState,
            planCheckSummary: planCheckSummary,
            serviceInspectionConfiguration: serviceInspectionConfiguration,
            openAssetPlanConfiguration: openAssetPlanConfiguration,
            openProjectEdit: openProjectEdit,
            openAssetEdit: openAssetEdit,
            openPlanEdit: openPlanEdit,
            openProjectDetail: openProjectDetail,
            selectPlanProject: selectPlanProject,
            saveEditor: saveEditor,
            removeProject: removeProject,
            removeAsset: removeAsset,
            syncPlan: syncPlan,
            syncAllPlans: syncAllPlans,
            syncMonitoringData: syncMonitoringData,
            openAlertDetail: openAlertDetail,
            handleAlertAction: handleAlertAction,
            openMonitoringDetails: openMonitoringDetails,
            metricLabel: metricLabel,
            metricValue: metricValue,
            togglePlan: togglePlan,
            removePlan: removePlan,
            executePlan: executePlan,
            openPlanRecords: openPlanRecords,
            backToPlans: backToPlans,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
