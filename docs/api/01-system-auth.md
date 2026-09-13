# M01 系统与认证接口及实施粒度规划

> 文档状态：`APPROVED`
> 模块：M01 系统与认证
> 基础路径：`/api/v1`；Actuator 健康检查保留原路径
> 依赖模块：M02 用户与门店、M10 审计与运维
> 目标：以小而稳定的 interface 统一完成系统健康读取、凭据校验、会话建立、主体解析和会话失效。

## 0. 逐步确认流程

本文件已按下表完成逐项评审；后续若修改已确认规则，必须重新确认受影响步骤。

| 步骤 | 评审主题 | 对应章节 | 状态 | 确认记录 |
|---:|---|---|---|---|
| 1 | 模块目标、范围、职责与 seam | 第 1、2 节 | confirmed | 2026-09-12 用户确认通过 |
| 2 | 会话模型、Cookie/CSRF 与有效期 | 第 3 节 | confirmed | 2026-09-12 用户确认通过 |
| 3 | 登录校验、密码安全与防爆破 | 第 4 节 | confirmed | 2026-09-12 用户确认通过；管理员统一创建/分发/重置密码，员工无改密权限 |
| 4 | 会话失效、并发登录与账号变更联动 | 第 5 节 | confirmed | 2026-09-12 用户确认通过 |
| 5 | 当前主体、权限 code 与前端菜单约定 | 第 6 节 | confirmed | 2026-09-12 用户确认通过；固定两角色与 5 个权限 code，不建设动态权限 |
| 6 | API-001、002、009～012 请求与响应 | 第 7、8 节 | confirmed | 2026-09-12 用户确认通过；API-001 以应用、MySQL、Redis 为核心健康标准 |
| 7 | 错误、审计、隐私与安全响应头 | 第 9 节 | confirmed | 2026-09-13 用户确认通过 |
| 8 | 页面、实施任务、测试、验收与完成定义 | 第 10～13 节 | confirmed | 2026-09-13 用户确认通过 |

确认规则：

- 用户回复“确认第 N 步”或表达等价的明确同意后，才将该步标为 `confirmed`。
- 用户提出修改时，只修改当前步骤及其直接受影响内容，更新后继续等待该步确认。
- 不以沉默、进入下一话题或完整草案已经存在推定确认。
- 8 个步骤全部确认并完成跨文档校验后，M01 标记为 `APPROVED`，P0-M01 标记为 `complete`。

## 1. 模块目标与范围

### 1.1 M01 包含

- 提供公开、最小化且不泄露依赖细节的健康检查。
- 提供登录后可读取的系统基础信息。
- 校验用户名与密码，统一处理账号不存在、密码错误和账号不可登录。
- 建立、解析、续期和撤销当前会话。
- 向其他后端模块提供可信的当前主体：用户 ID、角色、门店、权限 code 和会话标识。
- 接收 M02 的账号安全变更通知，使指定账号全部会话失效。
- 向 M10 发送脱敏的认证审计事件。
- 统一认证失败、未登录、会话过期和 CSRF 失败响应。

### 1.2 M01 不包含

- 用户创建、详情、分页、角色修改、账号生命周期、密码重置页面与接口；这些属于 M02。
- 门店创建、修改、停用和账号门店绑定；这些属于 M02。
- 业务模块的角色/门店授权规则；各模块仍在自己的 interface 上执行授权与数据范围校验。
- 审计日志查询、保留和导出；这些属于 M10。
- 数据库、Redis、Elasticsearch、模型和任务的详细运维状态；这些属于 M10。
- 用户自助注册、自助找回密码、短信/邮件验证码、第三方登录、SSO、OAuth 授权服务器、API Key 和“记住我”；首版不建设。
- 在前端仅靠隐藏菜单实现授权；前端菜单只改善体验，后端始终独立鉴权。

### 1.3 首版结论

- 只有 `ADMIN` 与 `USER` 两种账号角色，角色定义来自 M02。
- 未登录用户只能访问健康检查、CSRF 初始化和登录。
- 登录成功后，普通用户绑定且固定为一个门店；管理员不绑定门店。
- 认证主体中的角色、门店和权限以服务端当前状态为准，客户端提交的角色或门店一律不可信。
- M01 只定义认证和主体 interface，不吞并 M02 用户管理或各业务模块授权。

## 2. 模块设计与 seam

