<script setup lang="ts">
import { CircleHelp } from '@lucide/vue'

defineProps<{ content: string; label: string }>()
</script>

<template>
  <el-tooltip :content="content" :trigger="['hover', 'focus']" :show-after="150" :hide-after="0" placement="top" :popper-style="{ maxWidth: '320px', lineHeight: '1.6' }">
    <span class="info-tip" role="button" tabindex="0" :aria-label="`${label}说明`" @click.stop @keydown.enter.stop.prevent @keydown.space.stop.prevent>
      <CircleHelp :size="15" aria-hidden="true" />
    </span>
  </el-tooltip>
</template>

<style scoped>
.info-tip { display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; color: var(--el-text-color-secondary); vertical-align: middle; cursor: help; border-radius: 50%; }
.info-tip:hover { color: var(--el-color-primary); }
.info-tip:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
</style>
