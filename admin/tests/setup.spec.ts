import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import type { RouteLocationNormalized } from 'vue-router'
import { createSetupGuard } from '@/utils/setupGuard'
import { tokenStorage } from '@/api/tokenStorage'
import { ApiError } from '@/api/errors'
import { pair } from './helpers'

const apiMock = vi.hoisted(() => ({
  setupStatus: vi.fn(),
  setup: vi.fn(),
}))

vi.mock('@/api', () => ({
  api: apiMock,
  http: {},
  setAuthFailureHandler: () => {},
}))

/** ElInput forwards data-test either to its wrapper or to the inner <input> depending on version. */
function field(wrapper: VueWrapper, name: string) {
  const inner = wrapper.find(`[data-test="${name}"] input`)
  return inner.exists() ? inner : wrapper.find(`input[data-test="${name}"]`)
}

function loc(name: string, query: Record<string, string> = {}): RouteLocationNormalized {
  return { name, query, path: `/${name}`, fullPath: `/${name}` } as unknown as RouteLocationNormalized
}

describe('createSetupGuard', () => {
  it('redirects everything to /setup while setup is needed and keeps ?token=', async () => {
    const fetch = vi.fn().mockResolvedValue(true)
    const g = createSetupGuard(fetch)
    expect(await g.guard(loc('dashboard', { token: 'abc' }))).toEqual({ name: 'setup', query: { token: 'abc' } })
    expect(await g.guard(loc('login'))).toEqual({ name: 'setup', query: {} })
    expect(await g.guard(loc('setup'))).toBe(true)
    expect(fetch).toHaveBeenCalledTimes(1) // cached
  })

  it('sends /setup to login once setup is done', async () => {
    const g = createSetupGuard(vi.fn().mockResolvedValue(false))
    expect(await g.guard(loc('setup'))).toEqual({ name: 'login' })
    expect(await g.guard(loc('dashboard'))).toBe(true)
  })

  it('treats a failing status call as not needed', async () => {
    const g = createSetupGuard(vi.fn().mockRejectedValue(new Error('404')))
    expect(await g.guard(loc('dashboard'))).toBe(true)
  })

  it('markDone stops redirecting', async () => {
    const g = createSetupGuard(vi.fn().mockResolvedValue(true))
    expect(await g.guard(loc('dashboard'))).not.toBe(true)
    g.markDone()
    expect(await g.guard(loc('dashboard'))).toBe(true)
  })
})

describe('router + SetupView', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    tokenStorage.clear()
    setActivePinia(createPinia())
    const { setupGuard } = await import('@/router')
    setupGuard.reset()
  })

  it('redirects to /setup on load when the server needs setup', async () => {
    apiMock.setupStatus.mockResolvedValue({ needed: true, suggested: null })
    const { router } = await import('@/router')
    await router.push('/dashboard?token=t0k')
    expect(router.currentRoute.value.name).toBe('setup')
    expect(router.currentRoute.value.query.token).toBe('t0k')
  })

  async function mountSetup(query = '?token=from-url') {
    apiMock.setupStatus.mockResolvedValue({ needed: true, suggested: null })
    const { router } = await import('@/router')
    await router.push('/setup' + query)
    const SetupView = (await import('@/views/SetupView.vue')).default
    const wrapper = mount(SetupView, { global: { plugins: [ElementPlus, router] }, attachTo: document.body })
    await flushPromises()
    return { wrapper, router }
  }

  it('prefills the token from ?token= and creates the admin', async () => {
    const p = pair(1)
    apiMock.setup.mockResolvedValue(p)
    const { wrapper, router } = await mountSetup()
    const token = field(wrapper, 'token').element as HTMLInputElement
    expect(token.value).toBe('from-url')

    await field(wrapper, 'username').setValue('root')
    await field(wrapper, 'password').setValue('password-123')
    await field(wrapper, 'confirm').setValue('password-123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('bootstrap'))

    expect(apiMock.setup).toHaveBeenCalledWith('from-url', 'root', 'password-123')
    expect(tokenStorage.accessToken).toBe(p.accessToken)
    expect(router.currentRoute.value.query.setup).toBe('1')
    wrapper.unmount()
  })

  it('does not submit when the passwords differ', async () => {
    const { wrapper } = await mountSetup()
    await field(wrapper, 'username').setValue('root')
    await field(wrapper, 'password').setValue('password-123')
    await field(wrapper, 'confirm').setValue('password-456')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(apiMock.setup).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('两次输入的密码不一致')
    wrapper.unmount()
  })

  it('shows a Chinese error for a wrong token', async () => {
    apiMock.setup.mockRejectedValue(new ApiError(403, 'SETUP_TOKEN_INVALID', 'Invalid setup token'))
    const { wrapper, router } = await mountSetup('?token=bad')
    await field(wrapper, 'username').setValue('root')
    await field(wrapper, 'password').setValue('password-123')
    await field(wrapper, 'confirm').setValue('password-123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('[data-test="error"]').exists()).toBe(true))
    expect(wrapper.text()).toContain('初始化令牌不正确')
    expect(router.currentRoute.value.name).toBe('setup')
    expect(tokenStorage.get()).toBeNull()
    wrapper.unmount()
  })
})