M01 是一个深模块。控制器、前端和其他业务模块只使用“建立会话、读取当前主体、撤销会话”这一小组 interface，不需要编排密码哈希、Redis 键、Cookie、CSRF、失败计数、账户登录资格复核或审计字段。

### 2.1 M01 拥有

- 凭据校验流程与密码哈希 adapter 的使用规则。
- 会话 ID 的生成、轮换、续期、绝对过期和撤销。
- Redis 会话结构、按用户索引、失败计数和失效版本。
- 认证 Cookie、CSRF token 和认证相关安全响应。
- 当前主体快照与每次请求的有效性判断。
- 认证审计事件的脱敏与投递。

### 2.2 M01 不拥有

- 用户资料、角色定义、账户删除/注销、密码哈希和账号门店绑定的权威持久化；M02 是权威来源。
- 菜单文案、图标、排序和页面布局；前端基于权限 code 维护展示结构。
- 业务资源的门店隔离查询；各业务模块按 M01 提供的主体执行。
- 审计日志表、审计查询和保留策略；M10 负责。

### 2.3 外部 seam

名称表达职责，最终 Java 包名在工程骨架阶段确定。

```text
AuthenticationModule
  login(command, clientContext) -> AuthenticatedSession
  logout(currentSession) -> LogoutResult
  currentPrincipal(currentSession) -> AuthenticatedPrincipal
  invalidateAllSessions(userId, reason) -> InvalidationResult

SystemReadModule
  health() -> PublicHealth
  info(currentPrincipal) -> SystemInfo
```

`AuthenticatedPrincipal` 是所有受保护模块唯一可信的调用者上下文；禁止业务控制器自行解析 Cookie、直接查 Redis 或依据前端参数构造角色/门店。

### 2.4 M02 seam

```text
AuthenticationSubjectPort              // M02 production adapter；M01 使用
  findForLogin(normalizedUsername) -> AuthenticationSubject?
  findCurrent(userId) -> AuthenticationSubject?

CredentialPolicy                       // M01 提供；M02 创建/重置密码时使用
  validate(rawPassword) -> ValidationResult
  encode(rawPassword) -> PasswordHash

SessionInvalidationPort                 // M01 提供；M02 使用
  invalidateAll(userId, reason) -> InvalidationResult
```

- `AuthenticationSubject` 只含认证所需的用户 ID、用户名、密码哈希、登录资格、角色、门店 ID 和认证版本，不返回业务详情。
- M02 重置密码、使账号失去登录资格、改变角色或改变绑定门店后，在同一业务操作成功路径触发 `invalidateAll`。
- M02 的数据库事务与 Redis 撤销无法组成分布式事务；实现采用“先提交权威账号变更，再同步撤销并可重试”的安全优先顺序。撤销短暂失败时，请求认证还必须通过 `authenticationVersion` 或当前主体复核拒绝旧会话，不能继续放行。

### 2.5 M10 seam

```text
AuthenticationAuditPort                // M10 adapter
  record(event: AuthenticationAuditEvent) -> void
```

只传递成功/失败、用户 ID（若可确定）、脱敏用户名标识、原因 code、请求追踪号、客户端摘要和时间；不得传递密码、Cookie、CSRF token、会话原值或完整请求体。审计写入失败不得改变正确的认证结果，但必须进入应用运行日志与运维告警。

### 2.6 内部 seam

- Redis 会话存储：生产 Redis adapter；测试内存 adapter。
- 密码编码：生产 Spring Security `PasswordEncoder` adapter；测试仍使用真实编码器或低成本测试配置。
- 时钟与随机数：生产安全随机/系统时钟 adapter；测试固定时钟/确定性 adapter。
- 客户端地址解析：只信任部署明确配置的反向代理转发头，默认使用直连地址。

这些内部 seam 不暴露为 HTTP 接口，也不要求前端或其他业务模块理解。

## 3. 会话模型、Cookie 与 CSRF

### 3.1 已确认方案

首版采用“Redis 服务端会话 + 不透明随机会话 ID + HttpOnly Cookie”，不在浏览器 `localStorage/sessionStorage` 保存认证凭据，不签发前端可读 JWT，不提供 refresh token。

推荐 Cookie：

```text
SA_SESSION=<opaque-random-id>
HttpOnly; Secure(生产); SameSite=Lax; Path=/; Max-Age=43200
不设置 Domain（仅当前主机）；浏览器关闭并重新打开后，仍可在服务端会话有效期内恢复登录
```

