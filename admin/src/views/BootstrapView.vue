<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import QRCode from 'qrcode'
import { ElMessage } from 'element-plus'
import { Download, DocumentCopy, MagicStick } from '@element-plus/icons-vue'
import { api } from '@/api'
import { errorText } from '@/api/errors'
import type { BootstrapPayload } from '@/types/api'
import { parseBootstrapUri, verifyBootstrapSignature, type VerifyResult } from '@/utils/bootstrap'
import { formatTs, prettyJson } from '@/utils/format'

const canvas = ref<HTMLCanvasElement>()
const loading = ref(false)
const uri = ref('')
const serverJson = ref<BootstrapPayload | null>(null)
const decoded = ref<BootstrapPayload | null>(null)
const verify = ref<VerifyResult | null>(null)
const generatedAt = ref<number | null>(null)

const mismatch = computed(
  () => !!serverJson.value && !!decoded.value && JSON.stringify(serverJson.value) !== JSON.stringify(decoded.value),
)

async function generate() {
  loading.value = true
  try {
    const res = await api.bootstrap()
    uri.value = res.uri
    serverJson.value = typeof res.json === 'string' ? (JSON.parse(res.json) as BootstrapPayload) : res.json
    const parsed = parseBootstrapUri(res.uri)
    decoded.value = parsed.payload
    generatedAt.value = Date.now()
    await nextTick()
    if (canvas.value) {
      await QRCode.toCanvas(canvas.value, res.uri, { errorCorrectionLevel: 'M', margin: 2, width: 300 })
    }
    verify.value = null
    try {
      const pk = await api.pubkey()
      verify.value = await verifyBootstrapSignature(parsed.d, parsed.s, pk.publicKey)
    } catch {
      verify.value = 'unsupported'
    }
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function downloadPng() {
  if (!uri.value) return
  const url = await QRCode.toDataURL(uri.value, { errorCorrectionLevel: 'M', margin: 4, width: 1024 })
  const a = document.createElement('a')
  a.href = url
  a.download = `couple-bootstrap-v${decoded.value?.configVersion ?? 'x'}.png`
  a.click()
}

async function copyUri() {
  try {
    await navigator.clipboard.writeText(uri.value)
    ElMessage.success('已复制 URI')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}
</script>

<template>
  <div>
    <h1 class="page-title">引导二维码</h1>
    <el-alert type="info" :closable="false" show-icon class="mb">
      App 首次启动扫描此二维码获取服务器配置。二维码内容由服务器用 Ed25519 私钥签名，App 首次扫码时固定公钥，之后只接受同一公钥签名的配置；新配置 30 秒内连不上会自动回滚。
      修改服务器配置后请重新生成。
    </el-alert>

    <el-button type="primary" :icon="MagicStick" :loading="loading" @click="generate">
      {{ uri ? '重新生成' : '生成二维码' }}
    </el-button>

    <el-row v-show="uri" :gutter="16" class="mt">
      <el-col :xs="24" :md="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-h">
              <span>二维码</span>
              <el-tag v-if="verify === 'ok'" type="success">签名校验通过</el-tag>
              <el-tag v-else-if="verify === 'bad'" type="danger">签名校验失败</el-tag>
              <el-tag v-else-if="verify === 'unsupported'" type="info">浏览器无法校验签名</el-tag>
            </div>
          </template>
          <div class="qr"><canvas ref="canvas" /></div>
          <div class="btns">
            <el-button :icon="Download" @click="downloadPng">下载 PNG</el-button>
            <el-button :icon="DocumentCopy" @click="copyUri">复制 URI</el-button>
          </div>
          <div class="muted center">生成于 {{ formatTs(generatedAt) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="14">
        <el-card shadow="never" class="mb">
          <template #header>解码后的配置（来自二维码 d 参数）</template>
          <el-descriptions v-if="decoded" :column="1" border size="small" class="mb">
            <el-descriptions-item label="格式版本 v">{{ decoded.v }}</el-descriptions-item>
            <el-descriptions-item label="baseUrl"><span class="mono">{{ decoded.baseUrl }}</span></el-descriptions-item>
            <el-descriptions-item label="mqttUrl"><span class="mono">{{ decoded.mqttUrl }}</span></el-descriptions-item>
            <el-descriptions-item label="configVersion">{{ decoded.configVersion }}</el-descriptions-item>
            <el-descriptions-item label="issuedAt">{{ formatTs(decoded.issuedAt) }}</el-descriptions-item>
          </el-descriptions>
          <pre class="json-block mono">{{ prettyJson(decoded) }}</pre>
          <el-alert v-if="mismatch" type="warning" :closable="false" show-icon class="mt" title="二维码内容与服务器返回的 json 字段不一致，请检查后端实现" />
        </el-card>
        <el-card shadow="never">
          <template #header>URI</template>
          <pre class="json-block mono">{{ uri }}</pre>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 16px; }
.mt { margin-top: 16px; }
.card-h { display: flex; justify-content: space-between; align-items: center; }
.qr { display: flex; justify-content: center; }
.btns { display: flex; justify-content: center; gap: 8px; margin: 12px 0 6px; }
.center { text-align: center; }
</style>
