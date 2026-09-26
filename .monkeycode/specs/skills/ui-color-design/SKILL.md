---
name: ui-color-design
description: Material3 色彩与视觉层级评审 Skill。涵盖 M3 Color-Tokens、亮色/暗色双模式适配、WCAG-AA 文本对比度与 48dp 触控热区约束。
---

# UI-Color-Design - 色彩与视觉设计评审规范

## 适用范围与设计基准
- **适用项目**：InkLink-Pet Android Kotlin (普通彩色手机，非墨水屏)
- **设计基准**：Material3，支持亮色 / 暗色模式
- **参考规范**：`.monkeycode/specs/pet-system/design.md`

## 核心原则
- **配色收敛**：遵循 Material3 语义 Token（primary / onPrimary / secondary / surface / error），主色调不超过 3 种，严禁高饱和撞色。
- **双模适配**：亮色与暗色模式完整覆盖，严禁硬编码颜色字符串，优先引用主题 Color 资源。
- **可读性与无障碍**：普通文本对比度 $\ge 4.5:1$，大文本 $\ge 3:1$（WCAG-AA）；触控热区 $\ge 48\text{dp}$。
- **视觉层级**：主操作 > 次要操作 > 辅助信息；阴影与 Elevation 随主题自适应。
- **动效协调**：Rive 与 Konfetti 粒子色彩需与当前主题调和，不得遮挡文字。
