import InfoTip from './InfoTip.vue';
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { inspectionProfileFields, inferredTls, profileOptions, profileSecrets, validateProfileOptions } from './inspectionProfileFields';
const props = defineProps();
const emit = defineEmits();
const profile = ref(null);
const enabled = ref(false);
const options = ref({});
const secretValues = reactive({});
const secretCleared = reactive({});
const credentialSections = ref([]);
const connectionSections = ref([]);
const checks = ref([]);
const checkedAt = ref(null);
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const error = ref('');
const notice = ref('');
const fields = computed(() => inspectionProfileFields(props.asset.type, options.value, props.asset.endpoint));
const basicFields = computed(() => fields.value.filter(field => field.key !== 'tls'));
const advancedFields = computed(() => fields.value.filter(field => field.key === 'tls'));
const savedSecretFields = computed(() => fields.value.filter(field => field.kind === 'secret' && secretConfigured(field.key)));
const automaticTls = computed(() => inferredTls(props.asset.type, props.asset.endpoint));
const automaticConnection = computed(() => `${props.asset.endpoint.split('://')[0].toUpperCase()}（${automaticTls.value ? '加密连接' : '未加密连接'}）`);
const busy = computed(() => loading.value || saving.value || testing.value);
let generation = 0;
let controller = new AbortController();
function clearSecrets() {
    credentialSections.value = [];
    connectionSections.value = [];
    for (const key of Object.keys(secretValues))
        delete secretValues[key];
    for (const key of Object.keys(secretCleared))
        delete secretCleared[key];
}
// 独立组件使用现有会话令牌；切换资产时中止旧请求，防止结果串到其他资产。
async function request(id, suffix = '', method = 'GET', body) {
    const token = localStorage.getItem('ops_token');
    const response = await fetch(`/api/assets/${id}/inspection-profile${suffix}`, {
        method, signal: controller.signal,
        headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (response.status === 401)
        throw new Error('登录已过期，请重新登录。');
    if (!response.ok) {
        const detail = await response.json().catch(() => null);
        const message = detail?.errors?.[0] || detail?.message;
        throw new Error(typeof message === 'string' ? message : `操作失败（${response.status}），请检查配置后重试。`);
    }
    return response.status === 204 ? undefined : await response.json();
}
function applyProfile(value) {
    profile.value = value;
    enabled.value = value.enabled;
    options.value = profileOptions(props.asset.type, value.options || {}, props.asset.endpoint);
    checks.value = value.checks || [];
    checkedAt.value = value.checkedAt;
    clearSecrets();
}
async function load() {
    const current = generation;
    const id = props.asset.id;
    loading.value = true;
    error.value = '';
    try {
        const value = id ? await request(id) : { assetId: 0, enabled: false, revision: 0, options: {}, configuredSecrets: [], checks: [], checkedAt: null, keyConfigured: null };
        if (current === generation)
            applyProfile(value);
    }
    catch (cause) {
        if (current === generation)
            error.value = cause instanceof Error ? cause.message : '加载配置失败，请重试。';
    }
    finally {
        if (current === generation)
            loading.value = false;
    }
}
// 先校验再保存服务，避免本地校验失败时产生不完整的新服务。
function validate() {
    if (busy.value || !profile.value)
        throw new Error('检查配置尚未加载完成，请稍后重试。');
    error.value = enabled.value && profile.value.keyConfigured === false
        ? '系统尚未配置凭据加密主密钥，请联系管理员配置。'
        : enabled.value ? validateProfileOptions(options.value, profileSecrets(secretValues, secretCleared)) : '';
    if (error.value)
        throw new Error(error.value);
}
async function saveForAsset(id) {
    validate();
    if (!enabled.value && !profile.value?.revision)
        return;
    const current = generation;
    saving.value = true;
    try {
        const value = await request(id, '', 'PUT', {
            enabled: enabled.value, options: profileOptions(props.asset.type, options.value, props.asset.endpoint), secrets: profileSecrets(secretValues, secretCleared),
        });
        if (current !== generation)
            throw new Error('服务已切换，请重新打开后确认配置。');
        applyProfile(value);
        emit('saved');
    }
    catch (cause) {
        error.value = cause instanceof Error ? cause.message : '保存检查配置失败，请重试。';
        throw cause;
    }
    finally {
        if (current === generation)
            saving.value = false;
    }
}
const __VLS_exposed = { validate, saveForAsset };
defineExpose({ validate, saveForAsset });
async function test() {
    if (busy.value || !profile.value?.enabled || profile.value.keyConfigured === false)
        return;
    const current = generation;
    const id = props.asset.id;
    testing.value = true;
    error.value = '';
    notice.value = '';
    try {
        const value = await request(id, '/test', 'POST');
        if (current === generation) {
            checks.value = value;
            checkedAt.value = new Date().toISOString();
            notice.value = '手动测试完成，结果如下。';
        }
    }
    catch (cause) {
        if (current === generation)
            error.value = cause instanceof Error ? cause.message : '测试失败，请重试。';
    }
    finally {
        if (current === generation)
            testing.value = false;
    }
}
function secretConfigured(key) {
    return profile.value?.configuredSecrets?.includes(key) || false;
}
function displayTime(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN');
}
watch([() => props.asset.id, () => props.asset.type], () => {
    generation++;
    controller.abort();
    controller = new AbortController();
    clearSecrets();
    profile.value = null;
    options.value = {};
    enabled.value = false;
    checks.value = [];
    checkedAt.value = null;
    saving.value = false;
    testing.value = false;
    notice.value = '';
    void load();
}, { immediate: true });
onBeforeUnmount(() => { generation++; controller.abort(); clearSecrets(); }); /* PartiallyEnd: #3632/scriptSetup.vue */
function __VLS_template() {
    const __VLS_ctx = {};
    let __VLS_components;
    let __VLS_directives;
    ['connection-settings', 'credential-management', 'profile-summary', 'profile-summary', 'profile-title', 'profile-title', 'profile-endpoint', 'probe-result', 'probe-result',];
    // CSS variable injection
    // CSS variable injection end
    __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
        ...{ class: ("inspection-profile-panel") },
        'aria-label': ("检查配置"),
    });
    __VLS_asFunctionalDirective(__VLS_directives.vLoading)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.loading) }, null, null);
    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: ("profile-title") },
    });
    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: ("profile-heading") },
    });
    __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
    if (__VLS_ctx.editable) {
        // @ts-ignore
        /** @type { [typeof InfoTip, ] } */ ;
        // @ts-ignore
        const __VLS_0 = __VLS_asFunctionalComponent(InfoTip, new InfoTip({
            label: ("检查配置"),
            content: ("选择检查方式，并填写该服务的连接和验证参数。检查配置随服务一起保存。"),
        }));
        const __VLS_1 = __VLS_0({
            label: ("检查配置"),
            content: ("选择检查方式，并填写该服务的连接和验证参数。检查配置随服务一起保存。"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_0));
    }
    if (!__VLS_ctx.editable && __VLS_ctx.profile) {
        const __VLS_5 = {}.ElTag;
        /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
        // @ts-ignore
        const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
            size: ("small"),
            type: ("info"),
        }));
        const __VLS_7 = __VLS_6({
            size: ("small"),
            type: ("info"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_6));
        (__VLS_ctx.profile.enabled ? '验证服务可用性' : '仅检查连接');
        __VLS_10.slots.default;
        var __VLS_10;
    }
    if (!__VLS_ctx.editable) {
        __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: ("profile-endpoint") },
        });
        (__VLS_ctx.asset.endpoint);
    }
    if (__VLS_ctx.error) {
        const __VLS_11 = {}.ElAlert;
        /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
        // @ts-ignore
        const __VLS_12 = __VLS_asFunctionalComponent(__VLS_11, new __VLS_11({
            title: ((__VLS_ctx.error)),
            type: ("error"),
            showIcon: (true),
            closable: ((false)),
            role: ("alert"),
        }));
        const __VLS_13 = __VLS_12({
            title: ((__VLS_ctx.error)),
            type: ("error"),
            showIcon: (true),
            closable: ((false)),
            role: ("alert"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_12));
    }
    if (__VLS_ctx.notice) {
        const __VLS_17 = {}.ElAlert;
        /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
        // @ts-ignore
        const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
            title: ((__VLS_ctx.notice)),
            type: ("success"),
            showIcon: (true),
            closable: ((false)),
        }));
        const __VLS_19 = __VLS_18({
            title: ((__VLS_ctx.notice)),
            type: ("success"),
            showIcon: (true),
            closable: ((false)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    }
    if (!__VLS_ctx.profile && !__VLS_ctx.loading) {
        const __VLS_23 = {}.ElButton;
        /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
        // @ts-ignore
        const __VLS_24 = __VLS_asFunctionalComponent(__VLS_23, new __VLS_23({
            ...{ 'onClick': {} },
        }));
        const __VLS_25 = __VLS_24({
            ...{ 'onClick': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_24));
        let __VLS_29;
        const __VLS_30 = {
            onClick: (__VLS_ctx.load)
        };
        let __VLS_26;
        let __VLS_27;
        __VLS_28.slots.default;
        var __VLS_28;
    }
    if (__VLS_ctx.profile) {
        if (__VLS_ctx.editable && __VLS_ctx.profile.keyConfigured === false) {
            const __VLS_31 = {}.ElAlert;
            /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
            // @ts-ignore
            const __VLS_32 = __VLS_asFunctionalComponent(__VLS_31, new __VLS_31({
                title: ("系统尚未配置凭据加密主密钥，请联系管理员配置后再使用服务验证。"),
                type: ("warning"),
                showIcon: (true),
                closable: ((false)),
            }));
            const __VLS_33 = __VLS_32({
                title: ("系统尚未配置凭据加密主密钥，请联系管理员配置后再使用服务验证。"),
                type: ("warning"),
                showIcon: (true),
                closable: ((false)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_32));
        }
        if (__VLS_ctx.editable) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("profile-fields") },
            });
            const __VLS_37 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
                label: ("检查方式"),
            }));
            const __VLS_39 = __VLS_38({
                label: ("检查方式"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_38));
            __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
            {
                const { label: __VLS_thisSlot } = __VLS_42.slots;
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: ("profile-label") },
                });
                // @ts-ignore
                /** @type { [typeof InfoTip, ] } */ ;
                // @ts-ignore
                const __VLS_43 = __VLS_asFunctionalComponent(InfoTip, new InfoTip({
                    label: ("检查方式"),
                    content: ((__VLS_ctx.enabled ? '验证服务响应；需要认证的服务请填写对应凭据。' : '只检查连接是否可达，不验证账号或业务响应。')),
                }));
                const __VLS_44 = __VLS_43({
                    label: ("检查方式"),
                    content: ((__VLS_ctx.enabled ? '验证服务响应；需要认证的服务请填写对应凭据。' : '只检查连接是否可达，不验证账号或业务响应。')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_43));
            }
            const __VLS_48 = {}.ElSelect;
            /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
            // @ts-ignore
            const __VLS_49 = __VLS_asFunctionalComponent(__VLS_48, new __VLS_48({
                modelValue: ((__VLS_ctx.enabled)),
                disabled: ((__VLS_ctx.busy)),
                'aria-label': ("检查方式"),
            }));
            const __VLS_50 = __VLS_49({
                modelValue: ((__VLS_ctx.enabled)),
                disabled: ((__VLS_ctx.busy)),
                'aria-label': ("检查方式"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_49));
            const __VLS_54 = {}.ElOption;
            /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
            // @ts-ignore
            const __VLS_55 = __VLS_asFunctionalComponent(__VLS_54, new __VLS_54({
                label: ("仅检查连接"),
                value: ((false)),
            }));
            const __VLS_56 = __VLS_55({
                label: ("仅检查连接"),
                value: ((false)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_55));
            const __VLS_60 = {}.ElOption;
            /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
            // @ts-ignore
            const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
                label: ("验证服务可用性"),
                value: ((true)),
                disabled: ((__VLS_ctx.profile.keyConfigured === false)),
            }));
            const __VLS_62 = __VLS_61({
                label: ("验证服务可用性"),
                value: ((true)),
                disabled: ((__VLS_ctx.profile.keyConfigured === false)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_61));
            __VLS_53.slots.default;
            var __VLS_53;
            var __VLS_42;
            if (__VLS_ctx.enabled && __VLS_ctx.automaticTls !== undefined) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("profile-connection") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.automaticConnection);
                // @ts-ignore
                /** @type { [typeof InfoTip, ] } */ ;
                // @ts-ignore
                const __VLS_66 = __VLS_asFunctionalComponent(InfoTip, new InfoTip({
                    label: ("连接方式"),
                    content: ("根据连接地址自动确定。"),
                }));
                const __VLS_67 = __VLS_66({
                    label: ("连接方式"),
                    content: ("根据连接地址自动确定。"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_66));
            }
            for (const [field] of __VLS_getVForSourceType(((__VLS_ctx.enabled ? __VLS_ctx.basicFields : [])))) {
                const __VLS_71 = {}.ElFormItem;
                /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
                // @ts-ignore
                const __VLS_72 = __VLS_asFunctionalComponent(__VLS_71, new __VLS_71({
                    key: ((field.key)),
                    label: ((field.label)),
                }));
                const __VLS_73 = __VLS_72({
                    key: ((field.key)),
                    label: ((field.label)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_72));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { label: __VLS_thisSlot } = __VLS_76.slots;
                    __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: ("profile-label") },
                    });
                    (field.label);
                    if (field.hint) {
                        // @ts-ignore
                        /** @type { [typeof InfoTip, ] } */ ;
                        // @ts-ignore
                        const __VLS_77 = __VLS_asFunctionalComponent(InfoTip, new InfoTip({
                            label: ((field.label)),
                            content: ((field.hint)),
                        }));
                        const __VLS_78 = __VLS_77({
                            label: ((field.label)),
                            content: ((field.hint)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_77));
                    }
                }
                if (field.kind === 'secret') {
                    const __VLS_82 = {}.ElInput;
                    /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
                    // @ts-ignore
                    const __VLS_83 = __VLS_asFunctionalComponent(__VLS_82, new __VLS_82({
                        modelValue: ((__VLS_ctx.secretValues[field.key])),
                        type: ((field.multiline ? 'textarea' : 'password')),
                        ...{ class: (({ 'private-key-input': field.multiline })) },
                        rows: ((4)),
                        autocomplete: ("new-password"),
                        'aria-label': ((field.label)),
                        disabled: ((__VLS_ctx.busy || __VLS_ctx.secretCleared[field.key])),
                        placeholder: ((__VLS_ctx.secretCleared[field.key] ? '已选择清除，保存后生效' : __VLS_ctx.secretConfigured(field.key) ? '已配置，留空保留原值' : '未配置，填写后保存')),
                    }));
                    const __VLS_84 = __VLS_83({
                        modelValue: ((__VLS_ctx.secretValues[field.key])),
                        type: ((field.multiline ? 'textarea' : 'password')),
                        ...{ class: (({ 'private-key-input': field.multiline })) },
                        rows: ((4)),
                        autocomplete: ("new-password"),
                        'aria-label': ((field.label)),
                        disabled: ((__VLS_ctx.busy || __VLS_ctx.secretCleared[field.key])),
                        placeholder: ((__VLS_ctx.secretCleared[field.key] ? '已选择清除，保存后生效' : __VLS_ctx.secretConfigured(field.key) ? '已配置，留空保留原值' : '未配置，填写后保存')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_83));
                    if (__VLS_ctx.secretCleared[field.key]) {
                        const __VLS_88 = {}.ElTag;
                        /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                        // @ts-ignore
                        const __VLS_89 = __VLS_asFunctionalComponent(__VLS_88, new __VLS_88({
                            ...{ class: ("credential-clear-tag") },
                            size: ("small"),
                            type: ("warning"),
                        }));
                        const __VLS_90 = __VLS_89({
                            ...{ class: ("credential-clear-tag") },
                            size: ("small"),
                            type: ("warning"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_89));
                        __VLS_93.slots.default;
                        var __VLS_93;
                    }
                }
                else if (field.kind === 'select') {
                    const __VLS_94 = {}.ElSelect;
                    /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                    // @ts-ignore
                    const __VLS_95 = __VLS_asFunctionalComponent(__VLS_94, new __VLS_94({
                        modelValue: ((__VLS_ctx.options[field.key])),
                        disabled: ((__VLS_ctx.busy)),
                        'aria-label': ((field.label)),
                    }));
                    const __VLS_96 = __VLS_95({
                        modelValue: ((__VLS_ctx.options[field.key])),
                        disabled: ((__VLS_ctx.busy)),
                        'aria-label': ((field.label)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_95));
                    for (const [choice] of __VLS_getVForSourceType((field.choices))) {
                        const __VLS_100 = {}.ElOption;
                        /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                        // @ts-ignore
                        const __VLS_101 = __VLS_asFunctionalComponent(__VLS_100, new __VLS_100({
                            key: ((choice)),
                            label: ((field.key === 'tls' ? (choice === 'true' ? '启用' : '关闭') : choice)),
                            value: ((choice)),
                        }));
                        const __VLS_102 = __VLS_101({
                            key: ((choice)),
                            label: ((field.key === 'tls' ? (choice === 'true' ? '启用' : '关闭') : choice)),
                            value: ((choice)),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_101));
                    }
                    __VLS_99.slots.default;
                    var __VLS_99;
                }
                else {
                    const __VLS_106 = {}.ElInput;
                    /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
                    // @ts-ignore
                    const __VLS_107 = __VLS_asFunctionalComponent(__VLS_106, new __VLS_106({
                        modelValue: ((__VLS_ctx.options[field.key])),
                        type: ((field.kind === 'textarea' ? 'textarea' : 'text')),
                        rows: ((3)),
                        'aria-label': ((field.label)),
                    }));
                    const __VLS_108 = __VLS_107({
                        modelValue: ((__VLS_ctx.options[field.key])),
                        type: ((field.kind === 'textarea' ? 'textarea' : 'text')),
                        rows: ((3)),
                        'aria-label': ((field.label)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_107));
                }
                var __VLS_76;
            }
            if (__VLS_ctx.enabled && __VLS_ctx.advancedFields.length) {
                const __VLS_112 = {}.ElCollapse;
                /** @type { [typeof __VLS_components.ElCollapse, typeof __VLS_components.elCollapse, typeof __VLS_components.ElCollapse, typeof __VLS_components.elCollapse, ] } */ ;
                // @ts-ignore
                const __VLS_113 = __VLS_asFunctionalComponent(__VLS_112, new __VLS_112({
                    modelValue: ((__VLS_ctx.connectionSections)),
                    ...{ class: ("connection-settings") },
                }));
                const __VLS_114 = __VLS_113({
                    modelValue: ((__VLS_ctx.connectionSections)),
                    ...{ class: ("connection-settings") },
                }, ...__VLS_functionalComponentArgsRest(__VLS_113));
                const __VLS_118 = {}.ElCollapseItem;
                /** @type { [typeof __VLS_components.ElCollapseItem, typeof __VLS_components.elCollapseItem, typeof __VLS_components.ElCollapseItem, typeof __VLS_components.elCollapseItem, ] } */ ;
                // @ts-ignore
                const __VLS_119 = __VLS_asFunctionalComponent(__VLS_118, new __VLS_118({
                    title: ("高级连接设置"),
                    name: ("connection"),
                }));
                const __VLS_120 = __VLS_119({
                    title: ("高级连接设置"),
                    name: ("connection"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_119));
                for (const [field] of __VLS_getVForSourceType((__VLS_ctx.advancedFields))) {
                    const __VLS_124 = {}.ElFormItem;
                    /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
                    // @ts-ignore
                    const __VLS_125 = __VLS_asFunctionalComponent(__VLS_124, new __VLS_124({
                        key: ((field.key)),
                        label: ((field.label)),
                    }));
                    const __VLS_126 = __VLS_125({
                        key: ((field.key)),
                        label: ((field.label)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_125));
                    __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                    {
                        const { label: __VLS_thisSlot } = __VLS_129.slots;
                        __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: ("profile-label") },
                        });
                        (field.label);
                        // @ts-ignore
                        /** @type { [typeof InfoTip, ] } */ ;
                        // @ts-ignore
                        const __VLS_130 = __VLS_asFunctionalComponent(InfoTip, new InfoTip({
                            label: ((field.label)),
                            content: ("当前地址未明确是否使用加密连接，请按服务端配置选择。"),
                        }));
                        const __VLS_131 = __VLS_130({
                            label: ((field.label)),
                            content: ("当前地址未明确是否使用加密连接，请按服务端配置选择。"),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_130));
                    }
                    const __VLS_135 = {}.ElSelect;
                    /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                    // @ts-ignore
                    const __VLS_136 = __VLS_asFunctionalComponent(__VLS_135, new __VLS_135({
                        modelValue: ((__VLS_ctx.options[field.key])),
                        disabled: ((__VLS_ctx.busy)),
                        'aria-label': ((field.label)),
                    }));
                    const __VLS_137 = __VLS_136({
                        modelValue: ((__VLS_ctx.options[field.key])),
                        disabled: ((__VLS_ctx.busy)),
                        'aria-label': ((field.label)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_136));
                    const __VLS_141 = {}.ElOption;
                    /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                    // @ts-ignore
                    const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
                        label: ("启用"),
                        value: ("true"),
                    }));
                    const __VLS_143 = __VLS_142({
                        label: ("启用"),
                        value: ("true"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_142));
                    const __VLS_147 = {}.ElOption;
                    /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                    // @ts-ignore
                    const __VLS_148 = __VLS_asFunctionalComponent(__VLS_147, new __VLS_147({
                        label: ("关闭"),
                        value: ("false"),
                    }));
                    const __VLS_149 = __VLS_148({
                        label: ("关闭"),
                        value: ("false"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_148));
                    __VLS_140.slots.default;
                    var __VLS_140;
                    var __VLS_129;
                }
                __VLS_123.slots.default;
                var __VLS_123;
                __VLS_117.slots.default;
                var __VLS_117;
            }
            if (__VLS_ctx.enabled && __VLS_ctx.savedSecretFields.length) {
                const __VLS_153 = {}.ElCollapse;
                /** @type { [typeof __VLS_components.ElCollapse, typeof __VLS_components.elCollapse, typeof __VLS_components.ElCollapse, typeof __VLS_components.elCollapse, ] } */ ;
                // @ts-ignore
                const __VLS_154 = __VLS_asFunctionalComponent(__VLS_153, new __VLS_153({
                    modelValue: ((__VLS_ctx.credentialSections)),
                    ...{ class: ("credential-management") },
                }));
                const __VLS_155 = __VLS_154({
                    modelValue: ((__VLS_ctx.credentialSections)),
                    ...{ class: ("credential-management") },
                }, ...__VLS_functionalComponentArgsRest(__VLS_154));
                const __VLS_159 = {}.ElCollapseItem;
                /** @type { [typeof __VLS_components.ElCollapseItem, typeof __VLS_components.elCollapseItem, typeof __VLS_components.ElCollapseItem, typeof __VLS_components.elCollapseItem, ] } */ ;
                // @ts-ignore
                const __VLS_160 = __VLS_asFunctionalComponent(__VLS_159, new __VLS_159({
                    title: ("管理已保存的凭据"),
                    name: ("credentials"),
                }));
                const __VLS_161 = __VLS_160({
                    title: ("管理已保存的凭据"),
                    name: ("credentials"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_160));
                __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
                {
                    const { title: __VLS_thisSlot } = __VLS_164.slots;
                    __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: ("profile-label") },
                    });
                    // @ts-ignore
                    /** @type { [typeof InfoTip, ] } */ ;
                    // @ts-ignore
                    const __VLS_165 = __VLS_asFunctionalComponent(InfoTip, new InfoTip({
                        label: ("管理已保存的凭据"),
                        content: ("仅清除本平台保存的内容。勾选后点击底部“保存”才会生效，取消勾选可保留。"),
                    }));
                    const __VLS_166 = __VLS_165({
                        label: ("管理已保存的凭据"),
                        content: ("仅清除本平台保存的内容。勾选后点击底部“保存”才会生效，取消勾选可保留。"),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_165));
                }
                for (const [field] of __VLS_getVForSourceType((__VLS_ctx.savedSecretFields))) {
                    const __VLS_170 = {}.ElCheckbox;
                    /** @type { [typeof __VLS_components.ElCheckbox, typeof __VLS_components.elCheckbox, typeof __VLS_components.ElCheckbox, typeof __VLS_components.elCheckbox, ] } */ ;
                    // @ts-ignore
                    const __VLS_171 = __VLS_asFunctionalComponent(__VLS_170, new __VLS_170({
                        ...{ 'onChange': {} },
                        key: ((field.key)),
                        modelValue: ((__VLS_ctx.secretCleared[field.key])),
                        disabled: ((__VLS_ctx.busy)),
                    }));
                    const __VLS_172 = __VLS_171({
                        ...{ 'onChange': {} },
                        key: ((field.key)),
                        modelValue: ((__VLS_ctx.secretCleared[field.key])),
                        disabled: ((__VLS_ctx.busy)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_171));
                    let __VLS_176;
                    const __VLS_177 = {
                        onChange: (...[$event]) => {
                            if (!((__VLS_ctx.profile)))
                                return;
                            if (!((__VLS_ctx.editable)))
                                return;
                            if (!((__VLS_ctx.enabled && __VLS_ctx.savedSecretFields.length)))
                                return;
                            __VLS_ctx.secretValues[field.key] = '';
                        }
                    };
                    let __VLS_173;
                    let __VLS_174;
                    (field.label);
                    __VLS_175.slots.default;
                    var __VLS_175;
                }
                var __VLS_164;
                __VLS_158.slots.default;
                var __VLS_158;
            }
        }
        else {
            __VLS_elementAsFunction(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                ...{ class: ("profile-summary") },
            });
            if (__VLS_ctx.profile.enabled && __VLS_ctx.automaticTls !== undefined) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                (__VLS_ctx.automaticConnection);
            }
            for (const [field] of __VLS_getVForSourceType(((__VLS_ctx.profile.enabled ? __VLS_ctx.fields : [])))) {
                (field.key);
                __VLS_elementAsFunction(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({});
                (field.label);
                __VLS_elementAsFunction(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                (field.kind === 'secret' ? (__VLS_ctx.secretConfigured(field.key) ? '已配置' : '未配置') : field.key === 'tls' ? (__VLS_ctx.options[field.key] === 'true' ? '启用' : '关闭') : __VLS_ctx.options[field.key] || '未设置');
            }
        }
        if (!__VLS_ctx.editable) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: ("profile-title") },
            });
            __VLS_elementAsFunction(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({});
            if (__VLS_ctx.profile.enabled) {
                const __VLS_178 = {}.ElButton;
                /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
                // @ts-ignore
                const __VLS_179 = __VLS_asFunctionalComponent(__VLS_178, new __VLS_178({
                    ...{ 'onClick': {} },
                    loading: ((__VLS_ctx.testing)),
                    disabled: ((__VLS_ctx.busy || __VLS_ctx.profile.keyConfigured === false)),
                }));
                const __VLS_180 = __VLS_179({
                    ...{ 'onClick': {} },
                    loading: ((__VLS_ctx.testing)),
                    disabled: ((__VLS_ctx.busy || __VLS_ctx.profile.keyConfigured === false)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_179));
                let __VLS_184;
                const __VLS_185 = {
                    onClick: (__VLS_ctx.test)
                };
                let __VLS_181;
                let __VLS_182;
                __VLS_183.slots.default;
                var __VLS_183;
            }
            if (__VLS_ctx.checkedAt) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                    ...{ class: ("profile-help") },
                });
                (__VLS_ctx.displayTime(__VLS_ctx.checkedAt));
            }
            if (!__VLS_ctx.checks.length) {
                const __VLS_186 = {}.ElEmpty;
                /** @type { [typeof __VLS_components.ElEmpty, typeof __VLS_components.elEmpty, ] } */ ;
                // @ts-ignore
                const __VLS_187 = __VLS_asFunctionalComponent(__VLS_186, new __VLS_186({
                    description: ("暂无检查结果"),
                    imageSize: ((56)),
                }));
                const __VLS_188 = __VLS_187({
                    description: ("暂无检查结果"),
                    imageSize: ((56)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_187));
            }
            for (const [check, index] of __VLS_getVForSourceType((__VLS_ctx.checks))) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    key: ((index)),
                    ...{ class: ("probe-result") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: ("probe-heading") },
                });
                __VLS_elementAsFunction(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                (check.name);
                const __VLS_192 = {}.ElTag;
                /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
                // @ts-ignore
                const __VLS_193 = __VLS_asFunctionalComponent(__VLS_192, new __VLS_192({
                    type: ((check.passed ? 'success' : 'danger')),
                    size: ("small"),
                }));
                const __VLS_194 = __VLS_193({
                    type: ((check.passed ? 'success' : 'danger')),
                    size: ("small"),
                }, ...__VLS_functionalComponentArgsRest(__VLS_193));
                (check.passed ? '通过' : '失败');
                __VLS_197.slots.default;
                var __VLS_197;
                __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
                (check.category);
                (check.target);
                __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
                (check.message || '暂无详细信息');
                __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
                (check.durationMillis);
            }
        }
    }
    ['inspection-profile-panel', 'profile-title', 'profile-heading', 'profile-endpoint', 'profile-fields', 'profile-label', 'profile-connection', 'profile-label', 'private-key-input', 'credential-clear-tag', 'connection-settings', 'profile-label', 'credential-management', 'profile-label', 'profile-summary', 'profile-title', 'profile-help', 'probe-result', 'probe-heading',];
    var __VLS_slots;
    var $slots;
    let __VLS_inheritedAttrs;
    var $attrs;
    const __VLS_refs = {};
    var $refs;
    var $el;
    return {
        attrs: {},
        slots: __VLS_slots,
        refs: $refs,
        rootEl: $el,
    };
}
;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            InfoTip: InfoTip,
            profile: profile,
            enabled: enabled,
            options: options,
            secretValues: secretValues,
            secretCleared: secretCleared,
            credentialSections: credentialSections,
            connectionSections: connectionSections,
            checks: checks,
            checkedAt: checkedAt,
            loading: loading,
            testing: testing,
            error: error,
            notice: notice,
            fields: fields,
            basicFields: basicFields,
            advancedFields: advancedFields,
            savedSecretFields: savedSecretFields,
            automaticTls: automaticTls,
            automaticConnection: automaticConnection,
            busy: busy,
            load: load,
            test: test,
            secretConfigured: secretConfigured,
            displayTime: displayTime,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {
            ...__VLS_exposed,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    __typeEl: {},
});
; /* PartiallyEnd: #4569/main.vue */
