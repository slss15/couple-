import type { AxiosError } from 'axios'
import type { ApiErrorBody } from '@/types/api'

const ZH: Record<string, string> = {
  INVALID_CREDENTIALS: '用户名或密码错误',
  UNAUTHORIZED: '登录已失效，请重新登录',
  TOKEN_EXPIRED: '登录已过期，请重新登录',
  REFRESH_REUSED: '检测到令牌被重复使用，已强制下线，请重新登录',
  FORBIDDEN: '没有权限执行此操作',
  NOT_ADMIN: '该账号不是管理员',
  NOT_PAIRED: '尚未配对',
  NOT_FOUND: '资源不存在',
  VALIDATION_ERROR: '参数校验失败',
  NETWORK_ERROR: '网络错误，无法连接到服务器',
  INTERNAL_ERROR: '服务器内部错误',
  INTERNAL: '服务器内部错误',
  REFRESH_EXPIRED: '登录已过期，请重新登录',
  REFRESH_INVALID: '登录凭证无效，请重新登录',
  REFRESH_REVOKED: '登录凭证已被吊销，请重新登录',
  DEVICE_MISMATCH: '设备不匹配，请重新登录',
  CONFIG_INCOMPLETE: '请先在“服务器配置”中填写后端地址和 MQTT 地址并保存',
  AUTH_RATE_LIMITED: '尝试次数过多，请稍后再试',
  RELEASE_NOT_FOUND: '版本不存在',
  RATE_LIMITED: '请求过于频繁，请稍后再试',
  SETUP_TOKEN_INVALID: '初始化令牌不正确（请查看服务器日志中的 SETUP TOKEN 或 setup-token.txt）',
  SETUP_COMPLETED: '服务器已完成初始化，请直接登录',
  USERNAME_TAKEN: '用户名已存在',
  RELEASE_FILE_MISSING: '服务器上的 APK 文件缺失，请先重新同步',
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }

  /** 面向用户的中文提示 */
  get display(): string {
    return ZH[this.code] ?? this.message ?? this.code
  }
}

function isErrorBody(x: unknown): x is ApiErrorBody {
  return !!x && typeof x === 'object' && typeof (x as ApiErrorBody).code === 'string'
}

export function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err
  const ax = err as AxiosError
  if (ax && ax.isAxiosError) {
    const status = ax.response?.status ?? 0
    const data = ax.response?.data
    if (isErrorBody(data)) return new ApiError(status, data.code, data.message)
    if (!ax.response) return new ApiError(0, 'NETWORK_ERROR', ax.message || 'Network Error')
    return new ApiError(status, `HTTP_${status}`, ax.message)
  }
  return new ApiError(0, 'UNKNOWN', err instanceof Error ? err.message : String(err))
}

export function errorText(err: unknown): string {
  return toApiError(err).display
}
