<script setup lang="ts">
// 合并弹窗（ui.md 5.9）：选一个作为主知识点，保留它的标题、要点和复习进度，其他的来源并过来。
// 合并不能撤销（决策 029），弹窗本身就是确认
import { ref, watch } from 'vue'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{
  /** 勾选的知识点，按勾选顺序 */
  items: { id: number; title: string }[]
  busy: boolean
}>()
const emit = defineEmits<{ confirm: [primaryId: number] }>()

const primaryId = ref<number | null>(null)

// 每次打开时默认选第一个
watch(open, (value) => {
  if (value) {
    primaryId.value = props.items[0]?.id ?? null
  }
})

function confirm() {
  if (primaryId.value !== null) {
    emit('confirm', primaryId.value)
  }
}
</script>

<template>
  <el-dialog v-model="open" title="合并知识点" width="520" align-center>
    <p class="intro">
      选一个作为主知识点，保留它的标题、要点和复习进度。其他 {{ items.length - 1 }}
      个的来源并到主知识点上，学习记录在详情中一起显示，然后从知识库中去掉。合并后不能撤销。
    </p>
    <el-radio-group v-model="primaryId" class="options" aria-label="主知识点">
      <el-radio v-for="item in items" :key="item.id" :value="item.id" class="option">{{ item.title }}</el-radio>
    </el-radio-group>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="primaryId === null" @click="confirm">合并</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.intro {
  margin-bottom: 16px;
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.7;
}

.options {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
}

/* 标题可能很长：换行显示，不截断 */
.option {
  height: auto;
  min-height: 44px;
  margin-right: 0;
  white-space: normal;
}

.option :deep(.el-radio__label) {
  font-size: 15px;
  line-height: 1.5;
}
</style>
