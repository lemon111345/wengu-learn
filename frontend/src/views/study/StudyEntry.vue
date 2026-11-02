<script setup lang="ts">
// 学习的入口 /study/:kpId：开始（或接着）学习这个知识点，拿到会话后换成 /session/:id。
// URL 用会话 id：评分页刷新时不会因为按知识点重新开始而新建会话（决策 027）
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { startSession } from '@/api/sessions'

const props = defineProps<{ kpId: string }>()
const router = useRouter()
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    const session = await startSession(Number(props.kpId))
    await router.replace(`/session/${session.id}`)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '没有开始学习，请重试'
  }
})
</script>

<template>
  <div v-if="error" class="entry-error" role="alert">
    <p>{{ error }}</p>
    <RouterLink to="/">回到今天</RouterLink>
  </div>
  <el-skeleton v-else :rows="5" animated />
</template>

<style scoped>
.entry-error {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
