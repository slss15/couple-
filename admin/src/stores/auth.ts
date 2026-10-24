import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api'
import { ApiError } from '@/api/errors'
import { decodeJwt, tokenStorage, type StoredTokens } from '@/api/tokenStorage'
import type { TokenPair } from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const tokens = ref<StoredTokens | null>(tokenStorage.get())

  const user = computed(() => tokens.value?.user ?? null)
  const claims = computed(() => (tokens.value ? decodeJwt(tokens.value.accessToken) : null))
  const isLoggedIn = computed(() => !!tokens.value)
  const isAdmin = computed(() => claims.value?.role === 'admin')

  /** 拦截器在后台轮换令牌后，调用此方法同步到响应式状态 */
  function sync() {
    tokens.value = tokenStorage.get()
  }

  async function login(username: string, password: string) {
    const pair = await api.login(username, password)
    const role = decodeJwt(pair.accessToken)?.role
    if (role !== 'admin') {
      // 非管理员：立即注销刚拿到的 refresh token，不在本地留存
      await api.logout(pair.refreshToken).catch(() => undefined)
      throw new ApiError(403, 'NOT_ADMIN', '该账号不是管理员')
    }
    tokenStorage.set(pair)
    sync()
  }

  /** 初始化向导创建管理员后直接采用返回的令牌 */
  function adopt(pair: TokenPair) {
    tokenStorage.set(pair)
    sync()
  }

  async function logout() {
    const rt = tokenStorage.refreshToken
    tokenStorage.clear()
    sync()
    if (rt) await api.logout(rt).catch(() => undefined)
  }

  function clearLocal() {
    tokenStorage.clear()
    sync()
  }

  return { tokens, user, claims, isLoggedIn, isAdmin, sync, login, adopt, logout, clearLocal }
})
