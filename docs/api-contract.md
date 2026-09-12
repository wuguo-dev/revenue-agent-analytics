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
| 400 | DATE_RANGE_TOO_LARGE | 主查询日期区间超过 31 个自然日 |
| 401 | UNAUTHORIZED | 未登录、令牌无效或过期 |
| 403 | FORBIDDEN | 角色或门店范围不允许 |
| 404 | NOT_FOUND | 资源不存在 |
| 409 | DUPLICATE_RESOURCE | 唯一键冲突 |
| 409 | CATEGORY_PARENT_INVALID | 分类父级不存在、指向自身或形成循环 |
| 409 | CATEGORY_DEPTH_EXCEEDED | 创建或移动后分类树超过 5 级 |
| 409 | CATEGORY_PARENT_HAS_PRODUCTS | 目标父分类已有直接关联商品，不能增加子分类 |
| 409 | PRODUCT_CATEGORY_NOT_LEAF | 商品只能关联没有子分类的叶子分类 |
| 409 | CATEGORY_POP_CODE_CONFLICT | 单批文件内同一 POP 品类编码对应多个名称 |
| 409 | IMPORT_DUPLICATE | 重复导入文件 |
| 409 | INVALID_STATE | 当前状态不允许操作 |
| 413 | FILE_TOO_LARGE | 文件超过上限 |
| 422 | IMPORT_INVALID | 导入预检存在错误 |
| 422 | IMPORT_SOURCE_DATE_REQUIRED | 商品资料导入未选择资料日期 |
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
| CATEGORY_ORGANIZE | 普通用户、管理员 | 创建全系统共享分类，并调整分类父级归属 |
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
inventory: { quantity, sourceType, sourceDate, lastSyncedAt } | null
```

明确禁止包含：其他门店库存、`purchasePrice`、`salesQuantity`、`salesAmount`、`costAmount`、`grossProfit`、`grossMarginRate`。

### ProductAdminView `[G1]`

```text
id, barcode, name, suppliers[{ id, name, currentPurchasePrice }],
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
| API-030 | `GET /catalog/products` | USER_CATALOG | `keyword, supplierId, categoryId, status, page, size` | `ProductPublicView` 分页；分类包含 `id,name,status,path`，普通用户自动限定自身门店并返回该店当前售价及最新已知库存 |
| API-031 | `GET /catalog/products/{id}` | USER_CATALOG | 路径 ID | `ProductPublicView`；分类包含停用状态，只返回所属门店当前售价及最新已知库存，不属于门店则 404 |
| API-032 | `GET /admin/products` | ADMIN | `keyword, barcode, supplierId, categoryId, status, storeId?, page, size` | `ProductAdminView` 分页；包含当前分类、各门店当前售价及来源日期摘要，不默认返回销售汇总 |
| API-033 | `GET /admin/products/{id}` | ADMIN | 路径 ID | 管理员商品详情、当前分类、各门店当前售价、售价来源日期及货位，不默认返回销售汇总 |
| API-034 | `POST /admin/products` | ADMIN | `ProductAdminWrite`，含 `categoryId` `[G1]` | 新商品；`categoryId` 必须指向叶子分类 |
| API-035 | `PUT /admin/products/{id}` | ADMIN | `ProductAdminWrite`，含 `barcode, categoryId, version` `[G1]` | 更新商品；允许受控修改条码，`categoryId` 必须指向叶子分类 |
| API-036 | `PUT /admin/products/{id}/status` | ADMIN | `status` | 启用/停用，不删除历史销售 |
| API-037 | `GET /admin/products/{id}/suppliers` | ADMIN | 路径 ID | 商品的全部供应商关系及 `currentPurchasePrice, purchasePriceSourceDate` `[G1]`；不返回历史列表 |
| API-038 | `PUT /admin/products/{id}/suppliers` | ADMIN | `suppliers[{supplierId,currentPurchasePrice,status}]` `[G1]` | 整体更新商品供应商关系及最新进价；不保存历次进价，不改历史销售快照，不写业务审计 |
| API-039 | `PUT /admin/stores/{storeId}/products/{productId}/price` | ADMIN | `salePrice, version` `[G1]`；无日期字段 | 更新指定门店商品当前售价；返回 `salePrice, priceSourceDate, updatedAt, version`，不影响其他门店及历史销售快照 |

