<script setup lang="ts">
import { ref, watch } from 'vue'
import { decideFriendRequest, friendStatus, removeFriend, sendFriendRequest, type FriendStatus } from '@/api/chat'
import { useAuthStore } from '@/stores/auth'
import type { User } from '@/types'

const props = defineProps<{ peer: User['id']; incoming?: boolean }>()
const emit = defineEmits<{ decided: []; unfriended: [] }>()
const auth = useAuthStore()
const status = ref<FriendStatus | null>(null)
const pending = ref(false)
const error = ref('')
type ActionEvent = 'decided' | 'unfriended'

watch([() => props.peer, () => props.incoming, () => auth.token], () => {
  error.value = ''
  status.value = props.incoming ? 'RECEIVED' : null
  pending.value = false
  if (!props.incoming && auth.isLoggedIn) void perform(friendStatus, '无法读取好友状态')
}, { immediate: true })

async function perform(
  operation: (peer: User['id']) => Promise<FriendStatus>,
  message: string,
  event?: ActionEvent,
) {
  if (pending.value || !auth.isLoggedIn) return
  const peer = props.peer
  const incoming = props.incoming
  const context = auth.captureAuthOperationContext()
  const current = () => props.peer === peer && props.incoming === incoming && auth.isAuthOperationContextCurrent(context)
  error.value = ''
  pending.value = true
  try {
    const result = await operation(peer)
    if (!current()) return
    status.value = result
    if (event === 'decided') emit('decided')
    else if (event === 'unfriended') emit('unfriended')
  } catch (_) {
    if (current()) error.value = message
  } finally {
    if (current()) pending.value = false
  }
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <template v-if="status === 'RECEIVED'">
      <button type="button" class="ui-button ui-button-primary" :disabled="pending"
        @click="perform(peer => decideFriendRequest(peer, true), '接受好友申请失败', 'decided')">接受好友申请</button>
      <button type="button" class="ui-button" :disabled="pending"
        @click="perform(peer => decideFriendRequest(peer, false), '拒绝好友申请失败', 'decided')">拒绝</button>
    </template>
    <span v-else-if="status === 'SENT'" class="ui-muted text-sm">已发送好友申请</span>
    <template v-else-if="status === 'FRIEND'">
      <span class="ui-muted text-sm">已是好友</span>
      <button v-if="!incoming" type="button" class="ui-button" :disabled="pending"
        @click="perform(removeFriend, '解除好友失败', 'unfriended')">解除好友</button>
    </template>
    <button v-else-if="status === 'NONE'" type="button" class="ui-button" :disabled="pending"
      @click="perform(sendFriendRequest, '发送好友申请失败')">添加好友</button>
    <button v-if="error && status === null" type="button" class="ui-button" :disabled="pending"
      @click="perform(friendStatus, '无法读取好友状态')">重试好友状态</button>
    <span v-if="error" :role="status === null ? 'status' : 'alert'" class="ui-muted text-xs">{{ error }}</span>
  </div>
</template>
