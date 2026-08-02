# 广告收益与多租户分成发布手册

本手册用于发布服务端、管理后台和 App 的服务端验真广告链路。Taku 展示收益
S2S 第一阶段仅采集账号级原始事实，不会解锁内容、生成租户收益或进入分成账本。任何
代码发布都不会自动为租户开启广告解锁；新租户和存量租户必须保持 `OFF`，直到奖励
验真、展示归因、报表和新 APK 各自满足对应放量门槛。

## 日常更新路径

| 变更 | 执行入口 | 是否重启后端 | 是否重发 APK |
|---|---|---:|---:|
| SaaS 后端、数据库迁移 | 后端 `Backend CI/CD` | 是，滚动替换后端容器 | 否 |
| 管理后台页面 | 前端 `Frontend CI/CD` | 否，只替换 Nginx 前端容器 | 否 |
| 页面、会话编排等 Web 代码 | App `App hot update bundle` | 否 | 否，发布签名热更新包 |
| Taku/DJX 原生桥、SDK、签名公钥或协议大版本 | App `Android production APK` | 否 | 是，每个代理商构建一次新基础包 |

普通发布不得旋转广告凭据加密密钥、会话签名密钥、provider callback payload 密钥、
provider callback audit HMAC 密钥或热更新信任根。后端激活脚本会在首次发布生成并
持久化服务端密钥，后续发布复用现有值；旧 provider payload 密钥至少保留 7 天。
信任根轮换必须作为独立维护操作，并先发布包含新公钥的基础 APK。

## 发布前配置

### 后端仓库

GitHub Secrets 至少包含：

- `SERVER_HOST`、`SERVER_USER`、`SERVER_SSH_KEY`；
- `MYSQL_ROOT_PASSWORD`；
- 生产环境需要的可选端口及 `SERVER_SUDO_PASSWORD`（仅服务器确实需要时）。

热更新信任根不属于后端仓库级配置。每个代理商的 RSA 公钥只保存在其 SaaS App 发布档案中，由后端按当前租户返回公钥与指纹；后端 Secret、Variable 和 `.env` 均不得配置全局热更新公钥。

### App 仓库

每个发布档案必须使用独立的 GitHub Environment `android-production-${profile_code}`。`profile_code` 必须是 SaaS 发布档案中的稳定代码；该 Environment 只保存对应代理商的 Taku、穿山甲、APK 签名和热更新签名私钥等 Secret。与私钥匹配的至少 2048 bit RSA 公钥写入同一 SaaS 发布档案，不得提升为仓库级或后端全局配置。工作流输入中的租户标识必须等于 SaaS 中该代理商绑定的规范租户标识，不能使用昵称或临时展示名称。

热更新包必须发布到 HTTPS 地址；清单中的租户、应用 ID、协议版本、发布序号、ZIP SHA-256 和 URL 必须与后台保存的发布档案完全一致。

## 后端发布

1. 合并并推送后端代码，等待 `Backend CI/CD` 的单元测试、MySQL 8 集成测试、部署拓扑和密钥校验全部通过。
2. 激活脚本拉取不可变镜像标签，启动 MySQL/Redis，再启动 `runtime,prod` 后端。MySQL、Redis 和后端端口只绑定 `127.0.0.1`；公网请求只能通过前端 Nginx。
3. 后端启动时串行执行带 checksum 的 Skit 迁移并校验关键表、索引、外键、约束和触发器。任何不兼容漂移会令启动失败；不得为了上线手工跳过校验。
4. 工作流只在 `/actuator/health` 精确返回 `{"status":"UP"}` 后成功。失败时先检查容器日志和 schema 错误，不得清库重试。
5. 部署完成后确认所有租户的 rollout 状态仍为 `OFF` 或原来的 `SHADOW_TEST_USERS`，不得由部署脚本自动升到 `ENFORCED`。

首次创建数据库时，Compose 的初始化 SQL 会建立完整基线；存量数据库由应用迁移器升级。不要对存量库直接整包导入 bootstrap SQL。

## 前端发布

1. 推送前运行 Vitest、产品菜单回归、TypeScript 检查、lint 和生产构建。
2. 前端工作流只拉取并重建 `frontend` 服务，不执行 `docker compose up` 全栈命令，也不重启后端。
3. 发布后分别使用 `super_admin` 和代理商管理员验证：
   - 顶级菜单只有“首页”和“短剧 SaaS”；
   - `super_admin` 可看全代理商汇总并筛选单租户；
   - 代理商管理员看不到租户选择器，所有数据固定为其登录绑定租户；
   - 接口失败显示错误，空数据才显示零，跨币种按币种分组。

