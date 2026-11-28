<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowUp, Refresh, Upload } from '@element-plus/icons-vue'
import { api } from '@/api'
import { errorText } from '@/api/errors'
import type {
  AppearanceUpdate,
  AppearanceView,
  LayoutItem,
  LayoutItemSize,
  LayoutSpec,
  LayoutZone,
  LayoutZoneCatalog,
  RemoteTheme,
  ThemeCatalog,
} from '@/types/api'
import { formatTs } from '@/utils/format'

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const view = ref<AppearanceView | null>(null)
const layout = ref<LayoutSpec | null>(null)
const catalog = ref<LayoutZoneCatalog[]>([])
const theme = ref<RemoteTheme>({
  seed: 'linepuppy',
  darkMode: 'system',
  fontSize: 'standard',
  allowUserOverride: true,
})
const themeCatalog = ref<ThemeCatalog>({ seeds: [], darkModes: [], fontSizes: [] })

const seedColors: Record<string, string> = {
  rose: '#a8235a',
  blue: '#0061a4',
  green: '#006c4c',
  purple: '#6750a4',
  orange: '#8b5000',
  teal: '#006a6a',
  linepuppy: '#a63b00',
}

const previewColor = computed(() => seedColors[theme.value.seed] ?? seedColors.linepuppy)
const previewItems = computed(() => {
  const zone = layout.value?.zones.find((z) => z.id === 'bottomBar')
  return (zone?.items ?? [])
    .filter((item) => item.visible)
    .sort((a, b) => a.order - b.order)
    .map((item) => ({ ...item, label: itemLabel('bottomBar', item.id) }))
})

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function zoneOf(zoneId: string): LayoutZone | undefined {
  return layout.value?.zones.find((zone) => zone.id === zoneId)
}

function catalogOf(zoneId: string): LayoutZoneCatalog | undefined {
  return catalog.value.find((zone) => zone.id === zoneId)
}

/** 与后端 LayoutRules.ItemSize.gridSpan 对齐：区域宽度按“可见项尺寸之和”校验 */
const SPAN: Record<LayoutItemSize, number> = { S: 1, M: 2, L: 4 }

function gridWidthOf(zoneId: string): number | undefined {
  return catalogOf(zoneId)?.gridWidth
}

/** 区域当前可见项占用的格数（后端用同样的方式累加，超宽直接返回 ZONE_OVER_WIDTH 拒绝保存） */
function usedSpan(zoneId: string): number {
  return (zoneOf(zoneId)?.items ?? [])
    .filter((item) => item.visible)
    .reduce((sum, item) => sum + SPAN[item.size], 0)
}

function zoneOverWidth(zoneId: string): boolean {
  const width = gridWidthOf(zoneId)
  return width !== undefined && usedSpan(zoneId) > width
}

/** 已超宽的区域：保存按钮据此禁用，避免把后端必定拒绝的布局发出去 */
const overWidthZones = computed(() => catalog.value.filter((zone) => zoneOverWidth(zone.id)))

/** 把某一项改成 target 尺寸后会不会超宽；隐藏项不占格，改尺寸不影响总跨度 */
function sizeOverflows(zoneId: string, item: LayoutItem, target: LayoutItemSize): boolean {
  const width = gridWidthOf(zoneId)
  if (width === undefined || !item.visible) return false
  const current = usedSpan(zoneId)
  const next = current - SPAN[item.size] + SPAN[target]
  // 已经超宽（历史数据）时允许往小改，否则每一项都会被禁用、只能靠隐藏来修
  return next > width && next > current
}

/** 把隐藏项显示出来会不会超宽（显示开关也要拦，否则同样存出非法布局） */
function showOverflows(zoneId: string, item: LayoutItem): boolean {
  const width = gridWidthOf(zoneId)
  if (width === undefined || item.visible) return false
  return usedSpan(zoneId) + SPAN[item.size] > width
}

