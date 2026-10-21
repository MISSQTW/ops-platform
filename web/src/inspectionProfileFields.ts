export type ProfileField = {
  key: string
  label: string
  kind?: 'select' | 'textarea' | 'secret'
  defaultValue?: string
  choices?: string[]
  multiline?: boolean
  hint?: string
}

const username: ProfileField = { key: 'username', label: '用户名' }
const password: ProfileField = { key: 'password', label: '密码', kind: 'secret' }
const tls: ProfileField = { key: 'tls', label: '启用 TLS', kind: 'select', defaultValue: 'false', choices: ['false', 'true'] }
const accessKeys: ProfileField[] = [
  { key: 'accessKey', label: '访问密钥（Access Key）', kind: 'secret' },
  { key: 'secretKey', label: '私有密钥（Secret Key）', kind: 'secret' },
]
const fieldsByType: Record<string, ProfileField[]> = {
  SERVER: [username, { key: 'hostKey', label: 'SSH 主机指纹', hint: '填写 SHA256: 开头的主机公钥指纹，请通过可信渠道确认。' },
    { key: 'diskThreshold', label: '磁盘使用率阈值（%）', defaultValue: '80' },
    { key: 'mountPoints', label: '检查挂载点', hint: '多个挂载点用逗号分隔，留空检查全部挂载点。' },
    { key: 'excludedMountPoints', label: '排除挂载点', hint: '多个挂载点用逗号分隔。' }, password,
    { key: 'privateKey', label: 'SSH 私钥', kind: 'secret', multiline: true },
    { key: 'privateKeyPassphrase', label: '私钥口令', kind: 'secret' }],
  MYSQL: [username, { key: 'database', label: '数据库' }, tls, password],
  POSTGRESQL: [username, { key: 'database', label: '数据库' }, tls, password],
  REDIS: [username, tls, password],
  KAFKA: [{ key: 'securityProtocol', label: '安全协议', kind: 'select', defaultValue: 'PLAINTEXT', choices: ['PLAINTEXT', 'SASL_PLAINTEXT', 'SSL', 'SASL_SSL'] },
    { key: 'saslMechanism', label: 'SASL 机制', hint: '例如 PLAIN、SCRAM-SHA-256、SCRAM-SHA-512。' }, username, password],
  RABBITMQ: [username, { key: 'vhost', label: '虚拟主机', defaultValue: '/' }, tls, password],
  ROCKETMQ: accessKeys,
  MINIO: [{ key: 'bucket', label: '存储桶' }, tls, ...accessKeys],
  HTTP: [{ key: 'httpMethod', label: '请求方法', kind: 'select', defaultValue: 'GET', choices: ['GET', 'POST', 'HEAD', 'PUT'] },
    { key: 'expectedStatus', label: '预期状态码', defaultValue: '200' },
    { key: 'responseContains', label: '响应应包含的文本' },
    { key: 'headers', label: '请求头（JSON）', kind: 'secret', multiline: true, hint: '填写 JSON 字符串对象，可包含 Authorization 等认证信息；内容加密保存，留空保留原值。' },
    { key: 'body', label: '请求体', kind: 'secret', multiline: true, hint: '内容加密保存，留空保留原值。' }],
}

export function inspectionProfileFields(type: string): ProfileField[] {
  return [{ key: 'timeoutMillis', label: '检查超时（毫秒）', defaultValue: '3000' }, ...(fieldsByType[type.toUpperCase()] || [])]
}

export function profileOptions(type: string, saved: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {}
  for (const field of inspectionProfileFields(type)) {
    if (field.kind !== 'secret') result[field.key] = saved[field.key] ?? field.defaultValue ?? ''
  }
  return result
}

// 未填写表示保留原密钥；仅勾选清除时发送空串。
export function profileSecrets(values: Record<string, string>, cleared: Record<string, boolean>): Record<string, string> {
  const result: Record<string, string> = {}
  for (const key of new Set([...Object.keys(values), ...Object.keys(cleared)])) {
    if (cleared[key]) result[key] = ''
    else if (values[key]) result[key] = values[key]
  }
  return result
}

export function validateProfileOptions(options: Record<string, string>, secrets: Record<string, string> = {}): string {
  const timeout = Number(options.timeoutMillis)
  if (!Number.isInteger(timeout) || timeout < 1000 || timeout > 10000) return '检查超时必须为 1000 至 10000 毫秒的整数。'
  if (options.diskThreshold !== undefined && (!/^\d+$/.test(options.diskThreshold) || Number(options.diskThreshold) < 1 || Number(options.diskThreshold) > 100)) return '磁盘使用率阈值必须为 1 至 100 的整数。'
  if (options.expectedStatus !== undefined && (!/^\d{3}$/.test(options.expectedStatus) || Number(options.expectedStatus) < 100 || Number(options.expectedStatus) > 599)) return '预期状态码必须为 100 至 599 的整数。'
  if (secrets.headers?.trim()) {
    try {
      const headers: unknown = JSON.parse(secrets.headers)
      if (!headers || typeof headers !== 'object' || Array.isArray(headers) || Object.values(headers).some(value => typeof value !== 'string')) return '请求头必须是值为字符串的 JSON 对象。'
    } catch { return '请求头不是有效的 JSON。' }
  }
  return ''
}