### 6.2 供应商与多级分类

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-040 | `GET /admin/suppliers` | ADMIN | `keyword, status, page, size` | 供应商分页 `[G1]` |
| API-041 | `POST /admin/suppliers` | ADMIN | 供应商字段 `[G1]` | 新供应商 |
| API-042 | `PUT /admin/suppliers/{id}` | ADMIN | 供应商字段 `[G1]` | 更新结果 |
| API-043 | `PUT /admin/suppliers/{id}/status` | ADMIN | `status` | 新状态 |
| API-044 | `GET /categories/tree` | USER_CATALOG | `status?` | 多级分类树 `[{id,sourceType,popCode?,name,parentId,level,status,isLeaf,hasDirectProducts,children[]}]` `[G1]` |
| API-045 | `POST /categories` | CATEGORY_ORGANIZE | `name, parentId?, sortOrder?` `[G1]` | 创建人工根分类或父级分类；不接收业务编码，两种角色均可调用；结果不得超过第 5 级，目标父分类不得已有商品 |
| API-046 | `PUT /categories/{id}/parent` | CATEGORY_ORGANIZE | `parentId?, sortOrder?, version` | 调整分类父级；禁止自身或后代作为父级，移动后的整个子树不得超过第 5 级，目标父分类不得已有商品 |
| API-047 | `PUT /admin/categories/{id}` | ADMIN | `name, version` `[G1]` | 重命名分类，不接收或修改 POP 品类编码，也不调整父级 |
| API-057 | `PUT /admin/categories/{id}/status` | ADMIN | `status, version` | 启用或停用；返回新状态，关联商品时不级联修改商品或清空分类 |

分类采用全系统共享的父子层级模型，不包含 `storeId`，根分类的 `parentId` 为空且 `level=1`，最大允许 `level=5`。商品只能直接关联没有子分类的叶子分类，分类不得同时直接关联商品并拥有子分类。普通用户和管理员均可以创建分类并调整父级归属；只有管理员可以重命名、启用或停用分类，普通用户调用 API-047/API-057 返回 403。任何父级调整都必须阻止自引用和循环引用；API-045 创建结果超过第 5 级，或 API-046 移动后目标父级深度加被移动子树高度超过 5 时，返回 409 `CATEGORY_DEPTH_EXCEEDED`。在已有直接关联商品的分类下创建或移入子分类时返回 409 `CATEGORY_PARENT_HAS_PRODUCTS`；API-034/API-035 将商品关联至非叶子分类时返回 409 `PRODUCT_CATEGORY_NOT_LEAF`。上述失败均不得改变原分类树或商品关联。

API-057 停用分类时，即使存在直接或间接关联商品也允许提交；不得改变商品 `status` 或当前 `categoryId`。API-030 至 API-033 继续返回这些商品，并在分类对象中返回 `status=DISABLED` 供前端展示停用标记；ES 索引中的商品保持可检索，只同步分类状态。停用分类不得作为 API-034/API-035 的目标，重新启用后可再次选择。首次进入系统的新商品匹配到停用 POP 分类时的行为仍待确认。

分类记录来源分为 `POP_IMPORT` 和 `MANUAL`。`POP_IMPORT` 类别必须有 `popCode`，且 `popCode` 在全系统唯一，API-067 只按该编码匹配类别；`MANUAL` 分类的 `popCode` 必须为空，由系统内部 `id` 标识。API-045 不允许客户端提交 `popCode`，API-047 也不允许修改 POP 编码。同一编码在单批文件中对应多个名称时预检失败并返回 `CATEGORY_POP_CODE_CONFLICT`；已有编码在后续批次出现不同名称时，导入不覆盖系统分类名称、状态或父级，分类信息只能通过页面维护。

