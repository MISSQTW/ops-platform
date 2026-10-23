export function presentServiceState(asset, intervalSeconds = 0, now = Date.now()) {
    const collectedAt = asset.lastCollectedAt ? new Date(asset.lastCollectedAt).getTime() : NaN;
    const freshnessSeconds = Math.max(180, intervalSeconds + 60);
    if (!Number.isFinite(collectedAt))
        return { text: '尚未检测', type: 'info', lastResult: null };
    const lastResult = ['UP', 'HEALTHY', 'RUNNING'].includes(asset.status) ? '正常'
        : ['DOWN', 'CRITICAL', 'FAILED'].includes(asset.status) ? '异常' : null;
    if (now - collectedAt > freshnessSeconds * 1000 || asset.status === 'UNKNOWN') {
        return { text: '暂无最新结果', type: 'info', lastResult };
    }
    if (['UP', 'HEALTHY', 'RUNNING'].includes(asset.status))
        return { text: '正常', type: 'success', lastResult: null };
    return { text: '异常', type: 'danger', lastResult: null };
}
