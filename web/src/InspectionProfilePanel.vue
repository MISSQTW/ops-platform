<script setup lang="ts">
import InfoTip from './InfoTip.vue'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { inspectionProfileFields, inferredTls, profileOptions, profileSecrets, validateProfileOptions } from './inspectionProfileFields'

type ProbeCheck = { category: string; name: string; target: string; passed: boolean; message: string; durationMillis: number }
type InspectionProfile = { assetId: number; enabled: boolean; revision: number; options: Record<string, string>; configuredSecrets: string[]; checks: ProbeCheck[]; checkedAt: string | null; keyConfigured: boolean | null }
const props = defineProps<{ asset: { id: number; type: string; endpoint: string }; editable?: boolean }>()
const emit = defineEmits<{ saved: [] }>()
const profile = ref<InspectionProfile | null>(null)
const enabled = ref(false)
const options = ref<Record<string, string>>({})
const secretValues = reactive<Record<string, string>>({})
const secretCleared = reactive<Record<string, boolean>>({})
const credentialSections = ref<string[]>([])
const connectionSections = ref<string[]>([])
const checks = ref<ProbeCheck[]>([])
const checkedAt = ref<string | null>(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const error = ref('')
const notice = ref('')
const fields = computed(() => inspectionProfileFields(props.asset.type, options.value, props.asset.endpoint))
const basicFields = computed(() => fields.value.filter(field => field.key !== 'tls'))
const advancedFields = computed(() => fields.value.filter(field => field.key === 'tls'))
const savedSecretFields = computed(() => fields.value.filter(field => field.kind === 'secret' && secretConfigured(field.key)))
const automaticTls = computed(() => inferredTls(props.asset.type, props.asset.endpoint))
const automaticConnection = computed(() => `${props.asset.endpoint.split('://')[0].toUpperCase()}（${automaticTls.value ? '加密连接' : '未加密连接'}）`)
const busy = computed(() => loading.value || saving.value || testing.value)
let generation = 0
let controller = new AbortController()

function clearSecrets() {
  credentialSections.value = []
  connectionSections.value = []
  for (const key of Object.keys(secretValues)) delete secretValues[key]
  for (const key of Object.keys(secretCleared)) delete secretCleared[key]
}

// 独立组件使用现有会话令牌；切换资产时中止旧请求，防止结果串到其他资产。
async function request<T>(id: number, suffix = '', method = 'GET', body?: unknown): Promise<T> {
  const token = localStorage.getItem('ops_token')
  const response = await fetch(`/api/assets/${id}/inspection-profile${suffix}`, {
    method, signal: controller.signal,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (response.status === 401) throw new Error('登录已过期，请重新登录。')
  if (!response.ok) {
    const detail = await response.json().catch(() => null)
    const message = detail?.errors?.[0] || detail?.message
    throw new Error(typeof message === 'string' ? message : `操作失败（${response.status}），请检查配置后重试。`)
  }
  return response.status === 204 ? undefined as T : await response.json() as T
}

function applyProfile(value: InspectionProfile) {
  profile.value = value
  enabled.value = value.enabled
  options.value = profileOptions(props.asset.type, value.options || {}, props.asset.endpoint)
  checks.value = value.checks || []
  checkedAt.value = value.checkedAt
  clearSecrets()
}

async function load() {
  const current = generation
  const id = props.asset.id
  loading.value = true
  error.value = ''
  try {
    const value: InspectionProfile = id ? await request<InspectionProfile>(id) : { assetId: 0, enabled: false, revision: 0, options: {}, configuredSecrets: [], checks: [], checkedAt: null, keyConfigured: null }
    if (current === generation) applyProfile(value)
  } catch (cause) {
    if (current === generation) error.value = cause instanceof Error ? cause.message : '加载配置失败，请重试。'
  } finally { if (current === generation) loading.value = false }
}

// 先校验再保存服务，避免本地校验失败时产生不完整的新服务。
function validate() {
  if (busy.value || !profile.value) throw new Error('检查配置尚未加载完成，请稍后重试。')
  error.value = enabled.value && profile.value.keyConfigured === false
    ? '系统尚未配置凭据加密主密钥，请联系管理员配置。'
    : enabled.value ? validateProfileOptions(options.value, profileSecrets(secretValues, secretCleared)) : ''
  if (error.value) throw new Error(error.value)
}

async function saveForAsset(id: number) {
  validate()
  if (!enabled.value && !profile.value?.revision) return
  const current = generation
  saving.value = true
  try {
    const value = await request<InspectionProfile>(id, '', 'PUT', {
      enabled: enabled.value, options: profileOptions(props.asset.type, options.value, props.asset.endpoint), secrets: profileSecrets(secretValues, secretCleared),
    })
    if (current !== generation) throw new Error('服务已切换，请重新打开后确认配置。')
    applyProfile(value)
    emit('saved')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存检查配置失败，请重试。'
    throw cause
  } finally { if (current === generation) saving.value = false }
}

defineExpose({ validate, saveForAsset })

async function test() {
  if (busy.value || !profile.value?.enabled || profile.value.keyConfigured === false) return
  const current = generation
  const id = props.asset.id
  testing.value = true
  error.value = ''
  notice.value = ''
  try {
    const value = await request<ProbeCheck[]>(id, '/test', 'POST')
    if (current === generation) {
      checks.value = value
      checkedAt.value = new Date().toISOString()
      notice.value = '手动测试完成，结果如下。'
    }
  } catch (cause) {
    if (current === generation) error.value = cause instanceof Error ? cause.message : '测试失败，请重试。'
  } finally { if (current === generation) testing.value = false }
}

function secretConfigured(key: string) {
  return profile.value?.configuredSecrets?.includes(key) || false
}
function displayTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

watch([() => props.asset.id, () => props.asset.type], () => {
  generation++
  controller.abort()
  controller = new AbortController()
  clearSecrets()
  profile.value = null
  options.value = {}
  enabled.value = false
  checks.value = []
  checkedAt.value = null
  saving.value = false
  testing.value = false
  notice.value = ''
  void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++; controller.abort(); clearSecrets() })
</script>

<template>
  <section class="inspection-profile-panel" aria-label="检查配置" v-loading="loading">
    <div class="profile-title"><div class="profile-heading"><h3>检查配置</h3><InfoTip v-if="editable" label="检查配置" content="选择检查方式，并填写该服务的连接和验证参数。检查配置随服务一起保存。" /></div><el-tag v-if="!editable && profile" size="small" type="info">{{ profile.enabled ? '验证服务可用性' : '仅检查连接' }}</el-tag></div>
    <p v-if="!editable" class="profile-endpoint">检查目标：{{ asset.endpoint }}</p>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" role="alert" />
    <el-alert v-if="notice" :title="notice" type="success" show-icon :closable="false" />
    <el-button v-if="!profile && !loading" @click="load">重新加载配置</el-button>
    <template v-if="profile">
      <el-alert v-if="editable && profile.keyConfigured === false" title="系统尚未配置凭据加密主密钥，请联系管理员配置后再使用服务验证。" type="warning" show-icon :closable="false" />
      <div v-if="editable" class="profile-fields">
        <el-form-item label="检查方式"><template #label><span class="profile-label">检查方式<InfoTip label="检查方式" :content="enabled ? '验证服务响应；需要认证的服务请填写对应凭据。' : '只检查连接是否可达，不验证账号或业务响应。'" /></span></template><el-select v-model="enabled" :disabled="busy" aria-label="检查方式"><el-option label="仅检查连接" :value="false" /><el-option label="验证服务可用性" :value="true" :disabled="profile.keyConfigured === false" /></el-select></el-form-item>
        <div v-if="enabled && automaticTls !== undefined" class="profile-connection"><span>连接方式：{{ automaticConnection }}</span><InfoTip label="连接方式" content="根据连接地址自动确定。" /></div>
        <el-form-item v-for="field in (enabled ? basicFields : [])" :key="field.key" :label="field.label">
          <template #label><span class="profile-label">{{ field.label }}<InfoTip v-if="field.hint" :label="field.label" :content="field.hint" /></span></template>
          <template v-if="field.kind === 'secret'">
            <el-input v-model="secretValues[field.key]" :type="field.multiline ? 'textarea' : 'password'" :class="{ 'private-key-input': field.multiline }" :rows="4" autocomplete="new-password" :aria-label="field.label" :disabled="busy || secretCleared[field.key]" :placeholder="secretCleared[field.key] ? '已选择清除，保存后生效' : secretConfigured(field.key) ? '已配置，留空保留原值' : '未配置，填写后保存'" />
            <el-tag v-if="secretCleared[field.key]" class="credential-clear-tag" size="small" type="warning">将在保存时清除</el-tag>
          </template>
          <el-select v-else-if="field.kind === 'select'" v-model="options[field.key]" :disabled="busy" :aria-label="field.label">
            <el-option v-for="choice in field.choices" :key="choice" :label="field.key === 'tls' ? (choice === 'true' ? '启用' : '关闭') : choice" :value="choice" />
          </el-select>
          <el-input v-else v-model="options[field.key]" :type="field.kind === 'textarea' ? 'textarea' : 'text'" :rows="3" :aria-label="field.label" />
        </el-form-item>
        <el-collapse v-if="enabled && advancedFields.length" v-model="connectionSections" class="connection-settings">
          <el-collapse-item title="高级连接设置" name="connection">
            <el-form-item v-for="field in advancedFields" :key="field.key" :label="field.label">
              <template #label><span class="profile-label">{{ field.label }}<InfoTip :label="field.label" content="当前地址未明确是否使用加密连接，请按服务端配置选择。" /></span></template>
              <el-select v-model="options[field.key]" :disabled="busy" :aria-label="field.label">
                <el-option label="启用" value="true" /><el-option label="关闭" value="false" />
              </el-select>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
        <el-collapse v-if="enabled && savedSecretFields.length" v-model="credentialSections" class="credential-management">
          <el-collapse-item title="管理已保存的凭据" name="credentials">
            <template #title><span class="profile-label">管理已保存的凭据<InfoTip label="管理已保存的凭据" content="仅清除本平台保存的内容。勾选后点击底部“保存”才会生效，取消勾选可保留。" /></span></template>
            <el-checkbox v-for="field in savedSecretFields" :key="field.key" v-model="secretCleared[field.key]" :disabled="busy" @change="secretValues[field.key] = ''">清除{{ field.label }}</el-checkbox>
          </el-collapse-item>
        </el-collapse>
      </div>
      <dl v-else class="profile-summary">
        <template v-if="profile.enabled && automaticTls !== undefined"><dt>连接方式</dt><dd>{{ automaticConnection }}</dd></template>
        <template v-for="field in (profile.enabled ? fields : [])" :key="field.key">
          <dt>{{ field.label }}</dt><dd>{{ field.kind === 'secret' ? (secretConfigured(field.key) ? '已配置' : '未配置') : field.key === 'tls' ? (options[field.key] === 'true' ? '启用' : '关闭') : options[field.key] || '未设置' }}</dd>
        </template>
      </dl>
      <template v-if="!editable">
        <div class="profile-title"><h4>最近检查结果</h4><el-button v-if="profile.enabled" :loading="testing" :disabled="busy || profile.keyConfigured === false" @click="test">测试连接</el-button></div>
      <p v-if="checkedAt" class="profile-help">检查时间：{{ displayTime(checkedAt) }}</p>
      <el-empty v-if="!checks.length" description="暂无检查结果" :image-size="56" />
      <div v-for="(check, index) in checks" :key="index" class="probe-result">
        <div class="probe-heading"><strong>{{ check.name }}</strong><el-tag :type="check.passed ? 'success' : 'danger'" size="small">{{ check.passed ? '通过' : '失败' }}</el-tag></div>
        <p>{{ check.category }} · {{ check.target }}</p>
        <p>{{ check.message || '暂无详细信息' }}</p>
        <small>耗时 {{ check.durationMillis }} 毫秒</small>
      </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.inspection-profile-panel { min-height: 120px; }
.credential-management, .connection-settings { margin-top: 16px; }
.connection-settings .el-form-item { margin: 12px 0 0; }
.credential-management .el-checkbox { display: flex; height: auto; min-height: 32px; white-space: normal; }
.credential-clear-tag { margin-top: 6px; }
.profile-summary { display: grid; grid-template-columns: minmax(100px, 1fr) minmax(0, 2fr); gap: 10px 16px; font-size: 13px; }
.profile-summary dt { color: var(--el-text-color-secondary); }
.profile-summary dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.profile-title, .probe-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.profile-title h3 { margin: 0; }
.profile-heading, .profile-label { display: inline-flex; align-items: center; gap: 6px; }
.profile-title { margin-bottom: 18px; }
.profile-connection { display: flex; align-items: center; gap: 6px; margin: 0 0 18px; font-size: 13px; }
.profile-help, .profile-endpoint { color: var(--el-text-color-secondary); line-height: 1.6; overflow-wrap: anywhere; }
.profile-endpoint { font-size: 13px; }
.el-alert { margin: 12px 0; }
.el-select { width: 100%; }
.private-key-input :deep(textarea) { -webkit-text-security: disc; }
.probe-result { padding: 14px 0; border-bottom: 1px solid var(--el-border-color-light); overflow-wrap: anywhere; }
.probe-result p { margin: 8px 0; font-size: 13px; }
.probe-result small { color: var(--el-text-color-secondary); }
</style>
