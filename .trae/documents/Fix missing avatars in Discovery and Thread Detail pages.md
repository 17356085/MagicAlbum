I will fix the issue where user avatars are not displayed in the thread list and thread detail pages.

The root cause is that the frontend was only fetching the user's nickname and displaying initials, ignoring the `avatarUrl` returned by the backend.

My plan is:

1.  **Update `front/src/pages/Discover.vue`**:
    *   Import `normalizeImageUrl` from `@/utils/image`.
    *   Modify the `load` function to store the `avatarUrl` (as `avatar`) in the `profiles` map when fetching user profiles.
    *   Update the template to render an `<img>` tag with the user's avatar if available; otherwise, fall back to the existing initials display.

2.  **Update `front/src/pages/ThreadDetail.vue`**:
    *   Import `normalizeImageUrl` from `@/utils/image` (replacing the local function to reduce duplication).
    *   Add a new reactive variable `authorAvatar` to store the author's avatar URL.
    *   Modify the `load` function to set `authorAvatar` from the fetched user profile.
    *   Update the template to render an `<img>` tag with the author's avatar if available; otherwise, fall back to the existing initials display.

This will ensure that avatars are correctly displayed in both the discovery list and the thread detail view.