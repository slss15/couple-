<script setup lang="ts">
import type { SecretField } from '@/utils/configForm'

const model = defineModel<SecretField>({ required: true })
defineProps<{ placeholder?: string }>()
</script>

<template>
  <div class="secret">
    <el-input
      v-model="model.value"
      type="password"
      show-password
      autocomplete="new-password"
      :disabled="model.clear"
      :placeholder="model.masked ? `已设置（${model.masked}），留空保持不变` : (placeholder ?? '未设置')"
    />
    <div class="row">
      <el-tag v-if="model.masked && !model.clear && !model.value" size="small" type="success">已设置</el-tag>
      <el-tag v-else-if="model.value && !model.clear" size="small" type="warning">将覆盖</el-tag>
      <el-tag v-else-if="!model.masked && !model.clear" size="small" type="info">未设置</el-tag>
      <el-checkbox v-if="model.masked" v-model="model.clear" size="small">清除已保存的值</el-checkbox>
      <span class="muted">只写字段，服务器不会返回明文</span>
    </div>
  </div>
</template>

<style scoped>
.secret { width: 100%; }
.row { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
</style>
