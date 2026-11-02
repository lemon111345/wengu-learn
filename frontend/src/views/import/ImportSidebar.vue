<script setup lang="ts">
// 导入页的右侧栏（ui.md 5.7）：导入之后会发生什么；最近导入（名称和状态，点进去看进度或确认）
import { onMounted, ref } from 'vue'
import { fetchDrafts, type DraftStatus, type DraftSummary } from '@/api/drafts'

const drafts = ref<DraftSummary[] | null>(null)

onMounted(async () => {
  try {
    drafts.value = await fetchDrafts()
  } catch {
    drafts.value = []
  }
})

const statusLabels: Record<DraftStatus, string> = {
  CONFIRMED: '已确认',
  READY: '等你确认',
  RUNNING: '抽取中',
  FAILED: '失败',
}
</script>

<template>
  <aside class="side">
    <section class="panel">
      <h2>导入之后会发生什么</h2>
      <p>AI 会把题目拆成知识点：问法不同但考同一件事的题合成一个，大题按追问拆成几个。每个知识点写成一个问题，附一段要点。</p>
      <p>拆完后你会逐条看一遍，确认了才会加入每日计划。</p>
    </section>
    <section class="panel">
      <h2>最近导入</h2>
      <el-skeleton v-if="drafts === null" :rows="2" animated />
      <p v-else-if="drafts.length === 0" class="empty">还没有导入过</p>
      <ul v-else class="recent">
        <li v-for="d in drafts" :key="d.id">
          <RouterLink :to="`/drafts/${d.id}`" class="name">{{ d.sourceName ?? '未命名的资料' }}</RouterLink>
          <span class="status" :class="d.status.toLowerCase()">{{ statusLabels[d.status] }}</span>
        </li>
      </ul>
    </section>
  </aside>
</template>

<style scoped>
.side {
  flex: 1 1 320px;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 22px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

h2 {
  font-size: 16px;
}

.panel p {
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.75;
}

.recent {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.recent li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 36px;
  font-size: 14px;
}

.name {
  min-width: 0;
  overflow: hidden;
  color: var(--wg-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status {
  flex: none;
  color: var(--wg-text-muted);
}

/* 需要用户处理的两种状态突出显示 */
.status.ready {
  color: var(--wg-primary);
  font-weight: 700;
}

.status.failed {
  color: var(--wg-warn);
}

.empty {
  color: var(--wg-text-muted);
  font-size: 14px;
}

@media (max-width: 980px) {
  .side {
    max-width: none;
  }
}
</style>