## App 基础包与热更新

### 新基础 APK

只有原生 SDK、原生桥、应用签名、热更新公钥或协议大版本改变时才运行 `Android production APK`。为每个代理商传入其发布档案标识、递增的 Android `versionCode`、版本名和递增的 runtime release number。工作流必须通过：

- Taku/穿山甲真实配置检查；
- Android 单元测试与 release 构建；
- APK 签名证书指纹、应用 ID、HTTPS API、协议、公钥和无本地奖励兜底检查。

APK 先只分发给该租户的影子测试会员。后台提升最低原生版本之前，必须确认新包已安装、旧包拒绝策略已验证。

### 日常热更新

1. 运行 `App hot update bundle`，提供代理商标识、显示版本、严格递增的 `release_no` 和最终 HTTPS URL。
2. 上传 ZIP 与 `.manifest.json` 到该 URL 对应的发布存储；下载文件并重新计算 SHA-256，必须与清单一致。
3. 在代理商发布档案中保存同一 URL、SHA-256、release number、协议版本和签名。服务端会再次验签并拒绝跨租户、跨应用、降级或重放的清单。
4. 先让影子测试设备拉取、校验和激活，再扩大分发。普通热更新不重启后端，也不需要重发基础 APK。

## Taku 账号级展示收益回调（Phase 1）

### 已确认的供应商边界

Taku 商务已确认以下事实：

- 展示收益 S2S 权限和回调地址都是账号级；账号接入后，后续新增应用自动生效；
- 同一账号只能配置一个统一展示收益回调地址，不能为不同应用配置不同地址；
- 该地址不能通过 Open API 查询、修改或轮换，必须由 Taku 商务/控制台人工处理；
- 统一回调是否稳定回传 `package_name`、`placement_id`、`show_custom_ext` 仍待 Taku
  书面确认。没有书面字段合同前，不能宣称已具备应用或租户归因能力。

因此，一个 Taku 账号只能领取并长期使用一个账号路由 URL，不能按租户、应用或广告位
重复生成。共享 Taku 账号下新增应用沿用该 URL。奖励 S2S 的验真和解锁规则是另一条
安全边界，不能从展示收益回调推导奖励或权益。

### 生产 URL 发放门禁

当前仓库的 Compose 是单主机、单后端、单 MySQL、单 Redis，只允许完成真实
capture-only 或 `GATE_TEST` 验证，不具备生产 URL 发放资格。运行：

```bash
./deploy/test-provider-impression-callback-gate.sh \
  --environment production-equivalent --draft-connection-id <connection-id>
```

必须精确失败为：

```text
FAIL: required callback topology is single-host/single-backend; production key issuance is blocked
```

未来达到生产拓扑后，运维在离线签名机生成严格有序、ASCII、LF 结尾且不超过 8192
字节的清单，并用至少 2048 bit RSA 私钥执行 RSA-SHA256 签名。清单最多有效 15 分钟，
签发时间最多领先服务器 60 秒，且必须精确绑定 environment fingerprint、
`provider_connection_id`、`provider_route_id`、HTTPS accepted origin、callback path/template
version 和 key-independent callback contract fingerprint。以下 13 项证据缺一不可，每项值
都是对应不可变证据文件的 SHA-256：

1. `https_route`
2. `inbox_attempt_200`
3. `unknown_key_602`
4. `log_redaction`
5. `db_failpoint_503`
6. `load_p99`
7. `accepted_origin_contract`
8. `dual_entry`
9. `two_backend_instances`
10. `mysql_ha`
11. `redis_degradation`
12. `dns_cert_backup`
13. `key_custody`

签名私钥只存在于离线运维签名环境，禁止进入仓库、镜像、GitHub Actions、服务器、
数据库、日志或 API。部署仅临时接收以下四个 GitHub Secrets：

- `SKIT_PROVIDER_IMPRESSION_GATE_ENVIRONMENT_FINGERPRINT`
- `SKIT_PROVIDER_IMPRESSION_GATE_OPERATIONS_PUBLIC_KEY`（X.509 DER 的规范 Base64）
- `SKIT_PROVIDER_IMPRESSION_GATE_MANIFEST_BASE64`
- `SKIT_PROVIDER_IMPRESSION_GATE_SIGNATURE`

