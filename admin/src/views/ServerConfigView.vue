<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, type FormInstance, type FormItemRule } from 'element-plus'
import { api } from '@/api'
import { errorText } from '@/api/errors'
import SecretInput from '@/components/SecretInput.vue'
import ReleaseSettingsCard from '@/components/ReleaseSettingsCard.vue'
import type { AdminConfigView, SuggestedUrls } from '@/types/api'
import { formatTs } from '@/utils/format'
import { emptyForm, fromConfig, isHttpUrl, isWssUrl, toPayload, type ConfigForm } from '@/utils/configForm'

const formRef = ref<FormInstance>()
const form = ref<ConfigForm>(emptyForm())
const loading = ref(false)
const saving = ref(false)
const loadErr = ref('')
const meta = ref<{ version?: number; updatedAt?: number | null }>({})
/** 后端根据当前访问域名推导出的地址，用作占位提示和“使用自动检测值” */
const suggested = ref<SuggestedUrls | null>(null)
const advanced = ref<string[]>([])

function apply(v: AdminConfigView) {
  form.value = fromConfig(v)
  meta.value = { version: v.configVersion, updatedAt: v.updatedAt }
  if (v.suggested) suggested.value = v.suggested
}

function useSuggested() {
  if (!suggested.value) return
  form.value.baseUrl = suggested.value.publicBaseUrl
  form.value.mqttUrl = suggested.value.mqttUrl
  formRef.value?.clearValidate(['baseUrl', 'mqttUrl'])
  ElMessage.info('已填入自动检测的地址，确认无误后点击“保存配置”')
}

const urlRule = (msg: string, check: (s: string) => boolean): FormItemRule => ({
  trigger: 'blur',
  validator: (_r, v: string, cb) => (!v || check(v.trim()) ? cb() : cb(new Error(msg))),
})

const rules = {
  baseUrl: [{ required: true, message: '请填写后端地址', trigger: 'blur' }, urlRule('需为 http(s):// 开头的地址', isHttpUrl)],
  mqttUrl: [{ required: true, message: '请填写 MQTT 地址', trigger: 'blur' }, urlRule('需为 wss:// 开头的地址（MQTT over WSS）', isWssUrl)],
  storagePublicBaseUrl: [urlRule('需为 http(s):// 开头的地址', isHttpUrl)],
  storageEndpoint: [urlRule('需为 http(s):// 开头的地址', isHttpUrl)],
}