POP 商品资料当前只有单组“品类编码 + 品类名称”，不包含父级链路。API-067 预检必须展示类别匹配、根分类新增、新商品分类初始化和已有商品分类忽略数量，不得根据名称猜测或自动生成父级。条码首次进入本系统时，导入按 `popCode` 匹配类别；新 POP 类别以 `parentId=null` 创建为根分类并作为该商品的初始叶子分类，已有类别直接复用系统中的名称、状态和父级。条码已经存在时，无论文件类别是否变化，商品 `categoryId` 均保持不变。若新商品需要关联的 POP 类别已有子分类，该商品行返回 `PRODUCT_CATEGORY_NOT_LEAF`，整批不可提交。人工分类没有 `popCode`，不会被 POP 导入覆盖。

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

商品与供应商为多对多；供应商只用于商品来源展示和最新进价记录，不提供供应商销售分析接口。`ProductSupplier` 只保存每个“商品 + 供应商”的 `currentPurchasePrice` 和 `purchasePriceSourceDate`，不建设历次进价表。商品资料中的有效含税成本价以资料日期、每日销售中的“当前机构最后进价”以营业日期更新对应关系；仅当来源日期不早于当前 `purchasePriceSourceDate` 时覆盖，同日以最后成功提交批次为准。每日销售进价还要保存为当日销售成本快照，后续当前进价变化不得回写该快照。普通用户响应只能包含供应商名称，不得包含任何进价；供应商主档、关系及报价变化按业务约定不写入审计日志。

当前售价归属 `StoreProduct`，不归属全局 `Product`。`ProductPublicView` 只包含当前账号所属门店的 `salePrice`；`ProductAdminView` 通过 `storePrices[{storeId,storeName,salePrice,priceSourceDate,version}]` 展示各门店当前售价。API-034/035 的 `ProductAdminWrite` 不包含全局售价；管理员使用 API-039 单独维护门店售价。API-039 不接收生效日期或来源日期，系统自动以 `Asia/Shanghai` 的操作当天写入 `priceSourceDate`，以服务器时间写入 `updatedAt`，来源类型记为 `MANUAL`；同日后发生的成功修改或导入可以继续覆盖。任何售价修改不得回写或重算历史销售事实，普通用户调用 API-039 返回 403。

商品 `unit` 为自由文本字段，不引用单位字典，也不提供单位管理接口。API-034/API-035 的 `ProductAdminWrite` 可包含并修改 `unit`；API-030/API-031 及 `ProductPublicView` 只读返回该值，普通用户没有商品修改接口。商品资料导入中的非空单位按通用非空覆盖规则更新已有商品，空单元格保留旧值。

API-034/API-035 允许管理员通过 `categoryId` 创建或修改商品所属分类，目标必须存在、状态为启用且为叶子分类，否则分别返回 `NOT_FOUND`、`VALIDATION_ERROR` 或 `PRODUCT_CATEGORY_NOT_LEAF`。该操作不改变分类父子关系，成功后触发商品搜索索引同步，并在审计中记录商品、原分类和新分类。系统只保存当前有效 `categoryId`，不保存最近 POP 分类或商品分类来源状态；后续商品资料和每日销售导入均不得覆盖。普通用户只能通过 API-030/API-031 查看分类，直接调用商品写接口返回 403。

条码统一作为字符串传输和保存，保留前导零，长度为 1 至 64，只允许 ASCII 数字和英文字母，基础格式为 `[A-Za-z0-9]{1,64}`。空值、空格、标点符号、中文、超长值和 Excel 已丢失精度的科学计数法均返回 `PRODUCT_BARCODE_INVALID`；不得把条码转换为数值类型。

API-035 修改条码时，新条码必须符合上述格式且全局唯一；冲突返回 409 `PRODUCT_BARCODE_CONFLICT`，并发版本不一致返回 409 `VERSION_CONFLICT`。修改只变更该商品的当前业务条码，内部商品 ID 不变，因此历史销售、门店商品、库存快照、供应商和货位关系继续归属同一商品；同时创建 ES 索引同步任务。商品资料导入仍以当前条码作为唯一匹配键，不能通过同一行表达“旧条码改成新条码”。系统不建立旧条码别名，修改后的旧条码不再参与商品查询或导入匹配。普通用户没有商品写接口，调用 API-034 至 API-039 均返回 403。条码修改必须记录操作人、修改时间、旧条码和新条码，但审计记录中的旧值不具备业务匹配作用。

