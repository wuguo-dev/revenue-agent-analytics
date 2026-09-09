<template>
  <section>
    <h2>工程骨架已就绪</h2>
    <p class="muted">后续模块将按“接口、页面、联调测试”逐个交付。</p>
    <el-row :gutter="16">
      <el-col v-for="label in ['今日销售额', '门店库存', '负库存告警', 'Agent 报告']" :key="label" :span="6">
        <el-card shadow="never"><div class="metric-label">{{ label }}</div><div class="metric-value">—</div></el-card>
      </el-col>
    </el-row>
  </section>
</template>
