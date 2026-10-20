let authToken = localStorage.getItem('ops_token') || '';
function authHeaders(body = false) {
    return {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
    };
}
export function setAuthToken(token) {
    authToken = token;
    if (token)
        localStorage.setItem('ops_token', token);
    else
        localStorage.removeItem('ops_token');
}
async function request(path) {
    const response = await fetch(`/api${path}`, { headers: authHeaders() });
    if (response.status === 401)
        throw new Error('AUTH_REQUIRED');
    if (!response.ok)
        throw new Error(`请求失败：${response.status}`);
    return response.json();
}
async function mutate(path, method, body) {
    const response = await fetch(`/api${path}`, {
        method,
        headers: authHeaders(Boolean(body)),
        body: body ? JSON.stringify(body) : undefined,
    });
    if (response.status === 401)
        throw new Error('AUTH_REQUIRED');
    if (!response.ok) {
        const error = await response.json().catch(() => ({ message: `请求失败：${response.status}` }));
        throw new Error(error.errors?.[0] || error.message || `请求失败：${response.status}`);
    }
    return response.status === 204 ? undefined : response.json();
}
export const api = {
    login: (username, password) => mutate('/auth/login', 'POST', { username, password }),
    logout: () => mutate('/auth/logout', 'POST'),
    me: () => request('/auth/me'),
    summary: () => request('/dashboard/summary'),
    projects: () => request('/projects'),
    assets: () => request('/assets'),
    inspections: () => request('/inspections'),
    inspectionPlans: () => request('/inspection-plans'),
    inspectionItems: (id) => request(`/inspections/${id}/items`),
    alerts: () => request('/alerts'),
    alert: (id) => request(`/alerts/${id}`),
    alertEvents: (id) => request(`/alerts/${id}/events`),
    acknowledgeAlert: (id, note) => mutate(`/alerts/${id}/acknowledge`, 'POST', { note }),
    addAlertNote: (id, note) => mutate(`/alerts/${id}/notes`, 'POST', { note }),
    closeAlert: (id, note) => mutate(`/alerts/${id}/close`, 'POST', { note }),
    hertzBeatStatus: () => request('/integrations/hertzbeat/status'),
    syncAssetToHertzBeat: (id) => mutate(`/integrations/hertzbeat/assets/${id}/sync`, 'POST'),
    syncAllAssetsToHertzBeat: () => mutate('/integrations/hertzbeat/assets/sync-all', 'POST'),
    syncMonitoringData: () => mutate('/integrations/hertzbeat/monitoring/sync', 'POST'),
    monitoringRuns: () => request('/integrations/hertzbeat/monitoring/runs'),
    monitoringDetails: (id) => request(`/integrations/hertzbeat/assets/${id}/details`),
    createProject: (data) => mutate('/projects', 'POST', data),
    updateProject: (id, data) => mutate(`/projects/${id}`, 'PUT', data),
    deleteProject: (id) => mutate(`/projects/${id}`, 'DELETE'),
    createAsset: (data) => mutate('/assets', 'POST', data),
    updateAsset: (id, data) => mutate(`/assets/${id}`, 'PUT', data),
    deleteAsset: (id) => mutate(`/assets/${id}`, 'DELETE'),
    createInspectionPlan: (data) => mutate('/inspection-plans', 'POST', data),
    updateInspectionPlan: (id, data) => mutate(`/inspection-plans/${id}`, 'PUT', data),
    deleteInspectionPlan: (id) => mutate(`/inspection-plans/${id}`, 'DELETE'),
    executeInspectionPlan: (id) => mutate(`/inspection-plans/${id}/execute`, 'POST'),
};
