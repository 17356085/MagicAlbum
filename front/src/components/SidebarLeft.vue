<template>
  <aside class="space-y-4">
    <!-- 主导航菜单 -->
    <nav class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="px-4 py-3 text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">
        浏览
      </div>
      <ul class="space-y-1 px-2 pb-2">
        <!-- 分区（可折叠） -->
        <li>
          <button
            @click="toggleSections"
            class="group flex w-full items-center justify-between rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isSectionsActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <div class="flex items-center gap-3">
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
                <path stroke-linecap="round" stroke-linejoin="round" d="M3.75 6A2.25 2.25 0 016 3.75h2.25A2.25 2.25 0 0110.5 6v2.25a2.25 2.25 0 01-2.25 2.25H6a2.25 2.25 0 01-2.25-2.25V6zM3.75 15.75A2.25 2.25 0 016 13.5h2.25a2.25 2.25 0 012.25 2.25V18a2.25 2.25 0 01-2.25 2.25H6A2.25 2.25 0 013.75 18v-2.25zM13.5 6a2.25 2.25 0 012.25-2.25H18A2.25 2.25 0 0120.25 6v2.25A2.25 2.25 0 0118 10.5h-2.25a2.25 2.25 0 01-2.25-2.25V6zM13.5 15.75a2.25 2.25 0 012.25-2.25H18a2.25 2.25 0 012.25 2.25V18A2.25 2.25 0 0118 20.25h-2.25A2.25 2.25 0 0113.5 18v-2.25z" />
              </svg>
              <span>分区</span>
            </div>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-4 w-4 text-gray-400 transition-transform duration-200" :class="{ 'rotate-90': sectionsOpen }">
              <path fill-rule="evenodd" d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z" clip-rule="evenodd" />
            </svg>
          </button>
          
          <!-- 子菜单：分区列表 -->
          <div
            class="grid overflow-hidden transition-all duration-300 ease-in-out"
            :class="sectionsOpen ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'"
          >
            <div class="min-h-0 space-y-0.5 pl-10 pr-2 pt-1">
              <router-link
                to="/sections"
                class="block rounded-md px-2 py-1.5 text-xs transition-colors hover:bg-gray-50 hover:text-gray-900 dark:hover:bg-gray-700 dark:hover:text-gray-100"
                :class="route.path === '/sections' ? 'bg-gray-100 text-gray-900 font-medium dark:bg-gray-700 dark:text-gray-100' : 'text-gray-500 dark:text-gray-400'"
              >
                全部/概览
              </router-link>
              <div v-if="loadingSections" class="px-2 py-1 text-xs text-gray-400">加载中...</div>
              <router-link
                v-else
                v-for="s in sections"
                :key="s.id"
                :to="{ name: 'discover', query: { sectionId: s.id } }"
                class="block truncate rounded-md px-2 py-1.5 text-xs transition-colors hover:bg-gray-50 hover:text-gray-900 dark:hover:bg-gray-700 dark:hover:text-gray-100"
                :class="(route.name === 'discover' && String(route.query.sectionId) === String(s.id)) ? 'bg-brandDay-50 text-brandDay-600 font-medium dark:bg-brandNight-900/30 dark:text-brandNight-400' : 'text-gray-500 dark:text-gray-400'"
              >
                {{ s.name }}
              </router-link>
            </div>
          </div>
        </li>

        <!-- 发现 -->
        <li>
          <router-link
            to="/discover"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isDiscoverActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15.042 21.672L13.684 16.6m0 0l-2.51 2.225.569-9.47 5.227 7.917-3.286-.672zm-7.518-.267A8.25 8.25 0 1120.25 10.5M8.288 14.212A5.25 5.25 0 1117.25 10.5" />
            </svg>
            <span>发现</span>
          </router-link>
        </li>

        <!-- 排行榜 -->
        <li>
          <router-link
            to="/ranking"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isRankingActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z" />
            </svg>
            <span>排行榜</span>
          </router-link>
        </li>
      </ul>
    </nav>

    <!-- 标签 -->
    <div class="rounded-xl border border-gray-100 bg-white p-4 shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="mb-3 flex items-center justify-between gap-3">
        <span class="text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">
          {{ tagPanelTitle }}
        </span>
        <span v-if="currentTagSectionName" class="max-w-[7rem] truncate rounded bg-brandDay-50 px-2 py-0.5 text-[10px] font-medium text-brandDay-600 dark:bg-brandNight-900/30 dark:text-brandNight-300">
          {{ currentTagSectionName }}
        </span>
      </div>
      <div v-if="tagsLoading" class="py-2 text-xs text-gray-400">加载中...</div>
      <div v-else-if="tagsError" class="py-2 text-xs text-red-500">{{ tagsError }}</div>
      <div v-else-if="popularTags.length === 0" class="py-2 text-xs text-gray-400">
        暂无标签
      </div>
      <div v-else class="flex flex-wrap gap-2">
        <router-link
          v-for="tag in popularTags"
          :key="tag.id"
          :to="tagLink(tag.name)"
          :title="`${tag.name} · ${tag.threadCount} 篇帖子`"
          class="group inline-flex items-center gap-1 rounded-md bg-gray-50 px-2.5 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-brandDay-50 hover:text-brandDay-600 dark:bg-gray-700/50 dark:text-gray-300 dark:hover:bg-brandNight-900/30 dark:hover:text-brandNight-300"
        >
          <span>#{{ tag.name }}</span>
          <span class="text-[10px] text-gray-400 transition-colors group-hover:text-brandDay-400 dark:group-hover:text-brandNight-400">{{ tag.threadCount }}</span>
        </router-link>
      </div>
      <div class="mt-3 flex justify-end">
        <button
          type="button"
          class="inline-flex items-center gap-1 text-xs font-medium text-brandDay-500 hover:text-brandDay-600 dark:text-brandNight-400 dark:hover:text-brandNight-300"
          @click="toggleTagsExpanded"
        >
          <span>{{ tagsExpanded ? '收起标签' : '更多标签' }}</span>
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-3.5 w-3.5 transition-transform" :class="{ 'rotate-180': tagsExpanded }">
            <path fill-rule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 10.94l3.71-3.71a.75.75 0 111.08 1.04l-4.25 4.25a.75.75 0 01-1.08 0L5.21 8.27a.75.75 0 01.02-1.06z" clip-rule="evenodd" />
          </svg>
        </button>
      </div>

      <div
        class="grid overflow-hidden transition-all duration-300 ease-in-out"
        :class="tagsExpanded ? 'mt-3 grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'"
      >
        <div class="min-h-0">
          <div class="rounded-lg border border-gray-100 bg-gray-50/70 p-2.5 dark:border-gray-700 dark:bg-gray-900/30">
            <input
              v-model="tagSearch"
              type="text"
              class="w-full rounded-md border border-gray-200 bg-white px-2.5 py-1.5 text-xs text-gray-700 focus:border-brandDay-500 focus:outline-none focus:ring-1 focus:ring-brandDay-500 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
              :placeholder="currentTagSectionName ? `搜索${currentTagSectionName}标签` : '搜索标签'"
            />
            <div v-if="allTagsLoading" class="py-4 text-center text-xs text-gray-400">加载中...</div>
            <div v-else-if="allTagsError" class="py-4 text-center text-xs text-red-500">{{ allTagsError }}</div>
            <div v-else-if="allTags.length === 0" class="py-4 text-center text-xs text-gray-400">暂无匹配标签</div>
            <div v-else class="mt-2 flex flex-wrap gap-2">
              <router-link
                v-for="tag in allTags"
                :key="tag.id"
                :to="tagLink(tag.name)"
                :title="`${tag.name} · ${tag.threadCount} 篇帖子`"
                class="group inline-flex max-w-full items-center gap-1 rounded-md bg-white px-2 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-brandDay-50 hover:text-brandDay-600 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-brandNight-900/30 dark:hover:text-brandNight-300"
              >
                <span class="truncate">#{{ tag.name }}</span>
                <span class="text-[10px] text-gray-400 transition-colors group-hover:text-brandDay-400 dark:group-hover:text-brandNight-400">{{ tag.threadCount }}</span>
              </router-link>
            </div>
            <div class="mt-3 flex items-center justify-between text-[11px] text-gray-400">
              <span>共 {{ allTagsTotal }} 个标签</span>
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  class="rounded border border-gray-200 bg-white px-2 py-1 hover:text-gray-600 disabled:opacity-50 dark:border-gray-700 dark:bg-gray-800 dark:hover:text-gray-200"
                  :disabled="allTagsPage <= 1 || allTagsLoading"
                  @click="setTagsPage(allTagsPage - 1)"
                >
                  上页
                </button>
                <span>{{ allTagsPage }} / {{ allTagsTotalPages }}</span>
                <button
                  type="button"
                  class="rounded border border-gray-200 bg-white px-2 py-1 hover:text-gray-600 disabled:opacity-50 dark:border-gray-700 dark:bg-gray-800 dark:hover:text-gray-200"
                  :disabled="allTagsPage >= allTagsTotalPages || allTagsLoading"
                  @click="setTagsPage(allTagsPage + 1)"
                >
                  下页
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 动态 -->
    <div v-if="isLoggedIn" class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="flex items-center justify-between px-4 py-3">
        <span class="text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">动态</span>
        <button class="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-300" @click="toggleFollowCollapsed">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-4 w-4 transition-transform duration-200" :class="{ 'rotate-180': !followCollapsed }">
            <path fill-rule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 10.94l3.71-3.71a.75.75 0 011.08 1.04l-4.25 4.25a.75.75 0 01-1.08 0L5.21 8.27a.75.75 0 01.02-1.06z" clip-rule="evenodd" />
          </svg>
        </button>
      </div>
      
      <div v-show="!followCollapsed" class="px-2 pb-2">
        <div v-if="followLoading" class="py-4 text-center text-xs text-gray-400">加载中...</div>
        <div v-else-if="followError" class="py-4 text-center text-xs text-red-500">{{ followError }}</div>
        <div v-else-if="feedItems.length === 0" class="py-4 text-center text-xs text-gray-400">暂无动态</div>
        <ul v-else class="space-y-1">
          <li v-for="item in feedItems" :key="item.id" class="group rounded-lg transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/50">
            <router-link :to="`/threads/${item.id}`" class="flex items-start gap-3 p-2">
              <img
                :src="avatarOf(item)"
                alt="avatar"
                class="mt-0.5 h-8 w-8 rounded-full object-cover ring-2 ring-transparent transition-all group-hover:ring-brandDay-100 dark:group-hover:ring-brandNight-900"
              />
              <div class="min-w-0 flex-1">
                <div class="flex items-center justify-between">
                   <div class="truncate text-sm font-medium text-gray-700 dark:text-gray-200">
                     {{ displayNameOf(item) }}
                   </div>
                   <span class="shrink-0 text-[10px] text-gray-400">{{ formatRelativeTime(item.createdAt || item.updatedAt) }}</span>
                </div>
                <div class="mt-0.5 line-clamp-2 text-xs font-medium leading-5 text-gray-600 group-hover:text-brandDay-700 dark:text-gray-300 dark:group-hover:text-brandNight-300">
                  {{ item.title }}
                </div>
              </div>
            </router-link>
          </li>
        </ul>
        <div v-if="!followLoading && !followError" class="mt-2 flex justify-end border-t border-gray-50 pt-2 dark:border-gray-700/50">
          <router-link to="/feed" class="text-xs font-medium text-brandDay-500 hover:text-brandDay-600 dark:text-brandNight-400 dark:hover:text-brandNight-300">
            查看更多
          </router-link>
        </div>
      </div>
    </div>

    <!-- 最近浏览 -->
    <div v-if="isLoggedIn" class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="flex items-center justify-between px-4 py-3">
        <span class="text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">最近浏览</span>
        <div class="flex gap-2">
           <button class="text-[10px] text-gray-400 hover:text-brandDay-600 dark:hover:text-brandNight-400" @click="refreshRecent">刷新</button>
           <button class="text-[10px] text-gray-400 hover:text-red-500" @click="clearRecent">清除</button>
        </div>
      </div>
      
      <div class="px-2 pb-2">
        <div v-if="recentVisits.length === 0" class="py-4 text-center text-xs text-gray-400">暂无浏览记录</div>
        <ul v-else class="space-y-0.5">
          <li v-for="v in recentVisits" :key="v.path">
            <router-link :to="v.path" class="group flex items-center justify-between rounded-md px-2 py-1.5 text-xs text-gray-600 transition-colors hover:bg-gray-50 hover:text-brandDay-700 dark:text-gray-400 dark:hover:bg-gray-700/50 dark:hover:text-brandNight-300">
              <span class="truncate pr-2">{{ v.title || v.name || v.path }}</span>
              <span class="shrink-0 text-[10px] text-gray-400 opacity-0 transition-opacity group-hover:opacity-100">{{ formatRelativeShort(v.ts) }}</span>
            </router-link>
          </li>
        </ul>
        <div class="mt-2 border-t border-gray-50 pt-2 text-center dark:border-gray-700/50">
          <router-link to="/history" class="text-xs font-medium text-brandDay-500 hover:text-brandDay-600 dark:text-brandNight-400 dark:hover:text-brandNight-300">
            查看全部浏览
          </router-link>
        </div>
      </div>
    </div>

    <!-- 个人中心菜单 (仅登录) -->
    <nav v-if="isLoggedIn" class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="px-4 py-3 text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">
        我的
      </div>
      <ul class="space-y-1 px-2 pb-2">
        <li>
          <router-link
            to="/my/threads"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isMyThreadsActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
            </svg>
            <span>我的帖子</span>
          </router-link>
        </li>
        <li>
          <router-link
            to="/my/posts"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isMyPostsActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 01.865-.501 48.172 48.172 0 003.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z" />
            </svg>
            <span>我的评论</span>
          </router-link>
        </li>
        <li>
          <router-link
            to="/settings"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isSettingsActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M10.343 3.94c.09-.542.56-.94 1.11-.94h1.093c.55 0 1.02.398 1.11.94l.149.894c.07.424.384.764.78.93.398.164.855.142 1.205-.108l.737-.527a1.125 1.125 0 011.45.12l.773.774c.39.389.44 1.002.12 1.45l-.527.737c-.25.35-.272.806-.107 1.204.165.397.505.71.93.78l.893.15c.543.09.94.56.94 1.109v1.094c0 .55-.397 1.02-.94 1.11l-.893.149c-.425.07-.765.383-.93.78-.165.398-.143.854.107 1.204l.527.738c.32.447.269 1.06-.12 1.45l-.774.773a1.125 1.125 0 01-1.449.12l-.738-.527c-.35-.25-.806-.272-1.203-.107-.397.165-.71.505-.781.929l-.149.894c-.09.542-.56.94-1.11.94h-1.094c-.55 0-1.019-.398-1.11-.94l-.148-.894c-.071-.424-.384-.764-.781-.93-.398-.164-.854-.142-1.204.108l-.738.527c-.447.32-1.06.269-1.45-.12l-.773-.774a1.125 1.125 0 01-.12-1.45l.527-.737c.25-.35.273-.806.108-1.204-.165-.397-.505-.71-.93-.78l-.894-.15c-.542-.09-.94-.56-.94-1.109v-1.094c0-.55.398-1.02.94-1.11l.894-.149c.424-.07.765-.383.93-.78.165-.398.143-.854-.107-1.204l-.527-.738a1.125 1.125 0 01.12-1.45l.773-.773a1.125 1.125 0 011.45-.12l.737.527c.35.25.807.272 1.204.107.397-.165.71-.505.78-.929l.15-.894z" />
              <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
            <span>设置</span>
          </router-link>
        </li>
      </ul>
    </nav>
  </aside>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { getRecentVisits } from '@/composables/useRecentVisits'
