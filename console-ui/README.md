# 管控台前端（console-ui）

Vue 3 + TypeScript + Vite。构建产物嵌入网关 jar 的 `/console/`。

## 视觉改版（自检）

**目标**：云控制台 / MaxGUI-lite 观感——层次更清晰、密度适中、交互反馈一致；**不改 API 契约**。

| 项 | 决策 |
|---|---|
| 主题 | 深色默认（`--bg` / `--bg-card` 等 CSS 变量）；克制蓝青强调色，避免霓虹 |
| Shell | 侧栏图标导航 + sticky 顶栏（健康芯片 / 鉴权模式 / 用户角色） |
| 令牌 | `src/styles/tokens.css` 统一色板、圆角、阴影、字号、间距、动效 |
| 组件 | 全局按钮/表单/徽章/空态/骨架/Toast；卡片 hover + focus-visible |
| 页面 | 总览 KPI、实例工具栏与空态、抽屉滑入、SQL IDE 分栏、登录卡片、目录表 |
| 可访问性 | 焦点环、`role`/`aria-live` Toast、对比度按深色面板设计 |
| 依赖 | 无新增 UI 库（保持 vue + vue-router） |

**自检**：`npm run build`（含 `vue-tsc`）通过；RBAC 按钮显隐与「网关代理 / 透明代理」徽章语义不变。

## 开发

```bash
npm install
npm run dev      # 需代理到网关 /console/api/v1
npm run build    # 输出 dist/ → 由 Maven 资源拷贝进 jar
```
