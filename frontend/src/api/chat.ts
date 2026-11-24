import api from './client'
import { decodePageResponse } from './pagination'
import type { User } from '@/types'

export type Bucket = 'messages' | 'requests'
export type State = 'NEW' | 'PENDING' | 'ACTIVE' | 'DECLINED'
export interface Person { id: number; name: string }
export interface Preview {
  id: number
  peerId: number | null
  title: string
  state: State
  ownerId: number | null
  membership: 'JOINED' | 'INVITED'
  preview: string | null
  unread: boolean
}
export interface Quota { remaining: number; limit: number; resetAt: string }
export interface Direct {
  id: number | null
  peer: Person
  state: State
  mutual: boolean
  friend?: boolean
  canSend: boolean
  canDecide: boolean
  blockedByMe: boolean
  quota: Quota
}
export interface Message { id: number; seq: number; sender: Person; content: string | null; createdAt: string; recalled?: boolean }
export interface History { items: Message[]; hasMore: boolean; visibleFromSeq: number; revision: number }
export interface GroupMembership { state: Preview['membership']; ownerId: number }
export interface Member { user: Person; state: 'JOINED' | 'INVITED' }
export interface Send { clientMessageId: string; content: string }
interface Sent { conversationId: number; message: Message; state: State; quota: Quota }

const base = '/chat'

export async function conversationList(bucket: Bucket, page: number) {
  const { data } = await api.get(base + '/conversations', { params: { bucket, page, size: 30 } })
  return decodePageResponse<Preview>(data, { page, size: 30, endpoint: base + '/conversations' })
}

export async function friends(page: number, size = 30) {
  const { data } = await api.get(base + '/friends', { params: { page, size } })
  return decodePageResponse<User>(data, { page, size, endpoint: base + '/friends' })
}

export type FriendStatus = 'NONE' | 'SENT' | 'RECEIVED' | 'FRIEND' | 'UNAVAILABLE'

const friendRequestsEndpoint = base + '/friend-requests'

export async function incomingFriendRequests(page: number, size = 30) {
  const endpoint = friendRequestsEndpoint + '/incoming'
  const { data } = await api.get(endpoint, { params: { page, size } })
  return decodePageResponse<User>(data, { page, size, endpoint })
}

function friendRequestPath(peer: User['id']): string {
  return friendRequestsEndpoint + '/' + encodeURIComponent(String(peer))
}

export async function friendStatus(peer: User['id']): Promise<FriendStatus> {
  return (await api.get<{ status: FriendStatus }>(friendRequestPath(peer))).data.status
}

export async function sendFriendRequest(peer: User['id']): Promise<FriendStatus> {
  return (await api.put<{ status: FriendStatus }>(friendRequestPath(peer))).data.status
}

export async function decideFriendRequest(peer: User['id'], accept: boolean): Promise<FriendStatus> {
  return (await api.put<{ status: FriendStatus }>(friendRequestPath(peer) + '/decision', { accept })).data.status
}

export async function direct(peer: number) {
  return (await api.get<Direct>(base + '/direct/' + peer)).data
}

export async function sendDirect(peer: number, body: Send) {
  return (await api.post<Sent>(base + '/direct/' + peer + '/messages', body)).data
}

export async function decide(id: number, accept: boolean) {
  return (await api.put<Direct>(base + '/conversations/' + id + '/request', { accept })).data
}

export async function history(id: number, params: { beforeSeq?: number; afterSeq?: number } = {}) {
  return (await api.get<History>(base + '/conversations/' + id + '/messages', { params })).data
}

export async function recallMessage(conversationId: number, messageId: number): Promise<void> {
  await api.delete(base + '/conversations/' + conversationId + '/messages/' + messageId)
}

export async function read(id: number, seq: number) {
  await api.put(base + '/conversations/' + id + '/read', { seq })
}

export async function preferences() {
  return (await api.get<{ allowRequests: boolean }>(base + '/settings')).data.allowRequests
}

export async function savePreferences(allowRequests: boolean) {
  return (await api.patch<{ allowRequests: boolean }>(base + '/settings', { allowRequests })).data.allowRequests
}

export async function blocks() {
  return (await api.get<Person[]>(base + '/blocks')).data
}

export async function block(peer: number, value: boolean) {
  const path = base + '/blocks/' + peer
  if (value) await api.put(path)
  else await api.delete(path)
}

export async function createGroup(title: string, inviteeIds: number[]) {
  return (await api.post<{ id: number }>(base + '/groups', { title, inviteeIds })).data.id
}

export async function invite(id: number, userId: number) {
  await api.post(base + '/groups/' + id + '/invitations', { userId })
}

export async function respond(id: number, accept: boolean) {
  await api.put(base + '/groups/' + id + '/invitations/me', { accept })
}

export async function members(id: number) {
  return (await api.get<Member[]>(base + '/groups/' + id + '/members')).data
}

export async function groupMembership(id: number) {
  return (await api.get<GroupMembership>(base + '/groups/' + id + '/membership')).data
}

export async function transfer(id: number, userId: number) {
  await api.put(base + '/groups/' + id + '/owner', { userId })
}

export async function remove(id: number, user: number) {
  await api.delete(base + '/groups/' + id + '/members/' + user)
}

export async function closeGroup(id: number) {
  await api.delete(base + '/groups/' + id)
}

export async function sendGroup(id: number, body: Send) {
  return (await api.post<Message>(base + '/groups/' + id + '/messages', body)).data
}
