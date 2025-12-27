import api from './client'

const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export async function checkUsernameAvailable(username) {
  if (useMock) {
    await delay(300)
    const taken = username?.toLowerCase() === 'taken'
    return !taken
  }
  const { data } = await api.get('/users/availability', { params: { username } })
  return !!data?.available
}

export async function registerUser(payload) {
  if (useMock) {
    await delay(500)
    if (payload?.username?.toLowerCase() === 'taken') {
      const err = new Error('用户名不可重复')
      err.response = { status: 400, data: { message: '用户名不可重复' } }
      throw err
    }
    return {
      id: 1,
      username: payload.username,
      email: payload.email,
      phone: payload.phone,
    }
  }
  const { data } = await api.post('/users/register', payload)
  return data
}

export async function listUsers({ q, page = 1, size = 20 } = {}) {
  if (useMock) {
    await delay(300)
    const items = [
      { id: 1, username: 'alice', email: 'alice@example.com', phone: '13800000000' },
      { id: 2, username: 'bob', email: 'bob@example.com', phone: '13900000000' },
      { id: 3, username: 'charlie', email: 'charlie@example.com', phone: '13700000000' }
    ].filter(u => !q || String(u.username).toLowerCase().includes(String(q).toLowerCase()))
    return { items, page, size, total: items.length }
  }
  const params = {}
  if (q && String(q).trim()) params.q = q
  params.page = page
  params.size = size
  const { data } = await api.get('/users', { params })
  // 统一返回结构为 { items, page, size, total }
  if (Array.isArray(data)) {
    return { items: data, page, size, total: data.length }
  }
  const items = Array.isArray(data?.items) ? data.items : []
  const total = Number(data?.total ?? items.length)
  const retPage = Number(data?.page ?? page)
  const retSize = Number(data?.size ?? size)
  return { items, page: retPage, size: retSize, total }
}

// 用户搜索联想：根据关键字返回前 N 条匹配的用户
export async function suggestUsers(q, size = 5) {
  const keyword = String(q || '').trim()
  if (!keyword) return []
  if (useMock) {
    await delay(200)
    const items = [
      { id: 1, username: 'alice', email: 'alice@example.com' },
      { id: 2, username: 'bob', email: 'bob@example.com' },
      { id: 3, username: 'charlie', email: 'charlie@example.com' },
      { id: 4, username: 'david', email: 'david@example.com' },
      { id: 5, username: 'eve', email: 'eve@example.com' },
    ].filter(u => String(u.username).toLowerCase().includes(keyword.toLowerCase())).slice(0, size)
    return items
  }
  const params = { q: keyword, page: 1, size }
  const { data } = await api.get('/users', { params })
  const arr = Array.isArray(data) ? data : (data.items || [])
  return arr
}

// 获取当前登录用户信息，兼容不同后端路径与返回结构
export async function getCurrentUser() {
  if (useMock) {
    await delay(200)
    return {
      id: 1,
      username: 'mock_user',
      email: 'mock@example.com',
    }
  }
  const candidates = ['/users/me', '/auth/me', '/users/profile', '/users/current']
  for (const path of candidates) {
    try {
      const { data } = await api.get(path)
      const payload = data?.data ?? data?.result ?? data ?? null
      const user = payload?.user ?? payload?.profile ?? payload?.userInfo ?? payload ?? null
      if (user && typeof user === 'object') return user
    } catch (e) {
      // 继续尝试下一个候选路径
    }
  }
  return null
}

// 获取指定用户的公开资料（昵称、头像、主页、所在地、个人介绍、外链）
export async function getUserProfile(id) {
  const { data } = await api.get(`/users/${id}/profile`)
  return data
}

// 列出指定用户的主题帖（公开接口），分页与默认每页10条
export async function listUserThreads(id, { q, sectionId, sort = 'updatedAt', page = 1, size = 10 } = {}) {
  const params = {}
  if (q && String(q).trim()) params.q = String(q).trim()
  if (sectionId) params.sectionId = sectionId
  if (sort) params.sort = sort
  params.page = page
  params.size = size
  const { data } = await api.get(`/users/${id}/threads`, { params })
  return data
}