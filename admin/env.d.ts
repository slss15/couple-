/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端根地址（不含 /api/v1）。留空则走同源 + dev 代理。 */
  readonly VITE_API_BASE?: string
  /** dev 代理目标，仅 vite dev server 使用 */
  readonly VITE_PROXY_TARGET?: string
}
interface ImportMeta {
  readonly env: ImportMetaEnv
}

/**
 * 容器启动时写入 /config.js 的运行时配置（见 docker/40-couple-config.sh 与后端 AdminStatic.kt）。
 * 构建期拿不到，所以字段全部可选；本地 dev 没有这个对象，apiRoot() 会继续回退到 VITE_API_BASE。
 */
interface CoupleRuntimeConfig {
  /** 后端根地址（不含 /api/v1）。空串 = 与后台同源（推荐，由 nginx 反代 /api） */
  apiBase?: string
  /** 镜像版本号，仅用于展示 */
  version?: string
}

interface Window {
  __COUPLE_CONFIG__?: CoupleRuntimeConfig
}
