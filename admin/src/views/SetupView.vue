<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormItemRule, FormRules } from 'element-plus'
import { api } from '@/api'
import { errorText } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { setupGuard } from '@/router'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const form = reactive({
  token: typeof route.query.token === 'string' ? route.query.token : '',
  username: '',
  password: '',
  confirm: '',
})

const confirmRule: FormItemRule = {
  trigger: 'blur',
  validator: (_r, v: string, cb) => (v === form.password ? cb() : cb(new Error('两次输入的密码不一致'))),
}

const rules: FormRules = {
  token: [{ required: true, message: '请输入初始化令牌', trigger: 'blur' }],
  username: [
    { required: true, message: '请输入管理员用户名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_.-]{3,32}$/, message: '3–32 位字母、数字或 _ . -', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 128, message: '密码长度需为 8–128 位', trigger: 'blur' },
  ],
  confirm: [{ required: true, message: '请再次输入密码', trigger: 'blur' }, confirmRule],
}

const loading = ref(false)
const error = ref('')

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  error.value = ''
  if (form.password !== form.confirm) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    const pair = await api.setup(form.token.trim(), form.username.trim(), form.password)
    auth.adopt(pair)
    setupGuard.markDone()
    await router.replace({ name: 'bootstrap', query: { setup: '1' } })
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
      <h2 class="title">💞 初始化 Couple 服务器</h2>
      <p class="muted sub">创建第一个管理员账号。服务器地址会根据当前访问的域名自动配置。</p>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="err" data-test="error" />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="初始化令牌" prop="token">
          <el-input v-model="form.token" autocomplete="off" placeholder="服务器日志中 SETUP TOKEN 后面的内容" data-test="token" />
        </el-form-item>
        <el-form-item label="管理员用户名" prop="username">
          <el-input v-model="form.username" autocomplete="username" placeholder="例如 admin" data-test="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="至少 8 位" data-test="password" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" placeholder="再次输入密码" data-test="confirm" @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="btn" data-test="submit">完成初始化</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.wrap { min-height: 100%; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #fde2e7, #e8eefc); }
.card { width: 400px; }
.title { margin: 0; text-align: center; }
.sub { text-align: center; margin: 6px 0 18px; }
.err { margin-bottom: 14px; }
.btn { width: 100%; margin-top: 4px; }
</style>
