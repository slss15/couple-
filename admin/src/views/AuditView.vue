<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '@/api'
import { errorText } from '@/api/errors'
import type { AuditEntry } from '@/types/api'
import { formatTs, prettyJson } from '@/utils/format'

/**
 * 契约：GET /admin/audit?limit&before -> 数组（游标分页，没有总数）。
 * 这里把 before 当作“上一页最后一条的 ts（epoch 毫秒）”，按时间倒序向前翻。
 */
const pageSize = ref(20)
const rows = ref<AuditEntry[]>([])
const loading = ref(false)
const error = ref('')
/** cursors[i] = 第 i 页使用的 before；第 0 页为 undefined */
const cursors = ref<(number | undefined)[]>([undefined])
const page = ref(0)
const hasNext = ref(false)
const keyword = ref('')

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter((r) =>
    [r.action, r.target, r.actorId, typeof r.meta === 'string' ? r.meta : JSON.stringify(r.meta ?? '')]
      .join(' ')
      .toLowerCase()
      .includes(k),
  )
})

async function load(p: number) {
  loading.value = true
  error.value = ''
  try {
    // 多取 1 条用于判断是否还有下一页
    const data = await api.audit({ limit: pageSize.value + 1, before: cursors.value[p] })
    hasNext.value = data.length > pageSize.value
    rows.value = data.slice(0, pageSize.value)
    page.value = p
    const last = rows.value[rows.value.length - 1]
    if (hasNext.value && last) cursors.value[p + 1] = last.ts
    cursors.value.length = Math.min(cursors.value.length, p + 2)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function reset() {
  cursors.value = [undefined]
  load(0)
}

onMounted(reset)
</script>

<template>
  <div>
    <h1 class="page-title">审计日志</h1>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="在本页内筛选 action / target / actor" clearable class="search" />
      <div class="right">
        <span class="muted">每页</span>
        <el-select v-model="pageSize" class="size" @change="reset">
          <el-option v-for="n in [20, 50, 100]" :key="n" :label="`${n} 条`" :value="n" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="reset">刷新</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon class="mb" />
    <el-table v-loading="loading" :data="filtered" border stripe row-key="id" empty-text="暂无审计记录">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand">
            <div class="muted">ID：<span class="mono">{{ row.id }}</span></div>
            <pre class="json-block mono">{{ prettyJson(row.meta) || '（无 meta）' }}</pre>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="180">
        <template #default="{ row }">{{ formatTs(row.ts) }}</template>
      </el-table-column>
      <el-table-column prop="action" label="操作" min-width="160">
        <template #default="{ row }"><el-tag size="small">{{ row.action }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="target" label="对象" min-width="200" show-overflow-tooltip />
      <el-table-column prop="actorId" label="操作者" min-width="220">
        <template #default="{ row }"><span class="mono">{{ row.actorId }}</span></template>
      </el-table-column>
    </el-table>
    <div class="pager">
      <span class="muted">第 {{ page + 1 }} 页</span>
      <el-button-group>
        <el-button :disabled="page === 0 || loading" @click="load(0)">最新</el-button>
        <el-button :disabled="page === 0 || loading" @click="load(page - 1)">上一页</el-button>
        <el-button :disabled="!hasNext || loading" @click="load(page + 1)">下一页</el-button>
      </el-button-group>
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.search { max-width: 320px; }
.right { display: flex; align-items: center; gap: 8px; }
.size { width: 100px; }
.mb { margin-bottom: 12px; }
.expand { padding: 4px 16px; }
.pager { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 12px; }
</style>
