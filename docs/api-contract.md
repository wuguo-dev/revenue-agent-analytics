# API 接口契约（评审稿）

> 状态：DRAFT。本文档仅用于计划阶段汇总接口清单；项目计划定稿后，将按 M01–M10 拆分到 `docs/api/`，每个业务模块单独评审。标记 `[G1]` 的字段必须在样表分析后确认。

## 1. 通用约定

### 1.1 基础规则

- 基础路径：`/api/v1`；健康检查保留 Actuator 原路径。
- 认证：`Authorization: Bearer <accessToken>`。
- 时间：ISO-8601，业务时区 `Asia/Shanghai`。
- 金额：十进制定点数，JSON 中使用数字；最终精度由 G1 字段评审确定。商品供应商价格与销售成本快照分开保存。
- 数量：允许小数，暂按三位小数设计，最终由 G1 确认。
- 条形码：始终作为字符串传输，禁止前后端转换为数字。
- 删除：业务主数据默认不物理删除，使用状态停用；未提交导入批次可以放弃。

### 1.2 标准响应

```json
{
  "code": "OK",
  "message": "",
  "data": {}
}
```

分页数据：

```json
{
  "items": [],
  "page": 1,
  "size": 20,
  "total": 0
}
```

### 1.3 通用错误码

| HTTP | code | 含义 |
|---:|---|---|
| 400 | VALIDATION_ERROR | 参数、格式或业务校验失败 |
| 401 | UNAUTHORIZED | 未登录、令牌无效或过期 |
| 403 | FORBIDDEN | 角色或门店范围不允许 |
| 404 | NOT_FOUND | 资源不存在 |
| 409 | DUPLICATE_RESOURCE | 唯一键冲突 |
| 409 | IMPORT_DUPLICATE | 重复导入文件 |
| 409 | INVALID_STATE | 当前状态不允许操作 |
| 413 | FILE_TOO_LARGE | 文件超过上限 |
| 422 | IMPORT_INVALID | 导入预检存在错误 |
| 429 | TOO_MANY_REQUESTS | 请求或 AI 运行频率超限 |
| 500 | INTERNAL_ERROR | 未分类服务端错误 |
| 503 | AI_UNAVAILABLE | 模型未配置或暂不可用 |
| 503 | SEARCH_UNAVAILABLE | Elasticsearch 暂不可用 |

## 2. 权限定义

| 标记 | 可访问者 | 数据范围 |
|---|---|---|
| PUBLIC | 未登录用户 | 仅登录与健康接口 |
| AUTHENTICATED | 管理员、普通用户 | 当前用户信息 |
| USER_CATALOG | 普通用户、管理员 | 普通用户固定为自身门店并可查看该店最新已知库存；管理员可指定门店 |
| SALES_IMPORT | 普通用户、管理员 | 普通用户仅限 `DAILY_SALES` 和账号绑定门店，可读取所属门店全部销售导入批次；管理员可选门店 |
| ADMIN | 管理员 | 全部门店；具体接口可按门店过滤 |

普通用户的商品响应必须使用 `ProductPublicView`，不能先返回完整对象再由前端隐藏字段。普通用户只能读取所属门店的最新已知库存；`SALES_IMPORT` 不授权库存管理、其他门店库存、销售查询、成本、利润或 AI 权限。

## 3. 通用对象

### SessionView

```text
accessToken, expiresAt,
user: { id, username, displayName, roleCode, store: { id, code, name } | null }
```

### ProductPublicView

```text
id, barcode, name, suppliers[{ id, name }], specification, unit,
categoryName, salePrice, status,
inventory: { quantity, sourceType, sourceBusinessDate, lastSyncedAt } | null
```

明确禁止包含：其他门店库存、`purchasePrice`、`salesQuantity`、`salesAmount`、`costAmount`、`grossProfit`、`grossMarginRate`。

### ProductAdminView `[G1]`

```text
id, barcode, name, suppliers[{ id, name, currentPurchasePrice }],
specification, unit, category, salePrice, status,
storeInventories[{ storeId, storeName, quantity, sourceBusinessDate, lastSyncedAt }],
updatedAt
```

### ImportErrorView

```text
rowNumber, sourceColumn, targetField, sourceValue, errorCode, message
```

### ChartSpec

```text
type: metric | line | bar | pie | table
title, xAxis[], series[{ name, data[] }], columns[], rows[]
```

