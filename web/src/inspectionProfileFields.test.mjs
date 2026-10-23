import test from 'node:test'
import assert from 'node:assert/strict'
import { inspectionProfileFields, inferredTls, profileOptions, profileSecrets, validateProfileOptions } from './inspectionProfileFields.ts'

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
  assert.equal(profileOptions('SERVER', {}).osType, 'LINUX')
  assert.equal(profileOptions('SERVER', {}).winrmAuth, 'NTLM')
  assert.equal(profileOptions('SERVER', {}).diskThreshold, '80')
  assert.equal(profileOptions('RABBITMQ', {}).vhost, '/')
  assert.equal(profileOptions('MYSQL', { username: '巡检账号', timeoutMillis: '5000' }).username, '巡检账号')
  assert.equal(profileOptions('MYSQL', { timeoutMillis: '5000' }).timeoutMillis, '5000')
  for (const type of ['SERVER', 'MYSQL', 'POSTGRESQL', 'REDIS', 'KAFKA', 'RABBITMQ', 'ROCKETMQ', 'MINIO', 'HTTP']) {
    assert.ok(inspectionProfileFields(type).length > 1)
    assert.equal(profileOptions(type, {}).timeoutMillis, '3000')
  }
})

test('服务器按操作系统展示 SSH 或 Windows HTTPS 登录与盘符配置', () => {
  const windows = inspectionProfileFields('SERVER', { osType: 'WINDOWS' })
  assert.ok(windows.some(field => field.key === 'driveLetters'))
  assert.ok(windows.some(field => field.key === 'winrmAuth'))
  assert.ok(!windows.some(field => field.key === 'hostKey'))
  assert.ok(!inspectionProfileFields('SERVER', { osType: 'LINUX' }).some(field => field.key === 'driveLetters'))
  assert.ok(validateProfileOptions({ timeoutMillis: '3000', osType: 'WINDOWS', driveLetters: 'C:; Remove-Item *' }))
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

test('MinIO 根据连接地址确定 TLS，不再提供重复的选项且兼容已有无协议地址', () => {
  assert.ok(!inspectionProfileFields('MINIO', {}, 'https://storage:9000').some(field => field.key === 'tls'))
  assert.equal(profileOptions('MINIO', { tls: 'false' }, 'https://storage.example:9000').tls, 'true')
  assert.equal(profileOptions('MINIO', { tls: 'true' }, 'http://minio:9000').tls, 'false')
  assert.equal(profileOptions('MINIO', { tls: 'true' }, 'minio:9000').tls, 'true')
  assert.equal(profileOptions('MYSQL', { tls: 'true' }, 'mysql:3306').tls, 'true')
})

test('Redis 和 RabbitMQ 根据明确协议选择加密连接并隐藏重复选项', () => {
  for (const [type, plain, encrypted] of [
    ['REDIS', 'redis://cache:6379', 'rediss://cache:6379'],
    ['RABBITMQ', 'amqp://queue:5672', 'amqps://queue:5671'],
  ]) {
    assert.equal(profileOptions(type, { tls: 'false' }, encrypted).tls, 'true')
    assert.equal(profileOptions(type, { tls: 'true' }, plain).tls, 'false')
    assert.ok(!inspectionProfileFields(type, {}, encrypted).some(field => field.key === 'tls'))
    assert.ok(!inspectionProfileFields(type, {}, plain).some(field => field.key === 'tls'))
  }
})

test('无明确协议的地址保留原加密设置，数据库协议和端口不用于推测 TLS', () => {
  for (const [type, endpoint] of [
    ['MYSQL', 'mysql://db:3306'], ['POSTGRESQL', 'postgresql://db:5432'],
    ['REDIS', 'cache:6380'], ['RABBITMQ', 'queue:5671'], ['MINIO', 'storage:9000'],
    ['REDIS', 'tcp://cache:6379'],
  ]) {
    assert.equal(profileOptions(type, { tls: 'true' }, endpoint).tls, 'true')
    assert.equal(profileOptions(type, { tls: 'false' }, endpoint).tls, 'false')
    assert.equal(inspectionProfileFields(type, {}, endpoint).find(field => field.key === 'tls')?.label, '加密连接')
  }
})


test('未知协议不会被误识别成支持的加密连接', () => {
  for (const endpoint of ['https://cache:6379', 'constructor://cache:6379', 'tcp://cache:6379']) {
    assert.equal(inferredTls('REDIS', endpoint), undefined)
  }
})
