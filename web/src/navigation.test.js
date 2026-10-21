import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('巡检空状态不重复显示新增按钮', async () => {
  const source = await readFile(new URL('./App.vue', import.meta.url), 'utf8')
  const emptyState = source.match(/<div v-if="visibleInspectionPlans\.length === 0" class="empty-state plan-empty">([\s\S]*?)<\/div>/)?.[1]
  assert.ok(emptyState)
  assert.doesNotMatch(emptyState, /<el-button/)
})

test('巡检计划列表不显示重复标题说明', async () => {
  const source = await readFile(new URL('./App.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /巡检计划列表/)
  assert.doesNotMatch(source, /配置巡检周期并管理自动执行任务/)
})

test('没有可见巡检计划时隐藏同步全部计划按钮', async () => {
  const source = await readFile(new URL('./App.vue', import.meta.url), 'utf8')
  const syncButton = source.split('\n').find(line => line.includes('@click="syncAllPlans"')) || ''
  assert.match(syncButton, /visibleInspectionPlans\.length > 0/)
})
