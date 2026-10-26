<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '@/api'
import { errorText } from '@/api/errors'
import type { AppReleaseView, ReleaseHealthView, ReleasePatch, ReleaseStage, SyncState } from '@/types/api'
import { formatTs } from '@/utils/format'
import { HEALTH_TAG, STATUS_LABEL, STATUS_TAG, formatSize, shortDigest } from '@/utils/releases'

const releases = ref<AppReleaseView[]>([])
const loading = ref(false)
const loadErr = ref('')
const sync = ref<SyncState | null>(null)
const busy = ref<number | null>(null)
const health = ref<{ visible: boolean; loading: boolean; release: AppReleaseView | null; rows: ReleaseHealthView[] }>({
  visible: false,
  loading: false,
  release: null,
  rows: [],
})
let pollTimer: ReturnType<typeof setTimeout> | null = null

/** 最新的已发布版本（App 实际会收到的） */
const latestPublished = computed(() => releases.value.find((r) => r.status === 'PUBLISHED') ?? null)

async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    const [list, st] = await Promise.all([api.releases(), api.syncState().catch(() => null)])
    releases.value = list
    sync.value = st
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = setTimeout(async () => {
    try {
      sync.value = await api.syncState()
    } catch {
      /* 忽略，下次再试 */
    }
    if (sync.value?.running) schedulePoll()
    else {
      await load()
      if (sync.value?.lastResult === 'ok') {
        const n = sync.value.mirrored.length
        ElMessage.success(n ? `同步完成，新镜像 ${n} 个版本` : '同步完成，没有新版本')
      } else if (sync.value?.lastResult === 'error') {
        ElMessage.error(`同步失败：${sync.value.lastError ?? '未知错误'}`)
      }
    }
  }, 2000)
}

