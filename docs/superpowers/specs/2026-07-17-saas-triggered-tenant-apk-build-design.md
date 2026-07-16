# SaaS 触发租户 APK 构建设计

## 1. 背景与目标

当前租户已经有广告账号、回调密钥、报表凭据和 App 发布档案，但“广告对账”和“原生 APK 构建”在概念上容易被混为一谈。本设计明确：

1. 广告消费、回调验签、收益分成和广告对账全部由后端独立完成，不触发 APK 构建。
2. 管理员在 SaaS 的代理商管理页点击“构建 APK”后，系统才为指定租户创建一次构建任务并调用 CI。
3. 数据库是每个租户的配置唯一来源；构建使用不可变快照，避免构建过程中配置变更造成包内容不一致。
4. 密钥只在后端或构建机的短暂内存/临时文件中解密，前端、日志、GitHub workflow 输入和构建产物中不出现服务端密钥。

## 2. 范围

### 包含

- 按租户保存原生构建资料：包名、应用名称、版本号、API HTTPS 地址、穿山甲设置文件、Taku 客户端 App Key、签名文件及签名参数、运行时公钥和协议版本。
- SaaS 中的构建按钮、权限校验、重复构建保护、任务状态和 APK 产物记录。
- 后端生成租户配置快照并触发 `skit-saas-app` 的生产构建 workflow。
- CI 从后端一次性领取指定快照的构建资料，构建完成后回写状态、SHA-256 和产物地址。
- 失败重试、超时回收、审计记录和租户隔离。

### 不包含

- 不因为广告对账、广告回调、分成比例或报表凭据变化自动构建 APK。
- 不把 Taku App Secret、Publisher Key、回调密钥、奖励密钥、报表凭据或签名密码打入 APK。
- 不在本次工作中重写现有广告会话、奖励验证和对账算法；只复用其已有的租户隔离和加密凭据能力。

## 3. 方案选择

### 方案 A：每个租户维护 GitHub Environment Secrets

实现简单，但数据库与 GitHub 保存两份配置，容易产生漂移，不能满足“数据库是唯一来源”。不采用。

### 方案 B：后端生成快照，CI 使用 OIDC + 一次性领取接口（推荐）

SaaS 只向 GitHub Actions 传递不可猜测的 `buildJobId`。workflow 使用 GitHub OIDC 身份向后端换取一次性领取凭据；后端验证仓库、分支、workflow 和租户任务后，只返回该快照所需资料，领取后立即失效。数据库保持唯一配置源，且不会把服务端密钥写入 GitHub Secrets 或 workflow 输入。推荐此方案。

### 方案 C：SaaS 服务器本地构建

部署简单，但会把 Android/HBuilderX 构建依赖、签名文件和资源放进业务服务器，影响服务稳定性和权限边界。不采用。

## 4. 系统设计

### 4.1 数据模型

保留现有 `skit_ad_account`、回调密钥版本表、奖励密钥版本表、报表凭据版本表和 `skit_app_release_profile`，不复制广告账号数据。新增两个租户隔离表：

#### `skit_app_build_material`

每个租户可有多个版本，但只有一个 `ACTIVE` 版本可被新任务引用。字段包括：

- `tenant_id`、`material_version`、`status`、`reason`、`verified_at`；
- 非密文：API HTTPS 地址、包名、应用名称、原生 `versionCode`/`versionName`、运行时协议版本、公钥指纹；
- 密文：穿山甲 `SDK_Setting.json`、Taku 客户端 App Key、发布 keystore、store password、key alias、key password；
- 每个密文保存 `ciphertext`、`nonce`、`encryption_key_id`、`envelope_version`，应用层使用现有加密框架；
- 所有唯一键和外键都带 `tenant_id`，禁止跨租户引用。

服务端 Taku App Secret、Publisher Key、回调密钥和报表凭据仍使用现有广告账号/版本表，绝不复制到构建资料。

#### `skit_app_build_job`

一次点击对应一个任务，保存：

