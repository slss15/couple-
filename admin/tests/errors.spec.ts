import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/errors'

describe('ApiError.display：400 优先展示后端原因', () => {
  it('布局超宽的 VALIDATION_ERROR 透出具体原因，而不是“参数校验失败”', () => {
    const err = new ApiError(400, 'VALIDATION_ERROR', '区域 bottomBar 网格宽 16 格，可见项共占 18 格')
    expect(err.display).toBe('区域 bottomBar 网格宽 16 格，可见项共占 18 格')
  })

  it('400 但 message 为空或只有空白时回退到中文映射表', () => {
    expect(new ApiError(400, 'VALIDATION_ERROR', '').display).toBe('参数校验失败')
    expect(new ApiError(400, 'VALIDATION_ERROR', '   ').display).toBe('参数校验失败')
  })

  it('非 400 仍然使用映射表，不把后端英文内部信息抛给用户', () => {
    expect(new ApiError(404, 'NOT_PAIRED', 'not paired').display).toBe('尚未配对')
    expect(new ApiError(500, 'INTERNAL_ERROR', 'boom').display).toBe('服务器内部错误')
  })

  it('未知 code 时回退到后端 message', () => {
    expect(new ApiError(409, 'SOME_NEW_CODE', '新错误').display).toBe('新错误')
  })
})
