# 多 Agent 协作销售分析系统

面向内部零售运营的销售、库存与 AI 分析平台。项目采用 Spring Boot + Vue 3 的前后端分离模块化单体架构。

## 目录

- `backend`：REST API、Excel 导入、库存销售领域逻辑、Agent 工作流
- `frontend`：管理员工作台与普通用户商品目录
- `deploy`：本地 MySQL/Redis 编排与环境变量示例
- `docs`：实施计划、接口和数据字典

## 本地启动

1. 复制 `deploy/.env.example` 为 `deploy/.env` 并设置初始管理员密码与 DashScope Key。
2. 在 `deploy` 目录运行 `docker compose up -d mysql redis`。
3. 在 `backend` 目录运行 `mvn spring-boot:run`。
4. 在 `frontend` 目录运行 `npm install && npm run dev`。

默认前端地址为 `http://localhost:5173`，后端接口为 `http://localhost:8080/api/v1`。