## 7. 导入模板与批次

### 7.1 枚举

- `importType`: `PRODUCT`、`DAILY_SALES`。
- 首版只有 `PRODUCT`（页面名【商品资料】）和 `DAILY_SALES`（页面名【每日销售汇总】）两种导入模板，不再额外选择业务模式。两种模板均可更新当前进价、门店售价和门店最新已知库存；首版不提供进货累加、销售扣库存或人工库存调整模式。
- `status`: `UPLOADED`、`VALIDATING`、`READY`、`INVALID`、`COMMITTED`、`DISCARDED`。

两种模板的首版业务目标字段如下；表头仍通过映射模板按名称匹配，列顺序不固定：

- 【商品资料】：`barcode, productName, unit, specification?, supplierName, purchasePrice, salePrice, inventoryQuantity, popCategoryCode, popCategoryName, remark?`。其中条码和新商品必填字段按预检规则校验，已有商品继续执行非空覆盖。
- 【每日销售汇总】：`barcode, salesQuantity, salesRevenue, latestInventoryQuantity, currentPurchasePrice, currentSalePrice, supplierName?, salesGrossMarginRate?`。销售数量为 0 时整行舍弃；当前进价同步按“条码 + 供应商”定位关系，销售事实仍按条码聚合。

### 7.2 映射模板

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-060 | `GET /admin/import-templates` | ADMIN | `importType, keyword, page, size` | 模板分页 |
| API-061 | `GET /admin/import-templates/{id}` | ADMIN | 路径 ID | 模板及字段映射 |
| API-062 | `POST /admin/import-templates` | ADMIN | `name, importType, mappings[{sourceHeader,targetField}]` | 新模板 |
| API-063 | `PUT /admin/import-templates/{id}` | ADMIN | 同创建请求 | 更新模板 |
| API-064 | `DELETE /admin/import-templates/{id}` | ADMIN | 路径 ID | 未被进行中批次使用时删除 |
| API-065 | `GET /imports/templates/options` | SALES_IMPORT / ADMIN | `importType` | 已启用模板选项；普通用户只返回 `DAILY_SALES` 模板，不返回内部映射细节 |

目标字段列表由服务端按 `importType` 返回，不允许前端提交任意数据库列名：

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-066 | `GET /imports/target-fields` | SALES_IMPORT / ADMIN | `importType` | `field, label, required, dataType, aliases[]` `[G1]`；普通用户只可请求 `DAILY_SALES` |

API-066 在 `PRODUCT` 类型下不得返回商品毛利率目标字段；商品资料源列“毛利率”未映射并出现在 `ignoredColumns[]`。在 `DAILY_SALES` 类型下提供 `salesGrossMarginRate` 目标字段，用于保存 POP 当期销售毛利率快照，但不得提供同期销售收入、同期销售数量或同期销售毛利率目标字段，这三列同样进入 `ignoredColumns[]`。前端不能通过自定义 `mappingsJson` 绕过目标字段白名单。

### 7.3 文件预检和提交

| ID | 方法与路径 | 权限 | 请求 | 响应/行为 |
|---|---|---|---|---|
| API-067 | `POST /imports/preview` | SALES_IMPORT / ADMIN | `multipart: file, importType, businessDate?, sourceDate?, storeId?, templateId?, mappingsJson?` | 批次 ID、日期、表头、映射、忽略列、统计、错误、变更预览 |
| API-068 | `GET /imports` | SALES_IMPORT / ADMIN | `storeId?, importType, businessDate?, sourceDate?, status, createdFrom, createdTo, operatorId?, page, size` | 批次分页；普通用户固定为所属门店的 `DAILY_SALES` 批次，忽略/拒绝越权筛选 |
| API-069 | `GET /imports/{batchId}` | SALES_IMPORT / ADMIN | 路径 ID | 批次详情、错误、忽略列、变更摘要；普通用户可读取所属门店任意销售批次 |
| API-070 | `GET /imports/{batchId}/rows` | SALES_IMPORT / ADMIN | `resultType, page, size` | 预检行分页；普通用户可读取所属门店销售批次，但响应不含成本、毛利等敏感值 |
| API-071 | `GET /imports/{batchId}/errors.xlsx` | SALES_IMPORT / ADMIN | 路径 ID | 所属门店错误行 Excel；普通用户版本屏蔽敏感源值 |
| API-072 | `POST /imports/{batchId}/commit` | SALES_IMPORT / ADMIN | `expectedVersion` | 原子提交；普通用户只能提交本人创建的 READY 批次，管理员不受创建人限制 |
| API-073 | `DELETE /imports/{batchId}` | SALES_IMPORT / ADMIN | 路径 ID | 放弃 READY/INVALID 批次；普通用户只能操作本人创建的批次，管理员不受创建人限制 |