服务端拒绝未知图表类型和任何脚本文本。

## 4. 系统接口

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-001 | `GET /actuator/health` | PUBLIC | 无 | `status` |
| API-002 | `GET /system/info` | AUTHENTICATED | 无 | `version, environment, serverTime, aiEnabled` |

## 5. 认证、用户与门店

### 5.1 认证

| ID | 方法与路径 | 权限 | 请求 | 响应/行为 |
|---|---|---|---|---|
| API-010 | `POST /auth/login` | PUBLIC | `username, password` | `SessionView`；停用账号拒绝 |
| API-011 | `POST /auth/logout` | AUTHENTICATED | 无 | 当前令牌加入 Redis 失效列表 |
| API-012 | `GET /auth/me` | AUTHENTICATED | 无 | 当前用户、角色、绑定门店、菜单权限 |

登录失败统一返回“用户名或密码错误”，不泄露账号是否存在。系统不提供注册接口。

### 5.2 用户管理

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-013 | `GET /admin/users` | ADMIN | `keyword, roleCode, storeId, status, page, size` | 用户分页，不返回密码哈希 |
| API-014 | `GET /admin/users/{id}` | ADMIN | 路径 ID | 用户详情 |
| API-015 | `POST /admin/users` | ADMIN | `username, displayName, password, roleCode, storeId` | 新用户 |
| API-016 | `PUT /admin/users/{id}` | ADMIN | `displayName, roleCode, storeId` | 更新后用户 |
| API-017 | `PUT /admin/users/{id}/status` | ADMIN | `status: ACTIVE|DISABLED` | 新状态 |
| API-018 | `PUT /admin/users/{id}/password` | ADMIN | `newPassword` | 成功；现有令牌全部失效 |

校验：`USER` 必须绑定门店；`ADMIN` 的门店为空；用户名创建后不可修改；管理员不能停用当前登录账号。

### 5.3 门店管理

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-020 | `GET /admin/stores` | ADMIN | `keyword, status, page, size` | 门店分页 |
| API-021 | `GET /admin/stores/options` | ADMIN | `status` | 下拉选项 `id, code, name` |
| API-022 | `GET /admin/stores/{id}` | ADMIN | 路径 ID | 门店详情 |
| API-023 | `POST /admin/stores` | ADMIN | `code, name, address?, contactName?, contactPhone?` `[G1]` | 新门店 |
| API-024 | `PUT /admin/stores/{id}` | ADMIN | 可编辑门店字段 `[G1]` | 更新后门店 |
| API-025 | `PUT /admin/stores/{id}/status` | ADMIN | `status` | 新状态；有关联用户或业务数据时不可物理删除 |

## 6. 商品与基础资料

### 6.1 商品

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-030 | `GET /catalog/products` | USER_CATALOG | `keyword, supplierId, categoryId, status, page, size` | `ProductPublicView` 分页；普通用户自动限定自身门店并返回该店最新已知库存 |
| API-031 | `GET /catalog/products/{id}` | USER_CATALOG | 路径 ID | `ProductPublicView`；返回所属门店最新已知库存，不属于门店则 404 |
| API-032 | `GET /admin/products` | ADMIN | `keyword, barcode, supplierId, categoryId, status, page, size` | `ProductAdminView` 分页 |
| API-033 | `GET /admin/products/{id}` | ADMIN | 路径 ID | 管理员商品详情及货位，不默认返回销售汇总 |
| API-034 | `POST /admin/products` | ADMIN | `ProductAdminWrite` `[G1]` | 新商品 |
| API-035 | `PUT /admin/products/{id}` | ADMIN | `ProductAdminWrite`，含 `barcode, version` `[G1]` | 更新商品；允许受控修改条码 |
| API-036 | `PUT /admin/products/{id}/status` | ADMIN | `status` | 启用/停用，不删除历史销售 |
| API-037 | `GET /admin/products/{id}/suppliers` | ADMIN | 路径 ID | 商品的全部供应商关系及当前报价 `[G1]` |
| API-038 | `PUT /admin/products/{id}/suppliers` | ADMIN | `suppliers[{supplierId,currentPurchasePrice,status}]` `[G1]` | 整体更新商品供应商关系；不改历史销售快照 |

