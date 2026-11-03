<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchLlmStatus } from '@/api/llm'
import { useLogout } from '@/auth/logout'
import { currentUser } from '@/auth/session'
import { shortcutHelpOpen } from '@/shortcuts/list'

const router = useRouter()
const route = useRoute()
const logout = useLogout()

const username = computed(() => currentUser.value?.username ?? '')
// 头像取用户名首字。用 Array.from 按字符拆分：扩展区的生僻汉字在 JS 字符串中占两个位置，取 [0] 只会拿到半个
const initial = computed(() => (Array.from(username.value)[0] ?? '').toUpperCase())

// 模型出错时头像上显示红点（ui.md 第 7 节，决策 032）。进入应用和每次切换页面时取一次：
// 状态只是后端记下的最近一次结果，取一次很便宜；学习页没有这个菜单，回到应用页面时就能看到。
// 原因由设置页的 AI 模型卡片显示，菜单里只放一个去设置页的入口
const modelError = ref(false)

async function refreshModelStatus() {
  try {
    const status = await fetchLlmStatus()
    modelError.value = !!status.lastCall && !status.lastCall.ok
  } catch {
    // 取不到状态不影响使用，也不显示红点
  }
}

watch(() => route.path, refreshModelStatus, { immediate: true })

async function onCommand(command: string) {
  if (command === 'model') {
    await router.push('/settings')
  } else if (command === 'shortcuts') {
    shortcutHelpOpen.value = true
  } else if (command === 'logout') {
    await logout()
  }
}
</script>

<template>
  <el-dropdown trigger="click" placement="top-start" @command="onCommand">
    <button
      type="button"
      class="account-button"
      :aria-label="modelError ? `账户：${username}，AI 模型出错` : `账户：${username}`"
    >
      <span class="avatar" aria-hidden="true">
        {{ initial }}
        <span v-if="modelError" class="dot" />
      </span>
      <span class="name">{{ username }}</span>
      <svg
        width="14"
        height="14"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        stroke-width="1.8"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M4 6l4-3 4 3M4 10l4 3 4-3" />
      </svg>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-if="modelError" command="model" class="model-error">AI 模型出错，去设置查看</el-dropdown-item>
        <el-dropdown-item command="shortcuts" :divided="modelError">快捷键</el-dropdown-item>
        <el-dropdown-item command="logout" class="wg-danger" divided>退出登录</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.account-button {
  width: 100%;
  min-height: 52px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: none;
  border-radius: var(--wg-radius-control);
  background: none;
  color: var(--wg-text-muted);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.account-button:hover {
  background: var(--wg-tag-bg);
}

.avatar {
  position: relative;
  width: 34px;
  height: 34px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--wg-primary-soft);
  color: var(--wg-primary);
  font-size: 15px;
  font-weight: 700;
}

/* 红点：需要用户注意的事用 --wg-warn（ui.md 2.1），白色描边和头像分开 */
.dot {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 10px;
  height: 10px;
  border: 2px solid var(--wg-surface);
  border-radius: 50%;
  background: var(--wg-warn);
}

/* 下拉菜单由 Element Plus 渲染到 body 下，要用 :global 才作用得到 */
:global(.el-dropdown-menu__item.model-error) {
  color: var(--wg-warn);
}

.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--wg-text);
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
