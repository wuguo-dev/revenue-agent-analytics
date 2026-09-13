# M05 POP 库存接口与粒度规划

> 文档状态：`APPROVED`。本稿只规划模块接口、数据粒度、页面与验收，不创建数据库、迁移或业务代码。

## 1. 模块目标

M05 统一负责 POP 库存的五类能力：

1. 查询门店商品的最新已知库存和跨门店汇总；
2. 保存每次 POP 库存观察快照；
3. 保存每个导入批次的库存同步执行摘要；
4. 计算商品和门店的数据新鲜度；
5. 生成、去重、确认和自动恢复库存告警。

M05 是库存规则的深模块。调用方只提交标准化的 POP 库存观察值，不自行判断是否覆盖、是否陈旧、是否产生快照或告警。

## 2. 已确认基线

- POP 是库存的唯一权威来源；页面统一称“最新已知库存”，不得称“实时库存”。
- 商品资料和每日销售汇总都可提供库存观察值；销售数量、退货、销售修正版不加减库存。
- 两类导入均按批次中出现的条码局部覆盖；缺失商品不清零、不停用，也不视为异常。
- 商品资料使用管理员选择的资料日期，每日销售使用营业日期作为 `sourceDate`；日期仅用于历史追溯，不参与当前值覆盖优先级判断。
- 不比较 `sourceDate` 或营业日期：跨批次以后成功提交的 POP 批次直接覆盖当前库存，最终值由事务成功提交顺序决定。
- 同一批次内，同条码库存存在多个非空值时，由 M04 按原 Excel 行号取最后一个非空值并给出预览警告；差异不阻断提交。
- 每日销售的零销量行整行舍弃，不进入 M05；M05 接收 M04 标准化、去重后的每商品一个库存观察值。
- 负库存按 POP 原值保存，不改成零，并触发告警。
- 普通用户只能通过 M03 商品目录读取所属门店的当前库存摘要，不能访问 M05 管理接口、其他门店、快照、同步记录或告警。
- 不提供进货累加、销售扣减、人工调整或可编辑总库存。

## 3. 数据粒度

| 对象 | 唯一粒度 | 主要职责 | 是否可变 |
|---|---|---|---|
| 当前库存投影 `StoreProduct.inventory*` | `storeId + productId` 一条 | 高频查询该门店商品的最新已知数量、来源和版本 | 仅由成功应用的新快照覆盖 |
| 库存快照 `InventorySnapshot` | `sourceBatchId + storeId + productId` 一条 | 保存批次中该商品的一次标准化 POP 观察值及应用结果 | 创建后不可修改 |
| 同步记录 `InventorySyncRecord` | `sourceBatchId` 一条 | 汇总一次已提交导入批次对库存模块的执行结果 | 创建后不可修改 |
| 新鲜度 | 当前库存投影的派生状态 | 根据最后一次成功应用时间计算 `FRESH/STALE/UNKNOWN` | 查询时计算，不重复存主状态 |
| 库存告警 `InventoryAlert` | `storeId + productId + alertType + activeCycle` | 跟踪一次连续异常周期的发现、确认和恢复 | 生命周期内更新状态与次数 |

### 3.1 当前库存投影

建议字段：

```text
storeId, productId,
inventoryQuantity?, inventoryState: KNOWN|UNKNOWN,
inventorySourceType: PRODUCT|DAILY_SALES?,
inventorySourceDate?, inventorySourceBatchId?, inventorySyncRecordId?,
lastSyncedAt?, inventoryVersion
```

规则：

- `inventoryQuantity = null` 表示从未取得有效 POP 库存，不能用 `0` 代替。
- 零库存是有效已知值；负库存也是有效已知值。
- 成功应用相同数量的后提交批次时，数量不变，但来源批次、来源日期、`lastSyncedAt` 和版本仍更新。
- 每个有效观察值均应用到当前投影，不存在因来源日期较旧而跳过的分支。
- 总库查询不双写聚合结果，只对当前投影查询求和。

### 3.2 库存快照

建议字段：

