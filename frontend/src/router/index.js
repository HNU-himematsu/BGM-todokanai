import { createRouter, createWebHistory } from 'vue-router'
import JoinView from '../views/JoinView.vue'
import PlayView from '../views/PlayView.vue'
import HostView from '../views/HostView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: JoinView },
    { path: '/play', component: PlayView },
    { path: '/host', component: HostView },
  ],
})

export default router
