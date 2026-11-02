<script setup lang="ts">
import { currentUser } from '@/auth/session'
import ShortcutHelp from '@/shortcuts/ShortcutHelp.vue'
</script>

<template>
  <RouterView />
  <!-- 登录后才有快捷键 -->
  <ShortcutHelp v-if="currentUser" />
</template>
