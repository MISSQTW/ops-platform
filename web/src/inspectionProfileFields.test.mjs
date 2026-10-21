import test from 'node:test'
import assert from 'node:assert/strict'
import { inspectionProfileFields, profileOptions, profileSecrets, validateProfileOptions } from './inspectionProfileFields.ts'

test('HTTP 请求头和请求体仅作为敏感多行字段，不进入普通选项', () => {
  const fields = inspectionProfileFields('HTTP')
  for (const key of ['headers', 'body']) {
    assert.equal(fields.find(field => field.key === key)?.kind, 'secret')
    assert.equal(fields.find(field => field.key === key)?.multiline, true)
  }
  const options = profileOptions('HTTP', { headers: '不应回显', body: '不应回显', unknown: '旧字段' })
  assert.equal(options.headers, undefined)
  assert.equal(options.body, undefined)
  assert.equal(options.unknown, undefined)
  assert.equal(options.expectedStatus, '200')
})

test('密钥留空保留，非空替换，勾选清除显式发送空串', () => {
  assert.deepEqual(profileSecrets({ password: '', headers: '', body: '' }, {}), {})
  assert.deepEqual(profileSecrets({ password: '新密码', headers: '{"Authorization":"Bearer abc"}', body: '请求体' }, {}), {
    password: '新密码', headers: '{"Authorization":"Bearer abc"}', body: '请求体',
  })
  assert.deepEqual(profileSecrets({ headers: '草稿', body: '' }, { headers: true, body: true }), { headers: '', body: '' })
})

test('默认值与已保存选项正确合并', () => {
  assert.equal(profileOptions('SERVER', {}).diskThreshold, '80')
  assert.equal(profileOptions('RABBITMQ', {}).vhost, '/')
  assert.equal(profileOptions('MYSQL', { username: '巡检账号', timeoutMillis: '5000' }).username, '巡检账号')
  assert.equal(profileOptions('MYSQL', { timeoutMillis: '5000' }).timeoutMillis, '5000')
  for (const type of ['SERVER', 'MYSQL', 'POSTGRESQL', 'REDIS', 'KAFKA', 'RABBITMQ', 'ROCKETMQ', 'MINIO', 'HTTP']) {
    assert.ok(inspectionProfileFields(type).length > 1)
    assert.equal(profileOptions(type, {}).timeoutMillis, '3000')
  }
})

test('超时限定为 1000 至 10000 毫秒的整数', () => {
  for (const timeoutMillis of ['1000', '3000', '10000']) assert.equal(validateProfileOptions({ timeoutMillis }), '')
  for (const timeoutMillis of ['', '0', '999', '10001', '2500.5', 'abc']) assert.ok(validateProfileOptions({ timeoutMillis }))
})

test('请求头在敏感字段中校验 JSON 字符串对象，留空或清除合法', () => {
  const options = { timeoutMillis: '3000' }
  for (const headers of ['', '{"Authorization":"Bearer token"}']) assert.equal(validateProfileOptions(options, { headers }), '')
  for (const headers of ['[]', 'null', '{"Accept":2}', '{']) assert.ok(validateProfileOptions(options, { headers }))
})

test('磁盘阈值和 HTTP 状态码边界有效校验', () => {
  const options = { timeoutMillis: '3000' }
  for (const diskThreshold of ['1', '80', '100']) assert.equal(validateProfileOptions({ ...options, diskThreshold }), '')
  for (const diskThreshold of ['0', '101', '2.5']) assert.ok(validateProfileOptions({ ...options, diskThreshold }))
  for (const expectedStatus of ['100', '200', '599']) assert.equal(validateProfileOptions({ ...options, expectedStatus }), '')
  for (const expectedStatus of ['99', '600', '200.5']) assert.ok(validateProfileOptions({ ...options, expectedStatus }))
})
