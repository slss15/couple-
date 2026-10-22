import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import { router } from './router'
import { setAuthFailureHandler } from './api'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

setAuthFailureHandler(() => {
  useAuthStore(pinia).clearLocal()
  if (router.currentRoute.value.name !== 'login') {
    ElMessage.warning('登录已失效，请重新登录')
    router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})

// 其它标签页登出/轮换令牌时同步状态
window.addEventListener('storage', (e) => {
  if (e.key === 'couple.admin.tokens') useAuthStore(pinia).sync()
})

app.mount('#app')