工作流把四项值写入 mode `0600` 的 run-scoped `server.env`；激活脚本读取后立即删除
该文件，再在 mode `0700` 的稳定目录中创建 mode `0600` runtime properties，通过
只读挂载交给 Spring。后端完成健康验证后，无论成功或失败都删除 properties 文件，
四项证据绝不写入持久 `.env`。容器以后在没有该文件时重启仍可安全采集，但任何
issue/submit 都必须失败关闭。服务端在每一次 issue 和 submit 前重新验证签名、时效及
全部绑定，不能缓存一次成功结果。

### 单次领取、交付与提交

1. `super_admin` 使用当前密码重新认证，选择仍为草稿态的账号 connection/route；
2. 服务端通过短期签名门禁后只显示一次完整 URL，响应和页面使用 `no-store`，随后仅保留
   callback key hash、路由合同 fingerprint 和审计事实；
3. 运维只通过批准的单一保密通道将 URL 交给指定 Taku 商务，不复制到工单正文、群聊、
   日志、截图或导出；
4. 只记录工单号、接收人、交付时间、URL fingerprint 和证据 fingerprint，不记录完整
   URL 或 callback key；
5. 在同一清单 TTL 内标记 submitted。提交前服务端再次验签；失败不得修改数据库状态。

因为 Open API 无法查询、修改或轮换账号回调地址，不能自动“读回”证明 Taku 已保存，
也不能在事故中静默换 URL。生产交付后需由 Taku 商务书面确认已配置；若 URL 疑似泄露，
立即 block connection/route，保留不可变 Inbox/Attempt 和审计证据并告警，再与 Taku
商务协调人工更换。不得删除历史事实或伪造已轮换状态。

### Capture-only 验收与数据用途

- 公网入口对 callback route 关闭 access/error query 输出，覆盖客户端代理头，并限制为
  64 KiB request target/header、250 ms connect/send、1 s upstream read、无重试、无请求体
  和无缓冲；
- 应用在 2 秒供应商预算内先提交 Inbox 与 Attempt 再返回 `200`；未知 callback key 返回
  `602`，数据库写入失败返回 `503` 并告警；同一事实按账号 connection、`req_id`、
  `adsource_id` 幂等处理；
- payload 使用独立 provider AES key 加密，审计标识使用独立 HMAC key；旧 AES key在轮换
  后至少保留 7 天。明文 query、完整 URL、callback key、签名、设备标识和代理地址不得
  进入应用日志或告警；
- Phase 1 只保存 observation/quarantine 事实，用于验证回调可达性、幂等性和供应商字段。
  在 `package_name`、`placement_id`、`show_custom_ext` 获得书面合同并完成交叉校验前，
  禁止写租户收益、代理商分成、结算报表或内容权益，也禁止用客户端上报的租户身份补齐
  账号级回调归属。

Taku 报表仍按账号串行拉取并遵守平台限流；失败形成不可变 pull 事实并按退避策略重试。
报表和每日汇总只能用于后续对账，不能反向修改 Phase 1 原始 Inbox/Attempt，也不能替代
逐次展示的供应商字段确认。

## 监控与告警

至少监控：回调入口延迟和错误率、未知 callback key、未匹配展示、数据库 `503`、收件箱
积压、重复/冲突事实、客户端奖励但无奖励 S2S、报表失败/过期、对账 suspense 和调整金额。
Phase 1 metric tags、结构化日志和 retention job 日志只允许低基数字段：
`provider=TAKU`、callback/route type、稳定 decision/error category、count 和 timestamp；
不得携带 provider connection/route、账号、Inbox/Attempt、`req_id`、`adsource_id` 或租户
ID。调查人员通过受控、审计的数据库查询关联不可变事实。告警同样不得包含完整 URL、
callback key、Publisher Key、请求签名、原始 query、设备标识、代理地址或解密后的
payload。

## 回滚与故障处理

- 后端或前端代码回滚使用上一不可变镜像标签；数据库采用前向兼容迁移，不执行降级 DDL，不清库。
- 热更新故障发布一个更高 `release_no` 的修复包；客户端会拒绝旧序号，不能靠重传旧 ZIP 回滚。
- 原生故障发布更高 `versionCode` 的基础 APK，并保持租户 `OFF`/影子模式，直到新包验证完成。
- 报表失败可重试幂等任务；回调、报表、收益事件、对账 revision 和账本均不可编辑或删除。
- 只有确认安全事件时，`super_admin` 才能通过带原因、全审计的专用命令撤销权益；普通财务降额不撤销已授予的内容权益。

真实 Taku/Pangle/DJX 凭据、控制台配置、HTTPS 域名和新 APK 安装属于外部放量前提。代码和 CI 通过不等于某个租户已具备 `ENFORCED` 条件。