### 6.2 供应商与分类

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-040 | `GET /admin/suppliers` | ADMIN | `keyword, status, page, size` | 供应商分页 `[G1]` |
| API-041 | `POST /admin/suppliers` | ADMIN | 供应商字段 `[G1]` | 新供应商 |
| API-042 | `PUT /admin/suppliers/{id}` | ADMIN | 供应商字段 `[G1]` | 更新结果 |
| API-043 | `PUT /admin/suppliers/{id}/status` | ADMIN | `status` | 新状态 |
| API-044 | `GET /admin/categories` | ADMIN | `keyword, status, page, size` | 分类分页 `[G1]` |
| API-045 | `POST /admin/categories` | ADMIN | 分类字段 `[G1]` | 新分类 |
| API-046 | `PUT /admin/categories/{id}` | ADMIN | 分类字段 `[G1]` | 更新结果 |
| API-047 | `PUT /admin/categories/{id}/status` | ADMIN | `status` | 新状态 |

### 6.3 仓库、货位和商品位置

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-048 | `GET /admin/warehouses` | ADMIN | `storeId, keyword, status, page, size` | 仓库分页；无库存字段 |
| API-049 | `POST /admin/warehouses` | ADMIN | `storeId, code, name, description?` | 新仓库 |
| API-050 | `PUT /admin/warehouses/{id}` | ADMIN | `code, name, description?, status` | 更新仓库 |
| API-051 | `GET /admin/warehouses/{id}/locations` | ADMIN | `keyword, status` | 货位列表 |
| API-052 | `POST /admin/warehouses/{id}/locations` | ADMIN | `code, name?, description?` `[G1]` | 新货位 |
| API-053 | `PUT /admin/locations/{id}` | ADMIN | 可编辑货位字段 | 更新货位 |
| API-054 | `PUT /admin/products/{id}/locations` | ADMIN | `locationIds[], note?` | 替换商品货位关系，不改变库存 |

### 6.4 商品搜索索引

MySQL 是权威数据源；Elasticsearch 索引只包含搜索和权限过滤所需字段。商品增改、状态变化和导入提交后写入索引同步任务，由后台任务重试，不使用消息队列。

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-055 | `GET /admin/search/index-status` | ADMIN | 无 | 索引名、文档数、待同步数、失败数、最近同步时间 |
| API-056 | `POST /admin/search/reindex` | ADMIN | `confirm: true` | 创建全量重建任务并返回 `taskId, status`；重复运行返回 409 |

API-030/032 的 `keyword` 搜索使用 Elasticsearch，按条形码、商品名称、规格、供应商和分类检索；门店和角色过滤必须在服务端查询条件中执行，不能在结果返回后过滤。库存以 MySQL 为准，ES 返回商品 ID 后由后端按权限关联门店库存，避免把频繁变化的库存作为搜索索引权威值。

商品与供应商为多对多；供应商只用于商品来源展示和进货价格记录，不提供供应商销售分析接口。

条码统一作为字符串传输和保存，保留前导零，长度为 1 至 64，只允许 ASCII 数字和英文字母，基础格式为 `[A-Za-z0-9]{1,64}`。空值、空格、标点符号、中文、超长值和 Excel 已丢失精度的科学计数法均返回 `PRODUCT_BARCODE_INVALID`；不得把条码转换为数值类型。

API-035 修改条码时，新条码必须符合上述格式且全局唯一；冲突返回 409 `PRODUCT_BARCODE_CONFLICT`，并发版本不一致返回 409 `VERSION_CONFLICT`。修改只变更该商品的当前业务条码，内部商品 ID 不变，因此历史销售、门店商品、库存快照、供应商和货位关系继续归属同一商品；同时创建 ES 索引同步任务。商品资料导入仍以当前条码作为唯一匹配键，不能通过同一行表达“旧条码改成新条码”。系统不建立旧条码别名，修改后的旧条码不再参与商品查询或导入匹配。普通用户没有商品写接口，调用 API-034 至 API-038 均返回 403。条码修改必须记录操作人、修改时间、旧条码和新条码，但审计记录中的旧值不具备业务匹配作用。

## 7. 导入模板与批次

### 7.1 枚举

- `importType`: `PRODUCT`、`DAILY_SALES`。
- `mode`: 商品为 `PRODUCT_ONLY`、`PRODUCT_INVENTORY_SYNC`；销售固定为 `DAILY_SALES`。POP 中商品和库存数据完整，并可导出包含零库存、已停用商品的单门店完整商品资料；`PRODUCT_INVENTORY_SYNC` 允许将其分批同步到新系统，每批只覆盖其中出现的条码。首版不提供进货累加或人工库存调整模式。
- `status`: `UPLOADED`、`VALIDATING`、`READY`、`INVALID`、`COMMITTED`、`DISCARDED`。

