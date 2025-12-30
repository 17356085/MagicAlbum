# UI 视觉设计规范 (UI Visual Design Standards)

## 1. 圆角矩形 (Rounded Rectangles)

为了保持界面的一致性和现代感，所有页面组件应遵循以下圆角规范：

### 1.1 卡片容器 (Card Containers)
适用于页面主要内容区域、独立模块容器。
- **Class**: `.u-card` (Defined in `main.css`)
- **Tailwind**: `rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700`
- **圆角半径**: `12px` (0.75rem)
- **边框**: `1px solid gray-100` (Light), `gray-700` (Dark)
- **阴影**: `shadow-sm`

### 1.2 列表项/内部卡片 (List Items / Inner Cards)
适用于卡片内部的列表项、评论块、帖子摘要等。
- **Class**: `.u-item` (Defined in `main.css`)
- **Tailwind**: `rounded-lg border border-gray-100 bg-white dark:bg-gray-800 dark:border-gray-700`
- **圆角半径**: `8px` (0.5rem)

### 1.3 交互控件 (Interactive Controls)
适用于按钮、输入框、下拉选框等。
- **Class**: `.u-control` (Defined in `main.css`)
- **Tailwind**: `rounded`
- **圆角半径**: `4px` (0.25rem)
- **注意**: 部分大型编辑器组件可使用 `rounded-lg`。

## 2. 阴影 (Shadows)
- **默认卡片**: `shadow-sm`
- **悬停效果**: `hover:shadow-md` (适用于可点击的卡片)

## 3. 边框颜色 (Border Colors)
- **浅色模式**: `border-gray-100` (用于卡片/容器), `border-gray-200` (用于分割线), `border-gray-300` (用于输入框)
- **深色模式**: `border-gray-700`

---
*最后更新: 2025-12-30*