```text
id, sourceBatchId, syncRecordId, storeId, productId,
observedQuantity, currentQuantityBefore?, quantityDifference?,
sourceType: PRODUCT|DAILY_SALES, sourceDate,
observedAt, applyResult: UPDATED|UNCHANGED,
currentVersionBefore?, currentVersionAfter?
```

规则：

- 一条快照代表标准化、去重后的一个门店商品观察值，不代表一行原始 Excel；原始行追溯仍由 M04 批次行保存。
- 商品资料库存单元格为空时不创建快照，计入同步记录的 `missingValueCount`。
- 每日销售零销量行不创建快照，也不计入 M05 的缺值数量。
- 新商品或从未同步商品的 `currentQuantityBefore` 和 `quantityDifference` 为 `null`，不把未知视作零。
- `quantityDifference = observedQuantity - currentQuantityBefore`，仅表示两次 POP 观察差异，不解释为销售、进货、调拨或盘点。
- 所有有效观察都保留快照并应用；数量变化标记为 `UPDATED`，数量未变化标记为 `UNCHANGED`。
- 快照创建后不可编辑或删除；保留期限跟随原始导入批次保留策略，在 P11 统一确认。

### 3.3 同步记录

建议字段：

```text
id, sourceBatchId, storeId, sourceType, sourceDate,
observedCount, appliedCount, changedCount, unchangedCount,
missingValueCount, negativeCount,
startedAt, completedAt, operatorId
```

规则：

- 一个已提交导入批次最多生成一条同步记录；它是批次级摘要，不重复保存商品级前后数量。
- 同一批次没有任何有效库存值时仍生成摘要，`observedCount=0`，便于说明该批次没有产生库存效果。
- `observedCount = appliedCount = changedCount + unchangedCount`；缺值单独计入 `missingValueCount`，不计为观察值。
- 导入预检失败、被放弃或提交事务回滚时不生成已提交同步记录，失败原因继续由 M04 `ImportBatch` 表达。
- 同步记录和其快照、当前库存投影、导入批次 `COMMITTED` 状态在同一事务内完成；任一失败整批回滚。

## 4. 模块 seam 与职责

### 4.1 M04 调用 M05 的内部接口

```text
previewInventory(command) -> InventoryPreview
commitInventory(command) -> InventoryCommitResult
```

`InventoryPreviewCommand` 只接收标准化后的：

```text
sourceBatchId, storeId, sourceType, sourceDate,
items[{productId, observedQuantity?}]
```

M04 拥有导入工作流和 `ImportBusinessPlanner/ImportBusinessWriter` seam；M05 提供库存 adapter，并把调用转交给上述模块接口。M06 不在导入过程中绕过 M04 直接写库存。M05 在内部完成当前值读取、差异计算和预览统计，但不按来源日期决定是否覆盖。提交时不能直接信任预检结果，必须在门店级串行化或行级乐观锁下重新校验当前版本。

`InventoryCommitResult` 返回：

```text
syncRecordId, observedCount, appliedCount, changedCount,
unchangedCount, missingValueCount,
openedAlertCount, resolvedAlertCount
```

### 4.2 模块职责划分

| 规则 | 所属模块 |
|---|---|
| Excel 解析、映射、错误行、零销量过滤、同批次重复值按原 Excel 行号取最后非空值并警告 | M04 导入中心；销售聚合规则由 M06 提供 |
| 快照、当前投影、同步摘要、库存告警 | M05 POP 库存 |
| 销售事实新增或修正 | M06 每日销售 |
| 普通用户商品目录及本店库存字段 | M03 商品与基础资料，通过 M05 只读查询 seam 取得 |
| 库存异常进入经营异常页 | M07 只读消费 M05 查询结果，不复制告警规则 |
| Agent 库存分析 | M09 只调用预定义的 M05 汇总接口，不直接读表 |

## 5. 写入顺序与并发

一次导入提交中的库存写入顺序：