- 生产环境由同一站点对外提供前端和 `/api`，开发环境通过 Vite 代理访问后端；首版不支持把携带认证 Cookie 的前端与 API 部署为跨站点应用。
- Cookie 中只有随机标识，角色、门店和权限不放入 Cookie，均以服务端会话及 M02 当前权威状态为准。

会话时限：

- 空闲超时 30 分钟；有成功的受保护请求时滑动续期。
- 绝对有效期 12 小时；达到后必须重新登录，滑动续期不能突破。
- 前端不实现“记住我”。
- 服务端时间是唯一有效期依据，客户端倒计时只用于提示。

### 3.2 CSRF

- Cookie 自动随请求发送，因此所有会改变状态的请求必须校验 CSRF token。
- API-009 初始化 CSRF token；前端从可读但不具备认证能力的 `XSRF-TOKEN` Cookie 取值并写入 `X-XSRF-TOKEN` 请求头。
- 会话建立后轮换 CSRF token；退出后清除会话 Cookie 和 CSRF Cookie。
- 同时校验可信 `Origin`；生产只允许已配置的前端 origin，禁止通配 origin 搭配凭据。
- `GET/HEAD/OPTIONS` 必须无业务写入副作用。

## 4. 登录、密码安全与防爆破

### 4.1 登录规范

- 用户名去除首尾空白后按 M02 的规范化规则匹配；密码不 trim、不自动改变大小写。
- 账号不存在、密码错误或账号不具备登录资格统一返回 HTTP 401、`AUTH_INVALID_CREDENTIALS` 和“用户名或密码错误”。
- 对不存在账号执行等成本的伪密码哈希校验，降低时序枚举风险。
- 登录成功必须生成全新会话 ID，不能沿用登录前标识。
- 请求与日志不得记录密码；登录响应不得返回密码哈希、会话 ID 或 CSRF token 原值。

### 4.2 密码存储

- 使用 Spring Security `DelegatingPasswordEncoder`，首版新密码采用框架直接支持的 bcrypt，工作因子使用部署配置且不写死在业务代码。
- bcrypt 工作因子初始建议为 12；在目标部署环境验证登录响应时间后可以调整。
- 只保存带算法标识的单向哈希；禁止明文、可逆加密和固定无盐哈希。
- M02 创建/重置密码必须复用 M01 的 `CredentialPolicy`。

### 4.3 首版密码规则

- 账号统一由管理员创建并向员工分发；不开放用户自助注册、找回密码或短信/邮件验证。
- 密码长度 8～32 个半角字符，允许英文字母、数字和常用半角符号；不强制复杂的大小写、数字、特殊字符组合。
- 密码不得与用户名完全相同，不允许空白字符、控制字符或空字节；不得静默截断。
- 不做常见密码库或第三方泄露密码查询，不要求周期性修改，也不强制首次登录修改。
- 员工没有修改密码权限；忘记密码或需要更换密码时，只能由管理员通过 M02 重置。

### 4.4 登录节流

- 同一规范化用户名在 15 分钟内连续 5 次失败后冷却 15 分钟；无论账号是否真实存在都使用相同响应。
- 成功登录清除该用户名的连续失败计数；不永久锁定数据库账号。
- 失败计数和限流状态放 Redis；Redis 异常时默认拒绝登录并返回通用服务不可用错误，不能失去防护后无限放行。
- 首版不加验证码。

## 5. 会话失效与并发登录

- 允许同一账号最多 3 个有效会话；第 4 次登录成功时撤销最早会话。
- API-011 只撤销当前会话，重复退出按幂等成功处理。
- M02 重置密码、删除/注销账号、修改角色或修改门店绑定后，撤销该账号全部会话。
- 被删除或注销的账号不会自动恢复登录能力；重新创建的账号视为新身份，不能复用旧会话。
- 每次受保护请求至少校验：会话存在、未过期、未撤销、认证版本一致、账号仍具备登录资格。
- 会话中的角色/门店只作为快照；权威版本变化后旧会话拒绝，不能继续使用旧权限。
- Redis 会话读取失败时受保护请求返回 503，不降级为匿名或继续信任 Cookie。

## 6. 当前主体与权限 code

### 6.1 AuthenticatedPrincipal

```text
sessionIdHash, userId, username, displayName, gender, age?, hireDate?,
roleCode: ADMIN|USER,
store: { id, code, name } | null,
permissions: string[],
issuedAt, lastAccessAt, idleExpiresAt, absoluteExpiresAt
```

