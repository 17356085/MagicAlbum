import { computed, ref, type Ref } from 'vue'
import type { Id } from '@/types'
import type { CommentGroup, CommentItem, SortKey, SortOrder } from './types'

export function useCommentGroups(items: Ref<CommentItem[]>) {
  const sortKey = ref<SortKey>('time')
  const sortOrder = ref<SortOrder>('desc')
  const showTimeMenu = ref(false)
  const showRepliesMenu = ref(false)

  const groups = computed<CommentGroup[]>(() => {
    const list = Array.isArray(items.value) ? items.value.slice() : []
    const nodes: CommentItem[] = list.map((post) => ({ ...post, children: [] }))
    const byId = new Map<Id, CommentItem>(nodes.map((node) => [node.id, node]))

    nodes.forEach((node) => {
      const parentId = node.replyToPostId
      if (parentId && byId.has(parentId)) {
        const parent = byId.get(parentId)
        if (!parent) return
        parent.children?.push(node)
        node.parentAuthorUsername = parent.authorUsername || String(parent.authorId || '')
        node.parentAuthorId = parent.authorId
        node.parentAuthorNickname = parent.authorNickname || null
      }
    })

    const sortByCreated = (a: CommentItem, b: CommentItem) =>
      new Date(a.createdAt || 0).getTime() - new Date(b.createdAt || 0).getTime()

    const roots = nodes.filter((node) => !node.replyToPostId || !byId.has(node.replyToPostId)).sort(sortByCreated)
    const result: CommentGroup[] = []

    const walk = (node: CommentItem, depth: number, acc: CommentItem[], path: number[]) => {
      acc.push({ ...node, depth, floorLabel: path.join('-') })
      node.children?.sort(sortByCreated).forEach((child, idx) => walk(child, depth + 1, acc, [...path, idx + 1]))
    }

    roots.forEach((root, rootIndex) => {
      const acc: CommentItem[] = []
      root.floorLabel = `${rootIndex + 1}楼`
      root.children?.sort(sortByCreated).forEach((child, idx) => walk(child, 1, acc, [rootIndex + 1, idx + 1]))
      result.push({ root, items: acc })
    })

    return result
  })

  const sortedGroups = computed(() => {
    const arr = groups.value.slice()
    arr.sort((a, b) => {
      const va = sortKey.value === 'replies'
        ? (a.items?.length || 0)
        : sortKey.value === 'likes'
          ? Number(a.root?.likeCount || 0)
          : new Date(a.root?.createdAt || 0).getTime()
      const vb = sortKey.value === 'replies'
        ? (b.items?.length || 0)
        : sortKey.value === 'likes'
          ? Number(b.root?.likeCount || 0)
          : new Date(b.root?.createdAt || 0).getTime()
      if (va === vb) {
        const ta = new Date(a.root?.createdAt || 0).getTime()
        const tb = new Date(b.root?.createdAt || 0).getTime()
        const timeCmp = tb - ta
        if (timeCmp !== 0) return sortOrder.value === 'asc' ? -timeCmp : timeCmp
        const ia = Number(a.root?.id || 0)
        const ib = Number(b.root?.id || 0)
        return sortOrder.value === 'asc' ? ia - ib : ib - ia
      }
      return sortOrder.value === 'asc' ? va - vb : vb - va
    })
    return arr
  })

  function selectTimeOrder(order: SortOrder): void {
    sortKey.value = 'time'
    sortOrder.value = order === 'asc' ? 'asc' : 'desc'
    showTimeMenu.value = false
  }

  function selectRepliesOrder(order: SortOrder): void {
    sortKey.value = 'replies'
    sortOrder.value = order === 'asc' ? 'asc' : 'desc'
    showRepliesMenu.value = false
  }

  function selectLikesOrder(order: SortOrder): void {
    sortKey.value = 'likes'
    sortOrder.value = order === 'asc' ? 'asc' : 'desc'
    showRepliesMenu.value = false
    showTimeMenu.value = false
  }

  return {
    groups,
    selectRepliesOrder,
    selectLikesOrder,
    selectTimeOrder,
    showRepliesMenu,
    showTimeMenu,
    sortKey,
    sortOrder,
    sortedGroups,
  }
}