import { listSections } from '@/api/sections'
import { listFollowingFeed } from '@/api/followingFeed'
import { listPopularTags, listTags } from '@/api/tags'
import { getCurrentUser } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { getSingleQueryValue } from '@/utils/router'
import { formatRelativeTime } from '@/composables/time'
import type { Id, RecentVisit, Section, TagStats } from '@/types'
import type { FollowingFeedItem } from '@/api/followingFeed'

const route = useRoute()
const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const isSectionsActive = computed(() => route.name === 'sections' || route.path === '/sections')
const isDiscoverActive = computed(() => route.name === 'discover' || route.path === '/discover')
const isRankingActive = computed(() => route.name === 'ranking' || route.path === '/ranking')
const isSettingsActive = computed(() => route.path.startsWith('/settings'))
const isMyThreadsActive = computed(() => route.name === 'my-threads' || route.path === '/my/threads')
const isMyPostsActive = computed(() => route.name === 'my-posts' || route.path === '/my/posts')

// 分区折叠逻辑
const sectionsOpen = ref(true)
const sections = ref<Section[]>([])
const loadingSections = ref(false)
const popularTags = ref<TagStats[]>([])
const tagsLoading = ref(false)
const tagsError = ref('')
let tagsRequestSeq = 0
const tagsExpanded = ref(false)
const tagSearch = ref('')
const allTags = ref<TagStats[]>([])
const allTagsLoading = ref(false)
const allTagsError = ref('')
const allTagsPage = ref(1)
const allTagsSize = ref(18)
const allTagsTotal = ref(0)
let allTagsRequestSeq = 0
let tagSearchTimer: ReturnType<typeof setTimeout> | null = null