`preview` 主要响应：

```text
batchId, status, filename, fileHash, store, businessDate, sourceDate, importType,
headers[], resolvedMappings[], ignoredColumns[],
statistics { totalRows, validRows, errorRows, ignoredZeroQuantityRows, createRows, updateRows, returnRows },
inventoryOverwrite { presentRows, changedRows, unchangedRows, skippedOlderRows, conflictRows },
priceOverwrite { presentRows, changedRows, unchangedRows, skippedOlderRows, conflictRows },
purchasePriceOverwrite { presentRows, changedRows, unchangedRows, skippedOlderRows, conflictRows },
categorySync { matchedCategories, createdRootCategories, initializedProductCategories, ignoredExistingProductCategories, nonLeafConflicts },
warnings[], errors[]
```

商品资料预检只为新条码计算类别初始化结果；已有条码即使文件中的 POP 类别与系统当前分类不同，也不修改 `categoryId`，计入 `categorySync.ignoredExistingProductCategories`，不作为错误或需要确认的覆盖项。已有分类的名称、状态和父级同样不参与导入更新。

`DAILY_SALES` 的 `businessDate` 必填，且整份文件只能对应一个营业日期，不接收 `sourceDate`。`PRODUCT` 的 `sourceDate` 必填，页面名称为“资料日期”，代表 POP 商品资料的导出数据日期，不接收 `businessDate`；两类日期都不得从文件名、上传时间或提交时间推断。商品资料导入仅限管理员，且管理员必须提交 `storeId`；普通用户导入销售时不得提交 `storeId`，服务端从当前账号绑定关系取得门店。普通用户提交非 `DAILY_SALES` 类型、伪造门店、访问其他门店批次或操作他人批次时返回 403。

存在错误行时状态为 `INVALID`，API-072 必须返回 `IMPORT_INVALID`。销售导入的重复文件范围为“门店 + 营业日期 + 导入类型 + SHA-256”；该范围内已有 `COMMITTED` 批次时返回 `IMPORT_DUPLICATE`。同一门店、日期和条码已有销售事实但新文件指纹不同时，按修正版预检并展示销售差额；库存不按销售差额加减。

销售导入在同条码聚合和业务写入前，先过滤“本期销售数量”等于 0 的源行。该行结果类型为 `IGNORED_ZERO_QUANTITY`，计入 `totalRows` 和 `ignoredZeroQuantityRows`，不计入 `validRows`、`createRows`、`updateRows`、`returnRows` 以及库存、进价、售价覆盖统计；它不是错误行，不阻断同批次其他有效数据。即使该行销售收入非 0，也按业务规则整行舍弃，不创建或修正销售事实，不更新当前进价、门店售价和库存。负销量不属于零销量忽略行，仍按退货事实校验、聚合和提交。同一条码同时存在零销量与非零销量行时，先舍弃零销量行，再对剩余行执行一致性校验和聚合。API-070 的 `resultType` 必须支持 `IGNORED_ZERO_QUANTITY`，并返回行号、条码、商品名称和忽略原因；普通用户响应继续屏蔽销售收入、成本和毛利等敏感源值。

