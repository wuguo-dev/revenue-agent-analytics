# API 接口契约（评审稿）

> 状态：DRAFT。本文档仅用于计划阶段汇总接口清单；项目计划定稿后，将按 M01–M10 拆分到 `docs/api/`，每个业务模块单独评审。标记 `[G1]` 的字段必须在样表分析后确认。

## 1. 通用约定

### 1.1 基础规则

- 基础路径：`/api/v1`；健康检查保留 Actuator 原路径。
- 认证：Redis 服务端会话；浏览器通过 `HttpOnly` 的 `SA_SESSION` Cookie 携带随机会话标识，写请求同时提交 `X-XSRF-TOKEN`。
- 时间：ISO-8601，业务时区 `Asia/Shanghai`。
- 金额：十进制定点数，JSON 中使用数字；最终精度由 G1 字段评审确定。门店商品供应商价格与销售成本快照分开保存。
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
| 400 | DATE_RANGE_TOO_LARGE | 主查询日期区间超过 31 个自然日 |
| 401 | UNAUTHORIZED | 未登录、会话无效或过期 |
| 403 | FORBIDDEN | 角色或门店范围不允许 |
| 404 | NOT_FOUND | 资源不存在 |
| 409 | DUPLICATE_RESOURCE | 唯一键冲突 |
| 409 | CATEGORY_PARENT_INVALID | 分类父级不存在、指向自身或形成循环 |
| 409 | CATEGORY_DEPTH_EXCEEDED | 创建或移动后分类树超过 5 级 |
| 409 | CATEGORY_PARENT_HAS_PRODUCTS | 目标父分类已有直接关联商品，不能增加子分类 |
| 409 | PRODUCT_CATEGORY_NOT_LEAF | 商品只能关联没有子分类的叶子分类 |
| 409 | CATEGORY_POP_CODE_CONFLICT | 单批文件内同一 POP 品类编码对应多个名称 |
| 409 | IMPORT_DUPLICATE | 重复导入文件 |
| 409 | INVENTORY_ALERT_ACTION_INVALID | 库存告警当前状态不允许执行该动作 |
| 409 | INVENTORY_SETTINGS_VERSION_CONFLICT | 库存告警设置版本冲突 |
| 409 | INVALID_STATE | 当前状态不允许操作 |
| 413 | FILE_TOO_LARGE | 文件超过上限 |
| 422 | IMPORT_INVALID | 导入预检存在错误 |
| 422 | IMPORT_SOURCE_DATE_REQUIRED | 商品资料导入未选择资料日期 |
| 422 | INVENTORY_STALE_DAYS_INVALID | 库存陈旧阈值不在允许范围内 |
| 429 | TOO_MANY_REQUESTS | 请求或 AI 运行频率超限 |
| 500 | INTERNAL_ERROR | 未分类服务端错误 |
| 503 | AI_UNAVAILABLE | 模型未配置或暂不可用 |
| 503 | SEARCH_UNAVAILABLE | Elasticsearch 暂不可用 |

## 2. 权限定义

| 标记 | 可访问者 | 数据范围 |
|---|---|---|
| PUBLIC | 未登录用户 | 健康检查、CSRF 初始化、登录，以及幂等退出 |
| AUTHENTICATED | 管理员、普通用户 | 当前用户信息 |
| USER_CATALOG | 普通用户、管理员 | 普通用户固定为自身门店并可查看该店最新已知库存；管理员可指定门店 |
| CATEGORY_ORGANIZE | 普通用户、管理员 | 创建全系统共享分类，并调整分类父级归属 |
| SALES_IMPORT | 普通用户、管理员 | 普通用户仅限 `DAILY_SALES` 和账号绑定门店，可读取所属门店全部销售导入批次；管理员可选门店 |
| ADMIN | 管理员 | 全部门店；具体接口可按门店过滤 |

普通用户的商品响应必须使用 `ProductPublicView`，不能先返回完整对象再由前端隐藏字段。普通用户只能读取所属门店的最新已知库存；`SALES_IMPORT` 不授权库存管理、其他门店库存、销售查询、成本、利润或 AI 权限。

## 3. 通用对象

### SessionView

```text
user: { id, username, displayName, gender, age?, hireDate?, roleCode, store: { id, code, name } | null },
permissions: string[],
session: { issuedAt, idleExpiresAt, absoluteExpiresAt }
```

认证凭据不出现在 JSON 响应或浏览器 Web Storage 中；登录通过 HttpOnly Cookie 建立 Redis 服务端会话。

### ProductPublicView

```text
id, barcode, name, suppliers[{ id, name }], specification, unit,
categoryName, salePrice, status,
inventory: { quantity, sourceType, sourceDate, lastSyncedAt } | null
```

明确禁止包含：其他门店库存、`purchasePrice`、`salesQuantity`、`salesAmount`、`costAmount`、`grossProfit`、`grossMarginRate`。

### ProductAdminView `[G1]`