- `ADMIN.store = null`；`USER.store` 必须存在且处于可用状态，否则拒绝建立或继续会话。
- HTTP 响应不返回 `sessionIdHash`；它只用于内部追踪和审计关联。
- `permissions` 是稳定能力 code，不返回后端路由表达式或数据库权限记录。

### 6.2 首版权限 code

| code | ADMIN | USER | 说明 |
|---|:---:|:---:|---|
| `SYSTEM_READ` | 是 | 是 | 读取系统基础信息 |
| `USER_CATALOG` | 是 | 是 | 商品目录；普通用户固定所属门店 |
| `CATEGORY_ORGANIZE` | 是 | 是 | 创建分类、调整父级 |
| `SALES_IMPORT` | 是 | 是 | 每日销售导入；普通用户固定所属门店 |
| `ADMIN` | 是 | 否 | 全部管理功能、经营分析、冷热评分、多 Agent、审计与运维 |

首版不建设动态角色或逐按钮权限配置。权限 code 由固定角色在后端映射生成，只用于前端路由/按钮裁剪和后端声明式授权；菜单文案、图标、顺序与路由仍由前端静态维护。门店数据范围由对应业务模块根据主体 `store` 强制执行，不能只依赖权限 code。

## 7. 接口清单

| ID | 方法与路径 | 权限 | 行为 |
|---|---|---|---|
| API-001 | `GET /actuator/health` | PUBLIC | 最小健康检查，不返回依赖明细 |
| API-002 | `GET /api/v1/system/info` | AUTHENTICATED | 返回当前登录用户可见的系统基础信息 |
| API-009 | `GET /api/v1/auth/csrf` | PUBLIC | 初始化或轮换 CSRF token |
| API-010 | `POST /api/v1/auth/login` | PUBLIC + CSRF | 校验凭据并建立会话 |
| API-011 | `POST /api/v1/auth/logout` | PUBLIC + CSRF | 有会话则撤销当前会话；无会话仍幂等清 Cookie |
| API-012 | `GET /api/v1/auth/me` | AUTHENTICATED | 返回当前主体、权限和会话有效期 |

## 8. 完整请求与响应

除 Actuator 外统一使用 `{code,message,data}` 响应。所有时间为带偏移量 ISO-8601，业务时区为 `Asia/Shanghai`。

### 8.1 API-001 健康检查

成功 `200`：

```json
{"status":"UP"}
```

核心健康标准为：Java 后端可以正常响应，并且 MySQL、Redis 均可连接。三者正常时返回 `UP`；任一核心项不可用时返回 HTTP 503 与 `{"status":"DOWN"}`。Elasticsearch 或 AI 模型不可用只影响对应功能，不把系统整体健康状态改为 `DOWN`。匿名响应不得出现组件名称、数据库/Redis 地址、磁盘、主机名、版本或异常信息；详细组件状态只通过 M10 管理员运维接口读取。

### 8.2 API-002 系统信息

成功 `200`：

```json
{
  "code": "OK",
  "message": "",
  "data": {
    "applicationName": "多 Agent 协作销售分析系统",
    "version": "0.1.0",
    "serverTime": "2026-09-12T16:30:00+08:00",
    "businessTimezone": "Asia/Shanghai"
  }
}
```

不返回部署环境、依赖地址、数据库版本、模型密钥或 AI 配置状态；这些由 M10 管理员接口承担。

### 8.3 API-009 CSRF 初始化

请求无 body。成功 `204`，响应设置/刷新 `XSRF-TOKEN` Cookie；标准响应 body 例外为空。不得把 token 写入日志或 JSON。

### 8.4 API-010 登录

请求 `Content-Type` 为 `application/json`：`username`、`password` 均必填且必须是字符串；用户名最多接收 64 个字符并交由 M02 按最终用户名规则规范化，密码必须满足已确认的 8～32 个半角字符规则且服务端不得 trim。

请求：

```json
{
  "username": "zhangsan",
  "password": "user-entered-secret"
}
```

成功 `200`，响应设置新 `SA_SESSION` Cookie 并轮换 `XSRF-TOKEN`：

