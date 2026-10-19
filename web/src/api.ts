export type Summary = {
  projectCount: number
  assetCount: number
  abnormalAssetCount: number
  openAlertCount: number
  inspectionPassRate: number
}

export type Project = { id: number; name: string; owner: string; description: string; status: string }
export type Asset = {
  id: number
  name: string
  projectName: string
  environmentName: string
  type: string
  endpoint: string
  status: string
  hertzbeatMonitorId: number | null
  syncStatus: string
  syncMessage: string | null
  lastSyncedAt: string | null
}
export type AssetInput = Pick<Asset, 'name' | 'projectName' | 'environmentName' | 'type' | 'endpoint' | 'status'>
export type Inspection = { id: number; name: string; projectName: string; status: string; passedChecks: number; totalChecks: number; durationSeconds: number; executedAt: string }
export type InspectionPlan = { id: number; name: string; projectName: string; scopeText: string; scheduleText: string; status: string; nextRunAt: string }
export type InspectionPlanInput = Omit<InspectionPlan, 'id' | 'status'> & { status?: string }
export type InspectionItem = { id: number; inspectionId: number; category: string; targetName: string; checkName: string; status: string; message: string; durationMillis: number }
export type Alert = { id: number; level: string; projectName: string; targetName: string; message: string; status: string; occurredAt: string }
export type IntegrationStatus = { connected: boolean; endpoint: string; durationMillis: number; message: string }
export type AssetSyncAllResult = { total: number; succeeded: number; failed: number; assets: Asset[] }

let authToken = localStorage.getItem('ops_token') || ''

function authHeaders(body = false) {
  return {
    ...(body ? { 'Content-Type': 'application/json' } : {}),
    ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
  }
}

export function setAuthToken(token: string) {
  authToken = token
  if (token) localStorage.setItem('ops_token', token)
  else localStorage.removeItem('ops_token')
}

async function request<T>(path: string): Promise<T> {
  const response = await fetch(`/api${path}`, { headers: authHeaders() })
  if (response.status === 401) throw new Error('AUTH_REQUIRED')
  if (!response.ok) throw new Error(`请求失败：${response.status}`)
  return response.json() as Promise<T>
}

async function mutate<T>(path: string, method: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: authHeaders(Boolean(body)),
    body: body ? JSON.stringify(body) : undefined,
  })
  if (response.status === 401) throw new Error('AUTH_REQUIRED')
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: `请求失败：${response.status}` }))
    throw new Error(error.errors?.[0] || error.message || `请求失败：${response.status}`)
  }
  return response.status === 204 ? (undefined as T) : response.json() as Promise<T>
}

export const api = {
  login: (username: string, password: string) => mutate<{ token: string; username: string; displayName: string }>('/auth/login', 'POST', { username, password }),
  logout: () => mutate<void>('/auth/logout', 'POST'),
  me: () => request<{ username: string; displayName: string }>('/auth/me'),
  summary: () => request<Summary>('/dashboard/summary'),
  projects: () => request<Project[]>('/projects'),
  assets: () => request<Asset[]>('/assets'),
  inspections: () => request<Inspection[]>('/inspections'),
  inspectionPlans: () => request<InspectionPlan[]>('/inspection-plans'),
  inspectionItems: (id: number) => request<InspectionItem[]>(`/inspections/${id}/items`),
  alerts: () => request<Alert[]>('/alerts'),
  hertzBeatStatus: () => request<IntegrationStatus>('/integrations/hertzbeat/status'),
  syncAssetToHertzBeat: (id: number) => mutate<Asset>(`/integrations/hertzbeat/assets/${id}/sync`, 'POST'),
  syncAllAssetsToHertzBeat: () => mutate<AssetSyncAllResult>('/integrations/hertzbeat/assets/sync-all', 'POST'),
  createProject: (data: Omit<Project, 'id' | 'status'> & { status?: string }) => mutate<Project>('/projects', 'POST', data),
  updateProject: (id: number, data: Omit<Project, 'id'>) => mutate<Project>(`/projects/${id}`, 'PUT', data),
  deleteProject: (id: number) => mutate<void>(`/projects/${id}`, 'DELETE'),
  createAsset: (data: AssetInput) => mutate<Asset>('/assets', 'POST', data),
  updateAsset: (id: number, data: AssetInput) => mutate<Asset>(`/assets/${id}`, 'PUT', data),
  deleteAsset: (id: number) => mutate<void>(`/assets/${id}`, 'DELETE'),
  createInspectionPlan: (data: InspectionPlanInput) => mutate<InspectionPlan>('/inspection-plans', 'POST', data),
  updateInspectionPlan: (id: number, data: Omit<InspectionPlan, 'id'>) => mutate<InspectionPlan>(`/inspection-plans/${id}`, 'PUT', data),
  deleteInspectionPlan: (id: number) => mutate<void>(`/inspection-plans/${id}`, 'DELETE'),
  executeInspectionPlan: (id: number) => mutate<Inspection>(`/inspection-plans/${id}/execute`, 'POST'),
}