const currentTagSectionId = computed<Id | null>(() => {
  const raw = route.query.sectionId ? getSingleQueryValue(route.query.sectionId) : ''
  return raw || null
})

const currentTagSectionName = computed(() => {
  if (!currentTagSectionId.value) return ''
  return sections.value.find((section) => String(section.id) === String(currentTagSectionId.value))?.name || ''
})

const tagPanelTitle = computed(() => currentTagSectionId.value ? '分区热门标签' : '热门标签')
const allTagsTotalPages = computed(() => Math.max(1, Math.ceil(allTagsTotal.value / allTagsSize.value)))

function tagLink(name: string) {
  const query: Record<string, Id> = { tag: name, page: 1 }
  if (currentTagSectionId.value) {
    query.sectionId = currentTagSectionId.value
  }
  return { name: 'discover', query }
}

async function loadSectionsData(): Promise<void> {
  if (sections.value.length > 0) return
  loadingSections.value = true
  try {
    const data = await listSections({ size: 20 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (_) {
  } finally {
    loadingSections.value = false
  }
}

async function loadTagsData(): Promise<void> {
  const requestSeq = ++tagsRequestSeq
  tagsLoading.value = true
  tagsError.value = ''
  try {
    const items = await listPopularTags({ sectionId: currentTagSectionId.value, size: 12 })
    if (requestSeq === tagsRequestSeq) {
      popularTags.value = items
    }
  } catch (_) {
    if (requestSeq === tagsRequestSeq) {
      popularTags.value = []
      tagsError.value = '标签加载失败'
    }
  } finally {
    if (requestSeq === tagsRequestSeq) {
      tagsLoading.value = false
    }
  }
}

async function loadAllTagsData(): Promise<void> {
  const requestSeq = ++allTagsRequestSeq
  allTagsLoading.value = true
  allTagsError.value = ''
  try {
    const data = await listTags({
      q: tagSearch.value,
      sectionId: currentTagSectionId.value,
      page: allTagsPage.value,
      size: allTagsSize.value,
    })
    if (requestSeq === allTagsRequestSeq) {
      allTags.value = data.items || []
      allTagsTotal.value = Number(data.total || 0)
    }
  } catch (_) {
    if (requestSeq === allTagsRequestSeq) {
      allTags.value = []
      allTagsTotal.value = 0
      allTagsError.value = '标签加载失败'
    }
  } finally {
    if (requestSeq === allTagsRequestSeq) {
      allTagsLoading.value = false
    }
  }
}

function toggleTagsExpanded(): void {
  tagsExpanded.value = !tagsExpanded.value
  if (tagsExpanded.value && allTags.value.length === 0 && !allTagsLoading.value) {
    allTagsPage.value = 1
    loadAllTagsData()
  }
}

function setTagsPage(page: number): void {
  const next = Math.min(Math.max(1, page), allTagsTotalPages.value)
  if (next === allTagsPage.value) return
  allTagsPage.value = next
  loadAllTagsData()
}

function toggleSections(): void {
  sectionsOpen.value = !sectionsOpen.value
  if (sectionsOpen.value) {
    loadSectionsData()
  }
}

// 关注的用户
const followCollapsed = ref(false)
const feedItems = ref<FollowingFeedItem[]>([])
const followLoading = ref(false)
const followError = ref('')
let followRequestSeq = 0

function toggleFollowCollapsed(): void {
  followCollapsed.value = !followCollapsed.value
  try { localStorage.setItem('sidebar_follow_collapsed', String(followCollapsed.value)) } catch (_) {}
}

const currentUserId = computed(() => user.value?.id || user.value?.userId || null)

async function resolveCurrentUserId(): Promise<Id | null> {
  if (!isLoggedIn.value) return null
  if (currentUserId.value) return currentUserId.value
  try {
    const current = await getCurrentUser()
    const id = current?.id || current?.userId || null
    if (current && id) {
      authStore.updateCurrentUser(current)
      return id
    }
  } catch (_) {}
  return null
}

function displayNameOf(item: FollowingFeedItem): string {
  return item.authorNickname || item.authorUsername || item.followedAuthor.nickname || item.followedAuthor.username
}

function avatarOf(item: FollowingFeedItem): string {
  const name = displayNameOf(item)
  return normalizeImageUrl(item.authorAvatarUrl || item.followedAuthor.avatarUrl || `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(name || 'U')}`)
}

async function loadFeedItems(): Promise<void> {
  const requestSeq = ++followRequestSeq
  if (!isLoggedIn.value) {
    feedItems.value = []
    followError.value = ''
    followLoading.value = false
    return
  }
  feedItems.value = []
  followLoading.value = true
  followError.value = ''
  try {
    const userId = await resolveCurrentUserId()
    if (requestSeq !== followRequestSeq) return
    if (!userId) {
      feedItems.value = []
      return
    }
    const page = await listFollowingFeed(userId, { page: 1, size: 4, perUserSize: 3 })
    if (requestSeq === followRequestSeq) {
      feedItems.value = page.items || []
    }
  } catch (_) {
    if (requestSeq === followRequestSeq) {
      feedItems.value = []
      followError.value = '动态加载失败'
    }
  } finally {
    if (requestSeq === followRequestSeq) {
      followLoading.value = false
    }
  }
}

function onFollowStateUpdated(): void {
  loadFeedItems()
}

function onThreadTagsUpdated(): void {
  loadTagsData()
  if (tagsExpanded.value) {
    loadAllTagsData()
  }
}

function onAuthStateChanged(): void {
  followRequestSeq += 1
  feedItems.value = []
  followError.value = ''
  followLoading.value = false
  refreshRecent()
  loadTagsData()
  loadFeedItems()
}

// 最近浏览
const recentVisits = ref<RecentVisit[]>(getRecentVisits(5))
function refreshRecent(): void { recentVisits.value = getRecentVisits(5) }
function onRecentUpdated(): void { refreshRecent() }
function clearRecent(): void {
  try { localStorage.removeItem('recent_visits_v1') } catch (_) {}
  refreshRecent()
}

function formatRelativeShort(ts: number | string | null | undefined): string {
  const diff = Date.now() - Number(ts || 0)
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  return `${Math.floor(h / 24)}d`
}

onMounted(() => {
  try {
    const raw = localStorage.getItem('sidebar_follow_collapsed')
    if (raw === 'true') followCollapsed.value = true // Default open, save logic inverted? No, usually collapse=true means hidden.
  } catch (_) {}
  
  // 默认加载分区
  loadSectionsData()
  loadTagsData()
  loadFeedItems()
  
  window.addEventListener('recent-visits-updated', onRecentUpdated)
  window.addEventListener('follow-state-updated', onFollowStateUpdated)
  window.addEventListener('thread-tags-updated', onThreadTagsUpdated)
  window.addEventListener('auth-state-changed', onAuthStateChanged)
})

watch([currentUserId, isLoggedIn], () => {
  followRequestSeq += 1
  feedItems.value = []
  followError.value = ''
  followLoading.value = false
  loadFeedItems()
})

watch(currentTagSectionId, () => {
  loadTagsData()
  if (tagsExpanded.value) {
    allTagsPage.value = 1
    loadAllTagsData()
  }
})

watch(tagSearch, () => {
  if (!tagsExpanded.value) return
  if (tagSearchTimer) {
    clearTimeout(tagSearchTimer)
  }
  tagSearchTimer = setTimeout(() => {
    allTagsPage.value = 1
    loadAllTagsData()
  }, 250)
})

onBeforeUnmount(() => {
  if (tagSearchTimer) {
    clearTimeout(tagSearchTimer)
  }
  window.removeEventListener('recent-visits-updated', onRecentUpdated)
  window.removeEventListener('follow-state-updated', onFollowStateUpdated)
  window.removeEventListener('thread-tags-updated', onThreadTagsUpdated)
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>
