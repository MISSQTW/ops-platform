export type ProfileField = {
  key: string
  label: string
  kind?: 'select' | 'textarea' | 'secret'
  defaultValue?: string
  choices?: string[]
  multiline?: boolean
  hint?: string
  osType?: 'LINUX' | 'WINDOWS'
}

const username: ProfileField = { key: 'username', label: '用户名' }
const password: ProfileField = { key: 'password', label: '密码', kind: 'secret', hint: '用于登录该服务。留空保留已保存的密码，填写新密码则在保存时替换。' }
const tls: ProfileField = { key: 'tls', label: '加密连接', kind: 'select', defaultValue: 'false', choices: ['false', 'true'] }
const accessKeys: ProfileField[] = [
  { key: 'accessKey', label: '访问标识（Access Key）', kind: 'secret', hint: '由服务端提供，用于识别访问账号；与下方 Secret Key 配对使用。' },
  { key: 'secretKey', label: '访问密钥（Secret Key）', kind: 'secret', hint: '与 Access Key 配对，用于验证访问身份。加密保存，留空保留原值。' },
]
const fieldsByType: Record<string, ProfileField[]> = {
  SERVER: [{ key: 'osType', label: '服务器操作系统', kind: 'select', defaultValue: 'LINUX', choices: ['LINUX', 'WINDOWS'] }, username,
    { key: 'winrmAuth', label: 'Windows 认证方式', kind: 'select', defaultValue: 'NTLM', choices: ['NTLM', 'BASIC'], osType: 'WINDOWS', hint: '仅使用 WinRM HTTPS，默认端口 5986，路径 /wsman；必须信任服务端证书并匹配主机名。' },
    { key: 'domain', label: 'Windows 域或计算机名', osType: 'WINDOWS', hint: 'NTLM 可填写域名，或在用户名中填写 DOMAIN\\user；本地账号使用目标计算机名。' },
    { key: 'driveLetters', label: '检查盘符', osType: 'WINDOWS', hint: '例如 C:,D:，留空检查全部固定磁盘。' },
    { key: 'excludedDriveLetters', label: '排除盘符', osType: 'WINDOWS', hint: '多个盘符用逗号分隔，例如 D:,E:。' },
    { key: 'hostKey', label: 'SSH 主机指纹', osType: 'LINUX', hint: '填写 SHA256: 开头的主机公钥指纹，请通过可信渠道确认。' },
    { key: 'diskThreshold', label: '磁盘使用率阈值（%）', defaultValue: '80' },
    { key: 'mountPoints', label: '检查挂载点', osType: 'LINUX', hint: '多个挂载点用逗号分隔，留空检查全部挂载点。' },
    { key: 'excludedMountPoints', label: '排除挂载点', osType: 'LINUX', hint: '多个挂载点用逗号分隔。' }, password,
    { key: 'privateKey', label: 'SSH 私钥', kind: 'secret', multiline: true, osType: 'LINUX', hint: '使用 SSH 密钥登录时填写，与服务端已授权的公钥配对。加密保存，留空保留原值。' },
    { key: 'privateKeyPassphrase', label: '私钥口令', kind: 'secret', osType: 'LINUX', hint: '用于解锁加密的 SSH 私钥；仅在私钥设置了口令时需要填写。' }],
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

// 仅依据该服务支持的明确协议识别加密方式，不通过端口或数据库协议名推测。
export function inferredTls(type: string, endpoint: string): boolean | undefined {
  const schemes: Record<string, Record<string, boolean>> = {
    MINIO: { http: false, https: true }, HTTP: { http: false, https: true },
    REDIS: { redis: false, rediss: true }, RABBITMQ: { amqp: false, amqps: true },
  }
  const scheme = /^([a-z][a-z0-9+.-]*):\/\//.exec(endpoint)?.[1]
  const supported = schemes[type.toUpperCase()]
  return scheme && supported && Object.prototype.hasOwnProperty.call(supported, scheme) ? supported[scheme] : undefined
}

export function inspectionProfileFields(type: string, options?: Record<string, string>, endpoint = ''): ProfileField[] {
  const fields = fieldsByType[type.toUpperCase()] || []
  return [{ key: 'timeoutMillis', label: '检查超时（毫秒）', defaultValue: '3000' }, ...fields.filter(field => (field.key !== 'tls' || inferredTls(type, endpoint) === undefined) && (!options || !field.osType || field.osType === (options.osType || 'LINUX')))]
}

export function profileOptions(type: string, saved: Record<string, string>, endpoint = ''): Record<string, string> {
  const result: Record<string, string> = {}
  for (const field of inspectionProfileFields(type)) {
    if (field.kind !== 'secret') result[field.key] = saved[field.key] ?? field.defaultValue ?? ''
  }
  const automaticTls = inferredTls(type, endpoint)
  // 无协议地址沿用已有配置；明确协议优先，避免地址与隐藏的旧选项冲突。
  if ('tls' in result && automaticTls !== undefined) result.tls = String(automaticTls)
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
  if (options.osType !== undefined && !['LINUX', 'WINDOWS'].includes(options.osType)) return '请选择有效的服务器操作系统。'
  if (options.osType === 'WINDOWS') {
    if (options.winrmAuth && !['NTLM', 'BASIC'].includes(options.winrmAuth)) return 'Windows 认证方式必须为 NTLM 或 BASIC。'
    for (const key of ['driveLetters', 'excludedDriveLetters']) {
      if ((options[key] || '').split(/[,\n]/).map(value => value.trim()).filter(Boolean).some(value => !/^[A-Za-z]:$/.test(value))) return 'Windows 盘符格式应为 C:，多个盘符用逗号分隔。'
    }
  }
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
