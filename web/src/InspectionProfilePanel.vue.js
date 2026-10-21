import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { inspectionProfileFields, profileOptions, profileSecrets, validateProfileOptions } from './inspectionProfileFields';
const props = defineProps();
const emit = defineEmits();
const profile = ref(null);
const enabled = ref(false);
const options = ref({});
const secretValues = reactive({});
const secretCleared = reactive({});
const checks = ref([]);
const checkedAt = ref(null);
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const error = ref('');
const notice = ref('');
const fields = computed(() => inspectionProfileFields(props.asset.type));
const busy = computed(() => loading.value || saving.value || testing.value);
let generation = 0;
let controller = new AbortController();
function clearSecrets() {
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
    options.value = profileOptions(props.asset.type, value.options || {});
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
        const value = await request(id);
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
async function save() {
    if (busy.value || !profile.value)
        return;
    const secrets = profileSecrets(secretValues, secretCleared);
    error.value = enabled.value && !profile.value.keyConfigured ? '系统尚未配置凭据加密主密钥，请联系管理员配置后再启用深度巡检。' : validateProfileOptions(options.value, secrets);
    notice.value = '';
    if (error.value)
        return;
    const current = generation;
    const id = props.asset.id;
    saving.value = true;
    try {
        await request(id, '', 'PUT', { enabled: enabled.value, options: { ...options.value }, secrets });
        if (current !== generation)
            return;
        clearSecrets();
        notice.value = '配置已保存，关联计划将在下次同步后生效。';
        emit('saved');
        const value = await request(id);
        if (current === generation)
            applyProfile(value);
    }
    catch (cause) {
        if (current === generation)
            error.value = cause instanceof Error ? cause.message : '保存配置失败，请重试。';
    }
    finally {
        if (current === generation)
            saving.value = false;
    }
}
async function test() {
    if (busy.value || !profile.value?.enabled || !profile.value.keyConfigured)
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
watch(() => [props.asset.id, props.asset.type, props.asset.endpoint], () => {
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
    ['profile-title', 'profile-hint', 'profile-endpoint', 'profile-actions', 'probe-result', 'probe-result',];
    // CSS variable injection
    // CSS variable injection end
    __VLS_elementAsFunction(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
        ...{ class: ("inspection-profile-panel") },
        'aria-label': ("深度巡检配置"),
    });
    __VLS_asFunctionalDirective(__VLS_directives.vLoading)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.loading) }, null, null);
    __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: ("profile-title") },
    });
    __VLS_elementAsFunction(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({});
    if (__VLS_ctx.profile) {
        const __VLS_0 = {}.ElTag;
        /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            size: ("small"),
            type: ("info"),
        }));
        const __VLS_2 = __VLS_1({
            size: ("small"),
            type: ("info"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
        (__VLS_ctx.profile.revision);
        __VLS_5.slots.default;
        var __VLS_5;
    }
    __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: ("profile-help") },
    });
    __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: ("profile-endpoint") },
    });
    (__VLS_ctx.asset.endpoint);
    if (__VLS_ctx.error) {
        const __VLS_6 = {}.ElAlert;
        /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
        // @ts-ignore
        const __VLS_7 = __VLS_asFunctionalComponent(__VLS_6, new __VLS_6({
            title: ((__VLS_ctx.error)),
            type: ("error"),
            showIcon: (true),
            closable: ((false)),
            role: ("alert"),
        }));
        const __VLS_8 = __VLS_7({
            title: ((__VLS_ctx.error)),
            type: ("error"),
            showIcon: (true),
            closable: ((false)),
            role: ("alert"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_7));
    }
    if (__VLS_ctx.notice) {
        const __VLS_12 = {}.ElAlert;
        /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
            title: ((__VLS_ctx.notice)),
            type: ("success"),
            showIcon: (true),
            closable: ((false)),
        }));
        const __VLS_14 = __VLS_13({
            title: ((__VLS_ctx.notice)),
            type: ("success"),
            showIcon: (true),
            closable: ((false)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    }
    if (!__VLS_ctx.profile && !__VLS_ctx.loading) {
        const __VLS_18 = {}.ElButton;
        /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
        // @ts-ignore
        const __VLS_19 = __VLS_asFunctionalComponent(__VLS_18, new __VLS_18({
            ...{ 'onClick': {} },
        }));
        const __VLS_20 = __VLS_19({
            ...{ 'onClick': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_19));
        let __VLS_24;
        const __VLS_25 = {
            onClick: (__VLS_ctx.load)
        };
        let __VLS_21;
        let __VLS_22;
        __VLS_23.slots.default;
        var __VLS_23;
    }
    if (__VLS_ctx.profile) {
        if (!__VLS_ctx.profile.keyConfigured) {
            const __VLS_26 = {}.ElAlert;
            /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
            // @ts-ignore
            const __VLS_27 = __VLS_asFunctionalComponent(__VLS_26, new __VLS_26({
                title: ("系统尚未配置凭据加密主密钥，请联系管理员配置后再启用深度巡检。"),
                type: ("warning"),
                showIcon: (true),
                closable: ((false)),
            }));
            const __VLS_28 = __VLS_27({
                title: ("系统尚未配置凭据加密主密钥，请联系管理员配置后再启用深度巡检。"),
                type: ("warning"),
                showIcon: (true),
                closable: ((false)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_27));
        }
        const __VLS_32 = {}.ElForm;
        /** @type { [typeof __VLS_components.ElForm, typeof __VLS_components.elForm, typeof __VLS_components.ElForm, typeof __VLS_components.elForm, ] } */ ;
        // @ts-ignore
        const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
            ...{ 'onSubmit': {} },
            labelPosition: ("top"),
            disabled: ((__VLS_ctx.busy)),
            autocomplete: ("off"),
        }));
        const __VLS_34 = __VLS_33({
            ...{ 'onSubmit': {} },
            labelPosition: ("top"),
            disabled: ((__VLS_ctx.busy)),
            autocomplete: ("off"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_33));
        let __VLS_38;
        const __VLS_39 = {
            onSubmit: (__VLS_ctx.save)
        };
        let __VLS_35;
        let __VLS_36;
        const __VLS_40 = {}.ElFormItem;
        /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
        // @ts-ignore
        const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
            label: ("启用深度巡检"),
        }));
        const __VLS_42 = __VLS_41({
            label: ("启用深度巡检"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_41));
        const __VLS_46 = {}.ElSwitch;
        /** @type { [typeof __VLS_components.ElSwitch, typeof __VLS_components.elSwitch, ] } */ ;
        // @ts-ignore
        const __VLS_47 = __VLS_asFunctionalComponent(__VLS_46, new __VLS_46({
            modelValue: ((__VLS_ctx.enabled)),
            disabled: ((!__VLS_ctx.profile.keyConfigured && !__VLS_ctx.enabled)),
            'aria-label': ("启用深度巡检"),
        }));
        const __VLS_48 = __VLS_47({
            modelValue: ((__VLS_ctx.enabled)),
            disabled: ((!__VLS_ctx.profile.keyConfigured && !__VLS_ctx.enabled)),
            'aria-label': ("启用深度巡检"),
        }, ...__VLS_functionalComponentArgsRest(__VLS_47));
        __VLS_45.slots.default;
        var __VLS_45;
        for (const [field] of __VLS_getVForSourceType((__VLS_ctx.fields))) {
            const __VLS_52 = {}.ElFormItem;
            /** @type { [typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, typeof __VLS_components.ElFormItem, typeof __VLS_components.elFormItem, ] } */ ;
            // @ts-ignore
            const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
                key: ((field.key)),
                label: ((field.label)),
            }));
            const __VLS_54 = __VLS_53({
                key: ((field.key)),
                label: ((field.label)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_53));
            if (field.kind === 'secret') {
                const __VLS_58 = {}.ElInput;
                /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
                // @ts-ignore
                const __VLS_59 = __VLS_asFunctionalComponent(__VLS_58, new __VLS_58({
                    modelValue: ((__VLS_ctx.secretValues[field.key])),
                    type: ((field.multiline ? 'textarea' : 'password')),
                    ...{ class: (({ 'private-key-input': field.multiline })) },
                    rows: ((4)),
                    autocomplete: ("new-password"),
                    'aria-label': ((field.label)),
                    disabled: ((__VLS_ctx.secretCleared[field.key])),
                    placeholder: ((__VLS_ctx.secretConfigured(field.key) ? '已配置，留空保留原值' : '未配置，填写后保存')),
                }));
                const __VLS_60 = __VLS_59({
                    modelValue: ((__VLS_ctx.secretValues[field.key])),
                    type: ((field.multiline ? 'textarea' : 'password')),
                    ...{ class: (({ 'private-key-input': field.multiline })) },
                    rows: ((4)),
                    autocomplete: ("new-password"),
                    'aria-label': ((field.label)),
                    disabled: ((__VLS_ctx.secretCleared[field.key])),
                    placeholder: ((__VLS_ctx.secretConfigured(field.key) ? '已配置，留空保留原值' : '未配置，填写后保存')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_59));
                const __VLS_64 = {}.ElCheckbox;
                /** @type { [typeof __VLS_components.ElCheckbox, typeof __VLS_components.elCheckbox, typeof __VLS_components.ElCheckbox, typeof __VLS_components.elCheckbox, ] } */ ;
                // @ts-ignore
                const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
                    ...{ 'onChange': {} },
                    modelValue: ((__VLS_ctx.secretCleared[field.key])),
                    disabled: ((!__VLS_ctx.secretConfigured(field.key))),
                }));
                const __VLS_66 = __VLS_65({
                    ...{ 'onChange': {} },
                    modelValue: ((__VLS_ctx.secretCleared[field.key])),
                    disabled: ((!__VLS_ctx.secretConfigured(field.key))),
                }, ...__VLS_functionalComponentArgsRest(__VLS_65));
                let __VLS_70;
                const __VLS_71 = {
                    onChange: (...[$event]) => {
                        if (!((__VLS_ctx.profile)))
                            return;
                        if (!((field.kind === 'secret')))
                            return;
                        __VLS_ctx.secretValues[field.key] = '';
                    }
                };
                let __VLS_67;
                let __VLS_68;
                (field.label);
                __VLS_69.slots.default;
                var __VLS_69;
            }
            else if (field.kind === 'select') {
                const __VLS_72 = {}.ElSelect;
                /** @type { [typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, typeof __VLS_components.ElSelect, typeof __VLS_components.elSelect, ] } */ ;
                // @ts-ignore
                const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
                    modelValue: ((__VLS_ctx.options[field.key])),
                    'aria-label': ((field.label)),
                }));
                const __VLS_74 = __VLS_73({
                    modelValue: ((__VLS_ctx.options[field.key])),
                    'aria-label': ((field.label)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_73));
                for (const [choice] of __VLS_getVForSourceType((field.choices))) {
                    const __VLS_78 = {}.ElOption;
                    /** @type { [typeof __VLS_components.ElOption, typeof __VLS_components.elOption, ] } */ ;
                    // @ts-ignore
                    const __VLS_79 = __VLS_asFunctionalComponent(__VLS_78, new __VLS_78({
                        key: ((choice)),
                        label: ((field.key === 'tls' ? (choice === 'true' ? '启用' : '关闭') : choice)),
                        value: ((choice)),
                    }));
                    const __VLS_80 = __VLS_79({
                        key: ((choice)),
                        label: ((field.key === 'tls' ? (choice === 'true' ? '启用' : '关闭') : choice)),
                        value: ((choice)),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_79));
                }
                __VLS_77.slots.default;
                var __VLS_77;
            }
            else {
                const __VLS_84 = {}.ElInput;
                /** @type { [typeof __VLS_components.ElInput, typeof __VLS_components.elInput, ] } */ ;
                // @ts-ignore
                const __VLS_85 = __VLS_asFunctionalComponent(__VLS_84, new __VLS_84({
                    modelValue: ((__VLS_ctx.options[field.key])),
                    type: ((field.kind === 'textarea' ? 'textarea' : 'text')),
                    rows: ((3)),
                    'aria-label': ((field.label)),
                }));
                const __VLS_86 = __VLS_85({
                    modelValue: ((__VLS_ctx.options[field.key])),
                    type: ((field.kind === 'textarea' ? 'textarea' : 'text')),
                    rows: ((3)),
                    'aria-label': ((field.label)),
                }, ...__VLS_functionalComponentArgsRest(__VLS_85));
            }
            if (field.hint) {
                __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                    ...{ class: ("profile-hint") },
                });
                (field.hint);
            }
            __VLS_57.slots.default;
            var __VLS_57;
        }
        __VLS_elementAsFunction(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: ("profile-actions") },
        });
        const __VLS_90 = {}.ElButton;
        /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
        // @ts-ignore
        const __VLS_91 = __VLS_asFunctionalComponent(__VLS_90, new __VLS_90({
            ...{ 'onClick': {} },
            type: ("primary"),
            loading: ((__VLS_ctx.saving)),
            disabled: ((__VLS_ctx.busy || (__VLS_ctx.enabled && !__VLS_ctx.profile.keyConfigured))),
        }));
        const __VLS_92 = __VLS_91({
            ...{ 'onClick': {} },
            type: ("primary"),
            loading: ((__VLS_ctx.saving)),
            disabled: ((__VLS_ctx.busy || (__VLS_ctx.enabled && !__VLS_ctx.profile.keyConfigured))),
        }, ...__VLS_functionalComponentArgsRest(__VLS_91));
        let __VLS_96;
        const __VLS_97 = {
            onClick: (__VLS_ctx.save)
        };
        let __VLS_93;
        let __VLS_94;
        __VLS_95.slots.default;
        var __VLS_95;
        const __VLS_98 = {}.ElButton;
        /** @type { [typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ] } */ ;
        // @ts-ignore
        const __VLS_99 = __VLS_asFunctionalComponent(__VLS_98, new __VLS_98({
            ...{ 'onClick': {} },
            loading: ((__VLS_ctx.testing)),
            disabled: ((__VLS_ctx.busy || !__VLS_ctx.profile.enabled || !__VLS_ctx.profile.keyConfigured)),
        }));
        const __VLS_100 = __VLS_99({
            ...{ 'onClick': {} },
            loading: ((__VLS_ctx.testing)),
            disabled: ((__VLS_ctx.busy || !__VLS_ctx.profile.enabled || !__VLS_ctx.profile.keyConfigured)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_99));
        let __VLS_104;
        const __VLS_105 = {
            onClick: (__VLS_ctx.test)
        };
        let __VLS_101;
        let __VLS_102;
        __VLS_103.slots.default;
        var __VLS_103;
        __VLS_37.slots.default;
        var __VLS_37;
        const __VLS_106 = {}.ElAlert;
        /** @type { [typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, typeof __VLS_components.ElAlert, typeof __VLS_components.elAlert, ] } */ ;
        // @ts-ignore
        const __VLS_107 = __VLS_asFunctionalComponent(__VLS_106, new __VLS_106({
            type: ("info"),
            showIcon: (true),
            closable: ((false)),
        }));
        const __VLS_108 = __VLS_107({
            type: ("info"),
            showIcon: (true),
            closable: ((false)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_107));
        __VLS_elementAsFunction(__VLS_intrinsicElements.template, __VLS_intrinsicElements.template)({});
        {
            const { title: __VLS_thisSlot } = __VLS_111.slots;
        }
        __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
        var __VLS_111;
        __VLS_elementAsFunction(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({});
        if (__VLS_ctx.checkedAt) {
            __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                ...{ class: ("profile-help") },
            });
            (__VLS_ctx.displayTime(__VLS_ctx.checkedAt));
        }
        if (!__VLS_ctx.checks.length) {
            const __VLS_112 = {}.ElEmpty;
            /** @type { [typeof __VLS_components.ElEmpty, typeof __VLS_components.elEmpty, ] } */ ;
            // @ts-ignore
            const __VLS_113 = __VLS_asFunctionalComponent(__VLS_112, new __VLS_112({
                description: ("暂无深度检查结果"),
                imageSize: ((56)),
            }));
            const __VLS_114 = __VLS_113({
                description: ("暂无深度检查结果"),
                imageSize: ((56)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_113));
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
            const __VLS_118 = {}.ElTag;
            /** @type { [typeof __VLS_components.ElTag, typeof __VLS_components.elTag, typeof __VLS_components.ElTag, typeof __VLS_components.elTag, ] } */ ;
            // @ts-ignore
            const __VLS_119 = __VLS_asFunctionalComponent(__VLS_118, new __VLS_118({
                type: ((check.passed ? 'success' : 'danger')),
                size: ("small"),
            }));
            const __VLS_120 = __VLS_119({
                type: ((check.passed ? 'success' : 'danger')),
                size: ("small"),
            }, ...__VLS_functionalComponentArgsRest(__VLS_119));
            (check.passed ? '通过' : '失败');
            __VLS_123.slots.default;
            var __VLS_123;
            __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
            (check.category);
            (check.target);
            __VLS_elementAsFunction(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
            (check.message || '暂无详细信息');
            __VLS_elementAsFunction(__VLS_intrinsicElements.small, __VLS_intrinsicElements.small)({});
            (check.durationMillis);
        }
    }
    ['inspection-profile-panel', 'profile-title', 'profile-help', 'profile-endpoint', 'private-key-input', 'profile-hint', 'profile-actions', 'profile-help', 'probe-result', 'probe-heading',];
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
            profile: profile,
            enabled: enabled,
            options: options,
            secretValues: secretValues,
            secretCleared: secretCleared,
            checks: checks,
            checkedAt: checkedAt,
            loading: loading,
            saving: saving,
            testing: testing,
            error: error,
            notice: notice,
            fields: fields,
            busy: busy,
            load: load,
            save: save,
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
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    __typeEl: {},
});
; /* PartiallyEnd: #4569/main.vue */
