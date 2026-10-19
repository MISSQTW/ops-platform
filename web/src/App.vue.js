import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Activity, AlertTriangle, ArrowLeft, BellRing, CalendarClock, CheckCircle2, ChevronRight, CircleGauge, Clock3, CloudCog, Database, ExternalLink, FolderKanban, HardDrive, ListChecks, LogOut, Pencil, Play, Plus, Power, RefreshCw, Search, Server, ShieldCheck, Trash2, XCircle } from '@lucide/vue';
import { api, setAuthToken } from './api';
const activeView = ref('dashboard');
const authenticated = ref(false);
const authLoading = ref(true);
const loginLoading = ref(false);
const currentUser = reactive({ username: '', displayName: '', canManageIntegrations: false });
const loginForm = reactive({ username: 'admin', password: '' });
const loading = ref(true);
const error = ref('');
const keyword = ref('');
const lastUpdated = ref('');
const summary = ref({ projectCount: 0, assetCount: 0, abnormalAssetCount: 0, openAlertCount: 0, inspectionPassRate: 0 });
const projects = ref([]);
const assets = ref([]);
const inspections = ref([]);
const inspectionPlans = ref([]);
const inspectionItems = ref([]);
const inspectionSection = ref('plans');
const selectedInspectionPlan = ref(null);
const inspectionPage = ref(1);
const inspectionPageSize = 10;
const inspectionDrawerVisible = ref(false);
const selectedInspection = ref(null);
const inspectionDetailLoading = ref(false);
const alerts = ref([]);
const selectedProjectName = ref('');
const globalListSource = ref(false);
const serviceListMode = ref('all');
const serviceCategory = ref(null);
const pendingAlertsOnly = ref(false);
const hertzBeatConnected = ref(false);
const editorVisible = ref(false);
const editorMode = ref('project');
const editingId = ref(null);
const saving = ref(false);
const runningPlanId = ref(null);
const syncingAssetId = ref(null);
const syncingAllAssets = ref(false);
const syncingMonitoring = ref(false);
const monitoringRuns = ref([]);
const monitoringDrawerVisible = ref(false);
const monitoringDetailLoading = ref(false);
const monitoringDetails = ref(null);
const projectForm = reactive({ name: '', owner: '', description: '', status: 'RUNNING' });
const assetForm = reactive({ name: '', projectName: '', environmentName: '生产', type: 'SERVER', endpoint: '', status: 'UNKNOWN' });
const planForm = reactive({ name: '', projectName: '', scopeText: '', scheduleText: '每 5 分钟', status: 'ENABLED', nextRunAt: '' });
const scheduleForm = reactive({ mode: 'INTERVAL', intervalValue: 5, intervalUnit: '分钟', dailyTime: '09:00', cronExpression: '0 0 9 * * *' });
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
const filteredInspections = computed(() => inspections.value.filter(item => includesKeyword(item.name, item.projectName)));
const selectedPlanInspections = computed(() => filteredInspections.value.filter(item => !selectedInspectionPlan.value || (item.name === selectedInspectionPlan.value.name && item.projectName === selectedInspectionPlan.value.projectName)));
const pagedPlanInspections = computed(() => selectedPlanInspections.value.slice((inspectionPage.value - 1) * inspectionPageSize, inspectionPage.value * inspectionPageSize));
const filteredAlerts = computed(() => {
    const severityOrder = { CRITICAL: 0, WARNING: 1, INFO: 2 };
    return alerts.value
        .filter(item => (!selectedProjectName.value || item.projectName === selectedProjectName.value) && (!pendingAlertsOnly.value || item.status === 'OPEN') && includesKeyword(item.message, item.projectName, item.targetName))
        .sort((left, right) => new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime() || (severityOrder[left.level] ?? 9) - (severityOrder[right.level] ?? 9));
});
function navigateTo(view) {
    activeView.value = view;
    selectedProjectName.value = '';
    globalListSource.value = false;
    serviceListMode.value = 'all';
    serviceCategory.value = null;
    pendingAlertsOnly.value = false;
    if (view === 'inspections') {
        selectedInspectionPlan.value = null;
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
    if (activeView.value === 'assets' && serviceCategory.value) {
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
    selectedInspectionPlan.value = null;
    inspectionPage.value = 1;
    inspectionSection.value = 'results';
    globalListSource.value = true;
    activeView.value = 'inspections';
    keyword.value = '';
}
function backToDashboard() {
    selectedProjectName.value = '';
    selectedInspectionPlan.value = null;
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
        MAINTENANCE: 'warning', WARNING: 'warning', OPEN: 'danger', DOWN: 'danger', CRITICAL: 'danger', FAILED: 'danger', INFO: 'info',
    };
    return mapping[status] ?? 'info';
}
function statusText(status) {
    const mapping = {
        RUNNING: '运行中', UP: '可用', DOWN: '不可用', MAINTENANCE: '维护中', HEALTHY: '正常', WARNING: '注意', CRITICAL: '异常',
        PASSED: '通过', FAILED: '失败', OPEN: '待处理', ACKNOWLEDGED: '已确认', FIRING: '告警中', RESOLVED: '已恢复', INFO: '提示', UNKNOWN: '待采样',
        ENABLED: '已启用', DISABLED: '已停用',
    };
    return mapping[status] ?? status;
}
function formatTime(value) {
    return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value));
}
function formatRecordTime(value) {
    return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value));
}
function defaultNextRunAt() {
    const value = new Date(Date.now() + 5 * 60 * 1000);
    const offset = value.getTimezoneOffset() * 60_000;
    return new Date(value.getTime() - offset).toISOString().slice(0, 19);
}
function applyScheduleText() {
    if (scheduleForm.mode === 'INTERVAL')
        planForm.scheduleText = `每 ${scheduleForm.intervalValue} ${scheduleForm.intervalUnit}`;
    else if (scheduleForm.mode === 'DAILY')
        planForm.scheduleText = `每天 ${scheduleForm.dailyTime}`;
    else
        planForm.scheduleText = `Cron: ${scheduleForm.cronExpression}`;
}
function loadScheduleForm(scheduleText) {
    const interval = scheduleText.match(/^每\s*(\d+)\s*(分钟|小时)$/);
    if (interval)
        Object.assign(scheduleForm, { mode: 'INTERVAL', intervalValue: Number(interval[1]), intervalUnit: interval[2] });
    else if (scheduleText.startsWith('每天 '))
        Object.assign(scheduleForm, { mode: 'DAILY', dailyTime: scheduleText.slice(3) });
    else if (scheduleText.startsWith('Cron: '))
        Object.assign(scheduleForm, { mode: 'CRON', cronExpression: scheduleText.slice(6) });
    else
        Object.assign(scheduleForm, { mode: 'INTERVAL', intervalValue: 1, intervalUnit: '小时' });
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
            const [hertzBeatStatus, runData] = await Promise.all([api.hertzBeatStatus(), api.monitoringRuns()]);
            hertzBeatConnected.value = hertzBeatStatus.connected;
            monitoringRuns.value = runData;
        }
        catch {
            hertzBeatConnected.value = false;
        }
        lastUpdated.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date());
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
        Object.assign(projectForm, { name: '', owner: '', description: '', status: 'RUNNING' });
    else if (editorMode.value === 'asset')
        Object.assign(assetForm, { name: '', projectName: selectedProjectName.value || projects.value[0]?.name || '', environmentName: '生产', type: 'SERVER', endpoint: '', status: 'UNKNOWN' });
    else {
        Object.assign(planForm, { name: '', projectName: projects.value[0]?.name || '', scopeText: '', scheduleText: '每 5 分钟', status: 'ENABLED', nextRunAt: defaultNextRunAt() });
        Object.assign(scheduleForm, { mode: 'INTERVAL', intervalValue: 5, intervalUnit: '分钟', dailyTime: '09:00', cronExpression: '0 0 9 * * *' });
    }
    editorVisible.value = true;
}
function openProjectEdit(item) {
    editorMode.value = 'project';
    editingId.value = item.id;
    Object.assign(projectForm, { name: item.name, owner: item.owner, description: item.description, status: item.status });
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
    Object.assign(planForm, { name: item.name, projectName: item.projectName, scopeText: item.scopeText, scheduleText: item.scheduleText, status: item.status, nextRunAt: item.nextRunAt.slice(0, 19) });
    loadScheduleForm(item.scheduleText);
    editorVisible.value = true;
}
async function saveEditor() {
    saving.value = true;
    try {
        if (editorMode.value === 'project') {
            if (editingId.value)
                await api.updateProject(editingId.value, projectForm);
            else
                await api.createProject(projectForm);
        }
        else if (editorMode.value === 'asset') {
            if (editingId.value)
                await api.updateAsset(editingId.value, assetForm);
            else
                await api.createAsset(assetForm);
        }
        else {
            applyScheduleText();
            if (editingId.value)
                await api.updateInspectionPlan(editingId.value, planForm);
            else
                await api.createInspectionPlan(planForm);
        }
        ElMessage.success(editingId.value ? '修改已保存' : '新增成功');
        editorVisible.value = false;
        await loadData();
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '保存失败');
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
async function syncAsset(item) {
    syncingAssetId.value = item.id;
    try {
        const synced = await api.syncAssetToHertzBeat(item.id);
        const index = assets.value.findIndex(asset => asset.id === item.id);
        if (index >= 0)
            assets.value[index] = synced;
        if (synced.syncStatus === 'SYNCED')
            ElMessage.success(`已同步到 HertzBeat，监控 ID：${synced.hertzbeatMonitorId}`);
        else
            ElMessage.error(synced.syncMessage || '同步失败');
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '同步失败');
    }
    finally {
        syncingAssetId.value = null;
    }
}
async function syncAllAssets() {
    syncingAllAssets.value = true;
    try {
        const result = await api.syncAllAssetsToHertzBeat();
        assets.value = result.assets;
        if (result.failed === 0)
            ElMessage.success(`已同步 ${result.succeeded} 项资产到 HertzBeat`);
        else
            ElMessage.warning(`同步完成：成功 ${result.succeeded} 项，失败 ${result.failed} 项`);
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '一键同步失败');
    }
    finally {
        syncingAllAssets.value = false;
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
    await api.updateInspectionPlan(item.id, { ...item, status });
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
    runningPlanId.value = item.id;
    try {
        const result = await api.executeInspectionPlan(item.id);
        ElMessage.success(result.status === 'PASSED' ? '巡检执行完成，全部检查通过' : '巡检执行完成，发现异常项');
        selectedInspectionPlan.value = item;
        inspectionPage.value = 1;
        inspectionSection.value = 'results';
        await loadData();
    }
    catch (reason) {
        ElMessage.error(reason instanceof Error ? reason.message : '巡检执行失败');
    }
    finally {
        runningPlanId.value = null;
    }
}
function openPlanRecords(item) {
    selectedInspectionPlan.value = item;
    inspectionPage.value = 1;
    inspectionSection.value = 'results';
}
function backToPlans() {
    selectedInspectionPlan.value = null;
    inspectionPage.value = 1;
    inspectionSection.value = 'plans';
}
watch(keyword, () => {
    if (activeView.value === 'inspections' && inspectionSection.value === 'results')
        inspectionPage.value = 1;
});
onMounted(initializeAuth); /* PartiallyEnd: #3632/scriptSetup.vue */
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
        if (((__VLS_ctx.activeView === 'assets' || __VLS_ctx.activeView === 'alerts') && __VLS_ctx.selectedProjectName) || (__VLS_ctx.activeView === 'assets' && __VLS_ctx.serviceCategory)) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.backFromList) },
                ...{ class: ("records-back") },
                title: ((__VLS_ctx.serviceCategory ? '返回运行总览' : '返回项目中心')),
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
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_elementAsFunction(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({});
        (__VLS_ctx.currentTitle.title);
        __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
        (__VLS_ctx.currentTitle.description);
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("topbar-actions") },
        });
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("search") },
        });
        const __VLS_108 = {}.Search;
        /** @type { [typeof __VLS_components.Search, ] } */ ;
        // @ts-ignore
        const __VLS_109 = __VLS_asFunctionalComponent(__VLS_108, new __VLS_108({
            size: ((17)),
        }));
        const __VLS_110 = __VLS_109({
            size: ((17)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_109));
        __VLS_elementAsFunction(__VLS_intrinsicElements.input)({
            placeholder: ("搜索当前视图"),
        });
        (__VLS_ctx.keyword);
        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: ("updated") },
        });
        (__VLS_ctx.lastUpdated || '--:--:--');
        __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.loadData) },
            ...{ class: ("icon-button") },
            title: ("刷新数据"),
            disabled: ((__VLS_ctx.loading)),
        });
        const __VLS_114 = {}.RefreshCw;
        /** @type { [typeof __VLS_components.RefreshCw, ] } */ ;
        // @ts-ignore
        const __VLS_115 = __VLS_asFunctionalComponent(__VLS_114, new __VLS_114({
            size: ((17)),
            ...{ class: (({ spinning: __VLS_ctx.loading })) },
        }));
        const __VLS_116 = __VLS_115({
            size: ((17)),
            ...{ class: (({ spinning: __VLS_ctx.loading })) },
        }, ...__VLS_functionalComponentArgsRest(__VLS_115));
        if (__VLS_ctx.activeView === 'assets') {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.syncAllAssets) },
                ...{ class: ("secondary-button") },
                disabled: ((__VLS_ctx.syncingAllAssets)),
            });
            const __VLS_120 = {}.RefreshCw;
            /** @type { [typeof __VLS_components.RefreshCw, ] } */ ;
            // @ts-ignore
            const __VLS_121 = __VLS_asFunctionalComponent(__VLS_120, new __VLS_120({
                size: ((16)),
                ...{ class: (({ 'spin-icon': __VLS_ctx.syncingAllAssets })) },
            }));
            const __VLS_122 = __VLS_121({
                size: ((16)),
                ...{ class: (({ 'spin-icon': __VLS_ctx.syncingAllAssets })) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_121));
        }
        if (__VLS_ctx.activeView === 'assets' || __VLS_ctx.activeView === 'alerts') {
            __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.syncMonitoringData) },
                ...{ class: ("secondary-button") },
                disabled: ((__VLS_ctx.syncingMonitoring)),
            });
            const __VLS_126 = {}.Activity;
            /** @type { [typeof __VLS_components.Activity, ] } */ ;
            // @ts-ignore
            const __VLS_127 = __VLS_asFunctionalComponent(__VLS_126, new __VLS_126({
                size: ((16)),
                ...{ class: (({ 'spin-icon': __VLS_ctx.syncingMonitoring })) },
            }));
            const __VLS_128 = __VLS_127({
                size: ((16)),
                ...{ class: (({ 'spin-icon': __VLS_ctx.syncingMonitoring })) },
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
            (__VLS_ctx.activeView === 'projects' ? '项目' : __VLS_ctx.activeView === 'assets' ? '服务' : '计划');
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
            __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
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
            __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
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
            __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
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
            __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
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
            (__VLS_ctx.summary.inspectionPassRate);
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
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.summary.inspectionPassRate);
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
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
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("legend") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.i)({
                    ...{ class: ("ok") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.i)({
                    ...{ class: ("warn") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.i)({
                    ...{ class: ("bad") },
                });
            }
            if (__VLS_ctx.activeView === 'projects') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("project-grid") },
                });
                for (const [item] of __VLS_getVForSourceType((__VLS_ctx.filteredProjects))) {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.article, __VLS_intrinsicElements.article)({
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
                    const __VLS_228 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_229 = __VLS_asFunctionalComponent(__VLS_228, new __VLS_228({
                        type: ((__VLS_ctx.badge(item.status))),
                        effect: ("light"),
                    }));
                    const __VLS_230 = __VLS_229({
                        type: ((__VLS_ctx.badge(item.status))),
                        effect: ("light"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_229));
                    (__VLS_ctx.statusText(item.status));
                    __VLS_233.slots.default;
                    var __VLS_233;
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
                    const __VLS_234 = {}.Pencil;
                    /** @type { [typeof __VLS_components.Pencil, ] } */ ;
                    // @ts-ignore
                    const __VLS_235 = __VLS_asFunctionalComponent(__VLS_234, new __VLS_234({
                        size: ((15)),
                    }));
                    const __VLS_236 = __VLS_235({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_235));
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
                    const __VLS_240 = {}.Trash2;
                    /** @type { [typeof __VLS_components.Trash2, ] } */ ;
                    // @ts-ignore
                    const __VLS_241 = __VLS_asFunctionalComponent(__VLS_240, new __VLS_240({
                        size: ((15)),
                    }));
                    const __VLS_242 = __VLS_241({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_241));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
                    (item.name);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
                    (item.description);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("project-meta") },
                    });
                    __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
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
                    const __VLS_246 = {}.Server;
                    /** @type { [typeof __VLS_components.Server, ] } */ ;
                    // @ts-ignore
                    const __VLS_247 = __VLS_asFunctionalComponent(__VLS_246, new __VLS_246({
                        size: ((14)),
                    }));
                    const __VLS_248 = __VLS_247({
                        size: ((14)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_247));
                    (__VLS_ctx.assets.filter(asset => asset.projectName === item.name).length);
                    const __VLS_252 = {}.ChevronRight;
                    /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
                    // @ts-ignore
                    const __VLS_253 = __VLS_asFunctionalComponent(__VLS_252, new __VLS_252({
                        size: ((14)),
                    }));
                    const __VLS_254 = __VLS_253({
                        size: ((14)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_253));
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
                    const __VLS_258 = {}.AlertTriangle;
                    /** @type { [typeof __VLS_components.AlertTriangle, ] } */ ;
                    // @ts-ignore
                    const __VLS_259 = __VLS_asFunctionalComponent(__VLS_258, new __VLS_258({
                        size: ((14)),
                    }));
                    const __VLS_260 = __VLS_259({
                        size: ((14)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_259));
                    (__VLS_ctx.alerts.filter(alert => alert.projectName === item.name && alert.status === 'OPEN').length);
                    const __VLS_264 = {}.ChevronRight;
                    /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
                    // @ts-ignore
                    const __VLS_265 = __VLS_asFunctionalComponent(__VLS_264, new __VLS_264({
                        size: ((14)),
                    }));
                    const __VLS_266 = __VLS_265({
                        size: ((14)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_265));
                }
            }
            if (__VLS_ctx.activeView === 'assets') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("table-panel") },
                });
                const __VLS_270 = {}.ElTable;
                /** @type { [typeof __VLS_components.ElTable, typeof __VLS_components.elTable, typeof __VLS_components.ElTable, typeof __VLS_components.elTable, ] } */ ;
                // @ts-ignore
                const __VLS_271 = __VLS_asFunctionalComponent(__VLS_270, new __VLS_270({
                    data: ((__VLS_ctx.filteredAssets)),
                    stripe: (true),
                }));
                const __VLS_272 = __VLS_271({
                    data: ((__VLS_ctx.filteredAssets)),
                    stripe: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_271));
                const __VLS_276 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_277 = __VLS_asFunctionalComponent(__VLS_276, new __VLS_276({
                    prop: ("name"),
                    label: ("服务名称"),
                    minWidth: ("170"),
                }));
                const __VLS_278 = __VLS_277({
                    prop: ("name"),
                    label: ("服务名称"),
                    minWidth: ("170"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_277));
                const __VLS_282 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_283 = __VLS_asFunctionalComponent(__VLS_282, new __VLS_282({
                    prop: ("projectName"),
                    label: ("所属项目"),
                    minWidth: ("150"),
                }));
                const __VLS_284 = __VLS_283({
                    prop: ("projectName"),
                    label: ("所属项目"),
                    minWidth: ("150"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_283));
                const __VLS_288 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_289 = __VLS_asFunctionalComponent(__VLS_288, new __VLS_288({
                    prop: ("environmentName"),
                    label: ("环境"),
                    width: ("90"),
                }));
                const __VLS_290 = __VLS_289({
                    prop: ("environmentName"),
                    label: ("环境"),
                    width: ("90"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_289));
                const __VLS_294 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_295 = __VLS_asFunctionalComponent(__VLS_294, new __VLS_294({
                    prop: ("type"),
                    label: ("服务类型"),
                    width: ("120"),
                }));
                const __VLS_296 = __VLS_295({
                    prop: ("type"),
                    label: ("服务类型"),
                    width: ("120"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_295));
                const __VLS_300 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_301 = __VLS_asFunctionalComponent(__VLS_300, new __VLS_300({
                    prop: ("endpoint"),
                    label: ("连接地址"),
                    minWidth: ("190"),
                }));
                const __VLS_302 = __VLS_301({
                    prop: ("endpoint"),
                    label: ("连接地址"),
                    minWidth: ("190"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_301));
                const __VLS_306 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_307 = __VLS_asFunctionalComponent(__VLS_306, new __VLS_306({
                    label: ("运行状态"),
                    width: ("120"),
                }));
                const __VLS_308 = __VLS_307({
                    label: ("运行状态"),
                    width: ("120"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_307));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_311.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("asset-status") },
                    });
                    const __VLS_312 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_313 = __VLS_asFunctionalComponent(__VLS_312, new __VLS_312({
                        type: ((__VLS_ctx.badge(scope.row.status))),
                        effect: ("light"),
                    }));
                    const __VLS_314 = __VLS_313({
                        type: ((__VLS_ctx.badge(scope.row.status))),
                        effect: ("light"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_313));
                    (__VLS_ctx.statusText(scope.row.status));
                    __VLS_317.slots.default;
                    var __VLS_317;
                    if (scope.row.monitoringStale) {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (scope.row.lastCollectedAt ? '数据已陈旧' : '暂无采样');
                    }
                }
                var __VLS_311;
                const __VLS_318 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_319 = __VLS_asFunctionalComponent(__VLS_318, new __VLS_318({
                    label: ("监控同步"),
                    width: ("150"),
                }));
                const __VLS_320 = __VLS_319({
                    label: ("监控同步"),
                    width: ("150"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_319));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_323.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    const __VLS_324 = {}.ElTooltip;
                    /** @type { [typeof __VLS_components.ElTooltip, typeof __VLS_components.elTooltip, typeof __VLS_components.ElTooltip, typeof __VLS_components.elTooltip, ] } */ ;
                    // @ts-ignore
                    const __VLS_325 = __VLS_asFunctionalComponent(__VLS_324, new __VLS_324({
                        content: ((scope.row.monitoringError || scope.row.syncMessage || '尚未同步到 HertzBeat')),
                        placement: ("top"),
                    }));
                    const __VLS_326 = __VLS_325({
                        content: ((scope.row.monitoringError || scope.row.syncMessage || '尚未同步到 HertzBeat')),
                        placement: ("top"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_325));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("sync-state") },
                    });
                    const __VLS_330 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_331 = __VLS_asFunctionalComponent(__VLS_330, new __VLS_330({
                        type: ((scope.row.mappingStatus === 'MANAGED' ? 'success' : scope.row.mappingStatus === 'INVALIDATED' ? 'danger' : 'info')),
                        effect: ("light"),
                    }));
                    const __VLS_332 = __VLS_331({
                        type: ((scope.row.mappingStatus === 'MANAGED' ? 'success' : scope.row.mappingStatus === 'INVALIDATED' ? 'danger' : 'info')),
                        effect: ("light"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_331));
                    (scope.row.mappingStatus === 'MANAGED' ? 'HertzBeat 管理' : scope.row.mappingStatus === 'INVALIDATED' ? '映射失效' : '本地巡检');
                    __VLS_335.slots.default;
                    var __VLS_335;
                    __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (scope.row.lastSuccessfulSyncAt ? __VLS_ctx.formatTime(scope.row.lastSuccessfulSyncAt) : '未回写');
                    __VLS_329.slots.default;
                    var __VLS_329;
                }
                var __VLS_323;
                const __VLS_336 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_337 = __VLS_asFunctionalComponent(__VLS_336, new __VLS_336({
                    label: ("操作"),
                    width: ("170"),
                    fixed: ("right"),
                }));
                const __VLS_338 = __VLS_337({
                    label: ("操作"),
                    width: ("170"),
                    fixed: ("right"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_337));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_341.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
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
                        title: ("监控详情"),
                    });
                    const __VLS_342 = {}.Activity;
                    /** @type { [typeof __VLS_components.Activity, ] } */ ;
                    // @ts-ignore
                    const __VLS_343 = __VLS_asFunctionalComponent(__VLS_342, new __VLS_342({
                        size: ((15)),
                    }));
                    const __VLS_344 = __VLS_343({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_343));
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
                                __VLS_ctx.syncAsset(scope.row);
                            } },
                        title: ((scope.row.syncStatus === 'SYNCED' ? '重新同步到 HertzBeat' : '同步到 HertzBeat')),
                        disabled: ((__VLS_ctx.syncingAssetId === scope.row.id)),
                    });
                    const __VLS_348 = {}.RefreshCw;
                    /** @type { [typeof __VLS_components.RefreshCw, ] } */ ;
                    // @ts-ignore
                    const __VLS_349 = __VLS_asFunctionalComponent(__VLS_348, new __VLS_348({
                        size: ((15)),
                        ...{ class: (({ 'spin-icon': __VLS_ctx.syncingAssetId === scope.row.id })) },
                    }));
                    const __VLS_350 = __VLS_349({
                        size: ((15)),
                        ...{ class: (({ 'spin-icon': __VLS_ctx.syncingAssetId === scope.row.id })) },
                    }, ...__VLS_functionalComponentArgsRest(__VLS_349));
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
                    const __VLS_354 = {}.Pencil;
                    /** @type { [typeof __VLS_components.Pencil, ] } */ ;
                    // @ts-ignore
                    const __VLS_355 = __VLS_asFunctionalComponent(__VLS_354, new __VLS_354({
                        size: ((15)),
                    }));
                    const __VLS_356 = __VLS_355({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_355));
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
                    const __VLS_360 = {}.Trash2;
                    /** @type { [typeof __VLS_components.Trash2, ] } */ ;
                    // @ts-ignore
                    const __VLS_361 = __VLS_asFunctionalComponent(__VLS_360, new __VLS_360({
                        size: ((15)),
                    }));
                    const __VLS_362 = __VLS_361({
                        size: ((15)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_361));
                }
                var __VLS_341;
                __VLS_275.slots.default;
                var __VLS_275;
            }
            if (__VLS_ctx.activeView === 'inspections') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("inspection-center") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("inspection-view-heading") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("inspection-heading-title") },
                });
                if (__VLS_ctx.inspectionSection === 'results' && __VLS_ctx.selectedInspectionPlan) {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (__VLS_ctx.backToPlans) },
                        ...{ class: ("records-back") },
                        title: ("返回巡检计划"),
                    });
                    const __VLS_366 = {}.ArrowLeft;
                    /** @type { [typeof __VLS_components.ArrowLeft, ] } */ ;
                    // @ts-ignore
                    const __VLS_367 = __VLS_asFunctionalComponent(__VLS_366, new __VLS_366({
                        size: ((18)),
                    }));
                    const __VLS_368 = __VLS_367({
                        size: ((18)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_367));
                }
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                __VLS_elementAsFunction(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({});
                (__VLS_ctx.inspectionSection === 'plans' ? '巡检计划列表' : __VLS_ctx.selectedInspectionPlan ? `${__VLS_ctx.selectedInspectionPlan.name} · 执行记录` : '执行记录');
                if (__VLS_ctx.inspectionSection === 'plans') {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
                }
                if (__VLS_ctx.inspectionSection === 'plans') {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("plan-grid") },
                    });
                    for (const [plan] of __VLS_getVForSourceType((__VLS_ctx.inspectionPlans))) {
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
                        const __VLS_372 = {}.CalendarClock;
                        /** @type { [typeof __VLS_components.CalendarClock, ] } */ ;
                        // @ts-ignore
                        const __VLS_373 = __VLS_asFunctionalComponent(__VLS_372, new __VLS_372({
                            size: ((19)),
                        }));
                        const __VLS_374 = __VLS_373({
                            size: ((19)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_373));
                        const __VLS_378 = {}.ElTag;
                        /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                        // @ts-ignore
                        const __VLS_379 = __VLS_asFunctionalComponent(__VLS_378, new __VLS_378({
                            type: ((plan.status === 'ENABLED' ? 'success' : 'info')),
                            effect: ("light"),
                        }));
                        const __VLS_380 = __VLS_379({
                            type: ((plan.status === 'ENABLED' ? 'success' : 'info')),
                            effect: ("light"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_379));
                        (__VLS_ctx.statusText(plan.status));
                        __VLS_383.slots.default;
                        var __VLS_383;
                        __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
                        (plan.name);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
                        (plan.scopeText);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (plan.projectName);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (plan.scheduleText);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (__VLS_ctx.formatTime(plan.nextRunAt));
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
                            disabled: ((__VLS_ctx.runningPlanId === plan.id)),
                        });
                        if (__VLS_ctx.runningPlanId === plan.id) {
                            const __VLS_384 = {}.RefreshCw;
                            /** @type { [typeof __VLS_components.RefreshCw, ] } */ ;
                            // @ts-ignore
                            const __VLS_385 = __VLS_asFunctionalComponent(__VLS_384, new __VLS_384({
                                size: ((15)),
                                ...{ class: ("spin-icon") },
                            }));
                            const __VLS_386 = __VLS_385({
                                size: ((15)),
                                ...{ class: ("spin-icon") },
                            }, ...__VLS_functionalComponentArgsRest(__VLS_385));
                        }
                        else {
                            const __VLS_390 = {}.Play;
                            /** @type { [typeof __VLS_components.Play, ] } */ ;
                            // @ts-ignore
                            const __VLS_391 = __VLS_asFunctionalComponent(__VLS_390, new __VLS_390({
                                size: ((15)),
                            }));
                            const __VLS_392 = __VLS_391({
                                size: ((15)),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_391));
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
                                    __VLS_ctx.openPlanRecords(plan);
                                } },
                            title: ("执行记录"),
                        });
                        const __VLS_396 = {}.Clock3;
                        /** @type { [typeof __VLS_components.Clock3, ] } */ ;
                        // @ts-ignore
                        const __VLS_397 = __VLS_asFunctionalComponent(__VLS_396, new __VLS_396({
                            size: ((15)),
                        }));
                        const __VLS_398 = __VLS_397({
                            size: ((15)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_397));
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
                        const __VLS_402 = {}.Power;
                        /** @type { [typeof __VLS_components.Power, ] } */ ;
                        // @ts-ignore
                        const __VLS_403 = __VLS_asFunctionalComponent(__VLS_402, new __VLS_402({
                            size: ((15)),
                        }));
                        const __VLS_404 = __VLS_403({
                            size: ((15)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_403));
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
                        const __VLS_408 = {}.Pencil;
                        /** @type { [typeof __VLS_components.Pencil, ] } */ ;
                        // @ts-ignore
                        const __VLS_409 = __VLS_asFunctionalComponent(__VLS_408, new __VLS_408({
                            size: ((15)),
                        }));
                        const __VLS_410 = __VLS_409({
                            size: ((15)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_409));
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
                        const __VLS_414 = {}.Trash2;
                        /** @type { [typeof __VLS_components.Trash2, ] } */ ;
                        // @ts-ignore
                        const __VLS_415 = __VLS_asFunctionalComponent(__VLS_414, new __VLS_414({
                            size: ((15)),
                        }));
                        const __VLS_416 = __VLS_415({
                            size: ((15)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_415));
                    }
                    if (__VLS_ctx.inspectionPlans.length === 0) {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("empty-state plan-empty") },
                        });
                        const __VLS_420 = {}.CalendarClock;
                        /** @type { [typeof __VLS_components.CalendarClock, ] } */ ;
                        // @ts-ignore
                        const __VLS_421 = __VLS_asFunctionalComponent(__VLS_420, new __VLS_420({
                            size: ((26)),
                        }));
                        const __VLS_422 = __VLS_421({
                            size: ((26)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_421));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    }
                }
                else {
                    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: ("result-list") },
                    });
                    for (const [item] of __VLS_getVForSourceType((__VLS_ctx.pagedPlanInspections))) {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.article, __VLS_intrinsicElements.article)({
                            ...{ onClick: (...[$event]) => {
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
                                    __VLS_ctx.openInspectionDetail(item);
                                } },
                            key: ((item.id)),
                            ...{ class: ("result-card") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("result-status") },
                            ...{ class: ((item.status.toLowerCase())) },
                        });
                        if (item.status === 'PASSED') {
                            const __VLS_426 = {}.CheckCircle2;
                            /** @type { [typeof __VLS_components.CheckCircle2, ] } */ ;
                            // @ts-ignore
                            const __VLS_427 = __VLS_asFunctionalComponent(__VLS_426, new __VLS_426({
                                size: ((22)),
                            }));
                            const __VLS_428 = __VLS_427({
                                size: ((22)),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_427));
                        }
                        else {
                            const __VLS_432 = {}.XCircle;
                            /** @type { [typeof __VLS_components.XCircle, ] } */ ;
                            // @ts-ignore
                            const __VLS_433 = __VLS_asFunctionalComponent(__VLS_432, new __VLS_432({
                                size: ((22)),
                            }));
                            const __VLS_434 = __VLS_433({
                                size: ((22)),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_433));
                        }
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("result-main") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
                        (item.name);
                        const __VLS_438 = {}.ElTag;
                        /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                        // @ts-ignore
                        const __VLS_439 = __VLS_asFunctionalComponent(__VLS_438, new __VLS_438({
                            type: ((__VLS_ctx.badge(item.status))),
                            effect: ("light"),
                        }));
                        const __VLS_440 = __VLS_439({
                            type: ((__VLS_ctx.badge(item.status))),
                            effect: ("light"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_439));
                        (__VLS_ctx.statusText(item.status));
                        __VLS_443.slots.default;
                        var __VLS_443;
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("result-score") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                        (item.passedChecks);
                        (item.totalChecks);
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("result-meta") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.time, __VLS_intrinsicElements.time)({
                            ...{ class: ("result-executed-at") },
                        });
                        (__VLS_ctx.formatRecordTime(item.executedAt));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        const __VLS_444 = {}.Clock3;
                        /** @type { [typeof __VLS_components.Clock3, ] } */ ;
                        // @ts-ignore
                        const __VLS_445 = __VLS_asFunctionalComponent(__VLS_444, new __VLS_444({
                            size: ((13)),
                        }));
                        const __VLS_446 = __VLS_445({
                            size: ((13)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_445));
                        (item.durationSeconds);
                        const __VLS_450 = {}.ChevronRight;
                        /** @type { [typeof __VLS_components.ChevronRight, ] } */ ;
                        // @ts-ignore
                        const __VLS_451 = __VLS_asFunctionalComponent(__VLS_450, new __VLS_450({
                            size: ((18)),
                            ...{ class: ("result-arrow") },
                        }));
                        const __VLS_452 = __VLS_451({
                            size: ((18)),
                            ...{ class: ("result-arrow") },
                        }, ...__VLS_functionalComponentArgsRest(__VLS_451));
                    }
                    if (__VLS_ctx.selectedPlanInspections.length === 0) {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("empty-state") },
                        });
                        const __VLS_456 = {}.Clock3;
                        /** @type { [typeof __VLS_components.Clock3, ] } */ ;
                        // @ts-ignore
                        const __VLS_457 = __VLS_asFunctionalComponent(__VLS_456, new __VLS_456({
                            size: ((26)),
                        }));
                        const __VLS_458 = __VLS_457({
                            size: ((26)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_457));
                        __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    }
                    else {
                        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: ("inspection-pagination") },
                        });
                        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.selectedPlanInspections.length);
                        const __VLS_462 = {}.ElPagination;
                        /** @type { [typeof __VLS_components.ElPagination, typeof __VLS_components.elPagination, ] } */ ;
                        // @ts-ignore
                        const __VLS_463 = __VLS_asFunctionalComponent(__VLS_462, new __VLS_462({
                            currentPage: ((__VLS_ctx.inspectionPage)),
                            pageSize: ((__VLS_ctx.inspectionPageSize)),
                            total: ((__VLS_ctx.selectedPlanInspections.length)),
                            layout: ("prev, pager, next"),
                            background: (true),
                        }));
                        const __VLS_464 = __VLS_463({
                            currentPage: ((__VLS_ctx.inspectionPage)),
                            pageSize: ((__VLS_ctx.inspectionPageSize)),
                            total: ((__VLS_ctx.selectedPlanInspections.length)),
                            layout: ("prev, pager, next"),
                            background: (true),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_463));
                    }
                }
            }
            if (__VLS_ctx.activeView === 'alerts') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("table-panel") },
                });
                const __VLS_468 = {}.ElTable;
                /** @type { [typeof __VLS_components.ElTable, typeof __VLS_components.elTable, typeof __VLS_components.ElTable, typeof __VLS_components.elTable, ] } */ ;
                // @ts-ignore
                const __VLS_469 = __VLS_asFunctionalComponent(__VLS_468, new __VLS_468({
                    data: ((__VLS_ctx.filteredAlerts)),
                    stripe: (true),
                }));
                const __VLS_470 = __VLS_469({
                    data: ((__VLS_ctx.filteredAlerts)),
                    stripe: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_469));
                const __VLS_474 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_475 = __VLS_asFunctionalComponent(__VLS_474, new __VLS_474({
                    label: ("级别"),
                    width: ("100"),
                }));
                const __VLS_476 = __VLS_475({
                    label: ("级别"),
                    width: ("100"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_475));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_479.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: ("severity") },
                        ...{ class: ((scope.row.level.toLowerCase())) },
                    });
                    (__VLS_ctx.statusText(scope.row.level));
                }
                var __VLS_479;
                const __VLS_480 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_481 = __VLS_asFunctionalComponent(__VLS_480, new __VLS_480({
                    prop: ("message"),
                    label: ("告警内容"),
                    minWidth: ("250"),
                }));
                const __VLS_482 = __VLS_481({
                    prop: ("message"),
                    label: ("告警内容"),
                    minWidth: ("250"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_481));
                const __VLS_486 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_487 = __VLS_asFunctionalComponent(__VLS_486, new __VLS_486({
                    prop: ("projectName"),
                    label: ("所属项目"),
                    minWidth: ("160"),
                }));
                const __VLS_488 = __VLS_487({
                    prop: ("projectName"),
                    label: ("所属项目"),
                    minWidth: ("160"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_487));
                const __VLS_492 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_493 = __VLS_asFunctionalComponent(__VLS_492, new __VLS_492({
                    prop: ("targetName"),
                    label: ("告警对象"),
                    minWidth: ("160"),
                }));
                const __VLS_494 = __VLS_493({
                    prop: ("targetName"),
                    label: ("告警对象"),
                    minWidth: ("160"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_493));
                const __VLS_498 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_499 = __VLS_asFunctionalComponent(__VLS_498, new __VLS_498({
                    label: ("发生时间"),
                    width: ("150"),
                }));
                const __VLS_500 = __VLS_499({
                    label: ("发生时间"),
                    width: ("150"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_499));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_503.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    (__VLS_ctx.formatTime(scope.row.occurredAt));
                }
                var __VLS_503;
                const __VLS_504 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_505 = __VLS_asFunctionalComponent(__VLS_504, new __VLS_504({
                    label: ("外部状态"),
                    width: ("100"),
                }));
                const __VLS_506 = __VLS_505({
                    label: ("外部状态"),
                    width: ("100"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_505));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_509.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    const __VLS_510 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_511 = __VLS_asFunctionalComponent(__VLS_510, new __VLS_510({
                        type: ((scope.row.externalLifecycle === 'RESOLVED' ? 'success' : 'danger')),
                        effect: ("plain"),
                    }));
                    const __VLS_512 = __VLS_511({
                        type: ((scope.row.externalLifecycle === 'RESOLVED' ? 'success' : 'danger')),
                        effect: ("plain"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_511));
                    (__VLS_ctx.statusText(scope.row.externalLifecycle || 'FIRING'));
                    __VLS_515.slots.default;
                    var __VLS_515;
                }
                var __VLS_509;
                const __VLS_516 = {}.ElTableColumn;
                /** @type { [typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, typeof __VLS_components.ElTableColumn, typeof __VLS_components.elTableColumn, ] } */ ;
                // @ts-ignore
                const __VLS_517 = __VLS_asFunctionalComponent(__VLS_516, new __VLS_516({
                    label: ("处置状态"),
                    width: ("110"),
                }));
                const __VLS_518 = __VLS_517({
                    label: ("处置状态"),
                    width: ("110"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_517));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { default: __VLS_thisSlot } = __VLS_521.slots;
                    const [scope] = __VLS_getSlotParams(__VLS_thisSlot);
                    const __VLS_522 = {}.ElTag;
                    /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                    // @ts-ignore
                    const __VLS_523 = __VLS_asFunctionalComponent(__VLS_522, new __VLS_522({
                        type: ((__VLS_ctx.badge(scope.row.status))),
                        effect: ("light"),
                    }));
                    const __VLS_524 = __VLS_523({
                        type: ((__VLS_ctx.badge(scope.row.status))),
                        effect: ("light"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_523));
                    (__VLS_ctx.statusText(scope.row.status));
                    __VLS_527.slots.default;
                    var __VLS_527;
                }
                var __VLS_521;
                __VLS_473.slots.default;
                var __VLS_473;
            }
        }
        const __VLS_528 = {}.ElDrawer;
        /** @type { [typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, ] } */ ;
        // @ts-ignore
        const __VLS_529 = __VLS_asFunctionalComponent(__VLS_528, new __VLS_528({
            modelValue: ((__VLS_ctx.editorVisible)),
            title: ((`${__VLS_ctx.editingId ? '编辑' : '新增'}${__VLS_ctx.editorMode === 'project' ? '项目' : __VLS_ctx.editorMode === 'asset' ? '服务' : '巡检计划'}`)),
            size: ("440px"),
            destroyOnClose: (true),
        }));
        const __VLS_530 = __VLS_529({
            modelValue: ((__VLS_ctx.editorVisible)),
            title: ((`${__VLS_ctx.editingId ? '编辑' : '新增'}${__VLS_ctx.editorMode === 'project' ? '项目' : __VLS_ctx.editorMode === 'asset' ? '服务' : '巡检计划'}`)),
            size: ("440px"),
            destroyOnClose: (true),
        }, ...__VLS_functionalComponentArgsRest(__VLS_529));
        if (__VLS_ctx.editorMode === 'project') {
            const __VLS_534 = {}.ElForm;
            /** @type { [typeof __VLS_components.ElForm, typeof __VLS_components.elForm, typeof __VLS_components.ElForm, typeof __VLS_components.elForm, ] } */ ;
            // @ts-ignore
            const __VLS_535 = __VLS_asFunctionalComponent(__VLS_534, new __VLS_534({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }));
            const __VLS_536 = __VLS_535({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_535));
            let __VLS_540;
            const __VLS_541 = {
                onSubmit: (__VLS_ctx.saveEditor)
            };
            let __VLS_537;
            let __VLS_538;
            const __VLS_542 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_543 = __VLS_asFunctionalComponent(__VLS_542, new __VLS_542({
                label: ("项目名称"),
                required: (true),
            }));
            const __VLS_544 = __VLS_543({
                label: ("项目名称"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_543));
            const __VLS_548 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_549 = __VLS_asFunctionalComponent(__VLS_548, new __VLS_548({
                modelValue: ((__VLS_ctx.projectForm.name)),
                maxlength: ("80"),
                placeholder: ("例如：订单履约平台"),
            }));
            const __VLS_550 = __VLS_549({
                modelValue: ((__VLS_ctx.projectForm.name)),
                maxlength: ("80"),
                placeholder: ("例如：订单履约平台"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_549));
            __VLS_547.slots.default;
            var __VLS_547;
            const __VLS_554 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_555 = __VLS_asFunctionalComponent(__VLS_554, new __VLS_554({
                label: ("负责人"),
                required: (true),
            }));
            const __VLS_556 = __VLS_555({
                label: ("负责人"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_555));
            const __VLS_560 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_561 = __VLS_asFunctionalComponent(__VLS_560, new __VLS_560({
                modelValue: ((__VLS_ctx.projectForm.owner)),
                maxlength: ("40"),
                placeholder: ("请输入负责人"),
            }));
            const __VLS_562 = __VLS_561({
                modelValue: ((__VLS_ctx.projectForm.owner)),
                maxlength: ("40"),
                placeholder: ("请输入负责人"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_561));
            __VLS_559.slots.default;
            var __VLS_559;
            const __VLS_566 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_567 = __VLS_asFunctionalComponent(__VLS_566, new __VLS_566({
                label: ("项目描述"),
            }));
            const __VLS_568 = __VLS_567({
                label: ("项目描述"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_567));
            const __VLS_572 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_573 = __VLS_asFunctionalComponent(__VLS_572, new __VLS_572({
                modelValue: ((__VLS_ctx.projectForm.description)),
                type: ("textarea"),
                rows: ((4)),
                maxlength: ("240"),
                showWordLimit: (true),
            }));
            const __VLS_574 = __VLS_573({
                modelValue: ((__VLS_ctx.projectForm.description)),
                type: ("textarea"),
                rows: ((4)),
                maxlength: ("240"),
                showWordLimit: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_573));
            __VLS_571.slots.default;
            var __VLS_571;
            const __VLS_578 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_579 = __VLS_asFunctionalComponent(__VLS_578, new __VLS_578({
                label: ("运行状态"),
            }));
            const __VLS_580 = __VLS_579({
                label: ("运行状态"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_579));
            const __VLS_584 = {}.ElSegmented;
            /** @type { [typeof __VLS_components.ElSegmented, typeof __VLS_components.elSegmented, ] } */ ;
            // @ts-ignore
            const __VLS_585 = __VLS_asFunctionalComponent(__VLS_584, new __VLS_584({
                modelValue: ((__VLS_ctx.projectForm.status)),
                options: (([{ label: '运行中', value: 'RUNNING' }, { label: '维护中', value: 'MAINTENANCE' }])),
            }));
            const __VLS_586 = __VLS_585({
                modelValue: ((__VLS_ctx.projectForm.status)),
                options: (([{ label: '运行中', value: 'RUNNING' }, { label: '维护中', value: 'MAINTENANCE' }])),
            }, ...__VLS_functionalComponentArgsRest(__VLS_585));
            __VLS_583.slots.default;
            var __VLS_583;
            __VLS_539.slots.default;
            var __VLS_539;
        }
        else if (__VLS_ctx.editorMode === 'asset') {
            const __VLS_590 = {}.ElForm;
            /** @type { [typeof __VLS_components.ElForm, typeof __VLS_components.elForm, typeof __VLS_components.ElForm, typeof __VLS_components.elForm, ] } */ ;
            // @ts-ignore
            const __VLS_591 = __VLS_asFunctionalComponent(__VLS_590, new __VLS_590({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }));
            const __VLS_592 = __VLS_591({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_591));
            let __VLS_596;
            const __VLS_597 = {
                onSubmit: (__VLS_ctx.saveEditor)
            };
            let __VLS_593;
            let __VLS_594;
            const __VLS_598 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_599 = __VLS_asFunctionalComponent(__VLS_598, new __VLS_598({
                label: ("服务名称"),
                required: (true),
            }));
            const __VLS_600 = __VLS_599({
                label: ("服务名称"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_599));
            const __VLS_604 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_605 = __VLS_asFunctionalComponent(__VLS_604, new __VLS_604({
                modelValue: ((__VLS_ctx.assetForm.name)),
                maxlength: ("100"),
                placeholder: ("例如：payment-prod-mysql"),
            }));
            const __VLS_606 = __VLS_605({
                modelValue: ((__VLS_ctx.assetForm.name)),
                maxlength: ("100"),
                placeholder: ("例如：payment-prod-mysql"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_605));
            __VLS_603.slots.default;
            var __VLS_603;
            const __VLS_610 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_611 = __VLS_asFunctionalComponent(__VLS_610, new __VLS_610({
                label: ("所属项目"),
                required: (true),
            }));
            const __VLS_612 = __VLS_611({
                label: ("所属项目"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_611));
            const __VLS_616 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_617 = __VLS_asFunctionalComponent(__VLS_616, new __VLS_616({
                modelValue: ((__VLS_ctx.assetForm.projectName)),
                placeholder: ("选择项目"),
                ...{ style: ({}) },
            }));
            const __VLS_618 = __VLS_617({
                modelValue: ((__VLS_ctx.assetForm.projectName)),
                placeholder: ("选择项目"),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_617));
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.projects))) {
                const __VLS_622 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_623 = __VLS_asFunctionalComponent(__VLS_622, new __VLS_622({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.name)),
                }));
                const __VLS_624 = __VLS_623({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.name)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_623));
            }
            __VLS_621.slots.default;
            var __VLS_621;
            __VLS_615.slots.default;
            var __VLS_615;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("form-grid") },
            });
            const __VLS_628 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_629 = __VLS_asFunctionalComponent(__VLS_628, new __VLS_628({
                label: ("运行环境"),
                required: (true),
            }));
            const __VLS_630 = __VLS_629({
                label: ("运行环境"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_629));
            const __VLS_634 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_635 = __VLS_asFunctionalComponent(__VLS_634, new __VLS_634({
                modelValue: ((__VLS_ctx.assetForm.environmentName)),
                ...{ style: ({}) },
            }));
            const __VLS_636 = __VLS_635({
                modelValue: ((__VLS_ctx.assetForm.environmentName)),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_635));
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.environments))) {
                const __VLS_640 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_641 = __VLS_asFunctionalComponent(__VLS_640, new __VLS_640({
                    key: ((item)),
                    label: ((item)),
                    value: ((item)),
                }));
                const __VLS_642 = __VLS_641({
                    key: ((item)),
                    label: ((item)),
                    value: ((item)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_641));
            }
            __VLS_639.slots.default;
            var __VLS_639;
            __VLS_633.slots.default;
            var __VLS_633;
            const __VLS_646 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_647 = __VLS_asFunctionalComponent(__VLS_646, new __VLS_646({
                label: ("服务类型"),
                required: (true),
            }));
            const __VLS_648 = __VLS_647({
                label: ("服务类型"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_647));
            const __VLS_652 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_653 = __VLS_asFunctionalComponent(__VLS_652, new __VLS_652({
                modelValue: ((__VLS_ctx.assetForm.type)),
                ...{ style: ({}) },
            }));
            const __VLS_654 = __VLS_653({
                modelValue: ((__VLS_ctx.assetForm.type)),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_653));
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.assetTypes))) {
                const __VLS_658 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_659 = __VLS_asFunctionalComponent(__VLS_658, new __VLS_658({
                    key: ((item)),
                    label: ((item)),
                    value: ((item)),
                }));
                const __VLS_660 = __VLS_659({
                    key: ((item)),
                    label: ((item)),
                    value: ((item)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_659));
            }
            __VLS_657.slots.default;
            var __VLS_657;
            __VLS_651.slots.default;
            var __VLS_651;
            const __VLS_664 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_665 = __VLS_asFunctionalComponent(__VLS_664, new __VLS_664({
                label: ("连接地址"),
                required: (true),
            }));
            const __VLS_666 = __VLS_665({
                label: ("连接地址"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_665));
            const __VLS_670 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_671 = __VLS_asFunctionalComponent(__VLS_670, new __VLS_670({
                modelValue: ((__VLS_ctx.assetForm.endpoint)),
                maxlength: ("240"),
                placeholder: ("IP:端口或 HTTP 地址"),
            }));
            const __VLS_672 = __VLS_671({
                modelValue: ((__VLS_ctx.assetForm.endpoint)),
                maxlength: ("240"),
                placeholder: ("IP:端口或 HTTP 地址"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_671));
            __VLS_669.slots.default;
            var __VLS_669;
            if (__VLS_ctx.editingId) {
                const __VLS_676 = {}.ElFormItem;
                /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
                // @ts-ignore
                const __VLS_677 = __VLS_asFunctionalComponent(__VLS_676, new __VLS_676({
                    label: ("当前状态"),
                }));
                const __VLS_678 = __VLS_677({
                    label: ("当前状态"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_677));
                const __VLS_682 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_683 = __VLS_asFunctionalComponent(__VLS_682, new __VLS_682({
                    modelValue: ((__VLS_ctx.assetForm.status)),
                    ...{ style: ({}) },
                }));
                const __VLS_684 = __VLS_683({
                    modelValue: ((__VLS_ctx.assetForm.status)),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_683));
                const __VLS_688 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_689 = __VLS_asFunctionalComponent(__VLS_688, new __VLS_688({
                    label: ("未知"),
                    value: ("UNKNOWN"),
                }));
                const __VLS_690 = __VLS_689({
                    label: ("未知"),
                    value: ("UNKNOWN"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_689));
                const __VLS_694 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_695 = __VLS_asFunctionalComponent(__VLS_694, new __VLS_694({
                    label: ("正常"),
                    value: ("HEALTHY"),
                }));
                const __VLS_696 = __VLS_695({
                    label: ("正常"),
                    value: ("HEALTHY"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_695));
                const __VLS_700 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_701 = __VLS_asFunctionalComponent(__VLS_700, new __VLS_700({
                    label: ("注意"),
                    value: ("WARNING"),
                }));
                const __VLS_702 = __VLS_701({
                    label: ("注意"),
                    value: ("WARNING"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_701));
                const __VLS_706 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_707 = __VLS_asFunctionalComponent(__VLS_706, new __VLS_706({
                    label: ("异常"),
                    value: ("CRITICAL"),
                }));
                const __VLS_708 = __VLS_707({
                    label: ("异常"),
                    value: ("CRITICAL"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_707));
                __VLS_687.slots.default;
                var __VLS_687;
                __VLS_681.slots.default;
                var __VLS_681;
            }
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("production-note") },
            });
            const __VLS_712 = {}.ShieldCheck;
            /** @type { [typeof __VLS_components.ShieldCheck, ] } */ ;
            // @ts-ignore
            const __VLS_713 = __VLS_asFunctionalComponent(__VLS_712, new __VLS_712({
                size: ((17)),
            }));
            const __VLS_714 = __VLS_713({
                size: ((17)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_713));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_595.slots.default;
            var __VLS_595;
        }
        else {
            const __VLS_718 = {}.ElForm;
            /** @type { [typeof __VLS_components.ElForm, typeof __VLS_components.elForm, typeof __VLS_components.ElForm, typeof __VLS_components.elForm, ] } */ ;
            // @ts-ignore
            const __VLS_719 = __VLS_asFunctionalComponent(__VLS_718, new __VLS_718({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }));
            const __VLS_720 = __VLS_719({
                ...{ 'onSubmit': {} },
                labelPosition: ("top"),
                ...{ class: ("editor-form") },
            }, ...__VLS_functionalComponentArgsRest(__VLS_719));
            let __VLS_724;
            const __VLS_725 = {
                onSubmit: (__VLS_ctx.saveEditor)
            };
            let __VLS_721;
            let __VLS_722;
            const __VLS_726 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_727 = __VLS_asFunctionalComponent(__VLS_726, new __VLS_726({
                label: ("计划名称"),
                required: (true),
            }));
            const __VLS_728 = __VLS_727({
                label: ("计划名称"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_727));
            const __VLS_732 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_733 = __VLS_asFunctionalComponent(__VLS_732, new __VLS_732({
                modelValue: ((__VLS_ctx.planForm.name)),
                maxlength: ("100"),
                placeholder: ("例如：礼事随记生产巡检"),
            }));
            const __VLS_734 = __VLS_733({
                modelValue: ((__VLS_ctx.planForm.name)),
                maxlength: ("100"),
                placeholder: ("例如：礼事随记生产巡检"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_733));
            __VLS_731.slots.default;
            var __VLS_731;
            const __VLS_738 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_739 = __VLS_asFunctionalComponent(__VLS_738, new __VLS_738({
                label: ("所属项目"),
                required: (true),
            }));
            const __VLS_740 = __VLS_739({
                label: ("所属项目"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_739));
            const __VLS_744 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_745 = __VLS_asFunctionalComponent(__VLS_744, new __VLS_744({
                modelValue: ((__VLS_ctx.planForm.projectName)),
                placeholder: ("选择项目"),
                ...{ style: ({}) },
            }));
            const __VLS_746 = __VLS_745({
                modelValue: ((__VLS_ctx.planForm.projectName)),
                placeholder: ("选择项目"),
                ...{ style: ({}) },
            }, ...__VLS_functionalComponentArgsRest(__VLS_745));
            for (const [item] of __VLS_getVForSourceType((__VLS_ctx.projects))) {
                const __VLS_750 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_751 = __VLS_asFunctionalComponent(__VLS_750, new __VLS_750({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.name)),
                }));
                const __VLS_752 = __VLS_751({
                    key: ((item.id)),
                    label: ((item.name)),
                    value: ((item.name)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_751));
            }
            __VLS_749.slots.default;
            var __VLS_749;
            __VLS_743.slots.default;
            var __VLS_743;
            const __VLS_756 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_757 = __VLS_asFunctionalComponent(__VLS_756, new __VLS_756({
                label: ("巡检范围"),
                required: (true),
            }));
            const __VLS_758 = __VLS_757({
                label: ("巡检范围"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_757));
            const __VLS_762 = {}.ElInput;
            /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
            // @ts-ignore
            const __VLS_763 = __VLS_asFunctionalComponent(__VLS_762, new __VLS_762({
                modelValue: ((__VLS_ctx.planForm.scopeText)),
                type: ("textarea"),
                rows: ((3)),
                maxlength: ("240"),
                showWordLimit: (true),
                placeholder: ("例如：后端健康接口、PostgreSQL、H5 页面"),
            }));
            const __VLS_764 = __VLS_763({
                modelValue: ((__VLS_ctx.planForm.scopeText)),
                type: ("textarea"),
                rows: ((3)),
                maxlength: ("240"),
                showWordLimit: (true),
                placeholder: ("例如：后端健康接口、PostgreSQL、H5 页面"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_763));
            __VLS_761.slots.default;
            var __VLS_761;
            const __VLS_768 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_769 = __VLS_asFunctionalComponent(__VLS_768, new __VLS_768({
                label: ("执行周期"),
                required: (true),
            }));
            const __VLS_770 = __VLS_769({
                label: ("执行周期"),
                required: (true),
            }, ...__VLS_functionalComponentArgsRest(__VLS_769));
            const __VLS_774 = {}.ElSegmented;
            /** @type { [typeof __VLS_components.ElSegmented, typeof __VLS_components.elSegmented, ] } */ ;
            // @ts-ignore
            const __VLS_775 = __VLS_asFunctionalComponent(__VLS_774, new __VLS_774({
                modelValue: ((__VLS_ctx.scheduleForm.mode)),
                options: (([{ label: '间隔执行', value: 'INTERVAL' }, { label: '每天定时', value: 'DAILY' }, { label: 'Cron', value: 'CRON' }])),
            }));
            const __VLS_776 = __VLS_775({
                modelValue: ((__VLS_ctx.scheduleForm.mode)),
                options: (([{ label: '间隔执行', value: 'INTERVAL' }, { label: '每天定时', value: 'DAILY' }, { label: 'Cron', value: 'CRON' }])),
            }, ...__VLS_functionalComponentArgsRest(__VLS_775));
            __VLS_773.slots.default;
            var __VLS_773;
            if (__VLS_ctx.scheduleForm.mode === 'INTERVAL') {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("form-grid") },
                });
                const __VLS_780 = {}.ElFormItem;
                /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
                // @ts-ignore
                const __VLS_781 = __VLS_asFunctionalComponent(__VLS_780, new __VLS_780({
                    label: ("间隔数值"),
                    required: (true),
                }));
                const __VLS_782 = __VLS_781({
                    label: ("间隔数值"),
                    required: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_781));
                const __VLS_786 = {}.ElInputNumber;
                /** @type { [typeof __VLS_components.ElInputNumber, typeof __VLS_components.elInputNumber, ] } */ ;
                // @ts-ignore
                const __VLS_787 = __VLS_asFunctionalComponent(__VLS_786, new __VLS_786({
                    modelValue: ((__VLS_ctx.scheduleForm.intervalValue)),
                    min: ((1)),
                    max: ((1440)),
                    ...{ style: ({}) },
                }));
                const __VLS_788 = __VLS_787({
                    modelValue: ((__VLS_ctx.scheduleForm.intervalValue)),
                    min: ((1)),
                    max: ((1440)),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_787));
                __VLS_785.slots.default;
                var __VLS_785;
                const __VLS_792 = {}.ElFormItem;
                /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
                // @ts-ignore
                const __VLS_793 = __VLS_asFunctionalComponent(__VLS_792, new __VLS_792({
                    label: ("间隔单位"),
                    required: (true),
                }));
                const __VLS_794 = __VLS_793({
                    label: ("间隔单位"),
                    required: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_793));
                const __VLS_798 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_799 = __VLS_asFunctionalComponent(__VLS_798, new __VLS_798({
                    modelValue: ((__VLS_ctx.scheduleForm.intervalUnit)),
                    ...{ style: ({}) },
                }));
                const __VLS_800 = __VLS_799({
                    modelValue: ((__VLS_ctx.scheduleForm.intervalUnit)),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_799));
                const __VLS_804 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_805 = __VLS_asFunctionalComponent(__VLS_804, new __VLS_804({
                    label: ("分钟"),
                    value: ("分钟"),
                }));
                const __VLS_806 = __VLS_805({
                    label: ("分钟"),
                    value: ("分钟"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_805));
                const __VLS_810 = {}.ElOption;
                /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                // @ts-ignore
                const __VLS_811 = __VLS_asFunctionalComponent(__VLS_810, new __VLS_810({
                    label: ("小时"),
                    value: ("小时"),
                }));
                const __VLS_812 = __VLS_811({
                    label: ("小时"),
                    value: ("小时"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_811));
                __VLS_803.slots.default;
                var __VLS_803;
                __VLS_797.slots.default;
                var __VLS_797;
            }
            else if (__VLS_ctx.scheduleForm.mode === 'DAILY') {
                const __VLS_816 = {}.ElFormItem;
                /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
                // @ts-ignore
                const __VLS_817 = __VLS_asFunctionalComponent(__VLS_816, new __VLS_816({
                    label: ("每天执行时间"),
                    required: (true),
                }));
                const __VLS_818 = __VLS_817({
                    label: ("每天执行时间"),
                    required: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_817));
                const __VLS_822 = {}.ElTimePicker;
                /** @type { [typeof __VLS_components.ElTimePicker, typeof __VLS_components.elTimePicker, ] } */ ;
                // @ts-ignore
                const __VLS_823 = __VLS_asFunctionalComponent(__VLS_822, new __VLS_822({
                    modelValue: ((__VLS_ctx.scheduleForm.dailyTime)),
                    valueFormat: ("HH:mm"),
                    format: ("HH:mm"),
                    ...{ style: ({}) },
                }));
                const __VLS_824 = __VLS_823({
                    modelValue: ((__VLS_ctx.scheduleForm.dailyTime)),
                    valueFormat: ("HH:mm"),
                    format: ("HH:mm"),
                    ...{ style: ({}) },
                }, ...__VLS_functionalComponentArgsRest(__VLS_823));
                __VLS_821.slots.default;
                var __VLS_821;
            }
            else {
                const __VLS_828 = {}.ElFormItem;
                /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
                // @ts-ignore
                const __VLS_829 = __VLS_asFunctionalComponent(__VLS_828, new __VLS_828({
                    label: ("Cron 表达式"),
                    required: (true),
                }));
                const __VLS_830 = __VLS_829({
                    label: ("Cron 表达式"),
                    required: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_829));
                const __VLS_834 = {}.ElInput;
                /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
                // @ts-ignore
                const __VLS_835 = __VLS_asFunctionalComponent(__VLS_834, new __VLS_834({
                    modelValue: ((__VLS_ctx.scheduleForm.cronExpression)),
                    placeholder: ("例如：0 0/10 * * * *"),
                }));
                const __VLS_836 = __VLS_835({
                    modelValue: ((__VLS_ctx.scheduleForm.cronExpression)),
                    placeholder: ("例如：0 0/10 * * * *"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_835));
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("field-hint") },
                });
                __VLS_833.slots.default;
                var __VLS_833;
            }
            const __VLS_840 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_841 = __VLS_asFunctionalComponent(__VLS_840, new __VLS_840({
                label: ("计划状态"),
            }));
            const __VLS_842 = __VLS_841({
                label: ("计划状态"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_841));
            const __VLS_846 = {}.ElSegmented;
            /** @type { [typeof __VLS_components.ElSegmented, typeof __VLS_components.elSegmented, ] } */ ;
            // @ts-ignore
            const __VLS_847 = __VLS_asFunctionalComponent(__VLS_846, new __VLS_846({
                modelValue: ((__VLS_ctx.planForm.status)),
                options: (([{ label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }])),
            }));
            const __VLS_848 = __VLS_847({
                modelValue: ((__VLS_ctx.planForm.status)),
                options: (([{ label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }])),
            }, ...__VLS_functionalComponentArgsRest(__VLS_847));
            __VLS_845.slots.default;
            var __VLS_845;
            __VLS_723.slots.default;
            var __VLS_723;
        }
        __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
        {
            const { footer: __VLS_thisSlot } = __VLS_533.slots;
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("drawer-footer") },
            });
            const __VLS_852 = {}.ElButton;
            /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
            // @ts-ignore
            const __VLS_853 = __VLS_asFunctionalComponent(__VLS_852, new __VLS_852({
                ...{ 'onClick': {} },
            }));
            const __VLS_854 = __VLS_853({
                ...{ 'onClick': {} },
            }, ...__VLS_functionalComponentArgsRest(__VLS_853));
            let __VLS_858;
            const __VLS_859 = {
                onClick: (...[$event]) => {
                    if (!(!((__VLS_ctx.authLoading))))
                        return;
                    if (!(!((!__VLS_ctx.authenticated))))
                        return;
                    __VLS_ctx.editorVisible = false;
                }
            };
            let __VLS_855;
            let __VLS_856;
            __VLS_857.slots.default;
            var __VLS_857;
            const __VLS_860 = {}.ElButton;
            /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
            // @ts-ignore
            const __VLS_861 = __VLS_asFunctionalComponent(__VLS_860, new __VLS_860({
                ...{ 'onClick': {} },
                type: ("primary"),
                loading: ((__VLS_ctx.saving)),
            }));
            const __VLS_862 = __VLS_861({
                ...{ 'onClick': {} },
                type: ("primary"),
                loading: ((__VLS_ctx.saving)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_861));
            let __VLS_866;
            const __VLS_867 = {
                onClick: (__VLS_ctx.saveEditor)
            };
            let __VLS_863;
            let __VLS_864;
            __VLS_865.slots.default;
            var __VLS_865;
        }
        var __VLS_533;
        const __VLS_868 = {}.ElDrawer;
        /** @type { [typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, ] } */ ;
        // @ts-ignore
        const __VLS_869 = __VLS_asFunctionalComponent(__VLS_868, new __VLS_868({
            modelValue: ((__VLS_ctx.inspectionDrawerVisible)),
            title: ((__VLS_ctx.selectedInspection ? `巡检详细结果 · ${__VLS_ctx.selectedInspection.name}` : '巡检详细结果')),
            size: ("520px"),
        }));
        const __VLS_870 = __VLS_869({
            modelValue: ((__VLS_ctx.inspectionDrawerVisible)),
            title: ((__VLS_ctx.selectedInspection ? `巡检详细结果 · ${__VLS_ctx.selectedInspection.name}` : '巡检详细结果')),
            size: ("520px"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_869));
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
            const __VLS_874 = {}.CheckCircle2;
            /** @type { [typeof __VLS_components.CheckCircle2, ] } */ ;
            // @ts-ignore
            const __VLS_875 = __VLS_asFunctionalComponent(__VLS_874, new __VLS_874({
                size: ((15)),
            }));
            const __VLS_876 = __VLS_875({
                size: ((15)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_875));
            (__VLS_ctx.inspectionCount('PASSED'));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: ("warning") },
            });
            const __VLS_880 = {}.AlertTriangle;
            /** @type { [typeof __VLS_components.AlertTriangle, ] } */ ;
            // @ts-ignore
            const __VLS_881 = __VLS_asFunctionalComponent(__VLS_880, new __VLS_880({
                size: ((15)),
            }));
            const __VLS_882 = __VLS_881({
                size: ((15)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_881));
            (__VLS_ctx.inspectionCount('WARNING'));
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: ("failed") },
            });
            const __VLS_886 = {}.XCircle;
            /** @type { [typeof __VLS_components.XCircle, ] } */ ;
            // @ts-ignore
            const __VLS_887 = __VLS_asFunctionalComponent(__VLS_886, new __VLS_886({
                size: ((15)),
            }));
            const __VLS_888 = __VLS_887({
                size: ((15)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_887));
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
                    const __VLS_892 = {}.CheckCircle2;
                    /** @type { [typeof __VLS_components.CheckCircle2, ] } */ ;
                    // @ts-ignore
                    const __VLS_893 = __VLS_asFunctionalComponent(__VLS_892, new __VLS_892({
                        size: ((18)),
                    }));
                    const __VLS_894 = __VLS_893({
                        size: ((18)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_893));
                }
                else if (check.status === 'WARNING') {
                    const __VLS_898 = {}.AlertTriangle;
                    /** @type { [typeof __VLS_components.AlertTriangle, ] } */ ;
                    // @ts-ignore
                    const __VLS_899 = __VLS_asFunctionalComponent(__VLS_898, new __VLS_898({
                        size: ((18)),
                    }));
                    const __VLS_900 = __VLS_899({
                        size: ((18)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_899));
                }
                else {
                    const __VLS_904 = {}.XCircle;
                    /** @type { [typeof __VLS_components.XCircle, ] } */ ;
                    // @ts-ignore
                    const __VLS_905 = __VLS_asFunctionalComponent(__VLS_904, new __VLS_904({
                        size: ((18)),
                    }));
                    const __VLS_906 = __VLS_905({
                        size: ((18)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_905));
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
        __VLS_873.slots.default;
        var __VLS_873;
        const __VLS_910 = {}.ElDrawer;
        /** @type { [typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, typeof __VLS_components.ElDrawer, typeof __VLS_components.elDrawer, ] } */ ;
        // @ts-ignore
        const __VLS_911 = __VLS_asFunctionalComponent(__VLS_910, new __VLS_910({
            modelValue: ((__VLS_ctx.monitoringDrawerVisible)),
            title: ((__VLS_ctx.monitoringDetails ? `监控详情 · ${__VLS_ctx.monitoringDetails.asset.name}` : '监控详情')),
            size: ("560px"),
        }));
        const __VLS_912 = __VLS_911({
            modelValue: ((__VLS_ctx.monitoringDrawerVisible)),
            title: ((__VLS_ctx.monitoringDetails ? `监控详情 · ${__VLS_ctx.monitoringDetails.asset.name}` : '监控详情')),
            size: ("560px"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_911));
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
            (__VLS_ctx.statusText(__VLS_ctx.monitoringDetails.asset.status));
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.monitoringDetails.asset.hertzbeatMonitorId || '--');
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.monitoringDetails.asset.lastCollectedAt ? __VLS_ctx.formatTime(__VLS_ctx.monitoringDetails.asset.lastCollectedAt) : '--');
            if (!__VLS_ctx.monitoringDetails.metricsCollectedAt) {
                const __VLS_916 = {}.ElAlert;
                /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
                // @ts-ignore
                const __VLS_917 = __VLS_asFunctionalComponent(__VLS_916, new __VLS_916({
                    title: ("HertzBeat 暂无指标采样，当前状态为待采样"),
                    type: ("info"),
                    closable: ((false)),
                    showIcon: (true),
                }));
                const __VLS_918 = __VLS_917({
                    title: ("HertzBeat 暂无指标采样，当前状态为待采样"),
                    type: ("info"),
                    closable: ((false)),
                    showIcon: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_917));
            }
            else if (__VLS_ctx.monitoringDetails.metricsStale) {
                const __VLS_922 = {}.ElAlert;
                /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
                // @ts-ignore
                const __VLS_923 = __VLS_asFunctionalComponent(__VLS_922, new __VLS_922({
                    title: ("当前指标超过 180 秒未更新，数据已陈旧"),
                    type: ("warning"),
                    closable: ((false)),
                    showIcon: (true),
                }));
                const __VLS_924 = __VLS_923({
                    title: ("当前指标超过 180 秒未更新，数据已陈旧"),
                    type: ("warning"),
                    closable: ((false)),
                    showIcon: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_923));
            }
            if (__VLS_ctx.monitoringDetails.metricsMessage) {
                const __VLS_928 = {}.ElAlert;
                /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
                // @ts-ignore
                const __VLS_929 = __VLS_asFunctionalComponent(__VLS_928, new __VLS_928({
                    title: ((__VLS_ctx.monitoringDetails.metricsMessage)),
                    type: ("info"),
                    closable: ((false)),
                    showIcon: (true),
                }));
                const __VLS_930 = __VLS_929({
                    title: ((__VLS_ctx.monitoringDetails.metricsMessage)),
                    type: ("info"),
                    closable: ((false)),
                    showIcon: (true),
                }, ...__VLS_functionalComponentArgsRest(__VLS_929));
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
                __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
                (metric.collectedAt ? __VLS_ctx.formatTime(metric.collectedAt) : '暂无采集时间');
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
            for (const [run] of __VLS_getVForSourceType((__VLS_ctx.monitoringRuns.slice(0, 5)))) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    key: ((run.id)),
                });
                const __VLS_934 = {}.ElTag;
                /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                // @ts-ignore
                const __VLS_935 = __VLS_asFunctionalComponent(__VLS_934, new __VLS_934({
                    type: ((run.status === 'SUCCESS' ? 'success' : run.status === 'PARTIAL' ? 'warning' : 'danger')),
                    size: ("small"),
                }));
                const __VLS_936 = __VLS_935({
                    type: ((run.status === 'SUCCESS' ? 'success' : run.status === 'PARTIAL' ? 'warning' : 'danger')),
                    size: ("small"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_935));
                (run.status);
                __VLS_939.slots.default;
                var __VLS_939;
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.formatRecordTime(run.startedAt));
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (run.assetsUpdated);
                (run.alertsUpdated);
            }
        }
        __VLS_915.slots.default;
        var __VLS_915;
    }
    ['auth-loading', 'spinning', 'login-page', 'login-panel', 'login-brand', 'brand-mark', 'login-submit', 'app-shell', 'sidebar', 'brand', 'brand-mark', 'scope-label', 'nav-item', 'active', 'nav-arrow', 'sidebar-spacer', 'integration-list', 'scope-label', 'integration-dot', 'connected', 'operator', 'avatar', 'main-area', 'topbar', 'topbar-title', 'records-back', 'topbar-actions', 'search', 'updated', 'icon-button', 'spinning', 'secondary-button', 'spin-icon', 'secondary-button', 'spin-icon', 'primary-button', 'error-state', 'content', 'dashboard-view', 'metrics-grid', 'metric', 'metric-icon', 'blue', 'metric-arrow', 'metric', 'metric-icon', 'green', 'metric-arrow', 'metric', 'metric-icon', 'amber', 'metric-arrow', 'metric', 'metric-icon', 'red', 'metric-arrow', 'metric', 'metric-icon', 'cyan', 'metric-arrow', 'dashboard-grid', 'panel', 'span-two', 'panel-heading', 'text-button', 'asset-overview', 'asset-stat', 'health-bar', 'panel', 'panel-heading', 'pass-ring', 'mini-list', 'dot', 'panel', 'span-three', 'panel-heading', 'icon-button', 'alert-stream', 'alert-row', 'severity', 'alert-main', 'content', 'list-view', 'list-summary', 'legend', 'ok', 'warn', 'bad', 'project-grid', 'project-card', 'project-head', 'project-symbol', 'card-actions', 'danger-action', 'project-meta', 'project-footer', 'table-panel', 'asset-status', 'sync-state', 'row-actions', 'spin-icon', 'danger-action', 'inspection-center', 'inspection-view-heading', 'inspection-heading-title', 'records-back', 'plan-grid', 'plan-card', 'plan-card-head', 'plan-icon', 'plan-actions', 'plan-commands', 'spin-icon', 'plan-toggle-button', 'danger-action', 'empty-state', 'plan-empty', 'result-list', 'result-card', 'result-status', 'result-main', 'result-score', 'result-meta', 'result-executed-at', 'result-arrow', 'empty-state', 'inspection-pagination', 'table-panel', 'severity', 'editor-form', 'editor-form', 'form-grid', 'production-note', 'editor-form', 'form-grid', 'field-hint', 'drawer-footer', 'inspection-detail', 'detail-summary', 'detail-score', 'detail-counts', 'passed', 'warning', 'failed', 'detail-context', 'check-list', 'check-item', 'check-icon', 'check-content', 'monitoring-detail', 'monitoring-summary', 'monitoring-metrics', 'trend-section', 'trend-bars', 'empty-trend', 'sync-history',];
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
            lastUpdated: lastUpdated,
            summary: summary,
            projects: projects,
            assets: assets,
            inspections: inspections,
            inspectionPlans: inspectionPlans,
            inspectionItems: inspectionItems,
            inspectionSection: inspectionSection,
            selectedInspectionPlan: selectedInspectionPlan,
            inspectionPage: inspectionPage,
            inspectionPageSize: inspectionPageSize,
            inspectionDrawerVisible: inspectionDrawerVisible,
            selectedInspection: selectedInspection,
            inspectionDetailLoading: inspectionDetailLoading,
            alerts: alerts,
            selectedProjectName: selectedProjectName,
            serviceCategory: serviceCategory,
            hertzBeatConnected: hertzBeatConnected,
            editorVisible: editorVisible,
            editorMode: editorMode,
            editingId: editingId,
            saving: saving,
            runningPlanId: runningPlanId,
            syncingAssetId: syncingAssetId,
            syncingAllAssets: syncingAllAssets,
            syncingMonitoring: syncingMonitoring,
            monitoringRuns: monitoringRuns,
            monitoringDrawerVisible: monitoringDrawerVisible,
            monitoringDetailLoading: monitoringDetailLoading,
            monitoringDetails: monitoringDetails,
            projectForm: projectForm,
            assetForm: assetForm,
            planForm: planForm,
            scheduleForm: scheduleForm,
            environments: environments,
            assetTypes: assetTypes,
            navItems: navItems,
            filteredProjects: filteredProjects,
            assetCategoryStats: assetCategoryStats,
            currentTitle: currentTitle,
            filteredAssets: filteredAssets,
            selectedPlanInspections: selectedPlanInspections,
            pagedPlanInspections: pagedPlanInspections,
            filteredAlerts: filteredAlerts,
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
            openProjectEdit: openProjectEdit,
            openAssetEdit: openAssetEdit,
            openPlanEdit: openPlanEdit,
            saveEditor: saveEditor,
            removeProject: removeProject,
            removeAsset: removeAsset,
            syncAsset: syncAsset,
            syncAllAssets: syncAllAssets,
            syncMonitoringData: syncMonitoringData,
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
