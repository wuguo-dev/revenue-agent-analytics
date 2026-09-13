# M04 导入中心接口与实施粒度规划

> 状态：APPROVED
> 模块：M04 导入中心
> 基础路径：`/api/v1`
> 依赖模块：M01 认证、M02 门店、M03 商品与基础资料、M05 POP 库存、M06 每日销售、M10 审计
> 目标：把 Excel 文件可靠地转换为可审阅、可追踪、可原子提交的业务变更计划。

## 0. 逐步确认流程

本文件已按下表完成全部逐项评审。后续若业务规则发生变化，必须将受影响步骤重新置为待确认，并完成跨文档一致性校验后才能再次定稿。

| 步骤 | 评审主题 | 对应章节 | 状态 | 确认记录 |
|---:|---|---|---|---|
| 1 | 模块目标、范围与不做事项 | 第 1、2 节 | confirmed | 2026-09-12 用户确认通过 |
| 2 | 角色权限与门店数据范围 | 第 3 节 | confirmed | 2026-09-12 用户确认通过 |
| 3 | 批次枚举与状态机 | 第 4 节 | confirmed | 2026-09-12 用户确认通过 |
| 4 | 模板生命周期与目标字段 | 第 5.1～5.4 节 | confirmed | 2026-09-12 用户确认通过 |
| 5 | 字段映射优先级与映射校验 | 第 5.5 节 | confirmed | 2026-09-12 用户确认通过；采用普通用户免配置方案 |
| 6 | 文件上传、解析限制与预检顺序 | 第 6 节 | confirmed | 2026-09-12 用户确认通过；原始表头行布局编码前复核 |
| 7 | API-060～075 请求、响应与批次查询 | 第 7 节 | confirmed | 2026-09-12 用户确认通过；API-069 表述已澄清 |
| 8 | 两类导入业务预检规则 | 第 8 节 | confirmed | 2026-09-13 用户确认通过；采用 POP 当前值按提交顺序覆盖规则 |
| 9 | 幂等、并发、原子提交、错误下载与安全 | 第 9、10、14 节及第 7.5～7.6 节 | confirmed | 2026-09-13 用户确认通过 |
| 10 | 页面、实施任务、验收、门禁与完成定义 | 第 11～16 节 | confirmed | 2026-09-13 用户确认通过；M04 全部步骤完成 |

确认规则：

- 用户回复“确认第 N 步”或表达等价的明确同意后，才将该步标为 `confirmed`。
- 用户提出修改时，只修改当前步骤及其直接受影响内容，更新后继续等待该步确认。
- 不以沉默、进入下一话题或已有草案内容推定确认。
- 10 个步骤全部确认后，M04 标记为 `APPROVED`，P0-M04 标记为 `complete`；编码前仍须完成第 15 节 G1/G5 门禁项。

## 1. 范围与结论

M04 首版只支持两种导入类型：

| `importType` | 页面名称 | 操作者 | 必选上下文 | 业务结果 |
|---|---|---|---|---|
| `PRODUCT` | 商品资料 | 管理员 | `storeId + sourceDate` | 商品 upsert、目标门店供应商当前进价、门店售价、最新已知库存 |
| `DAILY_SALES` | 每日销售汇总 | 管理员、普通用户 | `storeId + businessDate`；普通用户门店由后端绑定 | 销售事实新增/修正、目标门店供应商当前进价、门店售价、最新已知库存 |

本模块包含：

- 官方模板与管理员自定义字段映射模板。
- `.xls`、`.xlsx` 文件上传、结构解析、表头识别和文件指纹。
- 字段映射、目标字段白名单、忽略列和映射快照。
- 行级预检、跨行取值规则与提示、业务变更预览和敏感字段脱敏。
- 批次列表、详情、行结果、状态机、放弃和审计摘要。
- 原子提交、重复导入保护、乐观锁、预检过期检查和事务回滚。
- 错误行 `.xlsx` 下载。

本模块不包含：

- 进货累加、按销量扣减库存、人工库存调整。
- 在导入页面修改商品分类、人工维护供应商或门店商品供应来源、或修改门店资料。
- `.csv`、`.xlsm`、压缩包、加密工作簿和在线表格地址。
- 后台异步排队导入；首版上传、预检和提交均为同步请求，超限文件直接拒绝。
- 数据库表与字段精度定稿；这部分仍受 G1 门禁约束。

## 2. 模块设计与 seam

M04 是一个深模块。前端只需要理解“模板 → 上传 → 预检 → 批次 → 提交/放弃”接口，不需要编排解析器、行校验器或各业务表写入顺序。

### 2.1 M04 拥有

- 文件存储引用、SHA-256、文件名、工作表与表头快照。
- 模板、模板版本、最终映射快照和忽略列快照。
- 导入批次状态、版本、统计、警告、错误和行级结果。
- 预检流水线和提交状态机。
- 权限裁剪、错误文件导出和导入审计摘要。

### 2.2 M04 不拥有

- 商品、分类、供应商、门店商品当前值的业务定义。
- 库存快照、库存同步记录和负库存告警的业务定义。
- 每日销售事实及修正版覆盖规则的业务定义。