1. M04 校验批次仍为 `READY` 且 `expectedVersion` 匹配；
2. 对目标门店提交串行化，或锁定涉及的当前库存投影；
3. M05 读取数据库中的当前数量与版本，为每个有效观察值重算差异；
4. 创建不可变库存快照；
5. 对全部 `UPDATED/UNCHANGED` 快照刷新当前投影及来源追溯字段；
6. 同步开启、更新或自动恢复负库存告警；
7. 创建一条同步记录摘要；
8. M04/M06 在同一事务内完成其他业务写入并将批次改为 `COMMITTED`。

约束：

- 同一 `sourceBatchId` 重试必须幂等，已存在同步记录时返回原结果，不重复创建快照或告警。
- 同一门店并发提交必须串行化；不论来源日期，后成功提交的批次覆盖先成功提交的批次。
- `sourceDate` 只保存和展示，不作为并发冲突或覆盖优先级条件。
- 预检到提交之间若当前版本变化，提交按最新值重算；如预览摘要发生实质变化，返回 `INVENTORY_PREVIEW_STALE`，要求重新预检，不静默提交不同结果。

## 6. HTTP 接口清单

所有路径省略统一前缀 `/api/v1`。M05 管理接口全部要求 `ADMIN`。

| ID | 方法与路径 | 请求 | 响应/行为 |
|---|---|---|---|
| API-080 | `GET /admin/inventory/stores/{storeId}` | `keyword?, categoryId?, inventoryState?, freshnessStatus?, negativeOnly?, sourceType?, sort?, page, size` | 指定门店当前库存分页 |
| API-081 | `GET /admin/inventory/summary` | `storeIds[]?, keyword?, categoryId?, freshnessStatus?, negativeOnly?, sort?, page, size` | 按商品跨门店汇总，并返回覆盖完整度与门店拆分 |
| API-082 | `GET /admin/inventory/sync-records` | `storeId?, sourceType?, sourceDateFrom?, sourceDateTo?, completedFrom?, completedTo?, operatorId?, page, size` | 批次级同步记录分页 |
| API-083 | `GET /admin/inventory/sync-records/{id}` | 路径 ID | 一次库存同步摘要、来源批次链接和统计 |
| API-084 | `GET /admin/inventory/snapshots` | `storeId?, productId?, sourceBatchId?, syncRecordId?, sourceType?, sourceDateFrom?, sourceDateTo?, applyResult?, changedOnly?, page, size` | 商品级库存观察快照分页 |
| API-085 | `GET /admin/inventory/freshness` | `storeId?` | 当前全局阈值、门店覆盖、新鲜/陈旧/未知数量及最旧记录 |
| API-086 | `GET /admin/inventory/alerts` | `storeId?, keyword?, type?, status?, detectedFrom?, detectedTo?, page, size` | 库存告警分页和统计 |
| API-087 | `PATCH /admin/inventory/alerts/{id}` | `action: ACKNOWLEDGE|REOPEN, note?, expectedVersion` | 人工确认知悉或重新打开，不修改库存 |
| API-088 | `GET /admin/inventory/settings` | 无 | 返回陈旧阈值、扫描时区、最近扫描结果和配置版本 |
| API-089 | `PUT /admin/inventory/settings` | `staleAfterDays, expectedVersion` | 修改全局陈旧阈值并触发一次告警重评估 |

API-080 至 API-087 沿用总接口编号；API-088/089 使用 M07 前的空号，补齐告警阈值读取与维护能力。

## 7. 主要响应视图

### 7.1 `CurrentInventoryView`

```text
store { id, code, name },
product { id, barcode, name, unit, categoryPath, status },
inventoryState, quantity?,
sourceType?, sourceDate?, sourceBatchId?, syncRecordId?,
lastSyncedAt?, freshnessStatus, freshnessAgeDays?,
isNegative, version
```

API-080 默认按 `lastSyncedAt ASC, productId ASC` 排序，让陈旧和未知项优先；调用方可改为数量、商品名或同步时间排序。

### 7.2 `InventorySummaryView`