### 7.2 映射模板

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-060 | `GET /admin/import-templates` | ADMIN | `importType, keyword, page, size` | 模板分页 |
| API-061 | `GET /admin/import-templates/{id}` | ADMIN | 路径 ID | 模板及字段映射 |
| API-062 | `POST /admin/import-templates` | ADMIN | `name, importType, mappings[{sourceHeader,targetField}]` | 新模板 |
| API-063 | `PUT /admin/import-templates/{id}` | ADMIN | 同创建请求 | 更新模板 |
| API-064 | `DELETE /admin/import-templates/{id}` | ADMIN | 路径 ID | 未被进行中批次使用时删除 |
| API-065 | `GET /imports/templates/options` | SALES_IMPORT / ADMIN | `importType, mode` | 已启用模板选项；普通用户只返回 `DAILY_SALES` 模板，不返回内部映射细节 |

目标字段列表由服务端按 `importType/mode` 返回，不允许前端提交任意数据库列名：

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-066 | `GET /imports/target-fields` | SALES_IMPORT / ADMIN | `importType, mode` | `field, label, required, dataType, aliases[]` `[G1]`；普通用户只可请求 `DAILY_SALES` |

### 7.3 文件预检和提交

| ID | 方法与路径 | 权限 | 请求 | 响应/行为 |
|---|---|---|---|---|
| API-067 | `POST /imports/preview` | SALES_IMPORT / ADMIN | `multipart: file, importType, mode, businessDate?, storeId?, templateId?, mappingsJson?` | 批次 ID、日期、表头、映射、忽略列、统计、错误、变更预览 |
| API-068 | `GET /imports` | SALES_IMPORT / ADMIN | `storeId?, importType, mode, businessDate?, status, createdFrom, createdTo, operatorId?, page, size` | 批次分页；普通用户固定为所属门店的 `DAILY_SALES` 批次，忽略/拒绝越权筛选 |
| API-069 | `GET /imports/{batchId}` | SALES_IMPORT / ADMIN | 路径 ID | 批次详情、错误、忽略列、变更摘要；普通用户可读取所属门店任意销售批次 |
| API-070 | `GET /imports/{batchId}/rows` | SALES_IMPORT / ADMIN | `resultType, page, size` | 预检行分页；普通用户可读取所属门店销售批次，但响应不含成本、毛利等敏感值 |
| API-071 | `GET /imports/{batchId}/errors.xlsx` | SALES_IMPORT / ADMIN | 路径 ID | 所属门店错误行 Excel；普通用户版本屏蔽敏感源值 |
| API-072 | `POST /imports/{batchId}/commit` | SALES_IMPORT / ADMIN | `expectedVersion` | 原子提交；普通用户只能提交本人创建的 READY 批次，管理员不受创建人限制 |
| API-073 | `DELETE /imports/{batchId}` | SALES_IMPORT / ADMIN | 路径 ID | 放弃 READY/INVALID 批次；普通用户只能操作本人创建的批次，管理员不受创建人限制 |

`preview` 主要响应：

```text
batchId, status, filename, fileHash, store, businessDate, importType, mode,
headers[], resolvedMappings[], ignoredColumns[],
statistics { totalRows, validRows, errorRows, createRows, updateRows, returnRows },
inventoryOverwrite { presentRows, changedRows, unchangedRows, skippedOlderRows, conflictRows },
errors[]
```

`DAILY_SALES` 的 `businessDate` 必填，且整份文件只能对应一个营业日期；其他导入类型不接收该字段。管理员必须提交 `storeId`，普通用户不得提交 `storeId`，服务端从当前账号绑定关系取得门店。普通用户提交非 `DAILY_SALES` 类型、伪造门店、访问其他门店批次或操作他人批次时返回 403。

存在错误行时状态为 `INVALID`，API-072 必须返回 `IMPORT_INVALID`。销售导入的重复文件范围为“门店 + 营业日期 + 导入类型 + SHA-256”；该范围内已有 `COMMITTED` 批次时返回 `IMPORT_DUPLICATE`。同一门店、日期和条码已有销售事实但新文件指纹不同时，按修正版预检并展示销售差额；库存不按销售差额加减。

