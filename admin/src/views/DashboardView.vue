<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '@/api'
import { ApiError, errorText, toApiError } from '@/api/errors'
import { apiRoot } from '@/api/http'
import type { BootstrapPubkey, Couple, HealthInfo, ServerUpdateStatus } from '@/types/api'
import { shortDigest, updaterResultTag } from '@/utils/releases'
import { formatDate, formatTs } from '@/utils/format'
import { base64ToBytes } from '@/utils/base64url'
import { useAuthStore } from '@/stores/auth'

/** 管理后台支持的协议版本（契约 P0 = 1） */
const SUPPORTED_PROTOCOL = 1

const auth = useAuthStore()

const health = ref<HealthInfo | null>(null)
const healthErr = ref('')
const latency = ref<number | null>(null)
const couple = ref<Couple | null>(null)
const coupleState = ref<'loading' | 'paired' | 'unpaired' | 'error'>('loading')
const coupleErr = ref('')
const pubkey = ref<BootstrapPubkey | null>(null)
const pubkeyFp = ref('')
const loading = ref(false)
const updStatus = ref<ServerUpdateStatus | null>(null)
const updErr = ref('')

async function fingerprint(b64: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', base64ToBytes(b64) as BufferSource)
  return Array.from(new Uint8Array(digest).slice(0, 8))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join(':')
}

async function loadHealth() {
  healthErr.value = ''
  const t0 = performance.now()
  try {
    health.value = await api.health()
    latency.value = Math.round(performance.now() - t0)
  } catch (e) {
    health.value = null
    latency.value = null
    healthErr.value = errorText(e)
  }
}

async function loadCouple() {
  coupleState.value = 'loading'
  try {
    couple.value = await api.couple()
    coupleState.value = 'paired'
  } catch (e) {
    const err = toApiError(e)
    couple.value = null
    if (err instanceof ApiError && (err.code === 'NOT_PAIRED' || err.status === 404)) coupleState.value = 'unpaired'
    else {
      coupleState.value = 'error'
      coupleErr.value = err.display
    }
  }
}

async function loadPubkey() {
  try {
    pubkey.value = await api.pubkey()
    pubkeyFp.value = await fingerprint(pubkey.value.publicKey).catch(() => '')
  } catch {
    pubkey.value = null
  }
}

async function loadUpdateStatus() {
  updErr.value = ''
  try {
    updStatus.value = await api.serverUpdateStatus()
  } catch (e) {
    updStatus.value = null
    updErr.value = errorText(e)
  }
}

async function reload() {
  loading.value = true
  await Promise.all([loadHealth(), loadCouple(), loadPubkey(), loadUpdateStatus()])
  auth.sync()
  loading.value = false
}

onMounted(reload)
</script>