```text
product { id, barcode, name, unit, categoryPath, status },
knownQuantitySum,
selectedStoreCount, knownStoreCount, unknownStoreCount,
freshStoreCount, staleStoreCount,
coverageComplete,
freshnessStatus,
stores[{ storeId, storeName, inventoryState, quantity?, sourceDate?, lastSyncedAt?, freshnessStatus }]
```

- `knownQuantitySum` 只汇总已知值；未知门店绝不能按零参与求和。
- `coverageComplete = knownStoreCount == selectedStoreCount`。
- 汇总新鲜度取最差状态：任一未知为 `UNKNOWN`，否则任一陈旧为 `STALE`，全部新鲜才为 `FRESH`。
- 页面必须同时展示数量、覆盖率和最差新鲜度，不能只展示一个看似完整的总数。

### 7.3 `InventorySyncRecordView`

```text
id, sourceBatchId, store, sourceType, sourceDate,
observedCount, appliedCount, changedCount, unchangedCount,
missingValueCount, negativeCount,
startedAt, completedAt, operator
```

同步记录详情通过 `sourceBatchId` 跳转 M04 批次；商品级明细使用 API-084 的 `syncRecordId` 筛选，不再建设第二套重复明细接口。

### 7.4 `InventorySnapshotView`

```text
id, store, product,
observedQuantity, currentQuantityBefore?, quantityDifference?,
sourceType, sourceDate, sourceBatchId, syncRecordId,
observedAt, applyResult
```

`changedOnly=true` 只返回 `UPDATED` 且 `quantityDifference != 0` 的快照；`UNCHANGED` 不计为数量变化。

## 8. 新鲜度规划

### 8.1 判定基准

- 新鲜度使用当前投影的 `lastSyncedAt`，即某个观察值最近一次成功应用到当前库存的时间。
- `sourceDate` 单独展示，只用于追溯对应 POP 资料日期或营业日期，不参与覆盖判断，也不直接替代 `lastSyncedAt`。
- 每个后来成功提交批次中的有效观察值都会刷新 `lastSyncedAt`，即使其 `sourceDate` 早于当前记录。
- 以 `Asia/Shanghai` 自然日计算：`freshnessAgeDays = 今天 - lastSyncedAt 的本地日期`。
- `lastSyncedAt = null` 时为 `UNKNOWN`；`freshnessAgeDays > staleAfterDays` 时为 `STALE`；其余为 `FRESH`。
- 若阈值为 3 天，第 0 至第 3 个自然日仍为 `FRESH`，第 4 天开始为 `STALE`。

### 8.2 汇总维度

API-085 返回：

```text
asOf, timezone, staleAfterDays,
overall { total, fresh, stale, unknown, negative },
stores[{ store, total, fresh, stale, unknown, negative, coverageRate, oldestSyncedAt? }],
lastScanAt?, nextScanAt?
```

新鲜度是当前投影的派生值，不在每条当前库存上持久化 `FRESH/STALE`，避免阈值变化后批量改表。持久化告警由定时扫描维护。

## 9. 告警规划

### 9.1 首版告警类型

| 类型 | 触发 | 自动恢复 |
|---|---|---|
| `NEGATIVE_INVENTORY` | 成功应用的当前数量 `< 0` | 后续成功应用数量 `>= 0` |
| `STALE_INVENTORY` | `freshnessAgeDays > staleAfterDays` | 后续成功应用快照使其恢复新鲜，或阈值调整后不再陈旧 |

`UNKNOWN` 只进入新鲜度统计，首版不生成告警。原因是商品资料允许分批同步，系统不知道尚未进入本系统的 POP 商品全集，不能把同步范围之外的商品误报为异常。

### 9.2 告警粒度和去重

- 同一门店、商品、类型在一个连续异常周期内只保留一条告警。
- 重复检测到同一异常时不新建告警，只更新 `lastDetectedAt`、`occurrenceCount`、当前数量或当前年龄。
- 异常消失时由系统自动改为 `RESOLVED`，记录 `resolvedAt` 和 `resolutionType=AUTO_RECOVERED`。
- 异常再次出现时创建新的 `activeCycle`，保留上一个周期的历史。
- 管理员只能 `ACKNOWLEDGE` 表示已知悉，不能在异常仍存在时手工标记为已恢复；确认知悉不影响后续检测和自动恢复。
- `REOPEN` 只用于撤销误操作的人工确认，已自动恢复的历史告警不可重新打开。
- 告警状态为 `OPEN|ACKNOWLEDGED|RESOLVED`。