### 2.3 内部端口

接口名称仅表达 seam，最终 Java 包名在工程骨架阶段确定。

```text
ImportWorkflow
  upload(command) -> UploadedBatch
  preview(command) -> ImportPreview
  commit(command) -> CommitResult
  discard(command) -> BatchView

ProductImportPort                 // M03 adapter
  previewProduct(command) -> ProductPreview
  commitProduct(command) -> ProductCommitResult

InventoryImportPort               // M05 adapter
  previewInventory(command) -> InventoryPreview
  commitInventory(command) -> InventoryCommitResult

SalesImportPort                   // M06 adapter
  previewSales(command) -> SalesPreview
  commitSales(command) -> SalesCommitResult
```

- M04 在内部把标准化行转换为三个类型化命令；M05 的命令固定为 `sourceBatchId, storeId, sourceType, sourceDate, items[{productId,observedQuantity?}]`，与 `docs/api/05-inventory.md` 一致。
- M03/M05/M06 分别提供生产 adapter；M04 负责编排调用和总事务，不复制各模块的业务规则。
- M04 测试使用内存 adapter，经同一 `ImportWorkflow` 接口验证可观察结果。
- 解析器、映射器、校验器、错误导出器是 M04 内部 seam，不暴露给前端或其他业务模块。

## 3. 权限与数据范围

| 能力 | 管理员 | 普通用户 |
|---|---|---|
| 维护映射模板 | 是 | 否 |
| 查看目标字段 | 两种类型 | 仅 `DAILY_SALES` |
| 上传商品资料 | 是 | 否 |
| 上传每日销售 | 是，可选门店 | 是，固定账号所属门店 |
| 查看批次 | 全部门店 | 所属门店全部销售批次 |
| 查看他人批次详情 | 是 | 同店销售批次可读 |
| 提交/放弃他人批次 | 是 | 否 |
| 下载错误文件 | 全部可见批次 | 同店销售批次，使用脱敏版本 |
| 查看金额、进价、成本、毛利率 | 是 | 否 |

服务端必须先按权限生成响应 DTO，禁止先返回管理员 DTO 再由前端隐藏。普通用户请求中出现 `storeId`、`PRODUCT` 或其他门店批次时返回 `403 FORBIDDEN`，不得静默扩大范围。

## 4. 枚举与状态机

### 4.1 枚举

```text
ImportType      = PRODUCT | DAILY_SALES
TemplateStatus  = ENABLED | DISABLED
BatchStatus     = UPLOADED | VALIDATING | READY | INVALID | COMMITTED | DISCARDED
RowResultType   = CREATE | UPDATE | CORRECTION | RETURN |
                  UNCHANGED | IGNORED_ZERO_QUANTITY | ERROR
FieldAction     = CREATED | UPDATED | UNCHANGED_BLANK |
                  VALIDATE_ONLY | IGNORED
```

### 4.2 状态转换

| 当前状态 | 操作 | 成功后 | 失败后 |
|---|---|---|---|
| 无 | 上传并解析 | `UPLOADED` | 不创建可操作批次，返回文件级错误 |
| `UPLOADED` | 预检 | `READY` 或 `INVALID` | `INVALID`，保存可解释错误 |
| `READY` / `INVALID` | 修改映射后重新预检 | `READY` 或 `INVALID` | `INVALID` |
| `READY` | 提交 | `COMMITTED` | 事务回滚，仍为 `READY`，记录失败摘要 |
| `UPLOADED` / `READY` / `INVALID` | 放弃 | `DISCARDED` | 状态不变 |
| `COMMITTED` / `DISCARDED` | 任意写操作 | 不允许 | `409 INVALID_STATE` |

约束：

- `VALIDATING` 是短暂状态，用于阻止同一批次并发预检；请求异常时恢复到操作前可重试状态或置为 `INVALID` 并记录原因。
- 预检每成功完成一次，覆盖上一版行结果和统计，增加 `version`，生成新的 `previewToken`。
- 批次不物理删除；放弃只改变状态。原文件和错误明细的保留期限在 G5 定稿。

## 5. 模板与字段映射

### 5.1 模板模型

```text
ImportTemplateView
  id, name, importType, status, builtIn,
  mappings[{ sourceHeader, targetField }],
  version, createdBy, createdAt, updatedBy, updatedAt
```

规则：

- 系统为两种导入类型各提供一个官方内置模板，内置模板不可删除；管理员可复制后定制。
- 自定义模板可以启用、停用；停用后不能用于新预检，但历史批次继续展示其名称与映射快照。
- 已被任一批次引用的模板不能物理删除，只能停用；从未引用的自定义模板可删除。
- 同一模板内一个源表头最多映射一个目标字段，一个目标字段最多出现一次。
- 模板更新使用 `version` 乐观锁；更新不反向修改历史批次。

### 5.2 目标字段描述

```text
TargetFieldView
  field, label, usage, dataType,
  requiredRule, sensitive, aliases[], description
```

`usage` 为 `WRITE` 或 `VALIDATE_ONLY`。`requiredRule` 支持：

