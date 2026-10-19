<script setup lang="ts">
const navigationItems = [
  { name: 'discover', path: '/discover', label: '发现', icon: 'discover' },
  { name: 'sections', path: '/sections', label: '分区', icon: 'sections' },
  { name: 'feed', path: '/feed', label: '动态', icon: 'feed' },
  { name: 'ranking', path: '/ranking', label: '排行', icon: 'ranking' },
  { name: 'me', path: '/me', label: '我的', icon: 'me' },
] as const
</script>

<template>
  <nav class="mobile-navigation" aria-label="移动端导航">
    <ul class="mobile-navigation__list">
      <li v-for="item in navigationItems" :key="item.name" class="mobile-navigation__item">
        <RouterLink :to="item.path" custom v-slot="{ href, navigate, isActive }">
          <a
            :href="href"
            class="mobile-navigation__link"
            :class="{ 'mobile-navigation__link--active': isActive }"
            :aria-current="isActive ? 'page' : undefined"
            @click="navigate"
          >
            <svg v-if="item.icon === 'discover'" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
              <path d="m15.2 15.2-2.1-5.3-5.3-2.1 2.1 5.3 5.3 2.1Z" stroke-width="1.8" stroke-linejoin="round" />
              <circle cx="12" cy="12" r="9" stroke-width="1.8" />
            </svg>
            <svg v-else-if="item.icon === 'sections'" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
              <rect x="4" y="4" width="6" height="6" rx="1.5" stroke-width="1.8" />
              <rect x="14" y="4" width="6" height="6" rx="1.5" stroke-width="1.8" />
              <rect x="4" y="14" width="6" height="6" rx="1.5" stroke-width="1.8" />
              <rect x="14" y="14" width="6" height="6" rx="1.5" stroke-width="1.8" />
            </svg>
            <svg v-else-if="item.icon === 'feed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
              <path d="M5 6.5h14M5 12h14M5 17.5h9" stroke-width="1.8" stroke-linecap="round" />
              <circle cx="18" cy="17.5" r="2.5" stroke-width="1.8" />
            </svg>
            <svg v-else-if="item.icon === 'ranking'" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
              <path d="M5 19V9.5h4V19M10 19V5h4v14M15 19v-7.5h4V19" stroke-width="1.8" stroke-linejoin="round" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
              <circle cx="12" cy="8" r="3.2" stroke-width="1.8" />
              <path d="M5 19.2c.9-2.7 3.3-4.2 7-4.2s6.1 1.5 7 4.2" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            <span>{{ item.label }}</span>
          </a>
        </RouterLink>
      </li>
    </ul>
  </nav>
</template>

<style scoped>
.mobile-navigation {
  position: fixed;
  inset-inline: 0;
  bottom: 0;
  z-index: var(--ui-z-navigation, 40);
  display: block;
  padding-bottom: env(safe-area-inset-bottom, 0px);
  border-top: 1px solid var(--ui-border);
  background: color-mix(in srgb, var(--ui-surface) 96%, transparent);
  color: var(--ui-muted);
  box-shadow: 0 -4px 18px rgb(15 23 42 / 0.08);
  backdrop-filter: blur(14px);
}

.mobile-navigation__list {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  height: 64px;
  min-height: 64px;
  box-sizing: border-box;
  margin: 0;
  padding: 4px 8px;
  list-style: none;
}

.mobile-navigation__item {
  min-width: 0;
}

.mobile-navigation__link {
  display: flex;
  min-width: 48px;
  min-height: 48px;
  height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border-radius: 12px;
  color: inherit;
  font-size: 0.72rem;
  font-weight: 600;
  line-height: 1.1;
  text-decoration: none;
  transition: background-color 160ms ease-out, color 160ms ease-out;
}

.mobile-navigation__link svg {
  width: 21px;
  height: 21px;
  flex: 0 0 auto;
}

.mobile-navigation__link:hover,
.mobile-navigation__link:focus-visible,
.mobile-navigation__link--active {
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
}

@media (min-width: 1024px) {
  .mobile-navigation {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .mobile-navigation__link {
    transition: none;
  }
}
</style>