建议字段：

```text
id, storeId, productId, alertType, activeCycle,
status, firstDetectedAt, lastDetectedAt, occurrenceCount,
quantityAtDetection?, freshnessAgeDaysAtDetection?,
acknowledgedBy?, acknowledgedAt?, acknowledgementNote?,
resolvedAt?, resolutionType?, lastSourceBatchId?, version
```

### 9.3 扫描和通知范围

- 负库存告警在导入提交事务中同步评估。
- 陈旧告警由本应用每天凌晨 `04:00 Asia/Shanghai` 定时扫描一次；不引入消息队列。
- 修改 `staleAfterDays` 后立即重评估，避免等待下一次定时任务。
- 首版只提供系统内告警中心和未处理数量角标，不发送短信、邮件、微信或第三方通知。
- 外部通知如后续纳入范围，由新的通知模块订阅告警结果；不把渠道逻辑放入 M05。

## 10. 权限与脱敏

| 能力 | ADMIN | 普通用户 |
|---|---|---|
| 所有门店当前库存、汇总 | 允许 | 禁止访问 M05 管理接口 |
| 所属门店当前库存摘要 | 允许 | 通过 M03 商品目录读取，门店由后端绑定 |
| 快照、同步记录、来源批次、操作人 | 允许 | 禁止 |
| 新鲜度汇总和告警 | 允许 | 禁止 |
| 修改新鲜度阈值、确认告警 | 允许 | 禁止 |
| 修改库存数量 | 不允许 | 不允许 |

普通用户的 `ProductPublicView` 只复用以下字段：

```text
inventoryState, latestKnownInventory?, inventorySourceDate?, lastSyncedAt?, freshnessStatus
```

不返回其他门店拆分、来源批次 ID、同步记录、操作人或告警处理信息。

## 11. 错误码

| 错误码 | HTTP | 场景 |
|---|---:|---|
| `INVENTORY_SYNC_RECORD_NOT_FOUND` | 404 | 同步记录不存在或不可见 |
| `INVENTORY_ALERT_NOT_FOUND` | 404 | 告警不存在 |
| `INVENTORY_ALERT_ACTION_INVALID` | 409 | 对已恢复告警确认/重开，或执行不符合当前状态的动作 |
| `INVENTORY_SETTINGS_VERSION_CONFLICT` | 409 | 告警设置乐观锁版本不一致 |
| `INVENTORY_STALE_DAYS_INVALID` | 400 | 陈旧阈值不在允许范围内 |

M05 在内部 seam 检测到预检结果过期时，由 M04 统一返回现有 `IMPORT_PREVIEW_STALE`；同一来源批次的重复提交由 M04 批次状态、幂等键和门店锁处理。门店不存在、商品不存在、分页错误和权限错误复用全局错误码。

## 12. 页面粒度

### 12.1 门店库存

- 管理员选择门店，按商品、分类、已知状态、新鲜度、负库存和来源类型筛选。
- 列表展示最新已知数量、来源类型、来源日期、最后同步时间、新鲜度和来源批次入口。
- 只读，不提供数量编辑按钮。

### 12.2 总库库存

- 每商品一行，展示 `knownQuantitySum`、已知门店数/所选门店数和最差新鲜度。
- 展开后查看各门店数量和时间；存在未知门店时显示“汇总不完整”，不以零补齐。

### 12.3 快照历史

- 每商品观察值一行，可按批次、门店、商品、来源类型、来源日期、应用结果和是否变化筛选。
- 明确区分“观察数量”“提交前当前数量”“差异”和“是否应用”。

### 12.4 同步记录

- 每已提交导入批次一行，展示应用、变化、未变化、缺值和负库存数量。
- 可跳转导入批次详情和筛选后的快照列表。