- `ALWAYS`
- `NEW_PRODUCT`
- `WHEN_PURCHASE_PRICE_PRESENT`
- `OPTIONAL`

### 5.3 PRODUCT 目标字段

| field | 用途 | 必填规则 | 备注 |
|---|---|---|---|
| `barcode` | 商品匹配键 | `ALWAYS` | 字符串，保留前导零 |
| `productName` | 商品名称 | `NEW_PRODUCT` | 已有商品空值保留旧值 |
| `unit` | 商品单位 | `OPTIONAL` | 自由文本 |
| `specification` | 商品规格 | `OPTIONAL` | 样表暂未出现 |
| `supplierName` | 门店商品供应来源 | `WHEN_PURCHASE_PRICE_PRESENT` | 新名称无需预建，直接写入；空值不删除旧记录 |
| `purchasePrice` | 当前进价 | `OPTIONAL` | 仅目标门店，敏感 |
| `salePrice` | 门店售价 | `OPTIONAL` | 仅目标门店 |
| `inventoryQuantity` | 最新已知库存 | 新商品必填，已有商品可空 | POP 覆盖值 |
| `popCategoryCode` | POP 分类键 | `NEW_PRODUCT` | 字符串，保留前导零 |
| `popCategoryName` | 初始分类名称 | `NEW_PRODUCT` | 仅新分类初始化 |
| `remark` | 商品备注 | `OPTIONAL` | 空值不清空 |

固定忽略：商品资料“毛利率”“提成率/固定值”。它们不进入目标字段白名单，也不参与计算。

### 5.4 DAILY_SALES 目标字段

| field | 用途 | 必填规则 | 备注 |
|---|---|---|---|
| `barcode` | 商品匹配与聚合键 | `ALWAYS` | 不存在的商品报错，不在销售导入中新建 |
| `productName` | 名称比对 | `OPTIONAL` | `VALIDATE_ONLY` |
| `salesQuantity` | 当日净销量 | `ALWAYS` | 0 行先过滤，负数为退货 |
| `salesRevenue` | 当日实际收入 | `ALWAYS` | 不由售价反算，普通用户不可见 |
| `latestInventoryQuantity` | POP 库存覆盖 | `ALWAYS` | 同条码重复行取最后一个非空值 |
| `currentPurchasePrice` | 当日成本快照与当前进价同步 | `OPTIONAL` | 有值时供应商必填，敏感 |
| `currentSalePrice` | 当日售价快照与门店售价同步 | `ALWAYS` | 不用于反算收入 |
| `supplierName` | 门店商品供应来源及当前进价定位 | `WHEN_PURCHASE_PRICE_PRESENT` | 新名称无需预建，直接写入；不进入销售分析维度 |
| `salesGrossMarginRate` | 历史毛利率快照 | `OPTIONAL` | 普通用户不可见 |
| `categoryName` | 商品分类比对 | `OPTIONAL` | `VALIDATE_ONLY`，不覆盖商品分类 |

固定忽略：销售占比、日均销售、同期销售收入、同期销售数量、同期销售毛利率。

### 5.5 映射解析顺序

普通用户只选择管理员已启用的模板，由系统应用模板和字段别名自动映射，不提供手工映射编辑器。只有管理员可以调整当前批次映射并保存为自定义模板；管理员也只能选择对应导入类型的目标字段白名单。

1. 校验显式 `mappings[]` 中的源表头和目标字段。
2. 对未显式映射的列应用所选模板。
3. 对仍未映射的列使用服务端目标字段别名自动匹配。
4. 剩余源列进入 `ignoredColumns[]`。
5. 任一必需目标字段未映射、一个目标字段被重复映射或指定未知目标字段时，不进入行级预检。

最终映射完整快照保存到批次；后续模板变化不影响该批次。

## 6. 文件解析与预检流水线

### 6.1 文件级规则

- 仅接受扩展名与文件签名一致的 `.xls`、`.xlsx`。
- 拒绝 `.xlsm`、密码保护、损坏文件、外部链接和宏内容。
- 首版只允许一个非空工作表；发现多个非空工作表时返回 `IMPORT_MULTIPLE_SHEETS_UNSUPPORTED`，避免静默选错。
- 第一行非空行作为表头；空表头、重复表头和完全空文件均拒绝。
- 公式单元格只读取缓存结果；缺少缓存结果时该单元格报错，不执行公式。
- 推荐默认上限为 20 MB、100,000 条数据行，最终值由 G1 确认并配置化。
- 原文件计算 SHA-256；文件名只用于展示，不参与唯一判断。
- 官方模板按已确认的规范化表头名称匹配；带 `|` 的名称表示层级表头路径。原始样表当前未保存在工作区，表头实际占一行或多行须在编码前用样表复核，但不改变本节已确认的目标字段及处理顺序。

### 6.2 权威处理顺序

```text
权限与上下文校验
→ 文件签名、工作表、表头解析
→ 映射白名单与必填映射校验
→ 排除空行和销售合计行
→ DAILY_SALES 零销量行过滤
→ 单元格类型、格式、必填和引用校验
→ 重复条码与供应商行的一致性校验
→ 按条码生成聚合业务行
→ 与系统当前值比较并生成“最后成功提交批次覆盖”计划
→ 生成业务变更计划、统计、警告和错误
→ READY 或 INVALID
```

