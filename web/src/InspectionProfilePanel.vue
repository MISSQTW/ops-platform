<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { inspectionProfileFields, profileOptions, profileSecrets, validateProfileOptions } from './inspectionProfileFields'

type ProbeCheck = { category: string; name: string; target: string; passed: boolean; message: string; durationMillis: number }
type InspectionProfile = { assetId: number; enabled: boolean; revision: number; options: Record<string, string>; configuredSecrets: string[]; checks: ProbeCheck[]; checkedAt: string | null; keyConfigured: boolean }
const props = defineProps<{ asset: { id: number; type: string; endpoint: string } }>()
const emit = defineEmits<{ saved: [] }>()
const profile = ref<InspectionProfile | null>(null)
const enabled = ref(false)
const options = ref<Record<string, string>>({})
const secretValues = reactive<Record<string, string>>({})
const secretCleared = reactive<Record<string, boolean>>({})
const checks = ref<ProbeCheck[]>([])
const checkedAt = ref<string | null>(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const error = ref('')
const notice = ref('')
const fields = computed(() => inspectionProfileFields(props.asset.type))
const busy = computed(() => loading.value || saving.value || testing.value)
let generation = 0
let controller = new AbortController()

function clearSecrets() {
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
  options.value = profileOptions(props.asset.type, value.options || {})
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
    const value = await request<InspectionProfile>(id)
    if (current === generation) applyProfile(value)
  } catch (cause) {
    if (current === generation) error.value = cause instanceof Error ? cause.message : '加载配置失败，请重试。'
  } finally { if (current === generation) loading.value = false }
}

async function save() {
  if (busy.value || !profile.value) return
  const secrets = profileSecrets(secretValues, secretCleared)
  error.value = enabled.value && !profile.value.keyConfigured ? '系统尚未配置凭据加密主密钥，请联系管理员配置后再启用深度巡检。' : validateProfileOptions(options.value, secrets)
  notice.value = ''
  if (error.value) return
  const current = generation
  const id = props.asset.id
  saving.value = true
  try {
    await request<unknown>(id, '', 'PUT', { enabled: enabled.value, options: { ...options.value }, secrets })
    if (current !== generation) return
    clearSecrets()
    notice.value = '配置已保存，关联计划将在下次同步后生效。'
    emit('saved')
    const value = await request<InspectionProfile>(id)
    if (current === generation) applyProfile(value)
  } catch (cause) {
    if (current === generation) error.value = cause instanceof Error ? cause.message : '保存配置失败，请重试。'
  } finally { if (current === generation) saving.value = false }
}

async function test() {
  if (busy.value || !profile.value?.enabled || !profile.value.keyConfigured) return
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

watch(() => [props.asset.id, props.asset.type, props.asset.endpoint], () => {
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
  <section class="inspection-profile-panel" aria-label="深度巡检配置" v-loading="loading">
    <div class="profile-title"><h3>深度巡检</h3><el-tag v-if="profile" size="small" type="info">配置版本 {{ profile.revision }}</el-tag></div>
    <p class="profile-help">端口基础检查确认网络可达；深度协议检查验证认证、服务响应及配置的业务条件。</p>
    <p class="profile-endpoint">检查目标：{{ asset.endpoint }}</p>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" role="alert" />
    <el-alert v-if="notice" :title="notice" type="success" show-icon :closable="false" />
    <el-button v-if="!profile && !loading" @click="load">重新加载配置</el-button>
    <template v-if="profile">
      <el-alert v-if="!profile.keyConfigured" title="系统尚未配置凭据加密主密钥，请联系管理员配置后再启用深度巡检。" type="warning" show-icon :closable="false" />
      <el-form label-position="top" :disabled="busy" autocomplete="off" @submit.prevent="save">
        <el-form-item label="启用深度巡检"><el-switch v-model="enabled" :disabled="!profile.keyConfigured && !enabled" aria-label="启用深度巡检" /></el-form-item>
        <el-form-item v-for="field in fields" :key="field.key" :label="field.label">
          <template v-if="field.kind === 'secret'">
            <el-input v-model="secretValues[field.key]" :type="field.multiline ? 'textarea' : 'password'" :class="{ 'private-key-input': field.multiline }" :rows="4" autocomplete="new-password" :aria-label="field.label" :disabled="secretCleared[field.key]" :placeholder="secretConfigured(field.key) ? '已配置，留空保留原值' : '未配置，填写后保存'" />
            <el-checkbox v-model="secretCleared[field.key]" :disabled="!secretConfigured(field.key)" @change="secretValues[field.key] = ''">清除已保存的{{ field.label }}</el-checkbox>
          </template>
          <el-select v-else-if="field.kind === 'select'" v-model="options[field.key]" :aria-label="field.label">
            <el-option v-for="choice in field.choices" :key="choice" :label="field.key === 'tls' ? (choice === 'true' ? '启用' : '关闭') : choice" :value="choice" />
          </el-select>
          <el-input v-else v-model="options[field.key]" :type="field.kind === 'textarea' ? 'textarea' : 'text'" :rows="3" :aria-label="field.label" />
          <p v-if="field.hint" class="profile-hint">{{ field.hint }}</p>
        </el-form-item>
        <div class="profile-actions">
          <el-button type="primary" :loading="saving" :disabled="busy || (enabled && !profile.keyConfigured)" @click="save">保存配置</el-button>
          <el-button :loading="testing" :disabled="busy || !profile.enabled || !profile.keyConfigured" @click="test">测试已保存配置</el-button>
        </div>
      </el-form>
      <el-alert type="info" show-icon :closable="false">
        <template #title>配置保存后，关联计划将在下次同步后生效。</template>
        <p>检查周期应大于实际检查耗时。手动测试使用已保存的配置，会占用资源，可能触发采集忙碌告警。</p>
      </el-alert>
      <h4>最近检查结果</h4>
      <p v-if="checkedAt" class="profile-help">检查时间：{{ displayTime(checkedAt) }}</p>
      <el-empty v-if="!checks.length" description="暂无深度检查结果" :image-size="56" />
      <div v-for="(check, index) in checks" :key="index" class="probe-result">
        <div class="probe-heading"><strong>{{ check.name }}</strong><el-tag :type="check.passed ? 'success' : 'danger'" size="small">{{ check.passed ? '通过' : '失败' }}</el-tag></div>
        <p>{{ check.category }} · {{ check.target }}</p>
        <p>{{ check.message || '暂无详细信息' }}</p>
        <small>耗时 {{ check.durationMillis }} 毫秒</small>
      </div>
    </template>
  </section>
</template>

<style scoped>
.inspection-profile-panel { min-height: 120px; }
.profile-title, .probe-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.profile-title h3 { margin: 0; }
.profile-help, .profile-hint, .profile-endpoint { color: var(--el-text-color-secondary); line-height: 1.6; overflow-wrap: anywhere; }
.profile-hint { width: 100%; margin: 5px 0 0; font-size: 12px; }
.profile-endpoint { font-size: 13px; }
.el-alert { margin: 12px 0; }
.el-select { width: 100%; }
.private-key-input :deep(textarea) { -webkit-text-security: disc; }
.profile-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.profile-actions .el-button { margin-left: 0; }
.probe-result { padding: 14px 0; border-bottom: 1px solid var(--el-border-color-light); overflow-wrap: anywhere; }
.probe-result p { margin: 8px 0; font-size: 13px; }
.probe-result small { color: var(--el-text-color-secondary); }
</style>