销售导入只包含当日销售商品，属于部分库存覆盖：文件中出现商品的“选中机构库存数量”（别名“当前机构库存数量”）直接覆盖门店最新已知库存，文件未出现商品保持原值。若同条码多行的库存值不一致，整批为 `INVALID`。若营业日期早于商品当前库存的来源营业日期，该行销售仍可修正，但库存覆盖跳过并记录原因；同一营业日期以最后成功提交批次为准。

商品资料以 `PRODUCT_INVENTORY_SYNC` 导入时，管理员选择门店；每个文件都按独立增量同步批次处理。这里的“条码不存在”专指新系统中尚无该条码，POP 中的商品及库存仍是完整、权威的。新系统中条码不存在时创建商品主档、商品供应商关系和门店商品关系；条码已存在时按字段合并，并用有效的“库存数量”直接覆盖该店库存。批次未出现的商品不参与校验或更新，不清零、不停用，也不阻断提交。该模式不是采购或实体入库，也不是进货累加，可拆分多批并重复执行。

商品资料已有条码的字段合并规则固定为“非空覆盖”：Excel 单元格为空时，该字段记为 `UNCHANGED` 并保留系统旧值；单元格非空且校验通过时记为 `UPDATED` 并覆盖旧值。空单元格不能用于清空系统字段；如以后需要清空，必须通过独立的显式编辑能力设计。条码本身始终必填；新商品缺少名称等必填字段时整行报错；任何非空值格式错误时均报错，不能以保留旧值代替校验。供应商单元格为空时保留现有供应商关系，不删除关系。预检变更摘要需要区分 `createdFields`、`updatedFields` 和 `unchangedBlankFields`。

普通用户导入页展示完成本次操作所需的批次状态、行号、条码、商品名称、所属门店库存覆盖结果、校验原因和提交结果；仍不返回销售金额、进价、成本、利润或毛利率。若敏感字段校验失败，错误响应返回字段名和原因，但屏蔽原始值。

## 8. 库存接口

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-080 | `GET /admin/inventory/stores/{storeId}` | ADMIN | `keyword, categoryId, negativeOnly, staleOnly, page, size` | 门店最新已知库存分页；返回来源营业日期和最后同步时间 `[G1]` |
| API-081 | `GET /admin/inventory/summary` | ADMIN | `keyword, categoryId, staleOnly, page, size` | 汇总各门店最新已知库存，并返回门店拆分和数据新鲜度 |
| API-082 | `GET /admin/inventory/sync-records` | ADMIN | `storeId, productId, sourceType, dateFrom, dateTo, page, size` | POP 库存覆盖记录及覆盖前后差异 |
| API-083 | `GET /admin/inventory/snapshots` | ADMIN | `storeId, businessDateFrom, businessDateTo, page, size` | 商品资料或销售批次产生的库存快照分页 |
| API-084 | `GET /admin/inventory/snapshots/{batchId}` | ADMIN | `productId?, changedOnly?, page, size` | 指定批次的库存快照明细 |
| API-085 | `GET /admin/inventory/freshness` | ADMIN | `storeId?, staleDays?` | 最后同步时间分布、陈旧商品数量和门店覆盖情况 |
| API-086 | `GET /admin/inventory/alerts` | ADMIN | `storeId, type, status, page, size` | 负库存等告警 |
| API-087 | `PUT /admin/inventory/alerts/{id}/status` | ADMIN | `status: OPEN|RESOLVED, resolution?` | 更新告警处理状态，不改库存 |

库存同步记录主要字段 `[G1]`：`id, store, product, quantityBefore, popQuantity, quantityDifference, sourceType, sourceBatchId, sourceBusinessDate, syncedAt, operator`。差异只表示两次 POP 观察值之差，不能解释为销售、进货或盘点流水。

## 9. 经营分析接口

所有接口权限为 ADMIN，公共查询参数为 `storeId?、dateFrom、dateTo`；`storeId` 为空表示全部门店；单次日期跨度不得超过一个月。