```text
id, barcode, name,
storeSuppliers[{ storeId, storeName, supplierId, supplierName, currentPurchasePrice, purchasePriceSourceDate, sourceBatchId }],
specification, unit, category, status,
storePrices[{ storeId, storeName, salePrice, priceSourceDate, version }],
storeInventories[{ storeId, storeName, quantity, sourceDate, lastSyncedAt }],
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
| API-001 | `GET /actuator/health` | PUBLIC | 无 | `status`；应用、MySQL、Redis 为核心健康项，ES/AI 不影响整体 UP/DOWN |
| API-002 | `GET /system/info` | AUTHENTICATED | 无 | `applicationName, version, serverTime, businessTimezone` |

## 5. 认证、用户与门店

### 5.1 认证

| ID | 方法与路径 | 权限 | 请求 | 响应/行为 |
|---|---|---|---|---|
| API-009 | `GET /auth/csrf` | PUBLIC | 无 | 204；初始化或轮换 `XSRF-TOKEN` Cookie |
| API-010 | `POST /auth/login` | PUBLIC + CSRF | `username, password` | `SessionView`；设置 `SA_SESSION` Cookie，响应体不返回认证凭据 |
| API-011 | `POST /auth/logout` | PUBLIC + CSRF | 无 | 有会话则撤销当前会话；无会话仍幂等清 Cookie |
| API-012 | `GET /auth/me` | AUTHENTICATED | 无 | `SessionView`；滑动空闲有效期 |

登录失败统一返回“用户名或密码错误”，不泄露账号是否存在。账号由管理员通过 API-015 创建并分发；密码为 8～32 个半角字符，bcrypt 哈希保存，员工无修改密码权限，管理员通过 API-018 重置。会话空闲 30 分钟、绝对 12 小时，同一账号最多 3 个有效会话，第 4 次登录撤销最早会话。

### 5.2 用户管理

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-013 | `GET /admin/users` | ADMIN | `keyword, roleCode, storeId, page, size` | 未删除账户分页，包含个人资料摘要，不返回密码哈希 |
| API-014 | `GET /admin/users/{id}` | ADMIN | 路径 ID | 用户详情，包含姓名、性别、手工年龄和入职日期 |
| API-015 | `POST /admin/users` | ADMIN | `username, displayName, gender?, age?, hireDate?, password, roleCode, storeId` | 201，创建后的完整用户视图 |
| API-016 | `PUT /admin/users/{id}` | ADMIN | `displayName, gender?, age?, hireDate?, roleCode, storeId, expectedVersion` | 更新后用户 |
| API-017 | `DELETE /admin/users/{id}` | ADMIN | `If-Match: "<version>"` | 204；删除普通用户或注销管理员，全部会话立即失效；不得注销最后一个管理员 |
| API-018 | `PUT /admin/users/{id}/password` | ADMIN | `newPassword, expectedVersion` | 204；现有会话全部失效 |
| API-019 | `PUT /users/me/profile` | AUTHENTICATED | `displayName, gender?, age?, hireDate?, expectedVersion` | 更新本人个人资料；不能修改用户名、角色、门店或密码 |

校验：`USER` 必须唯一绑定一个启用门店；`ADMIN` 的门店为空；一个门店可以绑定多个普通用户。管理员可创建两类账户、删除普通用户，并在仍保留其他管理员时注销自己或其他管理员；删除或注销不可恢复，原用户名永久保留，历史记录保留最小身份。角色变化或门店改绑后全部旧会话失效，历史业务记录不迁移。用户名去除首尾空白后转为小写，必须为 4～32 位、字母开头并仅含字母、数字、点、下划线或连字符，创建后不可修改或复用。`displayName` 为 1～50 个字符并允许中文；`age` 是 16～100 的可选手工整数，不采集出生日期且不自动更新；`gender` 为 `MALE|FEMALE|UNSPECIFIED`；`hireDate` 不晚于当天。员工不能修改密码，只有管理员可以设置或重置，重置后目标账户全部会话失效。

### 5.3 门店管理

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-020 | `GET /admin/stores` | ADMIN | `keyword, status, page, size` | 门店分页 |
| API-021 | `GET /admin/stores/options` | ADMIN | 无 | 全部启用门店下拉选项 `id, code, name` |
| API-022 | `GET /admin/stores/{id}` | ADMIN | 路径 ID | 门店详情 |
| API-023 | `POST /admin/stores` | ADMIN | `code, name, address?, contactName?, contactPhone?` | 201，新建的 `ACTIVE` 门店完整视图 |
| API-024 | `PUT /admin/stores/{id}` | ADMIN | `name, address?, contactName?, contactPhone?, expectedVersion` | 更新后门店；编码不可修改 |
| API-025 | `PUT /admin/stores/{id}/status` | ADMIN | `status, expectedVersion` | 启用或停用门店；存在未删除普通用户时不能停用 |
| API-026 | `DELETE /admin/stores/{id}` | ADMIN | `If-Match: "<version>"` | 204；物理删除已停用且无账号或业务引用的空门店 |

门店状态固定为 `ACTIVE/DISABLED`。停用门店不再用于新账号绑定、改绑或新业务操作，但历史数据继续保留并可由管理员查询。门店物理删除前必须先停用，且不得存在未删除普通用户绑定或任何主数据、业务数据引用；存在引用时只能保持停用，禁止级联删除、迁移或改写历史数据。删除在事务内复核状态和全部引用，并发产生新引用时完整拒绝。

门店编码去除首尾空白后转为大写，匹配 `[A-Z][A-Z0-9-]{1,31}`，在现存门店中全局唯一且创建后不可修改；符合条件的空门店物理删除后，原编码可供新的内部门店 ID 使用。名称去除首尾空白后为 1～100 字符并允许重名；地址最多 255 字符、联系人最多 50 字符、联系电话最多 30 字符。联系电话按文本保存以兼容座机、分机和国际区号，仅拒绝控制字符；可选文本全为空白时保存为 `null`。

用户和门店列表统一返回 `PageResult<T> { items, page, size, total }`，`page` 从 1 开始，`size` 默认 20、最大 100；无匹配记录返回空列表而非 404。创建返回 201，更新返回 200 和最新视图，删除及密码重置返回 204。PUT 在请求体提交 `expectedVersion`，API-017/API-026 DELETE 使用 `If-Match`；版本缺失或不一致返回 409 且不改变原数据。同一删除请求的网络重试可由删除记录确认后幂等返回 204 且不重复审计，从未存在的 ID 返回 404。

## 6. 商品与基础资料

### 6.1 商品

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-030 | `GET /catalog/products` | USER_CATALOG | `keyword, supplierId, categoryId, status, page, size` | `ProductPublicView` 分页；分类包含 `id,name,status,path`，普通用户自动限定自身门店并返回该店当前售价及最新已知库存 |
| API-031 | `GET /catalog/products/{id}` | USER_CATALOG | 路径 ID | `ProductPublicView`；分类包含停用状态，只返回所属门店当前售价及最新已知库存，不属于门店则 404 |
| API-032 | `GET /admin/products` | ADMIN | `keyword, barcode, supplierId, categoryId, status, storeId?, page, size` | `ProductAdminView` 分页；包含当前分类、各门店当前售价及按门店拆分的供应商当前进价摘要，不默认返回销售汇总 |
| API-033 | `GET /admin/products/{id}` | ADMIN | 路径 ID | 管理员商品详情、当前分类、各门店当前售价、各门店供应商当前进价、售价/进价来源日期及货位，不默认返回销售汇总 |
| API-034 | `POST /admin/products` | ADMIN | `ProductAdminWrite`，含 `categoryId` `[G1]` | 新商品；`categoryId` 必须指向叶子分类 |
| API-035 | `PUT /admin/products/{id}` | ADMIN | `ProductAdminWrite`，含 `barcode, categoryId, version` `[G1]` | 更新商品；允许受控修改条码，`categoryId` 必须指向叶子分类 |
| API-036 | `PUT /admin/products/{id}/status` | ADMIN | `status` | 启用/停用，不删除历史销售 |
| API-037 | `GET /admin/stores/{storeId}/products/{productId}/suppliers` | ADMIN | 门店 ID、商品 ID | 指定门店商品的全部供应来源记录及 `currentPurchasePrice, purchasePriceSourceDate, sourceBatchId` `[G1]`；只读且不返回其他门店或历史列表 |

### 6.2 供应商与多级分类

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-040 | `GET /admin/suppliers` | ADMIN | `keyword, page, size` | POP 导入形成的供应商只读分页 `[G1]`；无启停状态和写接口 |
| API-044 | `GET /categories/tree` | USER_CATALOG | `status?` | 多级分类树 `[{id,sourceType,popCode?,name,parentId,level,status,isLeaf,hasDirectProducts,children[]}]` `[G1]` |
| API-045 | `POST /categories` | CATEGORY_ORGANIZE | `name, parentId?, sortOrder?` `[G1]` | 创建人工根分类或父级分类；不接收业务编码，两种角色均可调用；结果不得超过第 5 级，目标父分类不得已有商品 |
| API-046 | `PUT /categories/{id}/parent` | CATEGORY_ORGANIZE | `parentId?, sortOrder?, version` | 调整分类父级；禁止自身或后代作为父级，移动后的整个子树不得超过第 5 级，目标父分类不得已有商品 |
| API-047 | `PUT /admin/categories/{id}` | ADMIN | `name, version` `[G1]` | 重命名分类，不接收或修改 POP 品类编码，也不调整父级 |
| API-057 | `PUT /admin/categories/{id}/status` | ADMIN | `status, version` | 启用或停用；返回新状态，关联商品时不级联修改商品或清空分类 |

分类采用全系统共享的父子层级模型，不包含 `storeId`，根分类的 `parentId` 为空且 `level=1`，最大允许 `level=5`。商品只能直接关联没有子分类的叶子分类，分类不得同时直接关联商品并拥有子分类。普通用户和管理员均可以创建分类并调整父级归属；只有管理员可以重命名、启用或停用分类，普通用户调用 API-047/API-057 返回 403。任何父级调整都必须阻止自引用和循环引用；API-045 创建结果超过第 5 级，或 API-046 移动后目标父级深度加被移动子树高度超过 5 时，返回 409 `CATEGORY_DEPTH_EXCEEDED`。在已有直接关联商品的分类下创建或移入子分类时返回 409 `CATEGORY_PARENT_HAS_PRODUCTS`；API-034/API-035 将商品关联至非叶子分类时返回 409 `PRODUCT_CATEGORY_NOT_LEAF`。上述失败均不得改变原分类树或商品关联。

API-057 停用系统分类时，即使存在直接或间接关联商品也允许提交；不得改变商品 `status` 或当前 `categoryId`。API-030 至 API-033 继续返回这些商品，并在分类对象中返回 `status=DISABLED` 供前端展示停用标记；ES 索引中的商品保持可检索，只同步分类状态。停用分类不得作为 API-034/API-035 的目标，重新启用后可再次选择。POP 源端不会停用分类，API-069 不接收 POP 分类状态，也不设计“新商品命中已停用 POP 分类”的阻断、重启或自动迁移分支。

分类记录来源分为 `POP_IMPORT` 和 `MANUAL`。`POP_IMPORT` 类别必须有 `popCode`，且 `popCode` 在全系统唯一，API-069 只按该编码匹配类别；`MANUAL` 分类的 `popCode` 必须为空，由系统内部 `id` 标识。API-045 不允许客户端提交 `popCode`，API-047 也不允许修改 POP 编码。同一编码在单批文件中对应多个名称时预检失败并返回 `CATEGORY_POP_CODE_CONFLICT`；已有编码在后续批次出现不同名称时，导入不覆盖系统分类名称、状态或父级，分类信息只能通过页面维护。

POP 商品资料当前只有单组“品类编码 + 品类名称”，不包含父级链路或停用状态。API-069 预检必须展示类别匹配、根分类新增、新商品分类初始化和已有商品分类忽略数量，不得根据名称猜测或自动生成父级。条码首次进入本系统时，导入按 `popCode` 匹配类别；新 POP 类别以 `parentId=null` 创建为根分类并作为该商品的初始叶子分类，已有类别直接复用系统中的名称和父级。条码已经存在时，无论文件类别是否变化，商品 `categoryId` 均保持不变，以首次导入后保存在系统中的当前分类为准；管理员通过 API-035 修改后的分类同样不会被后续导入覆盖。若新商品需要关联的 POP 类别已有子分类，该商品行返回 `PRODUCT_CATEGORY_NOT_LEAF`，整批不可提交。人工分类没有 `popCode`，不会被 POP 导入覆盖。

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

商品与供应商为多对多，但供货来源和当前进价按门店落在 `StoreProductSupplier`。每个“门店 + 商品 + 供应商”组合只保存一份 `currentPurchasePrice`、来源日期和来源批次，不建设历次进价表，也不设置启停状态；同一商品和供应商在不同门店可以有不同当前进价。系统不提供供应商、供应关系或进价的人工写接口。商品资料和每日销售无需预建关系，直接 upsert 本批目标门店中出现的供应商组合，不比较来源日期，最后成功提交的 POP 批次覆盖当前进价；同批次多值按 Excel 原始行号取最后一个非空值，批次未出现的旧供应商记录保留。每日销售进价还要保存为当日销售成本快照，后续当前进价变化不得回写该快照。供应商不作为销售分析维度；普通用户响应包含所属门店的全部供应商名称，但不得包含任何进价；管理员可读取各门店供应商名称及当前进价。供应商及报价变化按业务约定不写入审计日志。

当前售价归属 `StoreProduct`，不归属全局 `Product`。`ProductPublicView` 只包含当前账号所属门店的 `salePrice`；`ProductAdminView` 通过 `storePrices[{storeId,storeName,salePrice,priceSourceDate,sourceBatchId,version}]` 展示各门店当前售价。API-034/035 的 `ProductAdminWrite` 不包含全局售价；当前售价只由最后成功提交的 POP 商品资料或每日销售批次更新，不提供手工售价接口。来源日期只用于追溯，不参与覆盖优先级判断。任何当前售价变化不得回写或重算历史销售事实。

商品 `unit` 为自由文本字段，不引用单位字典，也不提供单位管理接口。API-034/API-035 的 `ProductAdminWrite` 可包含并修改 `unit`；API-030/API-031 及 `ProductPublicView` 只读返回该值，普通用户没有商品修改接口。商品资料导入中的非空单位按通用非空覆盖规则更新已有商品，空单元格保留旧值。

API-034/API-035 允许管理员通过 `categoryId` 创建或修改商品所属分类，目标必须存在、状态为启用且为叶子分类，否则分别返回 `NOT_FOUND`、`VALIDATION_ERROR` 或 `PRODUCT_CATEGORY_NOT_LEAF`。该操作不改变分类父子关系，成功后触发商品搜索索引同步，并在审计中记录商品、原分类和新分类。系统只保存当前有效 `categoryId`，不保存最近 POP 分类或商品分类来源状态；后续商品资料和每日销售导入均不得覆盖。普通用户只能通过 API-030/API-031 查看分类，直接调用商品写接口返回 403。

条码统一作为字符串传输和保存，保留前导零，长度为 1 至 64，只允许 ASCII 数字和英文字母，基础格式为 `[A-Za-z0-9]{1,64}`。空值、空格、标点符号、中文、超长值和 Excel 已丢失精度的科学计数法均返回 `PRODUCT_BARCODE_INVALID`；不得把条码转换为数值类型。

API-035 修改条码时，新条码必须符合上述格式且全局唯一；冲突返回 409 `PRODUCT_BARCODE_CONFLICT`，并发版本不一致返回 409 `VERSION_CONFLICT`。修改只变更该商品的当前业务条码，内部商品 ID 不变，因此历史销售、门店商品、库存快照、供应商和货位关系继续归属同一商品；同时创建 ES 索引同步任务。商品资料导入仍以当前条码作为唯一匹配键，不能通过同一行表达“旧条码改成新条码”。系统不建立旧条码别名，修改后的旧条码不再参与商品查询或导入匹配。普通用户没有商品写接口，调用 API-034 至 API-037 均返回 403。条码修改必须记录操作人、修改时间、旧条码和新条码，但审计记录中的旧值不具备业务匹配作用。

## 7. 导入模板与批次

M04 独立接口与实施粒度以 [`docs/api/04-imports.md`](api/04-imports.md) 为准；本节保留跨模块汇总。

### 7.1 枚举

- `importType`: `PRODUCT`、`DAILY_SALES`。
- 首版只有 `PRODUCT`（页面名【商品资料】）和 `DAILY_SALES`（页面名【每日销售汇总】）两种导入模板，不再额外选择业务模式。两种模板均可更新当前进价、门店售价和门店最新已知库存；首版不提供进货累加、销售扣库存或人工库存调整模式。
- `status`: `UPLOADED`、`VALIDATING`、`READY`、`INVALID`、`COMMITTED`、`DISCARDED`。

两种模板的首版业务目标字段如下；表头仍通过映射模板按名称匹配，列顺序不固定：

- 【商品资料】：`barcode, productName, unit, specification?, supplierName, purchasePrice, salePrice, inventoryQuantity, popCategoryCode, popCategoryName, remark?`。其中条码和新商品必填字段按预检规则校验，已有商品继续执行非空覆盖。
- 【每日销售汇总】：写入字段 `barcode, salesQuantity, salesRevenue, latestInventoryQuantity, currentPurchasePrice, currentSalePrice, supplierName?, salesGrossMarginRate?`，校验专用字段 `productName?, categoryName?`。销售数量为 0 时整行舍弃；当前进价同步按“目标门店 + 条码 + 供应商”定位关系，`currentPurchasePrice` 有值时 `supplierName` 必填，销售事实仍按条码聚合。

### 7.2 映射模板

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-060 | `GET /admin/import-templates` | ADMIN | `importType, keyword, page, size` | 模板分页 |
| API-061 | `GET /admin/import-templates/{id}` | ADMIN | 路径 ID | 模板及字段映射 |
| API-062 | `POST /admin/import-templates` | ADMIN | `name, importType, mappings[{sourceHeader,targetField}]` | 新模板 |
| API-063 | `PUT /admin/import-templates/{id}` | ADMIN | 创建字段加 `version` | 更新模板，历史批次保留映射快照 |
| API-064 | `PUT /admin/import-templates/{id}/status` | ADMIN | `status, version` | 启用或停用自定义模板 |
| API-065 | `DELETE /admin/import-templates/{id}` | ADMIN | 路径 ID | 仅删除从未被批次引用的自定义模板 |
| API-066 | `GET /imports/templates/options` | SALES_IMPORT / ADMIN | `importType` | 已启用模板选项；普通用户只返回 `DAILY_SALES` 模板，不返回内部映射细节 |

目标字段列表由服务端按 `importType` 返回，不允许前端提交任意数据库列名：

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-067 | `GET /imports/target-fields` | SALES_IMPORT / ADMIN | `importType` | `field, label, usage, requiredRule, dataType, sensitive, aliases[]` `[G1]`；普通用户只可请求 `DAILY_SALES` |

API-067 在 `PRODUCT` 类型下不得返回商品毛利率目标字段；商品资料源列“毛利率”未映射并出现在 `ignoredColumns[]`。在 `DAILY_SALES` 类型下提供 `salesGrossMarginRate` 目标字段，用于保存 POP 当期销售毛利率快照，但不得提供同期销售收入、同期销售数量或同期销售毛利率目标字段，这三列同样进入 `ignoredColumns[]`。前端不能通过批次映射覆盖绕过目标字段白名单。

### 7.3 文件预检和提交

| ID | 方法与路径 | 权限 | 请求 | 响应/行为 |
|---|---|---|---|---|
| API-068 | `POST /imports/uploads` | SALES_IMPORT / ADMIN | `multipart: file, importType, businessDate?, sourceDate?, storeId?` | 创建 `UPLOADED` 批次，返回文件指纹、真实表头和映射建议 |
| API-069 | `POST /imports/{batchId}/preview` | SALES_IMPORT / ADMIN | `templateId?, mappings[], expectedVersion` | 确认本批字段对应关系、保存批次快照并运行预检，返回统计、错误和变更计划 |
| API-070 | `GET /imports` | SALES_IMPORT / ADMIN | `storeId?, importType, businessDate?, sourceDate?, status, createdFrom, createdTo, operatorId?, page, size` | 批次分页；普通用户固定为所属门店的 `DAILY_SALES` 批次，拒绝越权筛选 |
| API-071 | `GET /imports/{batchId}` | SALES_IMPORT / ADMIN | 路径 ID | 批次详情、错误、忽略列、变更摘要；普通用户可读取所属门店任意销售批次 |
| API-072 | `GET /imports/{batchId}/rows` | SALES_IMPORT / ADMIN | `resultType?, errorCode?, keyword?, page, size` | 预检行分页；普通用户响应不含成本、毛利等敏感值 |
| API-073 | `GET /imports/{batchId}/errors.xlsx` | SALES_IMPORT / ADMIN | 路径 ID | 所属门店错误行 Excel；普通用户版本屏蔽敏感源值 |
| API-074 | `POST /imports/{batchId}/commit` | SALES_IMPORT / ADMIN | `expectedVersion, previewToken` | 原子提交；普通用户只能提交本人创建的 READY 批次 |
| API-075 | `DELETE /imports/{batchId}` | SALES_IMPORT / ADMIN | `If-Match: "<version>"` | 放弃 UPLOADED/READY/INVALID 批次；普通用户只能操作本人创建的批次 |

`preview` 主要响应：

```text
batchId, status, filename, fileHash, store, businessDate, sourceDate, importType,
headers[], resolvedMappings[], ignoredColumns[],
statistics { totalRows, validRows, errorRows, ignoredZeroQuantityRows, createRows, updateRows, returnRows },
inventoryOverwrite { presentRows, changedRows, unchangedRows, duplicateOverrideRows },
priceOverwrite { presentRows, changedRows, unchangedRows, duplicateOverrideRows },
purchasePriceOverwrite { presentRows, changedRows, unchangedRows, duplicateOverrideRows },
categorySync { matchedCategories, createdRootCategories, initializedProductCategories, ignoredExistingProductCategories, nonLeafConflicts },
warnings[], errors[]
```

商品资料预检只为新条码计算类别初始化结果；已有条码即使文件中的 POP 类别与系统当前分类不同，也以首次导入后保存在系统中的当前分类为准，不修改 `categoryId`，计入 `categorySync.ignoredExistingProductCategories`，不作为错误或需要确认的覆盖项。管理员在系统中修改过商品分类后规则相同。已有分类的名称、状态和父级同样不参与导入更新；POP 源端没有分类停用状态，预检不建立相应处理分支。

`DAILY_SALES` 的 `businessDate` 必填，且整份文件只能对应一个营业日期，不接收 `sourceDate`。`PRODUCT` 的 `sourceDate` 必填，页面名称为“资料日期”，代表 POP 商品资料的导出数据日期，不接收 `businessDate`；两类日期都不得从文件名、上传时间或提交时间推断。商品资料导入仅限管理员，且管理员必须在 API-068 提交 `storeId`；普通用户导入销售时不得提交 `storeId`，服务端从当前账号绑定关系取得门店。普通用户提交非 `DAILY_SALES` 类型、伪造门店、访问其他门店批次或操作他人批次时返回 403。

存在错误行时状态为 `INVALID`，API-074 必须返回 `IMPORT_INVALID`。上传后的最终映射由 API-069 保存为批次快照；模板后续变化不影响已预检批次。重复文件范围为“门店 + 对应业务/资料日期 + 导入类型 + SHA-256”；该范围内已有 `COMMITTED` 批次时返回 `IMPORT_DUPLICATE`。同一门店、日期和条码已有销售事实但新文件指纹不同时，按修正版预检并展示销售差额；库存不按销售差额加减。

销售导入在同条码聚合和业务写入前，先过滤“本期销售数量”等于 0 的源行。该行结果类型为 `IGNORED_ZERO_QUANTITY`，计入 `totalRows` 和 `ignoredZeroQuantityRows`，不计入 `validRows`、`createRows`、`updateRows`、`returnRows` 以及库存、进价、售价覆盖统计；它不是错误行，不阻断同批次其他有效数据。即使该行销售收入非 0，也按业务规则整行舍弃，不创建或修正销售事实，不更新当前进价、门店售价和库存。负销量不属于零销量忽略行，仍按退货事实校验、聚合和提交。同一条码同时存在零销量与非零销量行时，先舍弃零销量行，再对剩余行执行一致性校验和聚合。API-072 的 `resultType` 必须支持 `IGNORED_ZERO_QUANTITY`，并返回行号、条码、商品名称和忽略原因；普通用户响应继续屏蔽销售收入、成本和毛利等敏感源值。

业务方保证每份每日销售文件过滤后至少存在一条非零销量数据。因此 API-069/API-074 首版不定义“全部行均因零销量被忽略”的专用错误码或空批次提交行为，该情形也不列入验收范围。

销售导入只包含当日销售商品，属于部分库存覆盖：文件中出现商品的“选中机构库存数量”（别名“当前机构库存数量”）直接覆盖门店最新已知库存，文件未出现商品保持原值。同条码多行库存不一致时不阻断，按 Excel 原始行号取最后一个非空值；跨批次以后成功提交的 POP 批次覆盖当前库存，不比较营业日期。

销售文件中的“当前机构”固定指本次批次的目标门店：管理员由 API-068 的 `storeId` 选择，普通用户由后端账号门店绑定确定。“当前机构售价”必须保存为该营业日期的销售事实快照，并参与目标门店当前售价同步。批次成功提交后直接覆盖 `StoreProduct.salePrice`，同时保存 `businessDate` 和来源批次用于追溯，不比较日期。同条码重复行售价不一致时取 Excel 原始行号最后一个非空值，不阻断批次。售价快照或当前售价均不得用于反算实际销售收入。

每日销售中的“当前机构最后进价”必须保存为该营业日期的历史成本快照，并参与目标门店对应商品供应商当前进价同步。批次成功提交后直接覆盖 `StoreProductSupplier.currentPurchasePrice`，同时保存 `businessDate` 和来源批次用于追溯，不比较日期，其他门店同商品同供应商的进价不受影响。当前进价非空时供应商必须可识别；同批次同条码、同供应商多值时取 Excel 原始行号最后一个非空值，不阻断批次。

所有【商品资料】导入均由管理员选择门店和 `sourceDate`；每个文件都按独立增量同步批次处理。这里的“条码不存在”专指新系统中尚无该条码，POP 中的商品及库存仍是完整、权威的。新系统中条码不存在时创建商品主档、以 POP 类别初始化 `categoryId`、创建目标门店的门店商品关系，并按文件中的供应商名称直接 upsert 门店商品供应来源；供应商名称无需预建。条码已存在时按字段合并，但即使导入品类发生变化也不修改其 `categoryId`。有效“含税成本价”“售价”“库存数量”在批次成功提交后更新目标门店当前值，不比较 `sourceDate`；同一文件多值取 Excel 原始行号最后一个非空值，跨批次以后成功提交者胜出。当前进价更新不得影响其他门店，批次未出现的旧供应商记录继续保留。批次未出现的商品不参与校验或更新，不清零、不停用，也不阻断提交。已有分类名称、状态和父级不由导入覆盖，POP 分类停用不属于导入场景。该模板不是采购或实体入库，也不是进货累加，可拆分多批并重复执行。

商品资料已有条码的字段合并规则固定为“非空覆盖”：Excel 单元格为空时，该字段记为 `UNCHANGED` 并保留系统旧值；单元格非空且校验通过时记为 `UPDATED` 并覆盖旧值。空单元格不能用于清空系统字段；如以后需要清空，必须通过独立的显式编辑能力设计。条码本身始终必填；新商品缺少名称等必填字段时整行报错；任何非空值格式错误时均报错，不能以保留旧值代替校验。供应商名称非空时无需预建供应关系，由导入直接 upsert 对应“门店 + 商品 + 供应商”记录；供应商单元格为空或后续批次未出现原供应商时，保留目标门店已有记录，不删除关系。预检变更摘要需要区分 `createdFields`、`updatedFields` 和 `unchangedBlankFields`。

商品资料源列“毛利率”无论是否有值都不进入商品主档、门店商品或门店商品供应商关系，也不参与当前售价或当前进价计算。每日销售源列“销售毛利率”则保存到 `DailySales.salesGrossMarginRateSnapshot`，修正版按门店 + 营业日期 + 商品覆盖对应销售事实；后续商品售价或门店供应商进价变化不得回写该快照。

每日销售源列“同期|销售收入”“同期|销售数量”“同期|销售毛利率”全部忽略，不进入预检业务校验、销售事实或分析结果，也不能在系统历史不足时用于补值。

普通用户导入页展示完成本次操作所需的批次状态、行号、条码、商品名称、所属门店库存覆盖结果、校验原因和提交结果；仍不返回销售金额、进价、成本、利润或毛利率。若敏感字段校验失败，错误响应返回字段名和原因，但屏蔽原始值。

## 8. 库存接口

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-080 | `GET /admin/inventory/stores/{storeId}` | ADMIN | `keyword?, categoryId?, inventoryState?, freshnessStatus?, negativeOnly?, sourceType?, sort?, page, size` | 门店最新已知库存分页；返回来源日期、最后同步时间和新鲜度 |
| API-081 | `GET /admin/inventory/summary` | ADMIN | `storeIds[]?, keyword?, categoryId?, freshnessStatus?, negativeOnly?, sort?, page, size` | 按商品汇总已知库存，返回未知门店数、覆盖完整度和门店拆分 |
| API-082 | `GET /admin/inventory/sync-records` | ADMIN | `storeId?, sourceType?, sourceDateFrom?, sourceDateTo?, completedFrom?, completedTo?, operatorId?, page, size` | 每个已提交导入批次一条库存同步摘要 |
| API-083 | `GET /admin/inventory/sync-records/{id}` | ADMIN | 路径 ID | 同步摘要、来源批次链接和统计 |
| API-084 | `GET /admin/inventory/snapshots` | ADMIN | `storeId?, productId?, sourceBatchId?, syncRecordId?, sourceType?, sourceDateFrom?, sourceDateTo?, applyResult?, changedOnly?, page, size` | 每批、每门店商品一条不可变 POP 观察快照 |
| API-085 | `GET /admin/inventory/freshness` | ADMIN | `storeId?` | 全局阈值、门店覆盖、新鲜/陈旧/未知数量及最旧记录 |
| API-086 | `GET /admin/inventory/alerts` | ADMIN | `storeId?, keyword?, type?, status?, detectedFrom?, detectedTo?, page, size` | 负库存和陈旧库存告警 |
| API-087 | `PATCH /admin/inventory/alerts/{id}` | ADMIN | `action: ACKNOWLEDGE|REOPEN, note?, expectedVersion` | 确认知悉或撤销确认，不修改库存 |
| API-088 | `GET /admin/inventory/settings` | ADMIN | 无 | 陈旧阈值、扫描时区、最近扫描结果和配置版本 |
| API-089 | `PUT /admin/inventory/settings` | ADMIN | `staleAfterDays, expectedVersion` | 修改全局陈旧阈值并立即重评估告警 |

当前库存粒度为“门店 + 商品”；库存快照粒度为“来源批次 + 门店 + 商品”；同步记录粒度为“一个已提交导入批次一条”。快照的 `applyResult` 为 `UPDATED|UNCHANGED`；每个后来成功提交批次中的有效观察都会覆盖当前库存并刷新新鲜度，不比较来源日期。总库的 `knownQuantitySum` 只汇总已知值，未知门店不按零参与，并必须返回 `coverageComplete`。

新鲜度按最近成功应用当前投影的 `lastSyncedAt` 计算，使用 `Asia/Shanghai` 自然日；全局默认阈值为 3 天，`ageDays > 3` 才标记 `STALE`，从未同步为 `UNKNOWN`。负库存告警在导入提交时即时判断；陈旧库存每天凌晨 `04:00 Asia/Shanghai` 扫描。首版仅提供站内告警，类型为 `NEGATIVE_INVENTORY|STALE_INVENTORY`，状态为 `OPEN|ACKNOWLEDGED|RESOLVED`；管理员只能确认知悉，异常消失后由系统自动恢复。

库存差异只表示两次 POP 观察值之差，不能解释为销售、进货、调拨或盘点流水。完整粒度、视图、错误码、页面和验收规则见 `docs/api/05-inventory.md`。

## 9. 经营分析接口

所有接口权限为 ADMIN，公共查询参数为 `storeId?、dateFrom、dateTo`；`storeId` 为空表示全部门店。`dateFrom` 和 `dateTo` 均为必填且包含首尾日期，主查询区间最多连续 31 个自然日，超过时返回 `DATE_RANGE_TOO_LARGE`。

| ID | 方法与路径 | 额外请求 | 响应 `[G4]` |
|---|---|---|---|
| API-090 | `GET /admin/analytics/overview` | 公共参数 | 销量、销售额、成本、毛利、毛利率、退货及同比/环比；对比不足时返回不可用原因 |
| API-091 | `GET /admin/analytics/trend` | `granularity: DAY|WEEK|MONTH, metrics[]` | 时间点及所选指标序列 |
| API-092 | `GET /admin/analytics/stores` | `sortBy, order` | 门店指标对比 |
| API-093 | `GET /admin/analytics/products` | `categoryId?, sortBy, order, limit` | 商品销售排行 |
| API-094 | `GET /admin/analytics/margin` | `categoryId?, groupBy` | 毛利、成本和毛利率分组 |
| API-095 | `GET /admin/analytics/anomalies` | `types[], page, size` | 缺失日期、负库存、异常值、导入告警 |

空周期必须返回零值和空序列，不返回 500。退货是否计入净销售和毛利的确切公式在 G4 逐项确认。

每日销售事实长期保留，首版不自动清理，不因 31 日查询限制删除历史。同比区间为主查询区间向前推一个自然年的对应日期区间；环比区间为紧邻主查询区间之前、包含相同自然日数量的连续区间。两者均由后端读取系统历史销售事实计算，不读取 POP 同期字段。比较结果统一返回 `comparisonType, comparisonDateFrom, comparisonDateTo, available, unavailableReason?, values, growthRates`；历史不足时 `available=false`，增长率返回空值而不是 0。API-102 和 API-114 的主分析区间同样最多 31 个自然日。

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
| API-120 | `GET /admin/audit-logs` | ADMIN | `operatorId?, module?, action?, storeId?, result?, dateFrom, dateTo, page, size` | `AuditLogView` 分页；登录、账号权限、导入、商品条码与分类、库存同步/告警、分析配置和 Agent 操作；不记录供应商及报价变化 |
| API-121 | `GET /admin/system/config-status` | ADMIN | 无 | MySQL、Redis、AI 配置是否可用；不返回密钥 |
| API-122 | `GET /admin/system/operations` | ADMIN | 无 | 版本、运行时间、最近迁移、最近导入和 Agent 状态摘要 |

`AuditLogView` 主要字段：`id, occurredAt, operator{id,username}, module, action, targetType, targetId, storeId?, result, requestId, changeSummary?`。`changeSummary` 只保存追查所需的结构化摘要，例如条码、商品所属分类、分类名称或分类父级的修改前后值；不得保存密码、密码哈希、Cookie、会话标识、CSRF token、Redis 会话值、API Key、数据库凭据、完整请求体或完整 Excel 行内容。分类创建与父级调整由普通用户或管理员执行时均写入审计；管理员手工调整商品分类时记录商品和新旧分类，分类重命名及启停记录修改前后值。导入审计只记录批次、结果和数量摘要，不记录源文件完整行。审计记录由系统自动生成，首版只提供查询接口，不提供修改和删除接口；普通用户调用 API-120 至 API-122 返回 403。审计日志保留期限仍在 G5 确认，程序异常堆栈进入应用运行日志而不是业务审计日志。

## 13. 接口评审待确认项

以下内容必须在 G0/G1/G4 中明确后才能将本文档改为 APPROVED：

1. 门店、分类、仓库和货位的最终维护字段，以及供应商只读记录的名称匹配与展示字段。
2. POP 两类表格的最终字段、表头别名、数量和金额精度。
3. 销售汇总中退货、销售额、成本、毛利和毛利率的确切统计公式。
4. Agent 单次运行超时、并发限制、会话保留期限。
5. 审计日志和原始导入文件的保留期限。
