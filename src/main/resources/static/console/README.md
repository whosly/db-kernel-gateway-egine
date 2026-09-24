# /console 静态资源

正式 UI 由 `console-ui/`（Vue 3 + Vite）构建，经 `frontend-maven-plugin`
在 `mvn package` / `prepare-package` 阶段输出到
`target/classes/static/console/`（覆盖本目录占位）。

本地前端开发：

```bash
cd console-ui && npm ci && npm run dev
```

Vite 将 `/console/api` 代理到 `http://127.0.0.1:8080`；后端另开
`mvn spring-boot:run`。
