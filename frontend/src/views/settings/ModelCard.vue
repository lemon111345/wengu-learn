<script setup lang="ts">
// AI 模型：模型名、状态、最近一次调用（ui.md 5.10、第 7 节，决策 032）。
// 状态是后端记下的最近一次结果，不真实调用模型；不显示服务地址和 Key
import { computed } from 'vue'
import type { LlmStatus } from '@/api/llm'
import { timeAgo } from '@/utils/date'
import SettingsCard from './SettingsCard.vue'

/** 取不到状态时为 null */
const props = defineProps<{ status: LlmStatus | null }>()

const lastCall = computed(() => props.status?.lastCall ?? null)
const state = computed(() => {
  if (!props.status) {
    return { tone: 'unknown', text: '未知' }
  }
  if (!lastCall.value) {
    return { tone: 'unknown', text: '还没有调用' }
  }
  return lastCall.value.ok ? { tone: 'ok', text: '正常' } : { tone: 'error', text: '出错' }
})
</script>

<template>
  <SettingsCard title="AI 模型">
    <div class="row">
      <span class="model">{{ status?.model ?? '' }}</span>
      <span class="state">
        <span class="dot" :class="state.tone" aria-hidden="true" />
        {{ state.text }}
      </span>
    </div>
    <p v-if="lastCall && !lastCall.ok" class="reason" role="status">{{ lastCall.message }}</p>
    <p class="wg-muted detail">
      <template v-if="!status">暂时取不到模型状态，刷新页面再试</template>
      <template v-else-if="lastCall">
        最近一次调用：{{ timeAgo(lastCall.at) }}，{{ lastCall.ok ? '成功' : '失败' }}
      </template>
      <template v-else>还没有调用记录，开始学习或导入资料后这里会显示结果</template>
    </p>
  </SettingsCard>
</template>

<style scoped>
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.model {
  font-size: 16px;
  word-break: break-all;
}

.state {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  color: var(--wg-text-muted);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.ok {
  background: var(--wg-success);
}

.dot.error {
  background: var(--wg-warn);
}

.dot.unknown {
  background: var(--wg-level-new);
}

.reason {
  color: var(--wg-warn);
  font-size: 14px;
  line-height: 1.6;
}

.detail {
  font-size: 14px;
}
</style>