### 12.5 新鲜度与告警

- 新鲜度页展示全局阈值、门店覆盖率和陈旧/未知分布。
- 告警页展示类型、状态、持续时间、最近检测时间、当前数量或陈旧天数。
- 管理员可以确认知悉和撤销确认；库存恢复由系统自动判定。

## 13. 验收矩阵

### 13.1 当前库存与时序

- 首次有效观察创建快照和当前投影；`quantityBefore/difference` 为 `null`。
- 不论来源日期，后成功提交批次中的有效观察覆盖当前投影。
- 较旧来源日期仍照常保存快照并覆盖当前数量；来源日期仅作追溯。
- 相同数量的新观察产生 `UNCHANGED` 快照并刷新来源与 `lastSyncedAt`。
- 零库存、负库存均原值保存；空库存不写零、不创建快照。
- 销售修正版改变销量时不加减库存；其中的 POP 库存观察值按该批次成功提交顺序直接覆盖。

### 13.2 快照与同步记录

- 同条码重复源行聚合后只形成一条快照。
- 一批 N 个有效观察只生成一条同步记录和 N 条快照，不生成 N 条同步记录。
- `observed/applied/changed/unchanged/missingValue` 统计满足定义关系。
- 同一批次幂等重试不重复生成快照、同步记录或告警。
- 事务任一步失败时当前投影、快照、同步记录、告警和导入状态全部回滚。

### 13.3 汇总与隔离

- 总库按当前投影求和，不按快照累加，不保存可编辑总数。
- 未知门店不按零求和，返回 `coverageComplete=false`。
- 普通用户只能从商品目录看到所属门店摘要，伪造门店或直接调用管理接口返回 403。
- 管理员按门店、商品、来源和时间筛选结果准确，分页排序稳定。

### 13.4 新鲜度与告警

- 较旧来源日期的有效观察也会应用并刷新新鲜度；成功应用的相同数量快照同样刷新新鲜度。
- 阈值边界按“严格大于”生效，阈值当天仍新鲜，下一天变陈旧。
- 负库存首次出现只开一条告警，连续负数更新同一告警；恢复到零自动关闭，再次为负开启新周期。
- 陈旧扫描不重复建告警；新快照或阈值调整使数据新鲜时自动恢复。
- `ACKNOWLEDGED` 不改变库存，不阻止后续检测，也不等于异常恢复。
- 修改阈值使用乐观锁，并立即重评估告警。

## 14. 已确认决策

以下决策已经用户确认，并同步到总体计划和总接口契约：

1. **对象粒度**：同步记录按“一个已提交导入批次一条”，快照按“批次 + 门店 + 商品一条”，当前库存按“门店 + 商品一条”。
2. **新鲜度阈值**：全系统统一 `3` 个自然日；`ageDays > 3` 才算陈旧，由管理员可配置，不按门店分别配置。
3. **新鲜度基准**：以最近成功应用当前投影的 `lastSyncedAt` 判断；来源日期只用于追溯和辅助展示，不做时序保护；每个后来成功提交批次中的有效观察都会刷新新鲜度。
4. **告警类型**：首版只持久化负库存和陈旧库存；`UNKNOWN` 只统计不告警。
5. **告警处理**：管理员只能确认知悉，异常消失后系统自动恢复，避免仍为负数或仍陈旧时被人工伪装为已解决。
6. **扫描与通知**：陈旧告警每天凌晨 `04:00 Asia/Shanghai` 扫描；首版仅站内告警中心，不发送短信、邮件、微信或第三方通知。
7. **配置接口**：新增 API-088/089 读取和修改全局陈旧阈值；其他扫描参数首版固定在应用配置中。
8. **当前值覆盖顺序**：库存和同批次携带的进价、售价均以 POP 导入为唯一写入来源；同批次按原 Excel 行号取最后一个非空值，跨批次以后成功提交者直接覆盖，不比较资料日期或营业日期；日期只用于追溯。

M05 继续受 P0/G1 门禁约束；本次确认不授权创建数据库、迁移或业务实现。