async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    apply(await api.getConfig())
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
    const saved = await api.putConfig(toPayload(form.value))
    // 后端返回脱敏后的配置；若返回体为空则重新拉取
    apply(saved && typeof saved === 'object' && 'turn' in saved ? saved : await api.getConfig())
    ElMessage.success('配置已保存。记得重新生成引导二维码，让 App 获取新配置。')
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <h1 class="page-title">服务器配置</h1>
    <p v-if="meta.version != null" class="muted">当前配置版本 v{{ meta.version }} · 更新于 {{ formatTs(meta.updatedAt) }}（每次保存版本号 +1，二维码携带此版本）</p>
    <el-alert v-if="loadErr" :title="`加载失败：${loadErr}`" type="error" show-icon class="mb" />
    <el-form ref="formRef" v-loading="loading" :model="form" :rules="rules" label-width="140px" class="form">
      <el-card shadow="never" class="mb">
        <template #header>
          <div class="card-h">
            <span>入口</span>
            <el-button v-if="suggested" size="small" data-test="use-suggested" @click="useSuggested">使用自动检测值</el-button>
          </div>
        </template>
        <el-form-item label="后端地址" prop="baseUrl">
          <el-input v-model="form.baseUrl" :placeholder="suggested?.publicBaseUrl ?? 'https://couple.example.com'" />
          <div class="muted">App 使用的 REST 根地址（不含 /api/v1）</div>
        </el-form-item>
        <el-form-item label="MQTT 地址" prop="mqttUrl">
          <el-input v-model="form.mqttUrl" :placeholder="suggested?.mqttUrl ?? 'wss://couple.example.com/mqtt'" />
          <div v-if="suggested" class="muted">自动检测：{{ suggested.publicBaseUrl }} · {{ suggested.mqttUrl }}（根据当前访问的域名推导）</div>
        </el-form-item>
      </el-card>

      <el-collapse v-model="advanced" class="mb">
        <el-collapse-item name="advanced" title="高级（TURN 中继、对象存储，可选）">
          <el-card shadow="never" class="mb">
            <template #header>TURN 中继</template>
            <el-form-item label="提供方">
              <el-radio-group v-model="form.turnProvider">
                <el-radio-button value="cloudflare">Cloudflare Realtime</el-radio-button>
                <el-radio-button value="coturn">自建 coturn</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <template v-if="form.turnProvider === 'cloudflare'">
              <el-form-item label="TURN Key ID">
                <el-input v-model="form.cfKeyId" placeholder="Cloudflare TURN key id" />
              </el-form-item>
              <el-form-item label="API Token">
                <SecretInput v-model="form.cfApiToken" placeholder="Cloudflare TURN API token" />
              </el-form-item>
            </template>
            <template v-else>
              <el-form-item label="TURN URLs">
                <el-input v-model="form.coturnUrls" type="textarea" :rows="3" placeholder="每行一个，例如&#10;turn:turn.example.com:3478?transport=udp&#10;turns:turn.example.com:5349" />
              </el-form-item>
              <el-form-item label="Shared Secret">
                <SecretInput v-model="form.coturnSecret" placeholder="coturn static-auth-secret" />
              </el-form-item>
              <el-form-item label="凭证有效期">
                <el-input-number v-model="form.coturnTtl" :min="60" :max="604800" :step="3600" />
                <span class="muted ml">秒</span>
              </el-form-item>
            </template>
            <div class="muted hint">两种提供方的配置都会保存，切换时不会丢失。</div>
          </el-card>
          <el-card shadow="never" class="mb">
            <template #header>对象存储</template>
            <el-form-item label="类型">
              <el-radio-group v-model="form.storageProvider">
                <el-radio-button value="local">本地磁盘</el-radio-button>
                <el-radio-button value="minio">MinIO</el-radio-button>
                <el-radio-button value="r2">Cloudflare R2</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <template v-if="form.storageProvider !== 'local'">
              <el-form-item label="Endpoint" prop="storageEndpoint">
                <el-input v-model="form.storageEndpoint" :placeholder="form.storageProvider === 'r2' ? 'https://<account>.r2.cloudflarestorage.com' : 'http://minio:9000'" />
              </el-form-item>
              <el-form-item label="Bucket"><el-input v-model="form.storageBucket" /></el-form-item>
              <el-form-item label="Region"><el-input v-model="form.storageRegion" :placeholder="form.storageProvider === 'r2' ? 'auto' : 'us-east-1'" /></el-form-item>
              <el-form-item label="Access Key ID"><el-input v-model="form.storageAccessKeyId" autocomplete="off" /></el-form-item>
              <el-form-item label="Secret Access Key"><SecretInput v-model="form.storageSecret" /></el-form-item>
            </template>
            <el-form-item label="公开访问前缀" prop="storagePublicBaseUrl">
              <el-input v-model="form.storagePublicBaseUrl" placeholder="可选，例如 https://media.example.com" />
            </el-form-item>
          </el-card>
        </el-collapse-item>
      </el-collapse>

      <div class="actions">
        <el-button @click="load">重新加载</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
      </div>
    </el-form>

    <div class="form release">
      <ReleaseSettingsCard />
    </div>
  </div>
</template>

<style scoped>
.form { max-width: 820px; }
.mb { margin-bottom: 16px; }
.ml { margin-left: 8px; }
.hint { padding-left: 140px; }
.card-h { display: flex; justify-content: space-between; align-items: center; }
.actions { display: flex; justify-content: flex-end; gap: 8px; }
.release { margin-top: 24px; }
</style>