必须先过滤零销量行，再做同条码聚合。被过滤行不参与销售、进价、售价或库存取值。同一批次内的当前进价、售价和库存存在多个非空值时，按 Excel 原始行号取最后一个非空值，并在预检中给出覆盖提示，不作为错误。

### 6.3 行数口径

| 字段 | 定义 |
|---|---|
| `sourceRows` | Excel 表头后的非空源行，含合计行 |
| `summaryRows` | 识别并排除的合计行数 |
| `totalRows` | `sourceRows - summaryRows`，含忽略行和错误行 |
| `validRows` | 无错误且未忽略的源行数，聚合前口径 |
| `errorRows` | 至少有一个错误的源行数 |
| `errorCount` | 全部行级/批次级错误条数 |
| `ignoredZeroQuantityRows` | 销量为 0 的源行数 |
| `effectiveProductCount` | 过滤并聚合后的唯一条码数 |

## 7. 接口清单

本独立稿将总接口稿原来的 API-060～073 细化为 API-060～075。核心变化是把“上传解析”和“确认本批字段对应关系并预检”拆开。

### 7.1 模板与目标字段

| ID | 方法与路径 | 权限 | 行为 |
|---|---|---|---|
| API-060 | `GET /admin/import-templates` | ADMIN | 模板分页 |
| API-061 | `GET /admin/import-templates/{id}` | ADMIN | 模板详情和映射 |
| API-062 | `POST /admin/import-templates` | ADMIN | 创建自定义模板 |
| API-063 | `PUT /admin/import-templates/{id}` | ADMIN | 更新名称和映射 |
| API-064 | `PUT /admin/import-templates/{id}/status` | ADMIN | 启用/停用 |
| API-065 | `DELETE /admin/import-templates/{id}` | ADMIN | 删除从未使用的自定义模板 |
| API-066 | `GET /imports/templates/options` | SALES_IMPORT / ADMIN | 当前角色可用的启用模板 |
| API-067 | `GET /imports/target-fields` | SALES_IMPORT / ADMIN | 当前角色可用的目标字段白名单 |

创建/更新请求：

```json
{
  "name": "POP 每日销售模板",
  "importType": "DAILY_SALES",
  "mappings": [
    { "sourceHeader": "条码", "targetField": "barcode" },
    { "sourceHeader": "本期|销售数量", "targetField": "salesQuantity" }
  ],
  "version": 3
}
```

- 创建请求不传 `version`；更新必须传。
- 模板状态请求为 `{ "status": "ENABLED", "version": 3 }`。
- 普通用户 API-066 只返回 `DAILY_SALES` 的 `id,name,importType,builtIn,version`，不返回完整内部映射。

### 7.2 上传解析

| ID | 方法与路径 | 权限 |
|---|---|---|
| API-068 | `POST /imports/uploads` | SALES_IMPORT / ADMIN |

`multipart/form-data`：

```text
file: binary
importType: PRODUCT | DAILY_SALES
storeId?: string
sourceDate?: yyyy-MM-dd
businessDate?: yyyy-MM-dd
```

上下文规则：

- `PRODUCT`：仅管理员；`storeId`、`sourceDate` 必填；拒绝 `businessDate`。
- `DAILY_SALES` 管理员：`storeId`、`businessDate` 必填；拒绝 `sourceDate`。
- `DAILY_SALES` 普通用户：请求不得带 `storeId`；`businessDate` 必填，门店取当前账号绑定。
- 日期不得从文件名、上传时间或 Excel 内容推断。

成功响应：

```json
{
  "batchId": "imp_01...",
  "batchNo": "IMP202609120001",
  "status": "UPLOADED",
  "version": 1,
  "filename": "销售汇总.xls",
  "fileHash": "sha256...",
  "sheetName": "Sheet1",
  "headers": ["条码", "商品名称", "本期|销售数量"],
  "suggestedTemplateId": "tpl_sales_default",
  "suggestedMappings": [],
  "ignoredColumns": [],
  "mappingDiagnostics": [],
  "store": { "id": "s1", "name": "一店" },
  "businessDate": "2026-09-12",
  "sourceDate": null
}
```

上传只做权限、文件级解析、表头提取、指纹和映射建议，不生成业务写入计划。

### 7.3 确认本批字段对应关系并预检

| ID | 方法与路径 | 权限 |
|---|---|---|
| API-069 | `POST /imports/{batchId}/preview` | SALES_IMPORT / ADMIN |

请求：

```json
{
  "templateId": "tpl_sales_default",
  "mappings": [
    { "sourceHeader": "条码", "targetField": "barcode" },
    { "sourceHeader": "本期|销售数量", "targetField": "salesQuantity" }
  ],
  "expectedVersion": 1
}
```

`mappings` 是管理员对本次批次的显式覆盖，可为空；最终映射仍按 5.5 的顺序计算。普通用户请求中不得提交非空 `mappings`，否则返回 `403 FORBIDDEN`。响应：