<template>
  <div>
    <div class="head">
      <h1 class="page-title">仪表盘</h1>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-h">
              <span>服务健康</span>
              <el-tag v-if="health?.status === 'ok'" type="success">正常</el-tag>
              <el-tag v-else-if="health" type="warning">{{ health.status }}</el-tag>
              <el-tag v-else-if="healthErr" type="danger">不可达</el-tag>
            </div>
          </template>
          <el-alert v-if="healthErr" :title="healthErr" type="error" :closable="false" show-icon />
          <el-descriptions v-else :column="1" border size="small">
            <el-descriptions-item label="后端版本">{{ health?.version ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="协议版本">
              {{ health?.protocolVersion ?? '—' }}
              <el-tag v-if="health && health.protocolVersion !== SUPPORTED_PROTOCOL" type="warning" size="small" class="ml">
                后台支持 v{{ SUPPORTED_PROTOCOL }}，可能不兼容
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="响应耗时">{{ latency != null ? `${latency} ms` : '—' }}</el-descriptions-item>
            <el-descriptions-item label="API 地址"><span class="mono">{{ apiRoot() }}</span></el-descriptions-item>
            <el-descriptions-item label="签名公钥">
              <template v-if="pubkey">
                <span class="mono">{{ pubkey.alg }} · {{ pubkeyFp || '—' }}</span>
                <div class="muted">SHA-256 指纹前 8 字节，App 首次扫码时固定（TOFU）</div>
              </template>
              <span v-else class="muted">未获取</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-h">
              <span>服务器自动更新状态</span>
              <el-tag v-if="updStatus?.updater" :type="updaterResultTag(updStatus.updater)">{{ updStatus.updater.lastError ? '出错' : (updStatus.updater.lastResult || '未知') }}</el-tag>
              <el-tag v-else-if="updStatus" type="info">未上报</el-tag>
            </div>
          </template>
          <el-alert v-if="updErr" :title="updErr" type="error" :closable="false" show-icon />
          <el-descriptions v-else-if="updStatus" :column="1" border size="small">
            <el-descriptions-item label="后端版本">
              {{ updStatus.backend.version }}
              <span v-if="updStatus.backend.revision" class="muted mono">（{{ shortDigest(updStatus.backend.revision, 8) }}）</span>
            </el-descriptions-item>
            <template v-if="updStatus.updater">
              <el-descriptions-item v-for="(v, k) in updStatus.updater.current ?? {}" :key="k" :label="`当前 ${k}`">
                <el-tooltip :content="String(v ?? '—')" placement="top"><span class="mono">{{ shortDigest(v ?? null, 19) }}</span></el-tooltip>
              </el-descriptions-item>
              <el-descriptions-item v-if="updStatus.updater.channel" label="跟踪通道">{{ updStatus.updater.channel }}<span v-if="updStatus.updater.enabled === false" class="muted">（已停用）</span></el-descriptions-item>
              <el-descriptions-item label="上次检查">{{ formatTs(updStatus.updater.lastCheck ?? null) }}</el-descriptions-item>
              <el-descriptions-item label="结果">{{ updStatus.updater.lastResult || '—' }}</el-descriptions-item>
              <el-descriptions-item v-if="updStatus.updater.lastError" label="错误"><span class="err">{{ updStatus.updater.lastError }}</span></el-descriptions-item>
              <el-descriptions-item label="上报时间">{{ formatTs(updStatus.updaterReportedAt) }}</el-descriptions-item>
            </template>
            <el-descriptions-item v-else label="updater"><span class="muted">尚未收到 server-updater 的状态上报</span></el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-h">
              <span>情侣状态</span>
              <el-tag v-if="coupleState === 'paired'" type="success">已配对</el-tag>
              <el-tag v-else-if="coupleState === 'unpaired'" type="info">未配对</el-tag>
              <el-tag v-else-if="coupleState === 'error'" type="danger">获取失败</el-tag>
            </div>
          </template>
          <el-skeleton v-if="coupleState === 'loading'" :rows="3" animated />
          <el-alert v-else-if="coupleState === 'error'" :title="coupleErr" type="error" :closable="false" show-icon />
          <el-empty v-else-if="coupleState === 'unpaired'" description="当前管理员账号尚未与伴侣配对（在 App 中生成 6 位配对码）" :image-size="72" />
          <template v-else-if="couple">
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="Couple ID"><span class="mono">{{ couple.id }}</span></el-descriptions-item>
              <el-descriptions-item label="配对时间">{{ formatTs(couple.pairedAt) }}</el-descriptions-item>
              <el-descriptions-item label="纪念日">{{ couple.anniversary ? formatDate(couple.anniversary) : '未设置' }}</el-descriptions-item>
            </el-descriptions>
            <div class="members">
              <div v-for="m in couple.members" :key="m.id" class="member">
                <el-avatar :src="m.avatarUrl || undefined" :size="40">{{ (m.displayName || m.username).slice(0, 1) }}</el-avatar>
                <div>
                  <div>{{ m.displayName }} <span class="muted">@{{ m.username }}</span></div>
                  <div class="muted mono">{{ m.id }}</div>
                </div>
              </div>
            </div>
          </template>
        </el-card>

        <el-card shadow="never" class="card">
          <template #header>当前会话</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="账号">{{ auth.user?.username }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ auth.claims?.role }}</el-descriptions-item>
            <el-descriptions-item label="设备 ID"><span class="mono">{{ auth.claims?.did }}</span></el-descriptions-item>
            <el-descriptions-item label="Access 过期">{{ formatTs(auth.tokens?.accessExpiresAt) }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: flex-start; }
.card { margin-bottom: 16px; }
.card-h { display: flex; justify-content: space-between; align-items: center; }
.ml { margin-left: 8px; }
.members { display: flex; flex-direction: column; gap: 12px; margin-top: 14px; }
.member { display: flex; gap: 12px; align-items: center; }
.err { color: #f56c6c; }
</style>