业务方保证每份每日销售文件过滤后至少存在一条非零销量数据。因此 API-067/API-072 首版不定义“全部行均因零销量被忽略”的专用错误码或空批次提交行为，该情形也不列入验收范围。

销售导入只包含当日销售商品，属于部分库存覆盖：文件中出现商品的“选中机构库存数量”（别名“当前机构库存数量”）直接覆盖门店最新已知库存，文件未出现商品保持原值。若同条码多行的库存值不一致，整批为 `INVALID`。若营业日期早于商品当前库存的来源营业日期，该行销售仍可修正，但库存覆盖跳过并记录原因；同一营业日期以最后成功提交批次为准。

销售文件中的“当前机构”固定指本次批次的目标门店：管理员由 API-067 的 `storeId` 选择，普通用户由后端账号门店绑定确定。“当前机构售价”必须保存为该营业日期的销售事实快照，并参与目标门店当前售价同步。仅当 `businessDate >= priceSourceDate` 时覆盖 `StoreProduct.salePrice` 并将 `priceSourceDate` 更新为本次 `businessDate`；若日期更早，则销售事实仍可新增或修正，但当前售价跳过覆盖并在预检结果中标明。相同营业日期允许覆盖，以最后成功提交批次为准。同条码重复行的当前机构售价不一致时整批为 `INVALID`。售价快照或当前售价均不得用于反算实际销售收入。

每日销售中的“当前机构最后进价”必须保存为该营业日期的历史成本快照，并参与对应商品供应商当前进价同步。仅当 `businessDate >= purchasePriceSourceDate` 时覆盖 `ProductSupplier.currentPurchasePrice` 并更新来源日期；更早的销售导入只更新历史销售事实，不回退当前进价。同日以最后成功提交批次为准。同条码、同供应商重复行的进价不一致时整批为 `INVALID`。

所有【商品资料】导入均由管理员选择门店和 `sourceDate`；每个文件都按独立增量同步批次处理。这里的“条码不存在”专指新系统中尚无该条码，POP 中的商品及库存仍是完整、权威的。新系统中条码不存在时创建商品主档、以 POP 类别初始化 `categoryId`、创建商品供应商关系和门店商品关系；条码已存在时按字段合并，但不修改其 `categoryId`。有效“含税成本价”“售价”“库存数量”分别在 `sourceDate` 不早于当前进价、售价、库存来源日期时更新对应当前值。相同资料日期以最后成功提交批次为准，旧资料的成本、售价和库存均跳过覆盖并展示原因。批次未出现的商品不参与校验或更新，不清零、不停用，也不阻断提交。已有分类名称、状态和父级不由导入覆盖。该模板不是采购或实体入库，也不是进货累加，可拆分多批并重复执行。

商品资料已有条码的字段合并规则固定为“非空覆盖”：Excel 单元格为空时，该字段记为 `UNCHANGED` 并保留系统旧值；单元格非空且校验通过时记为 `UPDATED` 并覆盖旧值。空单元格不能用于清空系统字段；如以后需要清空，必须通过独立的显式编辑能力设计。条码本身始终必填；新商品缺少名称等必填字段时整行报错；任何非空值格式错误时均报错，不能以保留旧值代替校验。供应商单元格为空时保留现有供应商关系，不删除关系。预检变更摘要需要区分 `createdFields`、`updatedFields` 和 `unchangedBlankFields`。

商品资料源列“毛利率”无论是否有值都不进入商品主档、门店商品或商品供应商关系，也不参与当前售价或当前进价计算。每日销售源列“销售毛利率”则保存到 `DailySales.salesGrossMarginRateSnapshot`，修正版按门店 + 营业日期 + 商品覆盖对应销售事实；后续商品售价或供应商进价变化不得回写该快照。

每日销售源列“同期|销售收入”“同期|销售数量”“同期|销售毛利率”全部忽略，不进入预检业务校验、销售事实或分析结果，也不能在系统历史不足时用于补值。

普通用户导入页展示完成本次操作所需的批次状态、行号、条码、商品名称、所属门店库存覆盖结果、校验原因和提交结果；仍不返回销售金额、进价、成本、利润或毛利率。若敏感字段校验失败，错误响应返回字段名和原因，但屏蔽原始值。

