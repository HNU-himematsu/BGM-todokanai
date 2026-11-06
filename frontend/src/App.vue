<script setup>
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { ACCENT } from './theme'

const themeConfig = {
  token: {
    colorPrimary: ACCENT,
    colorInfo: ACCENT,
    colorLink: ACCENT,
    borderRadius: 2,
    fontFamily: 'Inter, "PingFang SC", "Microsoft YaHei", sans-serif',
  },
}
</script>

<template>
  <a-config-provider :locale="zhCN" :theme="themeConfig">
    <div class="app-shell">
      <main class="page-frame">
        <router-view />
      </main>
    </div>
  </a-config-provider>
</template>
