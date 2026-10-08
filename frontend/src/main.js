import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import App from './App.vue'
import router from './router'
import { ACCENT } from './theme'
import 'ant-design-vue/dist/reset.css'
import './styles.css'

// 主色注入：原生 CSS 层与组件库主题共用 theme.js 的同一主色来源
document.documentElement.style.setProperty('--accent', ACCENT)

createApp(App)
  .use(createPinia())
  .use(Antd)
  .use(router)
  .mount('#app')
