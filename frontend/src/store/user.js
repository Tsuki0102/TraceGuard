/**
 * 前端当前用户上下文工具（轻量，基于 localStorage 中的 userInfo）。
 * 仅用于前端展示/权限判断；真实鉴权以后端返回为准。
 */
const USER_INFO_KEY = 'userInfo'

function getUserInfo() {
  try {
    return JSON.parse(localStorage.getItem(USER_INFO_KEY) || '{}')
  } catch (e) {
    return {}
  }
}

export const UserContext = {
  get() {
    return getUserInfo()
  },
  getUsername() {
    return getUserInfo().username || ''
  },
  isAdmin() {
    return getUserInfo().role === 'admin'
  },
  get role() {
    return getUserInfo().role
  }
}

export default UserContext
