<script setup lang="ts">
// 面试参考答法的灰底块（ui.md 5.2、5.9、5.11，#111）。回顾页已经有整个会话，直接传 answer；
// 今天页和知识库只有会话 id，挂上时才去取
import { onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import MessageContent from '@/views/study/MessageContent.vue'
import { loadReferenceAnswer } from './referenceAnswer'

const props = withDefaults(
  defineProps<{
    sessionId: number
    /** 已经有的答法；不传时按 sessionId 去取 */
    answer?: string | null
    /** 是否显示“看完整对话”；回顾页本身就是完整对话，不显示 */
    showLink?: boolean
  }>(),
  { answer: undefined, showLink: true },
)

const loaded = ref<string | null | undefined>(props.answer)
const loadError = ref<string | null>(null)

async function load() {
  loadError.value = null
  try {
    loaded.value = await loadReferenceAnswer(props.sessionId)
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '答法没有加载出来，请重试'
  }
}

onMounted(() => {
  if (loaded.value === undefined) {
    void load()
  }
})
</script>

<template>
  <div class="reference-answer" aria-live="polite">
    <p v-if="loadError" class="muted">
      {{ loadError }}
      <button type="button" class="link" @click="load">重试</button>
    </p>
    <el-skeleton v-else-if="loaded === undefined" :rows="2" animated aria-busy="true" />
    <MessageContent v-else-if="loaded" class="content" :content="loaded" formatted />
    <p v-else class="muted">这次的收尾里没有单独的参考答法</p>
    <RouterLink v-if="showLink" class="full" :to="`/history/${sessionId}`">看完整对话 →</RouterLink>
  </div>
</template>

<style scoped>
.reference-answer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 18px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
}

/* 比对话里的正文小一号：这里是查阅，不是在学。加上父类提高优先级，盖过 MessageContent 自己的字号 */
.reference-answer .content {
  font-size: 15px;
  line-height: 1.8;
}

.muted {
  color: var(--wg-text-muted);
  font-size: 14px;
}

.link {
  padding: 0 4px;
  border: none;
  background: transparent;
  color: var(--wg-primary);
  font: inherit;
  cursor: pointer;
}

.full {
  align-self: flex-end;
  color: var(--wg-primary);
  font-size: 14px;
}

.full:hover,
.link:hover {
  color: var(--wg-primary-hover);
}
</style>
