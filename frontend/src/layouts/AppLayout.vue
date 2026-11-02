<script setup lang="ts">
import { useRoute } from 'vue-router'
import AccountMenu from './AccountMenu.vue'

// 图标路径来自设计稿（mockups/03-today.dc.html），24×24 的线条图标
const navItems = [
  { to: '/', label: '今天', icon: 'M4 6h16v14H4zM4 10h16M9 3v4M15 3v4' },
  { to: '/library', label: '知识库', icon: 'M5 4h10a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4zM5 16a4 4 0 0 1 4-4h10' },
  { to: '/import', label: '导入资料', icon: 'M4 13l2.5-7h11l2.5 7v6H4zM4 13h5l1 2h4l1-2h5' },
  { to: '/settings', label: '设置', icon: 'M5 6h14M5 12h14M5 18h14M9 4v4M15 10v4M11 16v4' },
]

const route = useRoute()

// “今天”只在首页亮；其他导航项在自己的子页面（如以后的知识点详情）也亮
function isActive(to: string) {
  return to === '/' ? route.path === '/' : route.path === to || route.path.startsWith(to + '/')
}
</script>

<template>
  <div class="app">
    <nav class="sidebar" aria-label="主导航">
      <span class="logo wg-serif">温故</span>
      <div class="nav-items">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ active: isActive(item.to) }"
          :aria-current="isActive(item.to) ? 'page' : undefined"
        >
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path :d="item.icon" />
          </svg>
          {{ item.label }}
        </RouterLink>
      </div>
      <AccountMenu class="account" />
    </nav>
    <div class="content">
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  flex: none;
  width: 240px;
  height: 100vh;
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding: 22px 16px;
  background: var(--wg-surface);
  border-right: 1px solid var(--wg-border);
}

.logo {
  padding: 0 12px;
  font-size: 24px;
  letter-spacing: 5px;
}

.nav-items {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  padding: 0 12px;
  border-radius: var(--wg-radius-control);
  color: var(--wg-text);
  font-size: 15px;
}

.nav-item:hover {
  color: var(--wg-text);
  background: var(--wg-tag-bg);
}

.nav-item.active {
  color: var(--wg-primary);
  background: var(--wg-primary-soft);
  font-weight: 700;
}

.account {
  margin-top: auto;
}

.content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

/* 窄屏：侧栏换到内容下方（ui.md 4.3） */
@media (max-width: 880px) {
  .app {
    flex-direction: column;
  }

  .sidebar {
    order: 1;
    width: auto;
    height: auto;
    position: static;
    border-right: none;
    border-top: 1px solid var(--wg-border);
  }
}
</style>
