<script setup lang="ts">
// 待处理的提示条（ui.md 5.2、第 7 节）：等你确认的导入结果、抽取失败的导入。
// 候选知识点由 #31 加进草稿列表后，在这里一起显示
import { computed } from 'vue'
import type { DraftSummary } from '@/api/drafts'

const props = defineProps<{ drafts: DraftSummary[] }>()

// 只有一份时直接去那份草稿，多份时去导入页的“最近导入”列表
function target(drafts: DraftSummary[]) {
  return drafts.length === 1 ? `/drafts/${drafts[0].id}` : '/import'
}

interface Notice {
  key: string
  text: string
  action: string
  to: string
}

const notices = computed(() => {
  const ready = props.drafts.filter((d) => d.status === 'READY')
  const failed = props.drafts.filter((d) => d.status === 'FAILED')
  const list: Notice[] = []
  if (ready.length > 0) {
    const items = ready.reduce((sum, d) => sum + d.itemCount, 0)
    list.push({
      key: 'ready',
      text: `有 ${ready.length} 份导入结果等你确认，共 ${items} 个知识点`,
      action: '去确认',
      to: target(ready),
    })
  }
  if (failed.length > 0) {
    list.push({ key: 'failed', text: `有 ${failed.length} 份导入抽取失败，可以重试`, action: '去重试', to: target(failed) })
  }
  return list
})
</script>

<template>
  <section v-for="notice in notices" :key="notice.key" class="banner">
    <div class="message">
      <span class="dot" />
      <span>{{ notice.text }}</span>
    </div>
    <RouterLink :to="notice.to" class="action">{{ notice.action }}</RouterLink>
  </section>
</template>

<style scoped>
.banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 20px;
  padding: 8px 22px;
  border-radius: 12px;
  background: var(--wg-warn-soft);
}

.message {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 15px;
}

.dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--wg-warn);
}

.action {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--wg-warn);
  font-size: 15px;
  font-weight: 700;
}

.action:hover {
  color: var(--wg-warn);
  text-decoration: underline;
}
</style>
