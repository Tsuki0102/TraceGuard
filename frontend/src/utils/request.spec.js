import { describe, it, expect, vi, beforeEach } from 'vitest'

// TST-05：request.js 响应拦截器单元测试（axios mock，捕获拦截器 handler 直接触发）
const { responseHandlers, requestHandlers } = vi.hoisted(() => ({
  responseHandlers: [],
  requestHandlers: []
}))

vi.mock('axios', () => ({
  default: {
    create: () => ({
      interceptors: {
        request: { use: (f) => requestHandlers.push(f) },
        response: { use: (f, r) => responseHandlers.push({ fulfilled: f, rejected: r }) }
      }
    })
  }
}))

const push = vi.fn()
vi.mock('@/router', () => ({
  default: {
    currentRoute: { value: { path: '/projects' } },
    push: (...args) => push(...args)
  }
}))

const errorToast = vi.fn()
vi.mock('element-plus', () => ({ ElMessage: { error: (m) => errorToast(m) } }))

import service from '@/utils/request'

describe('request.js 拦截器（TST-05）', () => {
  // node 环境无 localStorage，手动提供内存版 mock（支持 set/get/remove）
  const storage = {}
  beforeEach(() => {
    Object.keys(storage).forEach(k => delete storage[k])
    global.localStorage = {
      getItem: (k) => (k in storage ? storage[k] : null),
      setItem: (k, v) => { storage[k] = String(v) },
      removeItem: (k) => { delete storage[k] },
      clear: () => { Object.keys(storage).forEach(k => delete storage[k]) }
    }
    push.mockClear()
    errorToast.mockClear()
  })

  it('code=200 直接返回 res.data', async () => {
    const [h] = responseHandlers
    const data = await h.fulfilled({ data: { code: 200, data: { id: 1 } } })
    expect(data).toEqual({ id: 1 })
  })

  it('code!=200（非 401）抛错并提示、不跳转', async () => {
    const [h] = responseHandlers
    await expect(h.fulfilled({ data: { code: 403, message: '无权限' } })).rejects.toThrow('无权限')
    expect(errorToast).toHaveBeenCalledWith('无权限')
    expect(push).not.toHaveBeenCalled()
  })

  it('code=401 清除本地凭证并跳转登录页', async () => {
    localStorage.setItem('token', 'x')
    localStorage.setItem('userInfo', '{}')
    const [h] = responseHandlers
    await expect(h.fulfilled({ data: { code: 401, message: '未登录' } })).rejects.toThrow('未登录')
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('userInfo')).toBeNull()
    expect(push).toHaveBeenCalledWith('/login')
  })

  it('HTTP 401（rejected）清除凭证并跳转登录页', async () => {
    localStorage.setItem('token', 'x')
    localStorage.setItem('userInfo', '{}')
    const [h] = responseHandlers
    await expect(h.rejected({ response: { status: 401 } })).rejects.toThrow('登录已过期')
    expect(localStorage.getItem('token')).toBeNull()
    expect(push).toHaveBeenCalledWith('/login')
  })

  it('已注册请求拦截器（withCredentials 凭证通道由 create 入参承载，SEC-10①）', () => {
    expect(requestHandlers.length).toBe(1)
    expect(service).toBeDefined()
  })
})