```text
batchId, batchNo, status, version, previewToken,
filename, fileHash, sheetName, store, businessDate, sourceDate, importType,
headers[], resolvedMappings[], ignoredColumns[], mappingDiagnostics[],
statistics,
productChanges,
salesChanges,
inventoryOverwrite,
priceOverwrite,
purchasePriceOverwrite,
categorySync,
warnings[], errors[]
```

主要摘要：

```text
statistics {
  sourceRows, summaryRows, totalRows, validRows, errorRows, errorCount,
  ignoredZeroQuantityRows, effectiveProductCount
}
productChanges { createProducts, updateProducts, unchangedProducts }
salesChanges { createFacts, correctFacts, returnProducts }
inventoryOverwrite { present, changed, unchanged, duplicateOverrides }
priceOverwrite { present, changed, unchanged, duplicateOverrides }
purchasePriceOverwrite { present, changed, unchanged, duplicateOverrides }
categorySync {
  matchedCategories, createdRootCategories,
  initializedProductCategories, ignoredExistingProductCategories,
  nonLeafConflicts
}
```

### 7.4 批次查询

| ID | 方法与路径 | 权限 | 请求 |
|---|---|---|---|
| API-070 | `GET /imports` | SALES_IMPORT / ADMIN | `storeId?, importType?, businessDate?, sourceDate?, status?, createdFrom?, createdTo?, operatorId?, page, size` |
| API-071 | `GET /imports/{batchId}` | SALES_IMPORT / ADMIN | 路径 ID |
| API-072 | `GET /imports/{batchId}/rows` | SALES_IMPORT / ADMIN | `resultType?, errorCode?, keyword?, page, size` |

批次列表项：

```text
id, batchNo, importType, status, store,
businessDate, sourceDate, filename,
operator{id,displayName}, createdAt, previewedAt, committedAt,
statistics{totalRows,validRows,errorRows,ignoredZeroQuantityRows,effectiveProductCount},
version
```

行结果：

```text
rowNumber, aggregateKey, resultType,
barcode, productName,
fieldActions[{targetField,action,reasonCode}],
inventoryResult, priceResult, purchasePriceResult,
errors[{sourceColumn,targetField,errorCode,message,sourceValue?}]
```

- 管理员可见与排错相关的完整源值，但不返回无关的整行文件内容。
- 普通用户只返回行号、条码、商品名称、所属门店库存结果、结果类型和校验原因；金额、进价、成本、利润、毛利率以及相应 `sourceValue` 必须省略。

### 7.5 错误下载

| ID | 方法与路径 | 权限 |
|---|---|---|
| API-073 | `GET /imports/{batchId}/errors.xlsx` | SALES_IMPORT / ADMIN |

响应：

- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- `Content-Disposition: attachment; filename="import-errors-{batchNo}.xlsx"`
- 仅 `INVALID` 批次且 `errorRows > 0` 可下载，否则返回 `409 INVALID_STATE`。
- 工作簿包含“错误摘要”和“错误行”两个工作表。

“错误摘要”列：批次号、类型、门店、业务/资料日期、文件名、预检时间、总行数、错误行数、错误数。

“错误行”固定前置列：

```text
源行号, 条码, 商品名称, 源列, 目标字段, 错误码, 错误原因
```

- 管理员版本可在固定列后附加允许排错的原始源列。
- 普通用户版本不包含销售收入、当前进价、售价、成本、利润和毛利率原值；敏感字段错误只保留字段名与原因。
- 同一源行有多个错误时输出多行，便于筛选；`源行号` 保持 Excel 原始行号。
- 文本值若以 `= + - @` 开头，导出为安全文本，禁止形成 Excel 公式；真实数值负数仍以数值单元格输出。

### 7.6 提交与放弃

| ID | 方法与路径 | 权限 |
|---|---|---|
| API-074 | `POST /imports/{batchId}/commit` | SALES_IMPORT / ADMIN |
| API-075 | `DELETE /imports/{batchId}` | SALES_IMPORT / ADMIN |

提交请求：

```json
{
  "expectedVersion": 3,
  "previewToken": "sha256-of-preview-plan"
}
```

提交成功：

```text
batchId, batchNo, status=COMMITTED, committedAt,
productChanges, salesChanges,
inventoryOverwrite, priceOverwrite, purchasePriceOverwrite,
warnings[], version
```

放弃请求固定使用 `If-Match: "<version>"` 请求头传入预期版本，不使用 DELETE 请求体或查询参数；响应为最新批次状态。

## 8. 预检业务规则

### 8.1 通用规则

- 条码严格按 `[A-Za-z0-9]{1,64}` 字符串校验；科学计数法导致的精度丢失不可自动修复。
- 数量、金额、比例必须按目标字段类型解析；非空错误不能降级为“保留旧值”。
- 空行忽略；无法唯一识别的合计行不得作为普通业务行提交。
- 未映射多余列允许存在并展示为忽略列。
- 一个源行可以有多个错误；有任一错误时整批为 `INVALID`。
- 预检不写业务表，不更新 ES，只保存批次、映射、行结果和业务变更计划。

### 8.2 商品资料

