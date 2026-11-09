<script setup lang="ts">
// 问答记录（ui.md 5.12，决策 043）：左边是对话列表和搜索，右边是选中的对话，点开后还能继续问
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError } from '@/api/http'
import { fetchFollowUps, type FollowUpSummary } from '@/api/followUps'
import PageHeader from '@/components/PageHeader.vue'
import ConversationList from './ConversationList.vue'
import ConversationPane from './ConversationPane.vue'

/** 学习会话的 id；没有选中时为空 */
defineProps<{ id?: string }>()

const SEARCH_DELAY_MS = 300

const items = ref<FollowUpSummary[] | null>(null)
const loadError = ref<string | null>(null)
const keyword = ref('')
// 连续搜索时只采用最后一次请求的结果，避免先发的请求后返回、把新结果盖掉
let loadSeq = 0
let timer: ReturnType<typeof setTimeout> | undefined

async function load() {
  const seq = ++loadSeq
  try {
    const result = await fetchFollowUps(keyword.value.trim())
    if (seq === loadSeq) {
      items.value = result
      loadError.value = null
    }
  } catch (e) {
    if (seq === loadSeq) {
      loadError.value = e instanceof ApiError ? e.message : '对话列表没有加载出来，请重试'
    }
  }
}

watch(keyword, () => {
  clearTimeout(timer)
  timer = setTimeout(load, SEARCH_DELAY_MS)
})

onMounted(load)
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <PageHeader title="问答记录" />
  <div class="wg-page-body questions" :class="{ selected: !!id }">
    <ConversationList
      v-model:keyword="keyword"
      class="list"
      :items="items"
      :load-error="loadError"
      :selected-id="id ? Number(id) : null"
      @retry="load"
    />
    <div class="detail">
      <!-- 换一条对话时重新创建：离开时断开上一条的流式连接，后端照样生成完、存进库 -->
      <ConversationPane v-if="id" :key="id" :study-session-id="Number(id)" @asked="load" />
      <p v-else class="placeholder">选一条对话，接着看或接着问</p>
    </div>
  </div>
</template>

<style scoped>
.questions {
  display: flex;
  align-items: flex-start;
  gap: 32px;
}

.list {
  flex: 0 0 340px;
}

.detail {
  flex: 1 1 auto;
  min-width: 0;
  max-width: 760px;
}

.placeholder {
  padding-top: 8px;
  color: var(--wg-text-muted);
  font-size: 15px;
}

/* 窄屏：列表和对话分成两屏，选中后只显示对话 */
@media (max-width: 900px) {
  .questions {
    flex-direction: column;
    align-items: stretch;
  }

  .list {
    flex: none;
  }

  .questions.selected .list,
  .questions:not(.selected) .detail {
    display: none;
  }
}
</style>
