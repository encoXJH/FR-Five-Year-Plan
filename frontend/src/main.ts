import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/assets/main.css'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia).use(router).use(ElementPlus, { locale: zhCn })

window.addEventListener('unhandledrejection', (event) => {
  if (event.reason === 'cancel' || event.reason === 'close') event.preventDefault()
})

window.addEventListener('session-expired', () => {
  useAuthStore().clear()
  if (router.currentRoute.value.name !== 'login') router.push({ name: 'login', query: { expired: '1' } })
})

app.mount('#app')