- 按当前条码 upsert；不能通过导入表达旧条码改新条码。
- 新商品缺少名称、库存、POP 品类编码或名称等必需字段时为错误。
- 已有商品采用非空覆盖；空值形成 `UNCHANGED_BLANK`，不清空、不写零。
- 商品资料可分批导入；批次未出现商品不清零、不停用、不报错。
- 新 POP 分类编码不存在时计划创建根分类；编码已存在时使用系统分类名称和父级，不覆盖分类。
- 新商品目标分类已有子分类时返回 `PRODUCT_CATEGORY_NOT_LEAF`。
- 已有商品无论文件类别是否变化，都保留系统当前 `categoryId`，计入 `ignoredExistingProductCategories`。
- 进价按“目标门店 + 商品 + 供应商”、售价和库存按“目标门店 + 商品”定位当前值；文件中出现的非空供应商名称无需预建，提交时直接 upsert 对应门店商品供应来源及其当前进价。批次未出现的旧供应商记录继续保留；不比较 `sourceDate`，本批成功提交后直接覆盖文件中出现的当前值。
- 同一文件内多行非空当前值按 Excel 行号最后一行胜出；跨批次以后成功提交的批次胜出。`sourceDate` 只用于追溯。

### 8.3 每日销售

- 商品必须已存在；销售导入不创建商品或分类。
- 先舍弃 `salesQuantity = 0` 行；该行结果为 `IGNORED_ZERO_QUANTITY`，不参与任何覆盖或聚合。
- 负销量保留并标记 `RETURN`，但不增加库存。
- 过滤后同条码多供应商行按条码聚合销售数量、收入等销售事实；供应商不作为销售事实维度。
- 同条码库存、门店售价存在多个非空值时，按 Excel 原始行号取最后一个非空值并给出提示，不阻断批次。
- 同条码、同供应商当前进价存在多个非空值时，同样取最后一个非空值并给出提示；进价有值但供应商名称为空时仍为错误。非空的新供应商名称无需预建或识别为既有主档，提交时直接写入。
- 实际销售收入以源字段为准，不使用销量乘售价重算。
- 营业日期不参与当前进价、门店售价和库存的覆盖顺序；销售事实仍按门店、营业日期和商品新增或修正，当前值由最后成功提交批次覆盖。
- 门店 + 营业日期 + 商品已有事实且文件指纹不同，按修正版生成 `CORRECTION` 计划和差额摘要。
- 业务方保证零销量过滤后至少有一条有效销售数据；首版不增加“全文件均被过滤”的专用分支。

## 9. 重复、并发与原子提交

### 9.1 文件幂等

批次指纹键：

```text
PRODUCT     = storeId + sourceDate + importType + SHA-256
DAILY_SALES = storeId + businessDate + importType + SHA-256
```

- 指纹键已有 `COMMITTED` 批次时返回 `409 IMPORT_DUPLICATE`，响应只返回调用者有权查看的原批次摘要。
- 相同文件但日期不同不视为重复，因为资料日期或营业日期属于批次业务上下文和追溯信息。
- 同一门店和日期、文件内容不同的每日销售按修正版预检。

### 9.2 预检过期

`previewToken` 至少覆盖：文件哈希、上下文、最终映射、聚合结果、计划写入记录的当前版本。

提交前重新检查：

- 批次仍为 `READY`，`expectedVersion` 一致。
- 模板快照、文件和上下文未改变。
- 相关商品、门店商品、门店商品供应商、库存和销售事实版本未让预览结果发生变化。

若预览结果已过期，返回 `409 IMPORT_PREVIEW_STALE`，不自动静默采用新结果；用户必须重新预检。

### 9.3 事务与锁

- 首版提交时取得目标门店级互斥锁，文件解析不在锁内，缩短锁持有时间。
- 商品、分类、门店商品供应来源、销售事实、当前进价、门店售价、库存快照、库存同步记录、批次状态和审计摘要在同一数据库事务内提交。
- 任一步失败全部回滚，批次保留 `READY`，允许排除故障后重试。
- ES 实际同步不在数据库事务中执行；事务内只创建可重试的索引同步任务，提交成功后异步处理。
- 重复点击提交由状态、版本和门店锁共同防止二次写入。

## 10. 错误码

除通用错误码外，M04 至少提供：

| HTTP | code | 场景 |
|---:|---|---|
| 400 | `IMPORT_TYPE_UNSUPPORTED` | 非首版导入类型 |
| 400 | `IMPORT_FILE_TYPE_UNSUPPORTED` | 扩展名或签名不支持 |
| 400 | `IMPORT_WORKBOOK_INVALID` | 损坏、加密、宏或无可读表 |
| 400 | `IMPORT_MULTIPLE_SHEETS_UNSUPPORTED` | 多个非空工作表 |
| 400 | `IMPORT_HEADER_INVALID` | 空表头、重复表头或表头不可识别 |
| 400 | `IMPORT_MAPPING_INVALID` | 源列/目标字段未知或重复 |
| 422 | `IMPORT_REQUIRED_MAPPING_MISSING` | 必需目标字段未映射 |
| 422 | `IMPORT_SOURCE_DATE_REQUIRED` | 商品资料缺资料日期 |
| 422 | `IMPORT_BUSINESS_DATE_REQUIRED` | 每日销售缺营业日期 |
| 422 | `IMPORT_CELL_INVALID` | 单元格类型或格式错误 |
| 422 | `PRODUCT_BARCODE_INVALID` | 条码格式或精度错误 |
| 422 | `IMPORT_SUPPLIER_REQUIRED` | 有进价但供应商名称为空 |
| 422 | `IMPORT_INVALID` | 批次存在任何预检错误 |
| 409 | `IMPORT_DUPLICATE` | 指纹键已有已提交批次 |
| 409 | `IMPORT_PREVIEW_STALE` | 提交时业务现值已改变 |
| 409 | `VERSION_CONFLICT` | 模板或批次乐观锁冲突 |
| 409 | `INVALID_STATE` | 当前状态不允许该操作 |

