<script setup lang="ts">
import { ref } from 'vue'

const username = ref('')
const password = ref('')
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="brand-mark">SA</div>
      <h1>销售分析系统</h1>
      <p>门店经营数据与多 Agent 决策分析平台</p>
      <el-form label-position="top">
        <el-form-item label="用户名"><el-input v-model="username" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" type="password" show-password /></el-form-item>
        <el-button type="primary" disabled>登录（权限模块待实现）</el-button>
      </el-form>
    </section>
  </main>
</template>