/** 区域下方的实时提示：解释为什么某些开关/尺寸被禁用，而不是只在保存时才报错 */
function zoneHint(zoneId: string): string {
  if (zoneOverWidth(zoneId)) return `可见项已占 ${usedSpan(zoneId)} 格，超出 ${gridWidthOf(zoneId) ?? 0} 格：请隐藏入口或缩小尺寸，否则后端会拒绝保存。`
  if (zoneId === 'meSections') return '聊天入口默认隐藏；聊天模块仍保留，可在需要时重新显示。'
  return ''
}

function itemLabel(zoneId: string, itemId: string): string {
  return catalogOf(zoneId)?.items.find((item) => item.id === itemId)?.label ?? itemId
}

function isLocked(itemId: string): boolean {
  return catalog.value.some((zone) => zone.items.some((item) => item.id === itemId && item.locked))
}

function visibleByDefault(zoneId: string, itemId: string): boolean {
  if (zoneId !== 'bottomBar') return itemId !== 'chat_entry'
  return ['tab_home', 'tab_feed', 'tab_ledger', 'me_entry'].includes(itemId)
}

function sortedItems(zoneId: string): LayoutItem[] {
  return [...(zoneOf(zoneId)?.items ?? [])].sort((a, b) => a.order - b.order)
}

function ensureCatalogItems() {
  if (!layout.value) return
  for (const cat of catalog.value) {
    let zone = layout.value.zones.find((z) => z.id === cat.id)
    if (!zone) {
      zone = { id: cat.id, items: [] }
      layout.value.zones.push(zone)
    }
    const existing = new Map(zone.items.map((item) => [item.id, item]))
    const maxOrder = zone.items.reduce((max, item) => Math.max(max, item.order), -1)
    let nextOrder = maxOrder + 1
    for (const catItem of cat.items) {
      let item = existing.get(catItem.id)
      if (!item) {
        item = {
          id: catItem.id,
          order: catItem.locked && catItem.id === 'me_entry' ? 3 : nextOrder++,
          visible: visibleByDefault(cat.id, catItem.id),
          size: 'M',
        }
        zone.items.push(item)
      }
      if (catItem.locked) item.visible = true
      if (catItem.id === 'me_entry') {
        const conflict = zone.items.find((candidate) => candidate !== item && candidate.order === 3)
        if (conflict) conflict.order = Math.max(nextOrder, ...zone.items.map((candidate) => candidate.order)) + 1
        item.order = 3
      }
    }
    zone.items.sort((a, b) => a.order - b.order)
  }
}

