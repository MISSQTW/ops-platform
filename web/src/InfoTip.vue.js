import { CircleHelp } from '@lucide/vue';
const __VLS_props = defineProps(); /* PartiallyEnd: #3632/scriptSetup.vue */
function __VLS_template() {
    const __VLS_ctx = {};
    let __VLS_components;
    let __VLS_directives;
    ['info-tip', 'info-tip',];
    // CSS variable injection
    // CSS variable injection end
    const __VLS_0 = {}.ElTooltip;
    /** @type { [typeof __VLS_components.ElTooltip, typeof __VLS_components.elTooltip, typeof __VLS_components.ElTooltip, typeof __VLS_components.elTooltip, ] } */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        content: ((__VLS_ctx.content)),
        trigger: ((['hover', 'focus'])),
        showAfter: ((150)),
        hideAfter: ((0)),
        placement: ("top"),
        popperStyle: (({ maxWidth: '320px', lineHeight: '1.6' })),
    }));
    const __VLS_2 = __VLS_1({
        content: ((__VLS_ctx.content)),
        trigger: ((['hover', 'focus'])),
        showAfter: ((150)),
        hideAfter: ((0)),
        placement: ("top"),
        popperStyle: (({ maxWidth: '320px', lineHeight: '1.6' })),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    var __VLS_6 = {};
    __VLS_elementAsFunction(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ onClick: () => { } },
        ...{ onKeydown: () => { } },
        ...{ onKeydown: () => { } },
        ...{ class: ("info-tip") },
        role: ("button"),
        tabindex: ("0"),
        'aria-label': ((`${__VLS_ctx.label}说明`)),
    });
    const __VLS_7 = {}.CircleHelp;
    /** @type { [typeof __VLS_components.CircleHelp, ] } */ ;
    // @ts-ignore
    const __VLS_8 = __VLS_asFunctionalComponent(__VLS_7, new __VLS_7({
        size: ((15)),
        'aria-hidden': ("true"),
    }));
    const __VLS_9 = __VLS_8({
        size: ((15)),
        'aria-hidden': ("true"),
    }, ...__VLS_functionalComponentArgsRest(__VLS_8));
    __VLS_5.slots.default;
    var __VLS_5;
    ['info-tip',];
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
            CircleHelp: CircleHelp,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    __typeEl: {},
});
; /* PartiallyEnd: #4569/main.vue */
