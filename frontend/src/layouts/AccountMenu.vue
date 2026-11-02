<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logout } from '@/api/auth'
import { ApiError } from '@/api/http'
import { clearCurrentUser, currentUser } from '@/auth/session'
import { shortcutHelpOpen } from '@/shortcuts/list'

const router = useRouter()

const username = computed(() => currentUser.value?.username ?? '')
// 头像取用户名首字。用 Array.from 按字符拆分：扩展区的生僻汉字在 JS 字符串中占两个位置，取 [0] 只会拿到半个
const initial = computed(() => (Array.from(username.value)[0] ?? '').toUpperCase())

async function onCommand(command: string) {
  if (command === 'shortcuts') {
    shortcutHelpOpen.value = true
  } else if (command === 'logout') {
    try {
      await logout()
    } catch (e) {
      ElMessage.error(e instanceof ApiError ? e.message : '退出登录失败，请重试')
      return
    }
    clearCurrentUser()
    await router.replace('/login')
  }
}
</script>

<template>
  <el-dropdown trigger="click" placement="top-start" @command="onCommand">
    <button type="button" class="account-button" :aria-label="`账户：${username}`">
      <span class="avatar" aria-hidden="true">{{ initial }}</span>
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
        <el-dropdown-item command="shortcuts">快捷键</el-dropdown-item>
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
