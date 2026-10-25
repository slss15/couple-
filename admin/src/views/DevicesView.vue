<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '@/api'
import { errorText } from '@/api/errors'
import type { DeviceReportView } from '@/types/api'
import { formatTs } from '@/utils/format'
import { shortDigest } from '@/utils/releases'

const devices = ref<DeviceReportView[]>([])
const loading = ref(false)
const loadErr = ref('')

async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    devices.value = await api.devices()
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function copyId(id: string) {
  try {
    await navigator.clipboard.writeText(id)
    ElMessage.success('设备 ID 已复制')
  } catch {
    ElMessage.error('复制失败，请从悬停提示中手动复制')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="head">
      <h1 class="page-title">设备</h1>
      <div class="actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>
    <p class="muted">
      每台设备最近一次向服务器上报的信息（App 检查更新或健康上报时刷新）。要把某台手机设为 canary（灰度先行设备），复制它的设备 ID 填到
      「版本发布 → 发布设置 → Canary 设备」。
    </p>

    <el-alert v-if="loadErr" :title="`加载失败：${loadErr}`" type="error" show-icon class="mb" />
    <el-table v-loading="loading" :data="devices" border stripe empty-text="还没有设备上报过（App 登录后检查一次更新即可）">
      <el-table-column label="设备 ID" min-width="300">
        <template #default="{ row }">
          <div class="dev">
            <el-tooltip :content="row.deviceId" placement="top">
              <span class="mono small">{{ shortDigest(row.deviceId, 20) }}</span>
            </el-tooltip>
            <el-button link type="primary" @click="copyId(row.deviceId)">复制</el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="用户" prop="userId" min-width="200" show-overflow-tooltip />
      <el-table-column label="App 版本" width="100">
        <template #default="{ row }"><span class="mono">{{ row.versionCode }}</span></template>
      </el-table-column>
      <el-table-column label="伴侣协议" width="150">
        <template #default="{ row }"><span class="mono small">{{ row.protocolVersion }}（最低 {{ row.minPeerProtocol }}）</span></template>
      </el-table-column>
      <el-table-column label="Android" width="90">
        <template #default="{ row }">{{ row.sdkInt ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="上报时间" width="170">
        <template #default="{ row }">{{ formatTs(row.reportedAt) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: flex-start; }
.actions { display: flex; gap: 8px; }
.mb { margin-bottom: 12px; }
.small { font-size: 12px; }
.dev { display: flex; align-items: center; gap: 8px; }
</style>