分类相关错误继续复用 M03 的 `CATEGORY_POP_CODE_CONFLICT`、`PRODUCT_CATEGORY_NOT_LEAF` 等错误码。

## 11. 页面粒度

### 11.1 导入向导

1. 文件与上下文：选择类型、门店、资料/营业日期和文件。
2. 模板与映射：展示真实表头、建议模板、已映射/未映射/忽略列；管理员可保存为模板，普通用户只能选启用的销售模板。
3. 预检：展示统计卡、覆盖/未变化摘要、重复值取最后非空行提示、警告、错误分页和错误下载。
4. 提交结果：二次确认后提交，展示实际新增、修正、零销量忽略、三类当前值覆盖和批次号。

交互要求：

- `INVALID` 时提交按钮禁用，错误下载按钮可用。
- `READY` 时显示 `previewToken` 对应的预检时间；重新映射必须重新预检。
- 普通用户页面不渲染门店选择器、商品资料类型或敏感统计卡。
- 离开未提交批次时提示可在批次中心继续或放弃，不自动删除。

### 11.2 批次中心

- 筛选：类型、状态、门店、业务/资料日期、创建时间、操作人。
- 列表：批次号、类型、门店、日期、文件名、状态、结果摘要、操作人、创建/提交时间。
- 详情页签：概览、字段映射、行结果、错误与警告、提交摘要。
- 操作：继续预检、重新预检、提交、下载错误、放弃；按钮按状态、角色和创建人显示。

### 11.3 模板管理

- 管理员可查看、创建、编辑、复制、启停和删除未使用模板。
- 编辑器左侧为源表头，右侧为目标字段白名单，并实时提示重复目标和缺失必填映射。
- 内置模板显示只读标记；复制后形成自定义模板。

## 12. 实施任务拆分

### 12.1 后端

| 编号 | 任务 | 产物 | 验证 |
|---|---|---|---|
| M04-BE-01 | 模板与目标字段注册表 | 模板接口、官方模板、白名单 | 模板权限、版本、启停、删除测试 |
| M04-BE-02 | 文件接收与解析 | `.xls/.xlsx` parser、文件签名、哈希、表头快照 | 两种样表、损坏/加密/多表测试 |
| M04-BE-03 | 映射解析 | 显式/模板/别名合并、忽略列 | 冲突、缺失、未知字段测试 |
| M04-BE-04 | 通用预检流水线 | 状态转换、行结果、错误聚合 | 重入、覆盖旧预览、失败状态测试 |
| M04-BE-05 | PRODUCT planner adapter | 商品新增/合并、分类、进价/售价/库存计划 | 非空覆盖、分类叶子、最后批次覆盖测试 |
| M04-BE-06 | DAILY_SALES planner adapter | 零销量过滤、聚合、修正版、退货、三类当前值计划 | 重复供应商、最后非空值、最后批次覆盖测试 |
| M04-BE-07 | 批次读模型 | 列表、详情、行分页、DTO 脱敏 | 门店范围与敏感字段测试 |
| M04-BE-08 | 原子提交 | 版本、token、门店锁、事务、回滚、索引任务 | 并发双击、过期预览、回滚测试 |
| M04-BE-09 | 错误导出 | 两工作表 xlsx、脱敏、公式防护 | Excel 打开、列结构、权限测试 |
| M04-BE-10 | 审计与清理 | 预检/提交/放弃摘要、文件保留任务 | 不记录完整 Excel/敏感值测试 |

### 12.2 前端

| 编号 | 任务 | 产物 | 验证 |
|---|---|---|---|
| M04-FE-01 | 路由与权限 | 导入向导、批次中心、模板管理入口 | 两角色菜单与直链 403 |
| M04-FE-02 | 文件与上下文步骤 | 类型/门店/日期/文件表单 | 角色差异和必填校验 |
| M04-FE-03 | 映射编辑器 | 表头、模板、目标字段、冲突提示 | 列乱序、别名、多余列 |
| M04-FE-04 | 预检结果 | 统计、警告、错误分页、敏感裁剪 | INVALID 禁止提交 |
| M04-FE-05 | 提交确认 | 摘要确认、重复点击保护、结果页 | stale/version 错误恢复 |
| M04-FE-06 | 批次中心 | 筛选、详情、继续操作 | 普通用户同店可读/他人不可写 |
| M04-FE-07 | 模板管理 | 列表、编辑、复制、启停、删除 | 内置只读和版本冲突 |
| M04-FE-08 | 错误下载 | 下载状态、文件名、失败反馈 | 管理员/普通用户不同文件 |

