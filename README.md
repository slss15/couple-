# Couple

两人自用的情侣 App（monorepo）。

| 目录 | 内容 |
|---|---|
| [`backend/`](backend/README.md) | Ktor 后端（MySQL + EMQX） |
| [`admin/`](admin/README.md) | Vue 管理后台 |
| [`app/`](app/README.md) | Android App |
| [`deploy/`](deploy/README.md) | 生产部署、推送即部署、自动更新、发版 |
| [`contract.md`](contract.md) | 跨模块接口契约 |

**从零搭建服务器：照着 [`deploy/SETUP.md`](deploy/SETUP.md) 一步步做。**
发版：`git tag -a vX.Y.Z -m "说明" && git push origin vX.Y.Z`；推 `main` 自动部署 backend/admin，详见 [`deploy/README.md`](deploy/README.md)。
