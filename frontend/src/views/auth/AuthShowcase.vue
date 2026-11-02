<script setup lang="ts">
// 注册与登录页的左栏（ui.md 5.1）。标语和示例卡片的内容集中在这里，以后扩展到其他学习方向时只改这个组件
const slogan = ['把面试题拆成知识点，', '每天学懂几个，到时间再回来。']

const example = {
  tags: '新学，原理机制',
  title: 'HashMap 的扩容机制是怎样的？',
  ratings: [
    { label: '忘了', interval: '明天' },
    { label: '困难', interval: '3 天后' },
    { label: '良好', interval: '8 天后' },
    { label: '简单', interval: '15 天后' },
  ],
  picked: '良好',
  seeYou: '11月7日再见',
}
</script>

<template>
  <section class="showcase">
    <span class="logo wg-serif">温故</span>
    <div class="body">
      <h2 class="slogan wg-serif">
        <template v-for="(line, i) in slogan" :key="line"><br v-if="i > 0" />{{ line }}</template>
      </h2>
      <!-- 示例卡片只是插图，读屏软件跳过 -->
      <div class="card" aria-hidden="true">
        <span class="tags">{{ example.tags }}</span>
        <span class="title wg-serif">{{ example.title }}</span>
        <div class="ratings">
          <span
            v-for="r in example.ratings"
            :key="r.label"
            class="rating"
            :class="{ picked: r.label === example.picked }"
          >
            {{ r.label }}<span class="interval">{{ r.interval }}</span>
          </span>
        </div>
        <span class="see-you wg-serif">{{ example.seeYou }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.showcase {
  display: flex;
  flex-direction: column;
  gap: 48px;
  padding: 56px 64px;
  background: var(--wg-surface);
}

.logo {
  font-size: 28px;
  letter-spacing: 6px;
}

.body {
  display: flex;
  flex-direction: column;
  gap: 28px;
  max-width: 520px;
  margin: auto 0;
}

.slogan {
  font-size: 36px;
  line-height: 1.45;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 24px;
  background: var(--wg-bg);
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.tags {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.title {
  font-size: 19px;
}

.ratings {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.rating {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 10px 4px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-control);
  font-size: 14px;
  font-weight: 700;
}

.rating.picked {
  border-color: var(--wg-primary);
  box-shadow: inset 0 0 0 1px var(--wg-primary);
  background: var(--wg-primary-soft);
}

.interval {
  color: var(--wg-text-muted);
  font-size: 12px;
  font-weight: 400;
}

.see-you {
  color: var(--wg-primary);
  font-size: 30px;
}

@media (max-width: 600px) {
  .showcase {
    gap: 32px;
    padding: 32px 16px;
  }

  .slogan {
    font-size: 26px;
  }
}
</style>