### 12.3 测试夹具

必须保存脱敏夹具：

- 官方商品资料 `.xls`、等价 `.xlsx`。
- 官方每日销售 `.xls`、等价 `.xlsx`。
- 列乱序、别名、多余列、缺列、重复表头、空表。
- 条码前导零、科学计数法、非法字符、超长条码。
- 零销量、负销量、重复条码多供应商、库存/售价/进价多值取最后非空值。
- 商品非空覆盖、新商品缺必填、分类非叶子、旧日期批次后提交覆盖。
- 普通用户敏感字段错误、跨门店、他人批次提交/放弃。
- 提交中途故障、并发双击、预检后数据变化、相同指纹重复提交。

## 13. 验收矩阵

| 场景 | 预期 |
|---|---|
| `.xls/.xlsx` 官方样表 | 均能上传、映射和预检 |
| 列乱序或使用别名 | 通过模板/别名正确映射 |
| 多余列和固定舍弃列 | 进入忽略列，不写业务数据 |
| 缺少必需映射 | `INVALID`，不能提交 |
| 任一错误行 | 整批 `INVALID`，业务表零写入 |
| 商品已有字段为空 | 保留旧值并显示 `UNCHANGED_BLANK` |
| 商品较旧资料日期后提交 | 可预检并覆盖当前进价/售价/库存，日期只用于追溯 |
| 商品小批次 | 只影响文件内条码，批次外商品不变 |
| 新商品 POP 分类 | 仅首次初始化；非叶子分类阻断 |
| 已有商品品类变化 | 保留系统当前分类，不阻断 |
| 销售零销量行 | 忽略且不更新任何当前值 |
| 销售负销量行 | 保存退货事实，不改变库存算法 |
| 重复条码多供应商且值一致 | 销售按条码聚合，进价按供应商更新 |
| 重复条码库存/售价/进价多值 | 取 Excel 最后一条非空值，给出提示但不阻断 |
| 较旧销售补录后提交 | 销售事实按业务日期修正，当前进价/售价/库存按最后提交批次覆盖 |
| 任意日期后续批次 | 最后成功提交批次覆盖对应当前值 |
| 同指纹已提交 | `IMPORT_DUPLICATE`，不二次写入 |
| 预检后业务数据改变 | `IMPORT_PREVIEW_STALE`，要求重新预检 |
| 提交任一步失败 | 整批事务回滚，批次仍可重试 |
| 普通用户查看同店他人批次 | 可读脱敏详情 |
| 普通用户提交/放弃同店他人批次 | `403 FORBIDDEN` |
| 普通用户下载错误 | 文件不含金额、进价、成本、利润、毛利率原值 |

## 14. 可观测性、审计与安全

- 日志字段：`requestId, batchId, batchNo, importType, storeId, operatorId, status, durationMs`；不记录完整行和敏感值。
- 指标：上传/预检/提交次数与耗时、INVALID 比例、错误码计数、解析失败、重复文件、stale 预览、事务回滚。
- 审计：记录模板创建/修改/启停/删除，导入预检、提交、放弃；导入审计只保存批次号、操作者、门店、结果和数量摘要。
- 原文件下载接口不在首版范围，避免扩大敏感数据暴露面。
- 文件名需清理路径字符；文件内容不得被当作脚本、公式或服务端表达式执行。
- 上传、预检、下载和提交均再次校验当前用户权限，不能只依赖创建批次时的权限快照。

## 15. G1/G5 门禁项

以下内容不阻断本模块接口评审，但必须在建表和编码前定稿：

- 金额、数量、比例的数据库精度和舍入规则。
- 文件大小、最大数据行数、请求超时和并发数上限。
- 模板名称长度、备注长度、表头最大长度。
- 原文件、预检行、错误文件和已放弃批次的保留期限。
- 文件落本地磁盘还是对象存储；两种 adapter 必须保持相同的文件引用接口。
- API-075 的版本固定通过 `If-Match` 请求头传递；编码前只需统一弱/强 ETag 的具体格式。

## 16. 完成定义

M04 粒度规划评审完成需同时满足：

- API-060～075 的权限、请求、响应、状态和错误均无悬空引用。
- 两种模板的目标字段、固定忽略列、条件必填和敏感属性已确认。
- 上传解析与预检分阶段，前端不自行承担旧版 `.xls` 权威解析。
- `READY` 批次才可提交，错误整批阻断，提交原子回滚。
- 普通用户门店范围、同店可读/本人可写和错误下载脱敏有自动化测试。
- M04 对 M03/M05/M06 的 planner/writer 调用边界已明确，M05 已接受；M03/M06 后续独立评审必须复用该边界，如需改变则将受影响的 M04 步骤重新置为待确认。
- 总接口清单、总体实施计划和本文件接口编号一致。
- G1/G5 门禁项被明确跟踪，未以未确认字段提前建表。
