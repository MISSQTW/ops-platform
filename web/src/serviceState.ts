import type { Asset } from './api'

export function presentServiceState(
  asset: Pick<Asset, 'lastCollectedAt' | 'status'>,
  intervalSeconds = 0,
  now = Date.now(),
) {
  const collectedAt = asset.lastCollectedAt ? new Date(asset.lastCollectedAt).getTime() : NaN
  const freshnessSeconds = Math.max(180, intervalSeconds + 60)
  if (!Number.isFinite(collectedAt) || now - collectedAt > freshnessSeconds * 1000 || asset.status === 'UNKNOWN') {
    return { text: '待检测', type: 'info' }
  }
  if (['UP', 'HEALTHY', 'RUNNING'].includes(asset.status)) return { text: '正常', type: 'success' }
  return { text: '异常', type: 'danger' }
}
