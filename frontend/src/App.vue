<script setup>
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { Headphones, ShieldCheck } from 'lucide-vue-next'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const showHeader = computed(() => route.path !== '/')

const themeConfig = {
  token: {
    colorPrimary: '#ec5c3c',
    colorInfo: '#ec5c3c',
    colorLink: '#ec5c3c',
    borderRadius: 2,
    fontFamily: 'Inter, "PingFang SC", "Microsoft YaHei", sans-serif',
  },
}
</script>

<template>
  <a-config-provider :locale="zhCN" :theme="themeConfig">
    <div class="app-shell">
      <header v-if="showHeader" class="site-header">
        <router-link class="brand" to="/">
          <span class="brand-mark"><Headphones :size="18" /></span>
          <span>
            <strong>姬松</strong>
            <small>旮旯原声企划</small>
          </span>
        </router-link>
        <nav class="header-nav">
          <a-space :size="22">
            <router-link to="/">参与者</router-link>
          </a-space>
        </nav>
      </header>

      <main class="page-frame">
        <router-view />
      </main>

      <footer class="site-footer">
        <span><ShieldCheck :size="14" /> 本轮活动仅记录昵称与答题结果</span>
        <span>现场音响统一播放 BGM</span>
      </footer>
    </div>
  </a-config-provider>
</template>