```json
{
  "code": "OK",
  "message": "",
  "data": {
    "user": {
      "id": "usr_01",
      "username": "zhangsan",
      "displayName": "张三",
      "gender": "MALE",
      "age": 30,
      "hireDate": "2020-05-18",
      "roleCode": "USER",
      "store": {"id":"store_01","code":"S001","name":"一店"}
    },
    "permissions": ["SYSTEM_READ","USER_CATALOG","CATEGORY_ORGANIZE","SALES_IMPORT"],
    "session": {
      "issuedAt": "2026-09-12T08:00:00+08:00",
      "idleExpiresAt": "2026-09-12T08:30:00+08:00",
      "absoluteExpiresAt": "2026-09-12T20:00:00+08:00"
    }
  }
}
```

响应不返回任何认证凭据；Cookie 由浏览器管理。

### 8.5 API-011 退出

请求无 body。无论当前会话是否已撤销，均返回：

```json
{"code":"OK","message":"","data":{"loggedOut":true}}
```

若请求完全没有可识别会话，仍清理 Cookie 并按幂等成功；若 CSRF 校验失败则拒绝，不能以幂等为由绕过 CSRF。

### 8.6 API-012 当前用户

成功响应与 API-010 的 `data` 同结构，`idleExpiresAt` 返回本次成功访问滑动续期后的值。前端启动、刷新页面和收到跨标签页登录状态变化时必须调用本接口，不根据本地是否存在 Cookie 猜测登录状态；无效或过期会话返回 401 并清除失效的会话 Cookie。

## 9. 错误、审计、隐私与安全

### 9.1 模块错误码

| HTTP | code | 场景 | 对外 message |
|---:|---|---|---|
| 400 | `AUTH_REQUEST_INVALID` | 登录字段缺失、超长或格式错误 | 请求参数不正确 |
| 401 | `AUTH_INVALID_CREDENTIALS` | 用户不存在、密码错误、账号无登录资格或绑定无效 | 用户名或密码错误 |
| 401 | `AUTH_SESSION_INVALID` | 会话不存在、撤销、认证版本变化 | 登录状态已失效，请重新登录 |
| 401 | `AUTH_SESSION_EXPIRED` | 空闲或绝对有效期届满 | 登录已过期，请重新登录 |
| 403 | `AUTH_CSRF_INVALID` | CSRF token 缺失或不匹配 | 请求校验失败，请刷新后重试 |
| 429 | `AUTH_LOGIN_THROTTLED` | 同一用户名触发登录冷却 | 尝试次数过多，请稍后再试 |
| 503 | `AUTH_SESSION_STORE_UNAVAILABLE` | Redis 会话或限流存储不可用 | 登录服务暂不可用 |

受保护业务接口未登录统一 401；已经登录但角色/门店无权访问统一 403。不存在的他店资源可由业务模块按防枚举规则返回 404。

### 9.2 审计事件

| 事件 | 结果 | 必要摘要 |
|---|---|---|
| `AUTH_LOGIN` | SUCCESS/FAILURE | userId?、脱敏用户名标识、原因 code、客户端地址摘要、User-Agent 摘要、requestId |
| `AUTH_LOGOUT` | SUCCESS | userId、会话哈希、requestId |
| `AUTH_SESSION_REVOKED` | SUCCESS/FAILURE | userId、原因、撤销数量、触发模块、requestId |

普通的会话过期和每次 `/auth/me` 不写业务审计，避免无价值噪音；异常频率进入运行指标。

### 9.3 安全与隐私

- 生产只允许 HTTPS；认证 Cookie 必须 `Secure`。
- 响应设置适合 SPA 的 CSP、`X-Content-Type-Options: nosniff`、`Referrer-Policy` 和 frame 防护。
- 登录、退出和 `/auth/me` 响应使用 `Cache-Control: no-store`。
- 生产与开发均按同站点/代理方式访问，默认不开放 CORS；若未来确需跨 origin，只允许显式白名单和必要方法/请求头，携带凭据时禁止 `*`。
- 用户名、客户端地址和 User-Agent 按审计最小化原则保存；具体保留期限由 M10 定稿。
- 任何日志、追踪、错误响应和审计均禁止密码、Cookie、会话 ID、CSRF token、密码哈希和完整 Authorization 值。

## 10. 前端页面与交互

### 10.1 登录页

- 首次进入先调用 API-009，再允许提交登录。
- 用户名与密码只保存在组件内存；密码框禁止自动回填到应用状态或日志。
- 登录中按钮防重复提交；失败统一显示服务端通用文案。
- 登录成功后调用/使用会话数据，跳转原受保护地址；不允许开放重定向到外部 URL。

### 10.2 应用启动与路由

