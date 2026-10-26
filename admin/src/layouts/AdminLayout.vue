<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Brush, DataLine, Document, Grid, Monitor, Odometer, Setting, Connection, Iphone, Upload } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const active = computed(() => route.path)

/** 后续阶段的入口，P0 仅占位且禁用 */
const upcoming = [
  { key: 'theme', label: '主题', icon: Brush, phase: 'P1 主题 token / P5 编辑器' },
  { key: 'layout', label: '布局', icon: Grid, phase: 'P5' },
  { key: 'plugins', label: '插件', icon: Connection, phase: 'P2 / P5' },
  { key: 'calls', label: '通话统计', icon: DataLine, phase: 'P4' },
]

async function onLogout() {
  await ElMessageBox.confirm('确定退出登录吗？', '退出', { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' })
  await auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="208px" class="aside">
      <div class="brand">💞 Couple 管理后台</div>
      <el-menu :default-active="active" router class="menu">
        <el-menu-item index="/dashboard"><el-icon><Odometer /></el-icon><span>仪表盘</span></el-menu-item>
        <el-menu-item index="/config"><el-icon><Setting /></el-icon><span>服务器配置</span></el-menu-item>
        <el-menu-item index="/releases"><el-icon><Upload /></el-icon><span>版本发布</span></el-menu-item>
        <el-menu-item index="/devices"><el-icon><Monitor /></el-icon><span>设备</span></el-menu-item>
        <el-menu-item index="/bootstrap"><el-icon><Iphone /></el-icon><span>引导二维码</span></el-menu-item>
        <el-menu-item index="/audit"><el-icon><Document /></el-icon><span>审计日志</span></el-menu-item>
        <el-menu-item-group title="即将推出">
          <el-menu-item v-for="item in upcoming" :key="item.key" :index="`/soon/${item.key}`" disabled>
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
            <el-tag size="small" type="info" class="soon-tag">{{ item.phase.split(' ')[0] }}</el-tag>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="crumb">{{ route.meta.title }}</span>
        <div class="user">
          <el-tag type="danger" size="small" effect="plain">管理员</el-tag>
          <span>{{ auth.user?.displayName || auth.user?.username }}</span>
          <el-button link type="primary" @click="onLogout">退出</el-button>
        </div>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout { height: 100%; }
.aside { background: #fff; border-right: 1px solid #ebeef5; }
.brand { height: 56px; display: flex; align-items: center; padding: 0 20px; font-weight: 600; border-bottom: 1px solid #ebeef5; }
.menu { border-right: none; }
.soon-tag { margin-left: auto; }
.header { background: #fff; border-bottom: 1px solid #ebeef5; display: flex; align-items: center; justify-content: space-between; }
.crumb { font-weight: 500; }
.user { display: flex; align-items: center; gap: 10px; font-size: 14px; }
</style>