## 8. 库存接口

| ID | 方法与路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| API-080 | `GET /admin/inventory/stores/{storeId}` | ADMIN | `keyword, categoryId, negativeOnly, staleOnly, page, size` | 门店最新已知库存分页；返回来源营业日期和最后同步时间 `[G1]` |
| API-081 | `GET /admin/inventory/summary` | ADMIN | `keyword, categoryId, staleOnly, page, size` | 汇总各门店最新已知库存，并返回门店拆分和数据新鲜度 |
| API-082 | `GET /admin/inventory/sync-records` | ADMIN | `storeId, productId, sourceType, dateFrom, dateTo, page, size` | POP 库存覆盖记录及覆盖前后差异 |
| API-083 | `GET /admin/inventory/snapshots` | ADMIN | `storeId, sourceDateFrom, sourceDateTo, page, size` | 商品资料或销售批次产生的库存快照分页 |
| API-084 | `GET /admin/inventory/snapshots/{batchId}` | ADMIN | `productId?, changedOnly?, page, size` | 指定批次的库存快照明细 |
| API-085 | `GET /admin/inventory/freshness` | ADMIN | `storeId?, staleDays?` | 最后同步时间分布、陈旧商品数量和门店覆盖情况 |
| API-086 | `GET /admin/inventory/alerts` | ADMIN | `storeId, type, status, page, size` | 负库存等告警 |
| API-087 | `PUT /admin/inventory/alerts/{id}/status` | ADMIN | `status: OPEN|RESOLVED, resolution?` | 更新告警处理状态，不改库存 |

库存同步记录主要字段 `[G1]`：`id, store, product, quantityBefore, popQuantity, quantityDifference, sourceType, sourceBatchId, sourceDate, syncedAt, operator`。商品资料的 `sourceDate` 为管理员选择的资料日期，每日销售的 `sourceDate` 为营业日期。差异只表示两次 POP 观察值之差，不能解释为销售、进货或盘点流水。

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
| API-120 | `GET /admin/audit-logs` | ADMIN | `operatorId?, module?, action?, storeId?, result?, dateFrom, dateTo, page, size` | `AuditLogView` 分页；登录、账号权限、导入、商品条码与分类、门店售价、库存同步/告警、分析配置和 Agent 操作；不记录供应商及报价变化 |
| API-121 | `GET /admin/system/config-status` | ADMIN | 无 | MySQL、Redis、AI 配置是否可用；不返回密钥 |
| API-122 | `GET /admin/system/operations` | ADMIN | 无 | 版本、运行时间、最近迁移、最近导入和 Agent 状态摘要 |

`AuditLogView` 主要字段：`id, occurredAt, operator{id,username}, module, action, targetType, targetId, storeId?, result, requestId, changeSummary?`。`changeSummary` 只保存追查所需的结构化摘要，例如条码、商品所属分类、分类名称、分类父级或门店售价的修改前后值；不得保存密码、JWT、Redis 会话值、API Key、数据库凭据、完整请求体或完整 Excel 行内容。分类创建与父级调整由普通用户或管理员执行时均写入审计；管理员手工调整商品分类时记录商品和新旧分类，分类重命名及启停记录修改前后值。导入审计只记录批次、结果和数量摘要，不记录源文件完整行。审计记录由系统自动生成，首版只提供查询接口，不提供修改和删除接口；普通用户调用 API-120 至 API-122 返回 403。审计日志保留期限仍在 G5 确认，程序异常堆栈进入应用运行日志而不是业务审计日志。

## 13. 接口评审待确认项

以下内容必须在 G0/G1/G4 中明确后才能将本文档改为 APPROVED：

1. 门店、供应商、分类、仓库和货位的最终维护字段。
2. POP 两类表格的最终字段、表头别名、数量和金额精度。
3. 销售汇总中退货、销售额、成本、毛利和毛利率的确切统计公式。
4. 库存超过多少天未被两类 POP 文件覆盖时标记为陈旧。
5. Agent 单次运行超时、并发限制、会话保留期限。
6. 审计日志和原始导入文件的保留期限。