- 应用启动调用 API-012；成功后建立 Pinia 内存认证状态。
- 401 时清空内存状态并跳转登录，保留站内返回地址。
- 403 显示无权限页，不清除有效登录状态。
- 菜单由前端静态路由元数据 + `permissions` 过滤；后端仍独立鉴权。
- 多标签页使用 `BroadcastChannel` 或无敏感值的 storage 事件广播“已退出/需重新校验”，不传播会话凭据。

### 10.3 退出

- 用户点击退出调用 API-011；成功后清空内存主体并跳转登录。
- 网络失败时不能宣称退出成功，也不能由前端删除 HttpOnly Cookie；保留当前页面并提示重试，只有 API-011 成功后才清空主体和广播退出。

## 11. 实施任务

| 编号 | 后端任务 | 交付与验证 |
|---|---|---|
| M01-BE-01 | Spring Security 基线、统一 401/403/CSRF 响应 | 安全链集成测试 |
| M01-BE-02 | Redis 会话、空闲/绝对过期、按用户索引 | 时钟驱动的会话测试 |
| M01-BE-03 | 登录编排、真实密码编码、伪哈希校验 | 正误凭据与时序路径测试 |
| M01-BE-04 | 登录节流与 Redis 故障策略 | 阈值、恢复、并发测试 |
| M01-BE-05 | M02 主体 adapter 与认证版本复核 | 账号失去登录资格/改权/改店/改密失效测试 |
| M01-BE-06 | M10 脱敏审计 adapter | 敏感字段排除测试 |
| M01-BE-07 | API-001/002/009～012 与 OpenAPI | 合同测试 |

| 编号 | 前端任务 | 交付与验证 |
|---|---|---|
| M01-FE-01 | Cookie/CSRF 请求封装，移除 `localStorage` token | 单元测试与源码扫描 |
| M01-FE-02 | Pinia 内存主体、应用启动 `/auth/me` | 刷新与过期测试 |
| M01-FE-03 | 登录、退出、401/403 和安全返回地址 | 组件与路由测试 |
| M01-FE-04 | 权限 code 菜单裁剪 | 两角色页面测试 |
| M01-FE-05 | 系统状态页基础信息 | 真实接口联调 |

## 12. 测试与验收矩阵

| 场景 | 预期 |
|---|---|
| 匿名健康检查 | 后端、MySQL、Redis 正常时返回 UP，任一异常返回 DOWN；不泄露组件 |
| Elasticsearch 或 AI 不可用 | API-001 仍为 UP，对应功能独立提示不可用 |
| 正确管理员/普通用户登录 | 建立全新会话，主体、门店、权限准确 |
| 用户不存在/密码错误/无登录资格 | 相同 HTTP、code 和 message，不泄露原因 |
| 第 5 次连续失败 | 进入 15 分钟冷却；审计无密码和会话标识 |
| 页面刷新 | 通过 `/auth/me` 恢复主体，不读取本地 token |
| CSRF 缺失或跨站请求 | 写请求 403，业务未执行 |
| 空闲 30 分钟/绝对 12 小时 | 401，Cookie 清理并要求登录 |
| 第 4 个并发会话建立 | 最早会话失效，其他三个可用 |
| 当前会话重复退出 | 都成功且 Cookie 已清除 |
| M02 重置密码/取消登录资格/改角色/改门店 | 全部旧会话失效，旧权限不能继续调用 |
| Redis 不可用 | 登录/受保护请求安全失败，不绕过认证 |
| 普通用户伪造门店或访问管理员接口 | 403 或业务防枚举 404，服务端数据范围不扩大 |
| 安全扫描 | 源码和浏览器存储中无 token；日志/审计无敏感值 |

## 13. 门禁与完成定义

M01 已满足以下定稿条件，并于 2026-09-13 标记为 `APPROVED`：

- 8 个确认步骤全部由用户明确确认并有记录。
- `docs/api-contract.md`、`docs/implementation-plan.md`、`docs/data-dictionary.md`、README 与本文件不存在认证口径冲突。
- API 编号、路径、权限、DTO、错误码和页面映射没有悬空引用。
- 会话、密码、CSRF、限流、失效和审计规则均有可执行验收场景。
- 明确保持 P0 文档阶段：本次定稿不创建数据库、Redis 键、迁移或业务代码。

M01 接口文档批准后，相关实体字段和 Redis 键结构仍须进入 G1 数据模型评审，不能因接口定稿跳过数据库门禁。