| ID | 方法与路径 | 额外请求 | 响应 `[G4]` |
|---|---|---|---|
| API-090 | `GET /admin/analytics/overview` | 公共参数 | 销量、销售额、成本、毛利、毛利率、退货及环比 |
| API-091 | `GET /admin/analytics/trend` | `granularity: DAY|WEEK|MONTH, metrics[]` | 时间点及所选指标序列 |
| API-092 | `GET /admin/analytics/stores` | `sortBy, order` | 门店指标对比 |
| API-093 | `GET /admin/analytics/products` | `categoryId?, sortBy, order, limit` | 商品销售排行 |
| API-094 | `GET /admin/analytics/margin` | `categoryId?, groupBy` | 毛利、成本和毛利率分组 |
| API-095 | `GET /admin/analytics/anomalies` | `types[], page, size` | 缺失日期、负库存、异常值、导入告警 |

空周期必须返回零值和空序列，不返回 500。退货是否计入净销售和毛利的确切公式在 G4 逐项确认。

## 10. 冷热评分接口

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-100 | `GET /admin/scoring/config` | ADMIN | 无 | 当前权重、阈值、版本、更新时间 |
| API-101 | `PUT /admin/scoring/config` | ADMIN | `quantityWeight, amountWeight, growthWeight, activeDaysWeight, hotThreshold, coldThreshold` | 新配置版本 |
| API-102 | `POST /admin/scoring/runs` | ADMIN | `storeId?, dateFrom, dateTo, configVersion?` | `runId, status` |
| API-103 | `GET /admin/scoring/results` | ADMIN | `runId, level?, keyword?, page, size` | 商品得分和冷热等级分页 |
| API-104 | `GET /admin/scoring/results/{resultId}` | ADMIN | 路径 ID | 总分、各维度原值/标准分/权重及配置快照 |

校验：四项权重合计必须为 100；`0 <= coldThreshold < hotThreshold <= 100`。

## 11. 多 Agent 接口

### 11.1 会话和运行

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-110 | `POST /admin/agent/conversations` | ADMIN | `title?` | 新会话 |
| API-111 | `GET /admin/agent/conversations` | ADMIN | `keyword, page, size` | 当前管理员的会话分页 |
| API-112 | `GET /admin/agent/conversations/{id}` | ADMIN | 路径 ID | 会话、消息和运行摘要 |
| API-113 | `DELETE /admin/agent/conversations/{id}` | ADMIN | 路径 ID | 归档会话，不物理删除报告 |
| API-114 | `POST /admin/agent/conversations/{id}/runs` | ADMIN | `question, storeId?, dateFrom, dateTo` | `runId, status: QUEUED, eventsUrl` |
| API-115 | `GET /admin/agent/runs/{runId}/events` | ADMIN | SSE；支持 `Last-Event-ID` | 流式事件 |
| API-116 | `GET /admin/agent/reports/{runId}` | ADMIN | 路径 ID | 问题、范围、阶段摘要、报告、图表和完成时间 |

### 11.2 SSE 事件合同

```json
{
  "runId": 1001,
  "sequence": 3,
  "timestamp": "2026-09-07T10:00:00+08:00",
  "type": "stage_result",
  "payload": {}
}
```

| type | payload |
|---|---|
| task_started | `question, storeId, dateFrom, dateTo` |
| agent_started | `agentCode, agentName` |
| stage_result | `agentCode, summary, facts[]` |
| chart | `ChartSpec` |
| report | `markdown` |
| completed | `reportUrl, durationMs` |
| error | `errorCode, message, retryable` |

客户端按 `sequence` 去重和排序；运行完成后重连可使用 API-116 获取最终结果。

## 12. 审计与运维接口

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-120 | `GET /admin/audit-logs` | ADMIN | `operatorId, module, action, dateFrom, dateTo, page, size` | 登录、账号权限、导入、商品条码修改、库存调整、分析配置和 Agent 审计；不记录供应商主档及商品供应商关系变更 |
| API-121 | `GET /admin/system/config-status` | ADMIN | 无 | MySQL、Redis、AI 配置是否可用；不返回密钥 |
| API-122 | `GET /admin/system/operations` | ADMIN | 无 | 版本、运行时间、最近迁移、最近导入和 Agent 状态摘要 |

## 13. 接口评审待确认项

以下内容必须在 G0/G1/G4 中明确后才能将本文档改为 APPROVED：

1. 门店、供应商、分类、仓库和货位的最终维护字段。
2. POP 两类表格的最终字段、表头别名、数量和金额精度。
3. 销售汇总中退货、销售额、成本、毛利和毛利率的确切统计公式。
4. 库存超过多少天未被两类 POP 文件覆盖时标记为陈旧。
5. Agent 单次运行超时、并发限制、会话保留期限。
6. 审计日志和原始导入文件的保留期限。
