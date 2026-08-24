import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const service = axios.create({
  baseURL: '/api',
  timeout: 30000,
  // SEC-10①：JWT 存 HttpOnly Cookie，跨域请求必须携带凭证（withCredentials），前端不再读取/注入 token
  withCredentials: true
})

service.interceptors.request.use(
  config => {
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

service.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      if (res.code === 401) {
        clearAuthAndRedirect()
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res.data
  },
  error => {
    // Token 过期/无效：后端返回 HTTP 401，清除本地凭证并跳转登录页
    if (error.response && error.response.status === 401) {
      clearAuthAndRedirect()
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

/** 清除本地登录凭证并跳转登录页（避免在登录页重复跳转；SEC-10① token 在 HttpOnly Cookie，仅清本地 userInfo） */
function clearAuthAndRedirect() {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
  if (router.currentRoute.value.path !== '/login') {
    ElMessage.error('登录已过期，请重新登录')
    router.push('/login')
  }
}

export default service