async function syncNow() {
  try {
    sync.value = await api.syncReleases()
    ElMessage.info('已开始同步（从 GitHub 下载可能需要几分钟）')
    schedulePoll()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

async function patch(r: AppReleaseView, p: ReleasePatch, okMsg: string) {
  busy.value = r.versionCode
  try {
    const updated = await api.patchRelease(r.versionCode, p)
    const i = releases.value.findIndex((x) => x.versionCode === r.versionCode)
    if (i >= 0) releases.value[i] = updated
    ElMessage.success(okMsg)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    busy.value = null
  }
}

async function publish(r: AppReleaseView) {
  await ElMessageBox.confirm(
    `发布 ${r.versionName}（${r.versionCode}）后，灰度范围内的设备会在下次检查时收到更新提示。`,
    '发布版本',
    { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' },
  )
  await patch(r, { status: 'PUBLISHED' }, '已发布')
}

async function withdraw(r: AppReleaseView) {
  await ElMessageBox.confirm(`撤回 ${r.versionName} 后 App 将不再收到它，下载链接也会失效。`, '撤回版本', {
    type: 'warning',
    confirmButtonText: '撤回',
    cancelButtonText: '取消',
  })
  await patch(r, { status: 'WITHDRAWN' }, '已撤回')
}

async function setRollout(r: AppReleaseView) {
  const { value } = await ElMessageBox.prompt('0–100 的整数。按设备 ID 哈希分桶，调高只会增加设备，不会让已收到的设备掉出。', `灰度比例 · ${r.versionName}`, {
    inputValue: String(r.rolloutPercent),
    inputPattern: /^(100|[1-9]?\d)$/,
    inputErrorMessage: '请输入 0 到 100 的整数',
    confirmButtonText: '保存',
    cancelButtonText: '取消',
  })
  await patch(r, { rolloutPercent: Number(value) }, `灰度比例已设为 ${value}%`)
}

async function setMinSupported(r: AppReleaseView) {
  const { value } = await ElMessageBox.prompt(
    `低于该 versionCode 的设备会被强制更新（弹窗不可关闭，忽略灰度）。只对最新的已发布版本生效。0 = 不强制，最大 ${r.versionCode}。`,
    `最低支持版本 · ${r.versionName}`,
    {
      inputValue: String(r.minSupportedVersionCode),
      inputValidator: (v: string) => {
        const n = Number(v)
        return (/^\d+$/.test(v) && n >= 0 && n <= r.versionCode) || `请输入 0 到 ${r.versionCode} 的整数`
      },
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    },
  )
  await patch(r, { minSupportedVersionCode: Number(value) }, '最低支持版本已更新')
}

async function editNotes(r: AppReleaseView) {
  const { value } = await ElMessageBox.prompt('App 更新弹窗中显示的说明', `更新说明 · ${r.versionName}`, {
    inputType: 'textarea',
    inputValue: r.notes ?? '',
    confirmButtonText: '保存',
    cancelButtonText: '取消',
  })
  await patch(r, { notes: value ?? '' }, '更新说明已保存')
}

async function pause(r: AppReleaseView) {
  await ElMessageBox.confirm(
    `暂停 ${r.versionName} 后，所有设备（包括强制更新的设备）都看不到它，下载链接也会失效。`,
    '暂停版本',
    { type: 'warning', confirmButtonText: '暂停', cancelButtonText: '取消' },
  )
  await patch(r, { paused: true }, '已暂停（恢复请点“恢复”）')
}

async function resume(r: AppReleaseView) {
  await patch(r, { paused: false }, '已恢复')
}

async function toggleStage(r: AppReleaseView) {
  const to: ReleaseStage = r.stage === 'ALL' ? 'CANARY' : 'ALL'
  await ElMessageBox.confirm(
    to === 'CANARY'
      ? '切换为 CANARY 后，仅“发布设置”中 canaryDeviceIds 里的设备能看到该版本。'
      : '切换为 ALL 后，该版本对所有设备可见（仍受灰度比例限制）。',
    `切换发布阶段 · ${r.versionName}`,
    { type: 'warning', confirmButtonText: `切换为 ${to}`, cancelButtonText: '取消' },
  )
  await patch(r, { stage: to }, `发布阶段已切换为 ${to}`)
}

async function showHealth(r: AppReleaseView) {
  health.value = { visible: true, loading: true, release: r, rows: [] }
  try {
    health.value.rows = await api.releaseHealth(r.versionCode)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    health.value.loading = false
  }
}

/** ElMessageBox 取消会 reject 'cancel'，这里吞掉 */
function run(fn: (r: AppReleaseView) => Promise<void>, r: AppReleaseView) {
  fn(r).catch(() => undefined)
}

onMounted(async () => {
  await load()
  if (sync.value?.running) schedulePoll()
})
onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
})
</script>

<template>
  <div>
    <div class="head">
      <h1 class="page-title">版本发布</h1>
      <div class="actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :loading="sync?.running" @click="syncNow">立即同步</el-button>
      </div>
    </div>
    <p class="muted">
      后端定期从 GitHub Releases 镜像 APK（校验 sha256、大小和签名证书），App 只从本服务器下载。新镜像的版本默认不发布，需要在这里手动发布。
    </p>

    <el-alert v-if="loadErr" :title="`加载失败：${loadErr}`" type="error" show-icon class="mb" />
    <el-alert
      v-if="sync?.lastResult === 'error' && !sync.running"
      :title="`上次同步失败（${formatTs(sync.lastFinishedAt)}）：${sync.lastError}`"
      type="warning"
      show-icon
      class="mb"
    />
    <el-alert
      v-if="sync?.rejected?.length && !sync.running"
      :title="`上次同步拒绝了 ${sync.rejected.length} 个版本：${sync.rejected.join('；')}`"
      type="error"
      :closable="false"
      show-icon
      class="mb"
    />
    <p v-if="sync" class="muted small">
      <template v-if="sync.running">正在同步…</template>
      <template v-else-if="sync.lastFinishedAt">上次同步：{{ formatTs(sync.lastFinishedAt) }}（{{ sync.lastResult === 'ok' ? '成功' : '失败' }}）</template>
      <template v-else>本次启动后尚未同步</template>
      <template v-if="latestPublished"> · App 当前最新版本：{{ latestPublished.versionName }}（{{ latestPublished.versionCode }}）</template>
    </p>

    <el-table v-loading="loading" :data="releases" border stripe empty-text="暂无版本，点击“立即同步”从 GitHub 拉取">
      <el-table-column label="版本" min-width="130">
        <template #default="{ row }">
          <div class="strong">{{ row.versionName }}</div>
          <div class="muted mono small">{{ row.versionCode }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="210">
        <template #default="{ row }">
          <div>
            <el-tag :type="STATUS_TAG[row.status as keyof typeof STATUS_TAG]">{{ STATUS_LABEL[row.status as keyof typeof STATUS_LABEL] }}</el-tag>
            <el-tag v-if="!row.fileExists" type="danger" size="small" class="ml">文件缺失</el-tag>
          </div>
          <div v-if="row.stage === 'CANARY' || row.pausedAt" class="mt4">
            <el-tag v-if="row.stage === 'CANARY'" type="warning" size="small">CANARY</el-tag>
            <el-tooltip v-if="row.pausedAt" :content="`暂停于 ${formatTs(row.pausedAt)}：${row.pauseReason ?? '未知原因'}`" placement="top">
              <el-tag type="danger" size="small" :class="{ ml: row.stage === 'CANARY' }">已暂停</el-tag>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="灰度" width="90">
        <template #default="{ row }">{{ row.rolloutPercent }}%</template>
      </el-table-column>
      <el-table-column label="最低支持" width="110">
        <template #default="{ row }">
          <span v-if="row.minSupportedVersionCode > 0" class="mono">{{ row.minSupportedVersionCode }}</span>
          <span v-else class="muted">不强制</span>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatSize(row.size) }}</template>
      </el-table-column>
      <el-table-column label="发布时间" width="170">
        <template #default="{ row }">{{ formatTs(row.publishedAt) }}</template>
      </el-table-column>
      <el-table-column label="SHA-256" min-width="130">
        <template #default="{ row }">
          <el-tooltip :content="`APK ${row.sha256} / 证书 ${row.signingCertSha256}`" placement="top">
            <span class="mono small">{{ shortDigest(row.sha256) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="360" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'PUBLISHED'" link type="primary" :disabled="!row.fileExists || busy === row.versionCode" @click="run(publish, row)">发布</el-button>
          <el-button v-else link type="danger" :disabled="busy === row.versionCode" @click="run(withdraw, row)">撤回</el-button>
          <el-button v-if="row.status === 'PUBLISHED' && !row.pausedAt" link type="warning" :disabled="busy === row.versionCode" @click="run(pause, row)">暂停</el-button>
          <el-button v-if="row.pausedAt" link type="success" :disabled="busy === row.versionCode" @click="run(resume, row)">恢复</el-button>
          <el-button link :disabled="busy === row.versionCode" @click="run(toggleStage, row)">{{ row.stage === 'ALL' ? '设为 CANARY' : '设为全量' }}</el-button>
          <el-button link :disabled="busy === row.versionCode" @click="run(setRollout, row)">设置灰度比例</el-button>
          <el-button link :disabled="busy === row.versionCode" @click="run(setMinSupported, row)">设置最低支持版本</el-button>
          <el-button link :disabled="busy === row.versionCode" @click="run(editNotes, row)">说明</el-button>
          <el-button link :disabled="busy === row.versionCode" @click="run(showHealth, row)">健康上报</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="health.visible" :title="`健康上报 · ${health.release?.versionName ?? ''}（${health.release?.versionCode ?? ''}）`" width="860px">
      <el-table v-loading="health.loading" :data="health.rows" border stripe max-height="480" empty-text="该版本还没有健康上报（App 安装后首次启动会自动上报）">
        <el-table-column label="设备 / 用户" min-width="230">
          <template #default="{ row }">
            <div class="mono small">{{ row.deviceId }}</div>
            <div class="muted small">{{ row.userId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="HEALTH_TAG[row.status as keyof typeof HEALTH_TAG] ?? 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="MQTT" width="90">
          <template #default="{ row }">{{ row.mqttConnected == null ? '—' : row.mqttConnected ? '已连接' : '未连接' }}</template>
        </el-table-column>
        <el-table-column label="来自版本" width="95">
          <template #default="{ row }">{{ row.fromVersionCode ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="详情" min-width="170">
          <template #default="{ row }"><span class="small">{{ row.detail || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTs(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: flex-start; }
.actions { display: flex; gap: 8px; }
.mb { margin-bottom: 12px; }
.ml { margin-left: 6px; }
.mt4 { margin-top: 4px; }
.small { font-size: 12px; }
.strong { font-weight: 600; }
</style>