- `tenant_id`、`job_id`、`material_version`、广告账号版本、发布档案版本；
- `status`：`QUEUED`、`DISPATCHED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`EXPIRED`；
- 请求人、变更原因、GitHub workflow/run id、源代码 commit、构建版本；
- APK artifact 名称、下载地址、SHA-256、大小、开始/结束时间、错误码和脱敏错误信息；
- 一次性领取 token 的哈希、过期时间、领取时间和回写 token 哈希。

任务创建时把所有引用版本锁定为快照；之后修改广告账号或发布档案不会改变正在构建的任务。

### 4.2 SaaS 操作流程

1. 管理员打开代理商的“App 发布”页，查看当前构建资料和最后一次构建结果。
2. 点击“构建 APK”，填写构建版本/变更原因；后端校验租户权限、资料完整性、HTTPS、版本号单调递增和当前是否已有活动任务。
3. 后端事务内创建 `skit_app_build_job`，锁定资料版本并写入审计记录。
4. 后端只向 GitHub workflow 传递 `jobId`，不传递任何密钥。
5. 页面轮询任务详情或通过现有 WebSocket 更新状态，成功后显示 artifact 下载地址和 SHA-256。

权限建议：`super_admin` 可构建任意租户；`tenant_admin` 只能构建自己绑定的租户；普通成员无构建权限。

### 4.3 CI 领取与清理

workflow 使用 `id-token: write` 获取 OIDC token，调用后端领取接口。后端验证 OIDC 的 repository、ref、workflow 和 `jobId`，确认任务状态仍为 `DISPATCHED` 且 token 未领取，然后返回一次性构建资料。

CI 将资料写入 `$RUNNER_TEMP`，权限设为 `600`，执行现有 APK 构建和校验脚本。任何日志都只打印版本、租户编码、哈希和状态，不打印密钥或文件内容。`always()` 清理临时文件；领取超时或构建超时由后端任务回收器标记失败。

### 4.4 广告对账边界

对账调度器直接从租户广告账号和报表凭据版本表加载密文，在服务端解密后调用平台 API；对账结果写入现有报表拉取、重算和收益分配表。整个过程不读取 `skit_app_build_job`，也不创建构建任务。

## 5. API 与界面

新增管理接口（均执行租户范围校验）：

- `GET /skit/tenant/app-build/material?tenantId=...`：返回非敏感资料和“已配置”状态；
- `PUT /skit/tenant/app-build/material`：写入新资料版本，密钥字段只写不读；
- `POST /skit/tenant/app-build/jobs`：创建构建任务；
- `GET /skit/tenant/app-build/jobs`：分页查看当前租户构建历史；
- `GET /skit/tenant/app-build/jobs/{jobId}`：查看状态、错误、产物信息；
- 内部领取/回写接口：仅接受 GitHub OIDC 或一次性任务凭据，不开放给浏览器。

“App 发布”页保留热更新档案，同时新增“原生构建资料”和“构建 APK”区域。敏感字段显示“已配置”，保存后清空输入框；页面不显示任何原值。

## 6. 错误处理与安全约束

- 资料不完整时不创建任务，明确返回缺失项，例如 API 非 HTTPS、签名参数缺失、Taku 客户端 App Key 未配置。
- 同一租户只能有一个 `QUEUED`/`DISPATCHED`/`RUNNING` 任务；重复点击返回已有任务。
- CI 领取 token 只能使用一次、短时有效、绑定租户和 job；数据库只保存 token 哈希。
- 所有构建和资料变更写入现有管理审计；错误信息脱敏，禁止回显密文或 OIDC token。
- 密钥轮换通过新版本写入和重新验证完成，旧版本按接受窗口保留，不能删除仍被历史任务引用的版本。

## 7. 测试与验收

### 后端

- 租户权限、重复任务、版本锁定、资料完整性和 HTTPS 校验测试；
- OIDC 领取接口：错误仓库、错误租户、过期 token、重复领取均拒绝；
- 对账调用不会创建构建任务；
- 密文只写不读，API 响应不包含原值。

### 前端

- 资料保存后只显示“已配置”；
- 构建按钮展示确认、排队、运行、成功/失败状态；
- 代理商只能看到自己的构建历史；
- 失败信息可读但不泄露敏感值。

### CI/端到端

- 使用测试租户触发一次 workflow，验证领取、构建、回写、artifact SHA-256 和临时文件清理；
- 修改广告对账凭据并运行对账，确认不会出现新的构建任务；
- 同一快照构建结果可复现，配置变更只影响后续新任务。

