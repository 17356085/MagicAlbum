import api from './client'

const useMock = String(import.meta.env.VITE_USE_API_MOCK || '').toLowerCase() === 'true'
const isDev = !!import.meta.env.DEV
const mockSections = [
  { id: 1, name: '动画', slug: 'anime', description: '动画作品交流与讨论' },
  { id: 2, name: '音乐', slug: 'music', description: '音乐分享、鉴赏与创作交流' },
  { id: 3, name: '游戏', slug: 'game', description: '主机/PC/移动游戏讨论与攻略' },
  { id: 4, name: 'F1', slug: 'f1', description: '一级方程式赛事新闻与技术讨论' },
  { id: 5, name: '科技数码', slug: 'tech', description: '数码产品评测、折腾与交流' },
  { id: 6, name: '编程', slug: 'coding', description: '编程学习、技术分享与项目交流' },
  { id: 7, name: '美食', slug: 'food', description: '烹饪心得、美食探店与菜谱分享' },
  { id: 8, name: '模型', slug: 'model', description: '手办/拼装/高达/潮玩模型交流' },
  { id: 9, name: '阅读', slug: 'reading', description: '阅读心得、书评与推荐' },
]

export async function listSections(params = {}) {
  if (useMock) {
    return { items: mockSections, page: 1, size: 20, total: mockSections.length }
  }
  try {
    const { data } = await api.get('/sections', { params })
    return data
  } catch (e) {
    // 非 Mock 模式不再回退到本地 mock，以避免 ID 不一致导致后端报错
    throw e
  }
}