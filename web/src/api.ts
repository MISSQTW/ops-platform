export type Summary = {
  projectCount: number
  assetCount: number
  abnormalAssetCount: number
  openAlertCount: number
  inspectionPassRate: number
}

export type Project = { id: number; name: string; projectType: string; owner: string; contactPhone: string | null; maintenanceExpiresAt: string | null; description: string; status: string }
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
  monitoringMode: string
  mappingStatus: string
  monitoringStale: boolean
  lastCollectedAt: string | null
  lastSyncAttemptAt: string | null
  lastSuccessfulSyncAt: string | null
  monitoringError: string | null
}
export type AssetInput = Pick<Asset, 'name' | 'projectName' | 'environmentName' | 'type' | 'endpoint' | 'status'>
export type Inspection = { id: number; name: string; projectName: string; status: string; passedChecks: number; totalChecks: number; durationSeconds: number; executedAt: string }
export type InspectionPlan = { id: number; name: string; projectId: number | null; projectName: string; scopeText: string; scheduleText: string; status: string; nextRunAt: string; intervalSeconds: number | null; failureThreshold: number | null; alertLevel: string | null; lifecycleStatus: string | null; syncStatus: string | null; desiredVersion: number; lastSyncAttemptAt: string | null; lastSyncSucceededAt: string | null; lastVerifiedAt: string | null; syncError: string | null; assetIds: number[] }
export type InspectionPlanInput = { name: string; projectId: number; projectName: string; status: string; intervalSeconds: number; failureThreshold: number; alertLevel: string; assetIds: number[]; mode: 'MONITORING' }
export type MigrationItem = { serviceId: number; serviceName: string; projectName: string; status: string; reason: string; action: string; monitorId: number | null; legacyPlanIds: number[]; legacyIntervalSeconds: number | null; monitorIntervalSeconds: number | null }
export type MigrationProposal = { id: number; assetId: number; projectId: number; planName: string; intervalSeconds: number; status: string; preparedPlanId: number | null; resultPlanId: number | null; createdAt: string; confirmedAt: string | null }
export type PlanSyncSummary = { planId: number; status: string; processedTasks: number; error: string | null }
export type PlanSyncAllSummary = { totalPlans: number; results: PlanSyncSummary[] }
export type InspectionItem = { id: number; inspectionId: number; category: string; targetName: string; checkName: string; status: string; message: string; durationMillis: number }
export type Alert = { id: number; level: string; projectName: string; targetName: string; message: string; status: string; handlingStatus: string; occurredAt: string; externalLifecycle?: string; firstOccurredAt?: string; lastOccurredAt?: string; resolvedAt?: string; projectId?: number; inspectionPlanId?: number; assetId?: number; inspectionPlanName?: string; projectNameSnapshot?: string; targetNameSnapshot?: string; handledBy?: string; handledAt?: string; handlingNote?: string }
export type AlertHandlingEvent = { id: number; alertId: number; action: string; fromStatus: string; toStatus: string; operatorName: string; note: string; occurredAt: string }
export type IntegrationStatus = { connected: boolean; endpoint: string; durationMillis: number; message: string }
export type AssetSyncAllResult = { total: number; succeeded: number; failed: number; assets: Asset[] }
export type SessionUser = { username: string; displayName: string; canManageIntegrations: boolean }
export type MonitoringMetric = { name: string; value: number | null; booleanValue: boolean | null; unit: string | null; collectedAt: string | null }
export type MonitoringDetails = { asset: Asset; latestMetrics: MonitoringMetric[]; history: MonitoringMetric[]; metricsMessage: string | null; metricsCached: boolean; metricsCollectedAt: string | null; metricsStale: boolean }
export type MonitoringSyncRun = { id: number; triggerType: string; status: string; startedAt: string; finishedAt: string | null; assetsUpdated: number; alertsUpdated: number; errorSummary: string | null }

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
  login: (username: string, password: string) => mutate<SessionUser & { token: string }>('/auth/login', 'POST', { username, password }),
  logout: () => mutate<void>('/auth/logout', 'POST'),
  me: () => request<SessionUser>('/auth/me'),
  summary: () => request<Summary>('/dashboard/summary'),
  projects: () => request<Project[]>('/projects'),
  assets: () => request<Asset[]>('/assets'),
  inspections: () => request<Inspection[]>('/inspections'),
  inspectionPlans: () => request<InspectionPlan[]>('/inspection-plans'),
  migrationReport: () => request<{ items: MigrationItem[] }>('/inspection-plans/migration-report'),
  migrationProposals: () => request<MigrationProposal[]>('/inspection-plans/migration-proposals'),
  prepareMigration: (assetId: number, projectId: number, intervalSeconds?: number) => mutate<MigrationProposal>('/inspection-plans/migration-proposals', 'POST', { assetId, projectId, intervalSeconds }),
  confirmMigration: (id: number) => mutate<InspectionPlan>(`/inspection-plans/migration-proposals/${id}/confirm`, 'POST'),
  cancelMigration: (id: number) => mutate<MigrationProposal>(`/inspection-plans/migration-proposals/${id}/cancel`, 'POST'),
  inspectionItems: (id: number) => request<InspectionItem[]>(`/inspections/${id}/items`),
  alerts: () => request<Alert[]>('/alerts'),
  alert: (id: number) => request<Alert>(`/alerts/${id}`),
  alertEvents: (id: number) => request<AlertHandlingEvent[]>(`/alerts/${id}/events`),
  acknowledgeAlert: (id: number, note?: string) => mutate<Alert>(`/alerts/${id}/acknowledge`, 'POST', { note }),
  addAlertNote: (id: number, note: string) => mutate<Alert>(`/alerts/${id}/notes`, 'POST', { note }),
  closeAlert: (id: number, note?: string) => mutate<Alert>(`/alerts/${id}/close`, 'POST', { note }),
  hertzBeatStatus: () => request<IntegrationStatus>('/integrations/hertzbeat/status'),
  syncAssetToHertzBeat: (id: number) => mutate<Asset>(`/integrations/hertzbeat/assets/${id}/sync`, 'POST'),
  syncAllAssetsToHertzBeat: () => mutate<AssetSyncAllResult>('/integrations/hertzbeat/assets/sync-all', 'POST'),
  syncMonitoringData: () => mutate<MonitoringSyncRun>('/integrations/hertzbeat/monitoring/sync', 'POST'),
  monitoringRuns: () => request<MonitoringSyncRun[]>('/integrations/hertzbeat/monitoring/runs'),
  monitoringDetails: (id: number) => request<MonitoringDetails>(`/integrations/hertzbeat/assets/${id}/details`),
  createProject: (data: Omit<Project, 'id'>) => mutate<Project>('/projects', 'POST', data),
  updateProject: (id: number, data: Omit<Project, 'id'>) => mutate<Project>(`/projects/${id}`, 'PUT', data),
  deleteProject: (id: number) => mutate<void>(`/projects/${id}`, 'DELETE'),
  createAsset: (data: AssetInput) => mutate<Asset>('/assets', 'POST', data),
  updateAsset: (id: number, data: AssetInput) => mutate<Asset>(`/assets/${id}`, 'PUT', data),
  deleteAsset: (id: number) => mutate<void>(`/assets/${id}`, 'DELETE'),
  createInspectionPlan: (data: InspectionPlanInput) => mutate<InspectionPlan>('/inspection-plans', 'POST', data),
  updateInspectionPlan: (id: number, data: InspectionPlanInput) => mutate<InspectionPlan>(`/monitoring-plans/${id}`, 'PUT', data),
  deleteInspectionPlan: (id: number) => mutate<InspectionPlan>(`/monitoring-plans/${id}`, 'DELETE'),
  deleteLegacyInspectionPlan: (id: number) => mutate<void>(`/inspection-plans/${id}`, 'DELETE'),
  syncInspectionPlan: (id: number) => mutate<PlanSyncSummary>(`/monitoring-plans/${id}/sync`, 'POST'),
  syncAllInspectionPlans: () => mutate<PlanSyncAllSummary>('/monitoring-plans/sync-all', 'POST'),
  executeInspectionPlan: (id: number) => mutate<Inspection>(`/inspection-plans/${id}/execute`, 'POST'),
}
