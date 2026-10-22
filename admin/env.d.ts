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
