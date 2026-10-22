<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { errorText } from '@/api/errors'
import { apiRoot } from '@/api/http'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  loading.value = true
  error.value = ''
  try {
    await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    router.replace(redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/dashboard')
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <el-card class="card" shadow="always">
      <h2 class="title">💞 Couple 管理后台</h2>
      <p class="muted sub">仅限管理员账号登录</p>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="err" />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" autocomplete="username" placeholder="管理员用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="密码" @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="btn">登录</el-button>
      </el-form>
      <p class="muted api">API：{{ apiRoot() }}</p>
    </el-card>
  </div>
</template>

<style scoped>
.wrap { min-height: 100%; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #fde2e7, #e8eefc); }
.card { width: 380px; }
.title { margin: 0; text-align: center; }
.sub { text-align: center; margin: 6px 0 18px; }
.err { margin-bottom: 14px; }
.btn { width: 100%; margin-top: 4px; }
.api { margin: 14px 0 0; text-align: center; word-break: break-all; }
</style>
