import test from 'node:test'
import assert from 'node:assert/strict'
import { presentServiceState } from './serviceState.ts'

const now = Date.parse('2026-10-21T14:00:00Z')

test('五分钟周期内的正常检测结果不会被误判为待检测', () => {
  assert.equal(presentServiceState({ status: 'UP', lastCollectedAt: '2026-10-21T13:56:00Z' }, 300, now).text, '正常')
})

test('超过检查周期和同步宽限的结果显示待检测', () => {
  assert.equal(presentServiceState({ status: 'UP', lastCollectedAt: '2026-10-21T13:53:00Z' }, 300, now).text, '待检测')
})

test('未检查的服务不会显示正常', () => {
  assert.equal(presentServiceState({ status: 'UP', lastCollectedAt: null }, 300, now).text, '待检测')
})

test('有效时间内的失败结果显示异常', () => {
  assert.equal(presentServiceState({ status: 'DOWN', lastCollectedAt: '2026-10-21T13:59:00Z' }, 300, now).text, '异常')
})