function move(zoneId: string, itemId: string, delta: -1 | 1) {
  const zone = zoneOf(zoneId)
  if (!zone || isLocked(itemId)) return
  const items = sortedItems(zoneId)
  const index = items.findIndex((item) => item.id === itemId)
  if (index < 0) return
  let target = index + delta
  while (target >= 0 && target < items.length && isLocked(items[target]!.id)) target += delta
  if (target < 0 || target >= items.length) return
  const current = items[index]!
  const other = items[target]!
  const order = current.order
  current.order = other.order
  other.order = order
  zone.items.sort((a, b) => a.order - b.order)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const next = await api.getAppearance()
    view.value = next
    layout.value = clone(next.layout)
    theme.value = { ...next.theme }
    catalog.value = next.catalog
    themeCatalog.value = next.themeCatalog
    ensureCatalogItems()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!layout.value) return
  // 界面上已经实时拦住超宽，这里再兜一次：从服务器加载来的旧布局本身可能就越界
  if (overWidthZones.value.length) {
    error.value = `以下区域可见项超出网格宽度，后端会拒绝保存：${overWidthZones.value
      .map((zone) => `${zone.label}（${usedSpan(zone.id)} / ${zone.gridWidth} 格）`)
      .join('、')}`
    return
  }
  saving.value = true
  error.value = ''
  try {
    ensureCatalogItems()
    const body: AppearanceUpdate = { layout: layout.value, theme: theme.value }
    const next = await api.putAppearance(body)
    view.value = next
    layout.value = clone(next.layout)
    theme.value = { ...next.theme }
    ElMessage.success('主题与布局已保存')
  } catch (e) {
    error.value = errorText(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="head">
      <div>
        <h1 class="page-title">主题与布局</h1>
        <div class="muted">P5 · 服务端下发主题默认值与底部导航布局；聊天/通话模块保留，入口默认隐藏。</div>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" @click="load">重新加载</el-button>
        <el-button type="primary" :icon="Upload" :loading="saving" :disabled="overWidthZones.length > 0" @click="save">保存</el-button>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="mb" />

    <el-row :gutter="16">
      <el-col :xs="24" :lg="15">
        <el-card shadow="never" class="mb">
          <template #header>
            <div class="card-h">
              <span>主题默认值</span>
              <span class="muted">版本 {{ view?.appearanceVersion ?? 0 }} · {{ view?.updatedAt ? formatTs(view.updatedAt) : '未保存' }}</span>
            </div>
          </template>
          <el-form label-width="132px">
            <el-form-item label="主题色">
              <el-select v-model="theme.seed" style="width: 100%">
                <el-option v-for="option in themeCatalog.seeds" :key="option.id" :label="option.label" :value="option.id">
                  <span class="seed-dot" :style="{ background: seedColors[option.id] ?? '#999' }" />
                  {{ option.label }}
                </el-option>
              </el-select>
            </el-form-item>
            <el-form-item label="深浅色">
              <el-radio-group v-model="theme.darkMode">
                <el-radio-button v-for="option in themeCatalog.darkModes" :key="option.id" :value="option.id">
                  {{ option.label }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="字号">
              <el-radio-group v-model="theme.fontSize">
                <el-radio-button v-for="option in themeCatalog.fontSizes" :key="option.id" :value="option.id">
                  {{ option.label }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="允许用户覆盖">
              <el-switch v-model="theme.allowUserOverride" />
              <span class="muted ml">关闭后所有设备统一使用服务器主题。</span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="9">
        <el-card shadow="never" class="mb preview-card">
          <template #header>手机预览</template>
          <div class="phone">
            <div class="phone-top" :style="{ background: previewColor }">
              <span>Couple</span>
              <span>●●●</span>
            </div>
            <div class="phone-body">
              <div class="preview-title">今天也想你</div>
              <div class="preview-line" />
              <div class="preview-line short" />
              <div class="preview-block" :style="{ background: previewColor + '22', color: previewColor }">记一笔</div>
              <div class="preview-line" />
              <div class="preview-line short" />
            </div>
            <div class="phone-nav">
              <div v-for="item in previewItems" :key="item.id" class="nav-item">
                <span class="nav-dot" :style="{ background: previewColor }" />
                <span>{{ item.label }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-h">
          <span>布局编辑器</span>
          <span class="muted">拖拽未启用；用上下按钮排序。锁定项不可隐藏或移动。尺寸 S/M/L 分别占 1/2/4 格，会超出区域宽度的选项自动禁用。</span>
        </div>
      </template>

      <el-empty v-if="!layout" description="尚未加载布局" />
      <div v-else class="zones">
        <el-card v-for="zone in catalog" :key="zone.id" shadow="never" class="zone-card">
          <template #header>
            <div class="card-h">
              <span>{{ zone.label }}</span>
              <span class="muted" :class="{ danger: zoneOverWidth(zone.id) }">
                最多 {{ zone.capacity }} 项 · {{ zone.gridWidth }} 格 · 已用 {{ usedSpan(zone.id) }} 格
              </span>
            </div>
          </template>
          <el-alert
            v-if="zoneOverWidth(zone.id)"
            :title="`可见项共占 ${usedSpan(zone.id)} 格，超过 ${zone.gridWidth} 格，后端会拒绝保存；请隐藏入口或缩小尺寸。`"
            type="warning"
            :closable="false"
            show-icon
            class="zone-alert"
          />
          <div class="layout-row header-row">
            <span>入口</span>
            <span>显示</span>
            <span>尺寸</span>
            <span>排序</span>
          </div>
          <div v-for="item in sortedItems(zone.id)" :key="item.id" class="layout-row">
            <div class="item-name">
              <span>{{ itemLabel(zone.id, item.id) }}</span>
              <el-tag v-if="isLocked(item.id)" size="small" type="info">锁定</el-tag>
            </div>
            <el-switch
              v-model="item.visible"
              :disabled="isLocked(item.id) || showOverflows(zone.id, item)"
              :title="showOverflows(zone.id, item) ? '显示后会超出该区域的网格宽度' : undefined"
            />
            <el-select v-model="item.size" :disabled="isLocked(item.id)" size="small" style="width: 72px">
              <el-option label="S" value="S" :disabled="sizeOverflows(zone.id, item, 'S')" />
              <el-option label="M" value="M" :disabled="sizeOverflows(zone.id, item, 'M')" />
              <el-option label="L" value="L" :disabled="sizeOverflows(zone.id, item, 'L')" />
            </el-select>
            <div class="order-actions">
              <el-button size="small" :icon="ArrowUp" circle :disabled="isLocked(item.id)" @click="move(zone.id, item.id, -1)" />
              <el-button size="small" :icon="ArrowDown" circle :disabled="isLocked(item.id)" @click="move(zone.id, item.id, 1)" />
            </div>
          </div>
          <div class="muted hint" :class="{ danger: zoneOverWidth(zone.id) }">
            {{ zoneHint(zone.id) }}
          </div>
        </el-card>
      </div>
    </el-card>

    <div class="bottom-actions">
      <el-button @click="load">放弃修改</el-button>
      <el-button type="primary" :loading="saving" :disabled="overWidthZones.length > 0" @click="save">保存主题与布局</el-button>
    </div>
  </div>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.actions, .bottom-actions { display: flex; gap: 8px; }
.mb { margin-bottom: 16px; }
.card-h { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.ml { margin-left: 10px; }
.seed-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; }
.preview-card { position: sticky; top: 12px; }
.phone { width: 270px; margin: 0 auto; border: 8px solid #303133; border-radius: 30px; overflow: hidden; background: #fff; }
.phone-top { color: #fff; padding: 12px 16px; display: flex; justify-content: space-between; font-size: 12px; }
.phone-body { padding: 18px 16px 22px; min-height: 260px; }
.preview-title { font-size: 18px; font-weight: 600; color: #303133; margin-bottom: 18px; }
.preview-line { height: 8px; border-radius: 8px; background: #ebeef5; margin-bottom: 10px; }
.preview-line.short { width: 65%; }
.preview-block { border-radius: 12px; padding: 18px; font-weight: 600; margin: 18px 0; }
.phone-nav { display: flex; justify-content: space-around; border-top: 1px solid #ebeef5; padding: 10px 4px; background: #fafafa; }
.nav-item { display: flex; flex-direction: column; align-items: center; gap: 4px; font-size: 10px; color: #606266; min-width: 38px; }
.nav-dot { width: 8px; height: 8px; border-radius: 50%; }
.zones { display: grid; gap: 12px; }
.zone-card { border: 1px solid #ebeef5; }
.layout-row { display: grid; grid-template-columns: minmax(180px, 1fr) 80px 90px 110px; align-items: center; gap: 8px; padding: 10px 0; border-top: 1px solid #f2f3f5; }
.header-row { color: #909399; font-size: 12px; border-top: 0; padding-top: 0; }
.item-name { display: flex; align-items: center; gap: 8px; }
.order-actions { display: flex; gap: 6px; }
.hint { min-height: 18px; margin-top: 8px; }
.danger { color: #f56c6c; }
.zone-alert { margin-bottom: 8px; }
.bottom-actions { justify-content: flex-end; margin-top: 8px; }
@media (max-width: 768px) {
  .head { flex-direction: column; }
  .layout-row { grid-template-columns: 1fr 60px 80px 100px; }
  .preview-card { position: static; }
}
</style>
