import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createRenderer, h, nextTick } from 'vue'

// 运行实际组件的 setup 和响应式生命周期，避免通过匹配模板文字验证保存行为。
const filename = fileURLToPath(new URL('./InspectionProfilePanel.vue', import.meta.url))
const { descriptor } = parse(await readFile(filename, 'utf8'), { filename })
const compiled = compileScript(descriptor, { id: 'profile-editor-test' }).content
const directory = await mkdtemp(join(tmpdir(), 'ops-profile-editor-'))
const modulePath = join(directory, 'component.mjs')
let source = ts.transpileModule(compiled, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
source = source.replace(/from ['"]vue['"]/g, `from '${new URL('../node_modules/vue/index.mjs', import.meta.url).href}'`)
source = source.replace(/from ['"]\.\/inspectionProfileFields['"]/g, `from '${new URL('./inspectionProfileFields.js', import.meta.url).href}'`)
// 此测试只运行配置保存逻辑；说明图标的悬浮和焦点交互在浏览器中验证。
source = source.replace(/import InfoTip from ['"]\.\/InfoTip.vue['"];?/, 'const InfoTip = {}')
await writeFile(modulePath, source)
const { default: Component } = await import(pathToFileURL(modulePath).href)
Component.render = () => null
const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
})

const emptyProfile = { assetId: 17, enabled: false, revision: 0, options: {}, configuredSecrets: [], checks: [], checkedAt: null, keyConfigured: true }

test('新增表单在父组件重新渲染后仍保存所选检查方式和连接参数', async () => {
  const originalFetch = globalThis.fetch
  const originalStorage = globalThis.localStorage
  const writes = []
  globalThis.localStorage = { getItem: () => null }
  globalThis.fetch = async (_url, request) => {
    writes.push(JSON.parse(request.body))
    return Response.json({ ...emptyProfile, enabled: true, revision: 1, options: writes.at(-1).options })
  }
  const root = {}
  try {
    const asset = { id: 0, type: 'MYSQL', endpoint: 'db:3306' }
    renderer.render(h(Component, { asset, editable: true }), root)
    await nextTick()
    const vm = root._vnode.component
    vm.setupState.enabled = true
    vm.setupState.options.username = 'reader'
    vm.setupState.options.database = 'app'
    // 保存按钮的 loading 变化会令父组件重新创建同值的 asset 对象。
    renderer.render(h(Component, { asset: { ...asset }, editable: true }), root)
    await nextTick()
    vm.exposed.validate()
    await vm.exposed.saveForAsset(17)
    assert.equal(writes.length, 1)
    assert.equal(writes[0].enabled, true)
    assert.equal(writes[0].options.username, 'reader')
    assert.equal(writes[0].options.database, 'app')
  } finally {
    renderer.render(null, root)
    globalThis.fetch = originalFetch
    globalThis.localStorage = originalStorage
  }
})

test('检查配置保存失败保留输入，重试成功后清空凭据草稿', async () => {
  const originalFetch = globalThis.fetch
  const originalStorage = globalThis.localStorage
  const writes = []
  globalThis.localStorage = { getItem: () => null }
  globalThis.fetch = async (_url, request) => {
    if (request.method === 'GET') return Response.json(emptyProfile)
    writes.push(JSON.parse(request.body))
    if (writes.length === 1) return Response.json({ message: '暂时保存失败' }, { status: 503 })
    return Response.json({ ...emptyProfile, enabled: true, revision: 1, configuredSecrets: ['password'] })
  }
  const root = {}
  try {
    renderer.render(h(Component, { asset: { id: 0, type: 'MYSQL', endpoint: 'db:3306' }, editable: true }), root)
    await nextTick()
    const vm = root._vnode.component
    vm.setupState.enabled = true
    vm.setupState.secretValues.password = 'test-only-secret'
    await assert.rejects(vm.exposed.saveForAsset(17), /暂时保存失败/)
    assert.equal(vm.setupState.secretValues.password, 'test-only-secret')
    await vm.exposed.saveForAsset(17)
    assert.equal(writes[1].secrets.password, 'test-only-secret')
    assert.equal(vm.setupState.secretValues.password, undefined)
  } finally {
    renderer.render(null, root)
    globalThis.fetch = originalFetch
    globalThis.localStorage = originalStorage
  }
})

test('MinIO 地址从 HTTP 改为 HTTPS 时保存加密连接，留空凭据继续保留', async () => {
  const originalFetch = globalThis.fetch
  const originalStorage = globalThis.localStorage
  const writes = []
  globalThis.localStorage = { getItem: () => null }
  globalThis.fetch = async (_url, request) => {
    if (request.method === 'GET') return Response.json({ ...emptyProfile, enabled: true, revision: 1, options: { tls: 'false', bucket: 'test-bucket' }, configuredSecrets: ['accessKey', 'secretKey'] })
    writes.push(JSON.parse(request.body))
    return Response.json({ ...emptyProfile, enabled: true, revision: 2, options: writes.at(-1).options, configuredSecrets: ['accessKey', 'secretKey'] })
  }
  const root = {}
  try {
    renderer.render(h(Component, { asset: { id: 17, type: 'MINIO', endpoint: 'http://minio:9000' }, editable: true }), root)
    await new Promise(resolve => setImmediate(resolve))
    const vm = root._vnode.component
    renderer.render(h(Component, { asset: { id: 17, type: 'MINIO', endpoint: 'https://minio:9000' }, editable: true }), root)
    await nextTick()
    await vm.exposed.saveForAsset(17)
    assert.equal(writes[0].options.tls, 'true')
    assert.equal(writes[0].options.bucket, 'test-bucket')
    assert.deepEqual(writes[0].secrets, {})
  } finally {
    renderer.render(null, root)
    globalThis.fetch = originalFetch
    globalThis.localStorage = originalStorage
  }
})

test('Redis 和 RabbitMQ 保存时按最新地址识别加密连接并保留原凭据', async () => {
  const originalFetch = globalThis.fetch
  const originalStorage = globalThis.localStorage
  globalThis.localStorage = { getItem: () => null }
  try {
    for (const [type, plain, secure] of [['REDIS', 'redis://cache:6379', 'rediss://cache:6379'], ['RABBITMQ', 'amqp://queue:5672', 'amqps://queue:5671']]) {
      const writes = []
      globalThis.fetch = async (_url, request) => {
        if (request.method === 'GET') return Response.json({ ...emptyProfile, enabled: true, revision: 1, options: { tls: 'false' }, configuredSecrets: ['password'] })
        writes.push(JSON.parse(request.body))
        return Response.json({ ...emptyProfile, enabled: true, revision: 2, options: writes.at(-1).options, configuredSecrets: ['password'] })
      }
      const root = {}
      try {
        renderer.render(h(Component, { asset: { id: 17, type, endpoint: plain }, editable: true }), root)
        await new Promise(resolve => setImmediate(resolve))
        const vm = root._vnode.component
        renderer.render(h(Component, { asset: { id: 17, type, endpoint: secure }, editable: true }), root)
        await nextTick()
        await vm.exposed.saveForAsset(17)
        assert.equal(writes[0].options.tls, 'true')
        assert.deepEqual(writes[0].secrets, {})
      } finally { renderer.render(null, root) }
    }
  } finally {
    globalThis.fetch = originalFetch
    globalThis.localStorage = originalStorage
  }
})

test.after(async () => { await rm(directory, { recursive: true, force: true }) })
