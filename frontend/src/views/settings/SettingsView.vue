<script setup lang="ts">
// 设置页（ui.md 5.10）：修改后自动保存，没有保存按钮。
// 设置的数据放在这里，卡片只负责显示和报告改动；保存时先在页面上改好（乐观更新），失败了再向后端取一次真实值
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { ApiError } from '@/api/http'
import { fetchLlmStatus, type LlmStatus } from '@/api/llm'
import { fetchSettings, updateSettings, type Settings } from '@/api/settings'
import AccountCard from './AccountCard.vue'
import ModelCard from './ModelCard.vue'
import ScheduleCard from './ScheduleCard.vue'
import ShortcutsCard from './ShortcutsCard.vue'
import TeacherCard from './TeacherCard.vue'

const settings = ref<Settings | null>(null)
/** 取不到时为 null，卡片显示“暂时取不到” */
const llmStatus = ref<LlmStatus | null>(null)
const loadError = ref<string | null>(null)

/** 顶栏右边的状态：一开始写“修改后自动保存”，改动后变为“正在保存”“已保存” */
const saveState = ref<'idle' | 'saving' | 'saved'>('idle')
// 连续改了几次时，只采用最后一次请求的结果，避免先发的请求后返回、把新值盖掉
let saveSeq = 0

async function load() {
  loadError.value = null
  // 模型状态取不到不影响改设置，单独处理
  fetchLlmStatus()
    .then((s) => (llmStatus.value = s))
    .catch(() => (llmStatus.value = null))
  try {
    settings.value = await fetchSettings()
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '设置没有加载出来，请重试'
  }
}

async function save(changes: Partial<Settings>) {
  if (!settings.value) {
    return
  }
  const seq = ++saveSeq
  settings.value = { ...settings.value, ...changes }
  saveState.value = 'saving'
  try {
    const saved = await updateSettings(changes)
    if (seq === saveSeq) {
      settings.value = saved
      saveState.value = 'saved'
    }
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败，请重试')
    if (seq === saveSeq) {
      saveState.value = 'idle'
      // 页面上已经改了，改回后端实际保存的值
      try {
        settings.value = await fetchSettings()
      } catch {
        // 连后端都取不到时只能保留页面上的值，提示已经给出
      }
    }
  }
}

onMounted(load)
</script>

<template>
  <PageHeader title="设置">
    <span class="save-state" :class="saveState" role="status">
      <svg
        v-if="saveState !== 'saving'"
        width="14"
        height="14"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M3 8.5l3.2 3L13 4.5" />
      </svg>
      {{ saveState === 'idle' ? '修改后自动保存' : saveState === 'saving' ? '正在保存' : '已保存' }}
    </span>
  </PageHeader>

  <div v-if="loadError && !settings" class="wg-page-body">
    <section class="load-error" role="alert">
      <p>{{ loadError }}</p>
      <el-button @click="load">重试</el-button>
    </section>
  </div>

  <div v-else class="wg-page-body columns">
    <div class="column">
      <ScheduleCard v-if="settings" :settings="settings" @change="save" />
      <el-skeleton v-else class="skeleton" :rows="5" animated />
      <TeacherCard v-if="settings" :settings="settings" @change="save" />
    </div>
    <div class="column">
      <template v-if="settings">
        <AccountCard />
        <ModelCard :status="llmStatus" />
      </template>
      <template v-else>
        <el-skeleton class="skeleton" :rows="2" animated />
        <el-skeleton class="skeleton" :rows="2" animated />
      </template>
      <ShortcutsCard />
    </div>
  </div>
</template>

<style scoped>
.save-state {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--wg-text-muted);
  font-size: 14px;
}

.save-state svg {
  color: var(--wg-primary);
}

/* 两栏，宽度不够时右栏换到下面（ui.md 4.3） */
.columns {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 24px;
  padding-top: 32px;
}

.column {
  flex: 1 1 460px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.skeleton {
  padding: 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.load-error {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
  padding: 24px 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

@media (max-width: 600px) {
  .columns {
    padding-top: 20px;
  }
}
</style>
