<script setup lang="ts">
const modules = ['经营概览', '商品档案', '数据导入', '门店库存', '销售分析', '冷热商品', 'AI 分析', '系统管理']
</script>

<template>
  <el-container class="shell">
    <el-aside width="224px" class="sidebar">
      <div class="sidebar-title"><span class="brand-mark small">SA</span> 销售分析</div>
      <el-menu router default-active="/">
        <el-menu-item v-for="(module, index) in modules" :key="module" :index="index ? `/${index}` : '/'">
          {{ module }}
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="topbar"><span>多 Agent 协作销售分析系统</span><el-tag>工程骨架</el-tag></el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>
