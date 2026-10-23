<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, type FormInstance, type FormItemRule } from 'element-plus'
import { api } from '@/api'
import { errorText } from '@/api/errors'
import SecretInput from '@/components/SecretInput.vue'
import { formatTs } from '@/utils/format'
import {
  isRepo,
  isValidPollMinutes,
  normalizeCertSha256,
  releaseSettingsForm,
  releaseSettingsPayload,
  type ReleaseSettingsForm,
} from '@/utils/releases'

const formRef = ref<FormInstance>()
const form = ref<ReleaseSettingsForm>(releaseSettingsForm(null))
const updatedAt = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const loadErr = ref('')

const rules: Record<string, FormItemRule[]> = {
  repo: [
    {
      trigger: 'blur',
      validator: (_r, v: string, cb) => (isRepo(v ?? '') ? cb() : cb(new Error('格式为 owner/name，例如 slss15/couple-'))),
    },
  ],
  pollMinutes: [
    { validator: (_r, v: number, cb) => (isValidPollMinutes(v) ? cb() : cb(new Error('0（关闭）或 5–1440 分钟'))) },
  ],
  certPin: [
    {
      trigger: 'blur',
      validator: (_r, v: string, cb) =>
        !v || normalizeCertSha256(v) ? cb() : cb(new Error('需为 64 位十六进制 SHA-256（可带冒号）')),
    },
  ],
}

async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    const v = await api.getReleaseSettings()
    form.value = releaseSettingsForm(v)
    updatedAt.value = v.updatedAt
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  try {
    const v = await api.putReleaseSettings(releaseSettingsPayload(form.value))
    form.value = releaseSettingsForm(v)
    updatedAt.value = v.updatedAt
    ElMessage.success('版本发布设置已保存')
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never" class="card">
    <template #header>
      <div class="card-h">
        <span>App 版本发布（GitHub Releases 镜像）</span>
        <span v-if="updatedAt" class="muted">更新于 {{ formatTs(updatedAt) }}</span>
      </div>
    </template>
    <el-alert v-if="loadErr" :title="`加载失败：${loadErr}`" type="error" show-icon class="mb" />
    <el-form ref="formRef" v-loading="loading" :model="form" :rules="rules" label-width="140px">
      <el-form-item label="GitHub 仓库" prop="repo">
        <el-input v-model="form.repo" placeholder="slss15/couple-" />
      </el-form-item>
      <el-form-item label="GitHub Token">
        <SecretInput v-model="form.token" placeholder="可选；私有仓库或避免限流时填写（只需 contents:read）" />
      </el-form-item>
      <el-form-item label="自动发布">
        <el-switch v-model="form.autoPublish" />
        <span class="muted ml">开启后新镜像的版本直接发布（100% 灰度）；关闭则需在“版本发布”页手动发布</span>
      </el-form-item>
      <el-form-item label="轮询间隔" prop="pollMinutes">
        <el-input-number v-model="form.pollMinutes" :min="0" :max="1440" :step="5" />
        <span class="muted ml">分钟，0 = 关闭定时同步（仍可手动同步）</span>
      </el-form-item>
      <el-form-item label="签名证书固定" prop="certPin">
        <el-input v-model="form.certPin" placeholder="留空 = 首次镜像时自动固定（TOFU）" class="mono" />
        <div class="muted">APK 签名证书的 SHA-256。证书不符的版本一律拒绝；清空后下一次镜像会重新固定。</div>
      </el-form-item>
      <div class="actions">
        <el-button @click="load">重新加载</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存发布设置</el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.card { margin-bottom: 16px; }
.card-h { display: flex; justify-content: space-between; align-items: center; }
.mb { margin-bottom: 12px; }
.ml { margin-left: 8px; }
.actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
