<script setup lang="ts">
// 账户：用户名、修改密码、退出登录（ui.md 5.10）。危险操作只用文字按钮，放在最右侧（ui.md 第 3 节）
import { computed, ref } from 'vue'
import { useLogout } from '@/auth/logout'
import { currentUser } from '@/auth/session'
import PasswordDialog from './PasswordDialog.vue'
import SettingsCard from './SettingsCard.vue'

const username = computed(() => currentUser.value?.username ?? '')
const passwordOpen = ref(false)
const logout = useLogout()
</script>

<template>
  <SettingsCard title="账户">
    <div class="row">
      <span class="wg-muted">用户名</span>
      <span>{{ username }}</span>
    </div>
    <div class="actions">
      <el-button @click="passwordOpen = true">修改密码</el-button>
      <button type="button" class="logout" @click="logout">退出登录</button>
    </div>
    <PasswordDialog v-model="passwordOpen" />
  </SettingsCard>
</template>

<style scoped>
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  font-size: 15px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding-top: 18px;
  border-top: 1px solid var(--wg-tag-bg);
}

.actions .el-button {
  height: 44px;
  padding: 0 16px;
  font-size: 15px;
}

.logout {
  min-height: 44px;
  margin-left: auto;
  padding: 0 12px;
  border: none;
  border-radius: var(--wg-radius-control);
  background: none;
  color: var(--wg-warn);
  font: inherit;
  font-size: 15px;
  cursor: pointer;
}

.logout:hover {
  background: var(--wg-warn-soft);
}
</style>
