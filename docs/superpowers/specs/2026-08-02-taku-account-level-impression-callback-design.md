# Taku 账号级展示收益回调设计

日期：2026-08-02

状态：已完成方案确认，等待书面设计复核；尚未实施、部署或向 Taku 提交真实地址

关联核查：[Taku 广告展示收益 S2S 回调核查](./2026-08-02-taku-impression-revenue-s2s-findings.md)

## 决策摘要

当前 Skit 使用的 Taku 共享主账号只签发一个长期固定的展示收益回调地址：

```text
https://www.yunque8.top/app-api/skit/ad-callback/taku/{connectionKey}/impression
```

这里的 `connectionKey` 代表一个外部 Taku 账号连接，不代表租户、应用或广告位。当前只有一个共享主账号，因此只需要一个真实地址；以后只有新增独立 Taku 外部账号时，才为新账号连接签发另一个地址。

这个地址只接收“每次展示的预估收益观察”。它与现有按租户广告账号配置的激励回调完全分离，绝不解锁剧集、发放奖励或直接形成最终结算金额。

Taku 商务已确认账号级开通、新增应用自动覆盖、一个账号统一一个地址，以及地址不能通过 Open API 查询、修改或轮换。`package_name`、`placement_id`、`show_custom_ext` 的必达性和展示回调鉴权方式仍未知。为尽快提供地址，这些未知项不阻塞捕获入口上线；它们只阻塞可信归属、资金分配和授奖。

## 背景与已确认约束

### Taku 协议边界

- Taku 以 `GET` 请求开发者地址。
- 接收端必须在 2 秒内返回 HTTP 200；失败后 Taku 不重试。
- 官方要求用 `(req_id, adsource_id)` 去重。
- `adsource_price` 是 eCPM，单次展示估算值是 `Decimal(adsource_price) / 1000`。
- 官方参数表列出 `package_name`、`placement_id`、`show_custom_ext`，但没有声明必传性、空值规则或各广告形式与广告源的覆盖范围。
- 公开文档没有声明展示回调专用签名、鉴权 Header、来源 IP 段或 mTLS。

### 账号配置边界

- 展示收益 S2S 权限是账号级。
- 账号接入后，新增应用自动覆盖。
- 一个账号只能配置一个统一地址，不能按应用配置不同地址。
- 账号级地址不能通过 Open API 查询、修改或轮换，需要由 Taku AM 人工配置和协调紧急变更。

### Skit 当前实现边界

现有路径形状可以复用，但现有租户回调身份和展示解析流程不能直接复用：

- `SkitCallbackRoutingService` 先把路径 Key 解析为唯一的 `tenantId + adAccountId`，不适合一个账号覆盖多个租户。
- `SkitTenantAdCapabilityController` 当前由租户维度轮换回调 Key，并把同一 Key 用于奖励和展示地址；账号级展示地址提交后不能继续这样轮换。
- `SkitAdSchemaDdl` 中现有回调 Key 和 Inbox 都要求非空租户与广告账号，无法表达共享账号的未归属展示。
- `TakuCallbackCanonicalizer` 当前围绕激励视频会话设计：要求包名、广告位和 `show_custom_ext`，只接受 `adformat=1`，也拒绝未知参数；账号级展示捕获必须接受五种广告形式和协议扩展。
- `RedisSkitCallbackRateLimiter` 当前每 Key 每分钟 120 次，聚合整个账号的展示后明显不再是正确容量模型。
- 现有回调事务把 2 秒作为数据库事务超时，没有为网络、代理和响应留出安全余量。

因此，新入口必须在同一路径外观下增加独立的“账号连接”身份域和全局 Inbox，不能让一个虚拟租户承接其他租户的数据。

## 目标

1. 尽快生成一个可长期交给 Taku 的真实 HTTPS 地址。
2. 对有效账号连接的合法大小请求，先可靠持久化，再于 2 秒内返回 200。
3. 在字段和鉴权保证未知时仍不丢原始证据，并把不确定数据隔离。
4. 用服务端拥有的账号、应用、广告位、格式、广告源和展示上下文交叉归属到租户。
5. 记录单次展示估算收益，并与 Taku 后续报表进行可审计对账。
6. 保持激励回调、内容解锁和奖励凭据完全独立。
7. 让回调 URL 在处理器暂停、字段变化或归属逻辑故障时仍能退化为安全的“只捕获”模式。

## 非目标

- 不实现或假设存在展示回调配置 Open API。
- 不为同一共享 Taku 账号按应用或租户生成多个地址。
- 不把 URL 中的随机 Key 当作 Taku 来源的密码学证明。
- 不用展示收益回调授予内容权益或替代签名奖励回调。
- 不把展示估算值描述为最终结算、可提现余额或已收款收入。
- 不要求第一版立刻完成所有租户、会员、代理佣金的归属；无法证明的事件必须允许停留在隔离区。
- 不在本设计中改变 Taku SDK 广告展示或原生播放器接入。

## 方案选择

| 方案 | 结论 | 原因 |
| --- | --- | --- |
| 每个外部 Taku 账号一个带高熵 `connectionKey` 的固定地址 | 采用 | 与 Taku 的账号级配置一致；当前共享主账号只需一个地址，同时保留未来独立账号的隔离边界。 |
| 不带 Key 的全局固定地址 | 拒绝 | 无法在入口识别合法连接，任何人都可无成本灌入数据；也不能支持未来多个外部账号。 |
| 复用现有租户广告账号 Key | 拒绝 | 一个 Key 只能先解析出一个租户，无法安全承接账号下全部应用；租户侧轮换还会破坏已经交给 Taku 的地址。 |
| 每个应用或租户一个地址 | 拒绝 | Taku 已明确同一账号不能分别配置，生成更多地址也不会被调用。 |

“一个就可以”严格限定为“一个外部 Taku 账号一个”。当前共享主账号是一个外部账号，所以当前答案确实是一个；如果未来某租户使用 `TENANT_OWNED` 独立账号，它是另一个外部连接，必须使用另一个 Key 和地址。

## 公网协议

### 固定地址

```http
GET /app-api/skit/ad-callback/taku/{connectionKey}/impression
```

生产完整形状：

```text
https://www.yunque8.top/app-api/skit/ad-callback/taku/{connectionKey}/impression
```

交给 Taku AM 的模板在路径中填入真实 `connectionKey`，并保留 Taku 支持的宏参数：

```text
https://www.yunque8.top/app-api/skit/ad-callback/taku/{connectionKey}/impression?user_id={user_id}&req_id={req_id}&package_name={package_name}&adformat={adformat}&placement_id={placement_id}&nw_firm_id={nw_firm_id}&adsource_id={adsource_id}&adsource_price={adsource_price}&currency={currency}&timestamp={timestamp}&show_custom_ext={show_custom_ext}
```

真实 `connectionKey` 不写入源码、文档、Git、工单、普通日志或常规查询 API。只有签发响应展示明文一次，随后数据库只保留不可逆哈希和短指纹。

Taku 公开参数还包含地域、IP 和设备标识。第一版不要求把所有字段逐个放进配置模板；服务端必须容忍 Taku 追加任何符合通用安全扩展语法的已知或未知参数，并通过受限加密原文捕获观察实际投递。

### HTTP 响应

| 条件 | 行为 |
| --- | --- |
| Key 不存在、类型不匹配或已阻断 | 沿用现有过滤器的确定性非成功状态 `602`，不保存原始查询内容。 |
| 方法不是 GET 或路径形状非法 | 返回同一个 `602`，不进入 Inbox。 |
| 请求超过硬性字节、参数数量或单值长度上限 | 返回同一个 `602`；只记录不含 Key 和参数值的低基数拒绝指标。 |
| Key 有效且请求在大小边界内，包括字段缺失、未知或格式错误 | 先持久化为 Inbox/隔离数据，再返回 200。 |
| 完全重复投递 | 确认已有持久化记录后返回 200。 |
| 官方去重键相同但载荷不同 | 持久化冲突 Attempt、隔离规范事件并返回 200。 |
| 数据库提交失败 | 返回 503 并告警，绝不在未持久化时虚假返回 200。 |

响应 Body 使用固定短文本或空响应，不回显参数、解析错误、连接状态或内部 ID。入口内部 p99 目标不高于 250ms，为 Taku 的 2 秒总预算保留代理和网络余量。

## 账号连接与 Key 生命周期

### 连接模型

新增全局 `skit_ad_provider_connection`，一行稳定表示一个外部广告平台账号；人工换址也不改变这个 ID。最少包含：

- `id`
- `provider`：当前为 `TAKU`
- `account_mode`：`SHARED_MASTER` 或 `TENANT_OWNED`
- `owner_tenant_id`：共享主账号为空；租户自有账号必须非空
- `external_account_ref`：外部账号引用，按敏感度哈希或加密保存
- `connection_status`：`CONFIGURING`、`ACTIVE`、`MIGRATING`、`BLOCKED`、`RETIRED`
- `active_callback_route_id`
- `report_timezone`、`report_currency_policy`、`amount_scale`：首版金额 scale 固定为 8
- 当前报表凭据版本引用，以及连接级 report pull 的 lease、cursor、backoff 和最近成功时间
- `activated_at`、`blocked_at`
- 创建、更新和操作者审计字段

报表 App Key/Secret 等原文放在独立、版本化、envelope-encrypted 的连接凭据表中，所有读取均为内部 write-only credential boundary；连接表只保存当前版本引用。报表调度也以 `provider_connection_id` 抢占 lease，同一个外部账号同时只能有一个活跃拉取器，不能再让每个租户广告账号各拉一次共享账号报表。

另建不可变、版本化的 `skit_ad_provider_callback_route` 保存真实 URL 生命周期：

- `id + provider_connection_id + callback_route_version`
- `callback_route_registry_id + callback_key_fingerprint`
- 不含 Key 的 `canonical_origin`、`callback_path_version`、`callback_template_version`、`callback_origin_fingerprint`、`callback_contract_fingerprint`
- `route_status`：`DRAFT`、`ISSUED`、`SUBMITTED`、`ACTIVE`、`BLOCKED`、`ABANDONED`、`RETIRED`
- `route_slot`：`PRIMARY_ACCEPTING`、`MIGRATION_TARGET` 或 `INACTIVE`
- `supersedes_callback_route_id`、人工迁移状态与证据元数据
- `issued_at`、`submitted_at`、`activated_at`、`blocked_at`、`retired_at`

数据库 CHECK 必须保证 `SHARED_MASTER` 的 owner 为空、`TENANT_OWNED` 的 owner 非空，并限制连接、route 的合法状态组合与时间字段。生成列唯一约束保证当前只有一个非终态的 `TAKU + SHARED_MASTER` 连接；`BLOCKED` 连接仍占用唯一槽位。每个连接通常只有一个接收中的 route；只有连接显式进入 `MIGRATING` 时，才允许最多一个带 `supersedes_callback_route_id` 的替代 route 与旧 route 短时并存，唯一约束禁止第三条接收 route。未来的 `TENANT_OWNED` 连接按外部账号与租户分别唯一。

route 用两个 generated slot key 分别唯一约束每个连接最多一个 `PRIMARY_ACCEPTING` 和一个 `MIGRATION_TARGET`，并对 `supersedes_callback_route_id` 建唯一键。切换事务锁住 provider connection，原子地把旧 route 置为 `RETIRED/INACTIVE`、目标 route 提升为 `ACTIVE/PRIMARY_ACCEPTING`、更新 `active_callback_route_id` 并把连接从 `MIGRATING` 恢复为 `ACTIVE`。

旧、新 route 都解析到同一个 `provider_connection_id`，所以人工切换窗口的官方幂等键、报表凭据和拉取 lease 仍共享同一账号命名空间，不会因为两个 URL 把同一展示记两次。

### 版本化归属绑定

当前仓库没有稳定的 `skit_app` 实体，`skit_app_release_profile` 是可变化的发布档案，不应直接充当广告平台 App 身份。先新增 `skit_tenant_application`：`id + tenant_id + application_code + lifecycle_status`，并为当前每个有效 release profile 幂等迁移一条应用；release profile 后续通过 `tenant_id + application_id` 组合外键归属到该稳定应用。

新增不可变的 `skit_ad_provider_binding_snapshot`，把外部账号库存映射到现有租户域。每个快照至少冻结：

- `provider_connection_id`
- `tenant_id + ad_account_id + tenant_application_id`
- `package_name + provider_app_id`
- 版本化逻辑广告位、`placement_id + adformat`
- `network_firm_id + network_account_id + adsource_id`
- `config_revision + config_fingerprint + valid_from + valid_until`

绑定表必须用组合外键证明 App、广告账号、广告位和广告源属于同一租户。`TENANT_OWNED` 连接的 `owner_tenant_id` 必须和绑定租户一致；`SHARED_MASTER` 才能有多个租户绑定。

MySQL 没有时间区间排斥约束，因此另建稳定的 binding identity 行。当前有效快照通过 generated active identity key 唯一；变更事务先 `SELECT ... FOR UPDATE` 锁 identity，验证不存在与新 `[valid_from, valid_until)` 重叠的历史区间，关闭旧快照，再插入新快照。迁移 preflight 用窗口/自连接审计所有历史重叠，发现任何重叠就停止切读。

配置变更总是创建新快照并关闭旧快照，不原地改写。回调首次成功归属时把 `binding_snapshot_id + revision + fingerprint` 固化到独立归属记录，后来配置变化只能生成追加式重新归属修订，不能用新配置覆盖解释历史展示。

服务端展示上下文必须直接快照 `provider_connection_id + binding_snapshot_id`，worker 以该不可变引用选择历史配置。没有可信展示上下文时，只有满足以下条件才能降级为广告位归属：平台时间通过漂移检查，并且该连接下 `package_name + placement_id + adformat` 从未跨租户/应用复用。停用映射保留 tombstone，禁止把同一身份重新分配给另一个租户；否则事件保持 `UNATTRIBUTED`，不能按 worker 运行时的“当前配置”解释旧回调。

### Key 命名空间

新 Key 保持现有日志清洗规则兼容的 43 个 URL-safe 字符，但使用类型前缀：

```text
acct_{38 个 Base64URL 随机字符}
```

- 总长度恰好 43，字符集满足 `[A-Za-z0-9_-]{43}`。
- 随机部分必须从 CSPRNG 取得恰好 228 个随机 bit，再按每 6 bit 映射一个 Base64URL 字符；禁止用 `% 64` 或少于 228 bit 的输入制造取模偏差或虚标熵。
- `acct_` 是新 Key 的可识别类型提示，现有租户 Key 生成器以后禁止该前缀；它不是数据库路由依据。
- 新增全局 `skit_ad_callback_route_registry`，以 `key_hash` 为主键/唯一键，保存 `route_type=TENANT_CALLBACK_KEY | PROVIDER_CALLBACK_ROUTE` 和唯一 owner 引用；CHECK 保证两种 owner 只能二选一，provider route 再指向稳定的账号连接。
- 迁移直接把现有 `skit_ad_callback_key.callback_key_hash` 回填为 `TENANT_CALLBACK_KEY`，不需要也不能尝试从哈希反推旧 Key 是否以 `acct_` 开头。
- 新账号 Key 的 registry 行与 provider callback route 在同一事务创建；明文查找先哈希，再只查询一次 registry，随后按 `route_type` 分派，禁止“查账号表失败再查租户表”。
- 迁移后所有租户 Key 新建、轮换、撤销也必须在同一事务维护 registry；已撤销哈希保留 tombstone，不能重新分配给另一 owner。

该 Key 的作用是连接选择和抗随机灌入，不是 Taku 身份签名。即使 Key 正确，事件证据等级仍是 `UNSIGNED_PROVIDER_OBSERVATION`。

同一路径下的兼容分派必须是确定性的：

| Key 与 endpoint | 处理器 |
| --- | --- |
| registry 为 `PROVIDER_CALLBACK_ROUTE` 且 endpoint 是 `/impression` | 新账号连接 Inbox。新签发 Key 的明文外观为 `acct_...`。 |
| registry 为 `PROVIDER_CALLBACK_ROUTE` 且 endpoint 是 `/reward` | 固定拒绝；账号连接 Key 永远不能进入奖励链路。 |
| registry 为 `TENANT_CALLBACK_KEY` 且 endpoint 是 `/reward` | 保持现有租户奖励处理。 |
| registry 为 `TENANT_CALLBACK_KEY` 且 endpoint 是 `/impression` | 迁移期保持兼容并统计使用量，但不能作为共享主账号的新配置地址。 |

路由注册表让现有只存哈希的历史 Key 也能无歧义迁移；前缀只帮助新签发地址人工识别，不决定身份。所有 lookup miss 使用同一响应，避免因 owner 类型或查询顺序形成枚举信号。

registry 必须零停机迁移：

1. 先建 registry，旧读取路径保持不变。
2. 部署租户 Key 新建/轮换/撤销的幂等双写，失败时整个凭据事务回滚。
3. 分批回填所有存量 Key 哈希与 owner，并保留撤销 tombstone。
4. 比对原表/registry 的 owner、行数、哈希全集和活跃状态，差异非零就停止。
5. 再切换为 hash-first registry 读取，并观察旧/新结果 shadow comparison。
6. 最后加约束，禁止任何 Key owner 没有 registry 行；移除旧的直接读取分支。

### 签发与不可轮换规则

1. 超级管理员创建稳定账号连接及不含 Key 的 `DRAFT` callback route；只有 DRAFT route 可以直接丢弃。
2. 系统给该 route 签发一次 Key、返回一次完整地址，并在同一事务中进入 `ISSUED`；同一 route 不提供“再次显示”或“重新签发”。
3. 如果地址确定从未离开 Skit，超级管理员可以在二次确认并记录“从未共享”声明后把 `ISSUED` route 置为 `ABANDONED`，再在同一连接下创建新 route；不能覆盖旧记录。
4. 管理员把地址交给 Taku 后，立即把 route 标记为 `SUBMITTED`。提交证据只能保存 Taku 工单号/收件方、时间、操作者、Key 哈希指纹和 origin 指纹；禁止上传或保存包含完整 URL 的邮件、截图、附件或正文。
5. 收到第一条真实回调只是激活证据之一。只有真实 Inbox、Taku AM 配置证据和域名/路径指纹全部核对后，超级管理员才能确认 route 进入 `ACTIVE` 并更新连接的 `active_callback_route_id`；不可信来路的一条请求不能自动激活。
6. `SUBMITTED` 或 `ACTIVE` route 没有“本地轮换”“删除 Key”“重新显示明文”或回到 DRAFT 的操作。
7. 计划内域名/地址迁移且旧 Key 没有泄露时，连接进入 `MIGRATING`：旧 route 保持 `ACTIVE/PRIMARY_ACCEPTING`，AM 同意后创建带 `supersedes_callback_route_id` 的 `MIGRATION_TARGET`，只在受审计窗口同时接受新旧地址。新地址完成真实金丝雀并由超级管理员激活后，旧 route 才进入 `RETIRED/INACTIVE`。
8. 疑似泄露或旧入口已经故障时，旧 route 立即进入 `BLOCKED/INACTIVE`，禁止为了无缝切换继续接受旧 Key；替代 route 仍需 Taku AM 人工配置，但没有双地址重叠期。立即阻断会制造 Taku 不重试的不可恢复缺口，操作界面必须明确提示并记录这一后果。

对 Taku 提交后，部署可以暂停异步处理，但必须尽力保持原地址的 HTTPS、路由和持久化入口可用。

`SUBMITTED` 或 `ACTIVE` route 把 `/taku/{key}/impression` 路径、ordered query macro 集和 scheme/host 固化为部署不变量。route 同时保存不含 Key 的 canonical origin、`callback_template_version` 以及由 Key hash、origin、path version、ordered macro template 共同生成的 `callback_contract_fingerprint`。

`SKIT_AD_CALLBACK_PUBLIC_BASE_URL` 只负责生成新 route；生产另维护 `SKIT_AD_CALLBACK_ACCEPTED_ORIGINS` 集合。启动/activation 必须证明每条接收中的 route 都在 accepted origins 中，并且对应 DNS、证书、Nginx ingress、路径版本和宏模板已经安装；任一不匹配即 fail closed。跨域人工换址时，旧、新 origin 只在 `MIGRATING` 窗口同时存在，旧 route 退休后立即移除旧 origin。长期使用 `www.yunque8.top` 也意味着该域名不能随前端品牌或站点迁移而提前释放。

## 全局展示 Inbox

现有租户 Inbox 继续承接奖励回调和已有租户级语义。账号级展示另建全局表，避免在未知租户时伪造 `tenant_id`。

### `skit_provider_impression_inbox`

每个幂等捕获身份一行，只保存账号级证据，不保存租户投影：

- `id`、`provider_connection_id`
- `dedupe_scheme`：`OFFICIAL_V1` 或 `FALLBACK_WIRE_V1`
- 始终非空的 `dedupe_key_hash`
- `provider_request_id_lexical`、`adsource_id_lexical`：允许为空，使用二进制/大小写敏感语义保存
- `canonical_attempt_id`
- `material_integrity_hash`、`integrity_status`、单调递增的 `integrity_revision`
- `authentication_level`：首版固定 `UNSIGNED_PROVIDER_OBSERVATION`
- `quarantine_reason`、`processing_status`
- `first_received_at`、`last_received_at`、处理租约、重试与死信字段

### `skit_provider_callback_attempt`

每次“有效 Key 且未超硬边界”的实际 HTTP 投递都写一条 Attempt，不允许用计数替代审计行：

- `provider_connection_id`、`inbox_id`
- `wire_payload_hash`、可解析时的 `material_integrity_hash`、`delivery_integrity_status`
- 加密原始 query envelope 及过期时间
- `received_at`、`response_decision` 和安全审计元数据

`response_decision=ACK_200` 只证明数据库提交后服务端决定返回 200，不证明 Taku 已在网络另一端收到响应。未知/阻断 Key 和超硬边界请求只产生脱敏低基数指标，不保存 Attempt 或原文。

Inbox 的 `canonical_attempt_id` 固定指向首次规范投递；worker 从该 Attempt 的密文解析。完全重复和冲突也各写自己的 Attempt。同一官方键不同业务载荷时，Inbox 保持一行并进入 `PAYLOAD_CONFLICT`，新载荷留在冲突 Attempt 中，不能绕过唯一约束生成第二笔估算。

### `skit_provider_impression_observation`

解析成功后新增独立、账号级的“未经签名展示观察”事实表，一条 Inbox 最多一条初始观察：

- `provider_connection_id + inbox_id + source_integrity_revision`
- 包名、广告位、格式、network firm、network account、广告源和平台时间等规范字段
- eCPM 原始词法、高精度规范值、币种、`estimated_amount_units + amount_scale`
- `evidence_provenance=UNSIGNED_PROVIDER_OBSERVATION`
- 追加式状态修订引用，例如 `OBSERVED` 或后续 `ESTIMATE_INVALIDATED`

该表没有 `tenant_id`、member、session 或 commission policy 外键。它承接 `PLACEMENT_ATTRIBUTED` 也能展示的账号/应用/广告位级估值，不能伪造会员或会话去满足现有收益表约束。

### `skit_provider_impression_attribution_revision`

归属结果使用独立追加式修订表，而不是把 nullable 租户列写回全局 Inbox：

- `observation_id + attribution_revision`
- `binding_snapshot_id + config_revision + config_fingerprint`
- `routing_level`、`show_context_id`、`attribution_status`
- `supersedes_attribution_id`、原因、时间与处理版本

租户、广告账号、App、广告位和广告源身份全部从不可变 binding snapshot 取得，并由组合外键证明所有权。租户 API 只读自己的租户投影，永远不能查询共享连接的 Inbox、Attempt、原始观察或其他租户绑定。

新增租户侧 `skit_provider_impression_tenant_projection` 作为明确的 bridge seam。它引用 `tenant_id + attribution_revision_id + binding_snapshot_id`，并用组合外键绑定同租户的 session、source member 和 policy snapshot；`observation_id + attribution_revision` 全局唯一。它不是现有租户 callback Inbox，也不复制原始 query。

只有 `CONTEXT_MATCHED` 且 tenant projection 同时满足 `session + source_member + policy_snapshot` 不变量时，才允许向 `skit_ad_revenue_event` 追加收益事件。收益 schema 新增 `source_kind` 和显式 XOR 来源：`TENANT_CALLBACK_INBOX` 要求现有 `callback_inbox_id` 非空且 `provider_tenant_projection_id` 为空；`PROVIDER_TENANT_PROJECTION` 要求相反；两者恰好一个成立。现有旧来源外键不放宽，也禁止创建假的 `skit_ad_callback_inbox` 行来凑约束。

`PLACEMENT_ATTRIBUTED` 只留在观察/租户聚合投影中；禁止伪造 member/session、借用 legacy 标志或进入会员佣金路径。

### 幂等约束

- `req_id` 按严格 UTF-8 percent-decode 后的原值计算，不 trim、不折叠大小写；控制字符、空值或超过 512 bytes 时不具备官方键资格。
- `adsource_id` 必须是 1..19 位正十进制，去掉前导零后作为规范值；原始词法仍保留。
- 两者有效时，`dedupe_key_hash = SHA-256("TAKU_IMPRESSION_OFFICIAL_V1" + length_frame(req_id_utf8) + length_frame(normalized_adsource_id))`。
- 任一官方字段缺失或无效时，`dedupe_key_hash = SHA-256("TAKU_IMPRESSION_FALLBACK_WIRE_V1" + wire_payload_hash)`，并永久标记 `FALLBACK_WIRE_V1 + QUARANTINED`。
- 数据库唯一键固定为 `(provider_connection_id, dedupe_scheme, dedupe_key_hash)`；三个字段全部非空。CHECK 保证 scheme、官方词法字段和隔离状态组合合法，不依赖 MySQL nullable UNIQUE 行为。
- `wire_payload_hash` 对边界内实际 raw query bytes 计算。`material_integrity_hash` 对影响归属或金额的已知字段按固定字段顺序、缺失标记、长度分帧和规范数值计算，使参数重排和等价 percent-encoding 不产生假冲突；未知字段和设备标识不进入 material hash，但仍保留在 wire hash/密文中。
- `FALLBACK_WIRE_V1` 只能把完全相同的原始载荷归到一个捕获组。两次相同载荷可能是两次真实展示，因此展示次数只能看到两条 Attempt，不能把该 Inbox 当作一次展示、事后晋升收益或佣金。
- 完全重复仍写 Attempt，但不重复创建观察或收益。官方键相同而 `material_integrity_hash` 不同才是完整性冲突；只有 wire hash 不同但 material 相同视为等价重复。
- 绝不能用无分隔符字符串拼接 `req_id + adsource_id` 作为唯一键。

## 入口处理顺序

单个数据库短事务只做身份解析和持久化：

1. Nginx 的 callback location 关闭 access log；应用最前置 `SkitCallbackSecretSanitizingFilter` 在任何通用日志 Filter 前设置固定安全 URL 并禁止参数日志。Nginx 当前并不重写 Key/query，文档和测试不能假设它已经重写。
2. 校验 GET、路径形状和请求边界。首版不可运行时放宽的硬常量为：原始 query 不超过 32 KiB、参数不超过 64 个、percent-decode 后的参数名匹配安全 ASCII token `[A-Za-z0-9_.-]{1,64}`、单值不超过 24 KiB，且总大小约束始终优先。任何调整都需要版本化代码、测试和 Nginx 同步变更；Nginx 请求行/缓冲区上限必须与应用一致，避免请求先被代理用另一状态拒绝。
3. 直接读取有界的 `HttpServletRequest.getQueryString()`，禁止使用 Servlet parameter map；先保存实际顺序、重复值和 percent-encoding 的 wire 视图，再用专用 adapter 解码一次。
4. 对 `connectionKey` 做哈希，只查询一次全局 route registry，严格解析到一个未阻断的 Taku 账号连接；该步骤不进入任何租户上下文。
5. 计算 `wire_payload_hash`、`dedupe_key_hash` 和可用时的 `material_integrity_hash`，并对原始 query 做 AEAD envelope encryption。
6. 单个 MySQL 事务执行：按非空唯一键原子 upsert Inbox 并取得 canonical ID；`SELECT ... FOR UPDATE` 锁住该 Inbox；写本次 Attempt；新行设置 `canonical_attempt_id`，旧行比较 material hash 并单调更新完整性状态；需要时同时写 Outbox/失效修订。
7. 数据库提交成功后立即返回 200。
8. 独立持久化 worker 租约拉取、解析、归属、计算估值和发布 Outbox 事件。

已知参数重复会在落库后隔离；未知参数和未知参数重复会原样留在加密 envelope 中。无法安全解码时使用原始字节哈希，仍只做捕获，不进入金额计算。若非法 request line 或 percent-encoding 被 Nginx/Tomcat 在应用 Filter 前拒绝，应用无法承诺持久化；必须用真实 Nginx + Tomcat 集成测试建立可捕获边界并将前置拒绝计入缺口指标。

worker 的解密、解析、候选映射和金额准备全部在行锁外完成。只有最终发布短事务才锁 Inbox、重新比较 `integrity_revision/status`，并原子写观察/归属/Outbox；revision 已变化就放弃候选并重试，不能拿长事务阻塞入口。

入口在返回 200 前禁止执行：

- 租户查找或切换租户数据源；
- 会话、会员、代理或佣金查询；
- Taku Open API/报表调用；
- 奖励签名验证或内容解锁；
- 消息中间件同步确认；
- 任何依赖未知字段必达的严格业务校验。

事务超时要明显短于 2 秒，并通过压测确定，不再把 2 秒本身当成内部事务预算。MySQL 不可用时返回 503 并高优先级告警；Taku 不重试意味着这类失败必须进入可见的缺口对账。

### 迟到冲突与追加式失效

Inbox 的 `integrity_status` 只能从 `CANONICAL` 单调变为 `PAYLOAD_CONFLICT`，`integrity_revision` 每次新冲突递增。worker 发布观察和 Outbox 时必须锁同一 Inbox，并把 `source_integrity_revision` 固化到下游唯一来源链接，入口和 worker 由行锁串行化：

- 冲突先提交时，worker 看到 `PAYLOAD_CONFLICT` 后不得发布观察。
- 观察先提交、冲突后到时，入口冲突事务只在全局域追加幂等 `ESTIMATE_INVALIDATED` 调整和 Outbox；它不在 2 秒入口同步查询或修改租户账本。
- 如果观察已经桥接到租户收益事件，异步 tenant projector 根据 invalidation Outbox 追加对应 reversal/freeze 修订，所有对账、余额和佣金消费者按来源链接排除或冲正。
- 每个失效动作唯一键包含 `observation_id + integrity_revision + adjustment_type`，重复冲突不会重复扣减。

Outbox 必须携带 `inbox_id + observation_id + integrity_revision`。消费者以已见最大 revision 做 CAS；即使 invalidation 因重试/分区顺序先于 observation 到达，也先保存 revision tombstone，之后拒绝应用任何更旧的观察，保证乱序不复活失效收益。

展示估算在报表对账前始终是可撤销观察，不能提前变成不可逆提现余额。自动化测试必须覆盖“收益事务刚提交后，另一事务才送达同官方键异载荷”的真实 MySQL 并发时间线。

## 宽容解析与隐私

### 解析规则

- 原始 query 只在边界内加密保存，已知字段另存词法值和解析结果。
- 未知字段只要名称和值满足上述通用字节/字符边界就允许出现，不因它未在当前 allow-list 而拒绝整次投递；超出安全 token 的名称仍在持久化前确定性拒绝。
- 已知参数重复、非法 URL 编码、数值越界、非法货币或不合理时间戳时，捕获后隔离，不猜测正确值。
- `adformat` 接受官方当前枚举 `0..4`；未知新值保留并隔离，不再只允许激励视频 `1`。
- `show_custom_ext` 允许缺失、空白或非当前 22 字符会话 ID；只有符合服务器已签发上下文格式并能查到记录时才提升归属等级。
- Taku `timestamp` 同时保存原始值和解析值。只有通过合理时钟漂移检查后才用于业务日期；`received_at` 始终是接收事实时间。
- 数据库存储的接收、平台事件和处理时间统一为 UTC；报表日期另按连接上明确配置的报表时区派生。

### 数据最小化

- 不把 `client_ip`、GAID、OAID、IMEI、IDFA、IDFV 或 Amazon ID 抽取到普通业务列。
- 原始 query 因协议核查需要短期存在，使用 AEAD envelope encryption、独立密钥、访问审计和自动销毁；每条 Attempt 保存 `key_id`、唯一 96-bit nonce 和绑定 provider connection、Attempt correlation ID、wire hash 的 AAD。初始默认保留 7 天，配置上限 30 天。
- Web 服务器看到的来源 IP 只用于粗粒度防滥用；不以明文写业务表，不和 Taku 上报的 `client_ip` 混为一谈。
- 普通管理 API、异常文本、MDC、APM span、指标标签和数据库慢查询日志不得包含 Key、原始 URL、query 值或设备标识。
- `show_custom_ext` 普通列表仅显示哈希/匹配状态，需要故障审计时走受控解密流程。
- 全局连接与 Inbox 的 Mapper/Service 必须显式绕开租户拦截器，但只能由公网捕获服务、受租约约束的内部 worker 和超级管理员接口访问；普通租户 API 不能用 `@TenantIgnore` 读取它们。
- 清理器只有在结构化解析完成，或事件明确进入已告警的死信状态后，才能按到期索引销毁密文；Attempt 按接收月分区/归档，确保重复或恶意流量不会让主索引无限增长。

## 归属与隔离

异步 worker 只使用服务端配置进行交叉验证，不能相信 query 中自报的租户身份：

1. `provider_connection_id` 确定外部 Taku 账号边界。
2. `package_name` 必须映射到该连接下已启用的 Skit 应用。
3. `placement_id` 必须映射到该应用、租户和版本化逻辑广告位。
4. `adformat` 必须和广告位声明一致。
5. `network_firm_id + adsource_id` 必须在绑定快照中唯一解析出同一 `network_account_id`，并证明该 network account、广告源、连接和广告位属于同一版本；同一 adsource 在多个 network account 下有歧义时不得猜测。
6. `show_custom_ext` 若存在，必须映射到服务器签发的展示上下文，并与以上所有维度一致。

归属等级：

| 等级 | 条件 | 允许用途 |
| --- | --- | --- |
| `CONTEXT_MATCHED` | 连接、包名、广告位、格式、广告源与服务端展示上下文全部一致 | 形成展示级租户/应用/会话估算事件；仍不授奖。 |
| `PLACEMENT_ATTRIBUTED` | 上下文缺失，但连接、包名、广告位、格式与广告源映射唯一且一致 | 只做租户/应用/广告位聚合估算，不做会员或代理级分配。 |
| `UNATTRIBUTED` | 必需路由字段缺失，或服务端无唯一映射 | 隔离并等待报表与配置补全。 |
| `CONFLICTED` | 任意服务器所有权字段互相冲突，或官方键出现不同载荷 | 隔离、告警，禁止生成收益事件。 |

`show_custom_ext` 是关联线索，不是回调来源签名。即使达到 `CONTEXT_MATCHED`，证据仍是未经密码学证明的展示观察。奖励权威继续由独立的 Taku 签名奖励 S2S 等受支持凭据提供。

未使用的逻辑广告位继续保持禁用而非删除；映射必须带配置修订号和指纹，避免回调到达时使用被覆盖的新配置解释旧展示。

## 金额与对账

### 展示级估算

只在以下条件全部满足时生成估算金额：

- 官方幂等键完整且没有载荷冲突；
- `adsource_price` 是无正负号、无指数、最多 12 位小数的普通十进制，数值位于首版审计上限 `0..1000000` eCPM；
- `currency` 严格匹配 ASCII `[A-Z]{3}`，不把小写静默改成大写；
- 至少达到 `PLACEMENT_ATTRIBUTED`。

计算过程禁止二进制浮点：

```text
estimated = Decimal(adsource_price) / 1000
estimated_amount_units = round_half_even(estimated, scale=8) * 10^8
```

同时永久保留 eCPM 原始词法值和规范高精度十进制，并记录 `money_policy_version=TAKU_ECPM_SCALE8_V1`，便于检查舍入。科学计数法、数值溢出、负数、NaN/Infinity、超上限或精度异常仍被捕获，但只隔离，不截断后入账。测试必须覆盖零、最大值、12 位小数、scale=8 的 HALF_EVEN 正反两侧和恰好中点。

连接级 `amount_scale` 首版固定为 8，并要求展示事件、报表桶、对账修订和账本分配使用相同 scale。当前代码允许事件动态 scale、报表账号默认 scale=8；若两边 scale 不一致，现有查询会漏掉可对账事件，因此实施时必须先归一化再开启对账。

历史动态 scale 事件不得原地改写。实施前先审计所有未对账展示事件，用 BigInteger/十进制做精确重缩放，并追加带 `money_policy_version`、原 units/scale、目标 units/scale、舍入差额和来源事件 ID 的 `MONEY_NORMALIZED` 修订；已经完成的历史对账保持不可变。无法重缩放或溢出的事件进入 suspense。查询门禁必须证明 mixed-scale 事件先经过规范投影，不再因 `amount_scale=8` 条件被静默漏掉。

### 连接级报表拉取与对账

- S2S 估算与 Taku 日报/账单分开保存，不覆盖原事件。
- 新增全局 `skit_provider_report_pull` 及连接级 bucket/revision；原始报表先按 `provider_connection_id` 幂等落库，再映射租户。凭据版本、lease、cursor、重试/backoff 都由连接拥有，一个外部账号在同一时间只能有一个拉取器。
- 调度器按有效 binding snapshot 枚举外部 App/广告位/格式，但用连接、查询区间、维度和报表版本的 query fingerprint 去重，不能让每个租户 `ad_account` 重复拉共享账号。
- 现有报表实现只支持单 App、单广告位和 `rewarded_video`，不得直接当作多格式共享账号拉取器；必须先扩展并用真实 API readback 证明每个启用格式的维度完整性。
- 报表桶身份固定为连接、provider App、报表时区日期、广告位、广告格式、`network_firm_id`、`network_account_id`、`adsource_id`、币种和 scale。
- 如果报表不返回广告格式，只能由“一广告位永久对应一个格式”的不可变快照推导；存在歧义时整桶进入 suspense。
- 报表金额采用 `REPORT_SCALE8_V1`：永久保留每行原始词法与高精度十进制，先在同一桶内以高精度求和，再对桶总额做一次 HALF_EVEN scale=8 转换；保存转换前总额、目标 units 和舍入残差。解析失败、溢出或残差超过半个 scale=8 unit 的桶整体进入 suspense，不能部分分配。
- 只有维度充分且唯一的展示参与分摊；未归属金额进入 suspense，不猜分给某租户。
- 报表实际金额按稳定事件 ID 和整数余数规则分配，保证桶内总额严格守恒。
- UI 中实时值标注“展示预估收益”；对账后的事件值标注“平台报表已对账分摊”，不能标成“最终结算”或“已到账”。
- 不同币种永不直接相加；未来需要换汇时必须形成带来源、日期和版本的独立换汇事件。

如果迟到载荷冲突使已经参加对账的观察失效，系统追加新的 bucket reconciliation revision：先撤回该观察原分配，再按同一 target actual 对剩余有效观察重分配；没有安全候选时把被撤回金额转入 suspense。账本只追加 reversal/delta，且每个新 revision 始终满足 `有效 allocation + suspense = report actual`，不修改旧 revision。

展示回调永远不触发内容权益。会员、代理或上级分成只有在服务器展示上下文充分、对账规则生效且现有佣金策略明确允许时，才能由独立账本流程处理。

## 管理 API 与界面

账号级连接属于平台超级管理员，不属于普通租户设置页。第一版提供：

- 创建/查看共享 Taku 账号连接；
- 签发一次展示回调地址；
- 标记已提交给 Taku，并只记录不含完整 URL 的人工提交元数据；
- 查看是否收到第一条真实回调、最近接收时间和当前健康状态；
- 置为 `BLOCKED` 的紧急开关；
- 查看字段存在率、隔离原因、重复/冲突和对账覆盖率。

GET 查询永不返回真实 Key 或完整地址，只返回例如 `sha256:7f31c8d2a104` 的哈希派生指纹、固定 canonical origin、状态和时间；指纹不能截取 Key 的真实前后缀。签发接口要求重新认证、超级管理员权限、审计日志，并只在一次响应中返回明文。

一次性签发响应必须设置 `Cache-Control: no-store`，关闭 API 响应体日志、审计命令正文、APM body、浏览器持久化、前端埋点和剪贴板内容上报；DTO `toString()`、异常和序列化测试必须证明不会输出 Key。若响应丢失，只有在确认从未共享后按 `ISSUED -> ABANDONED -> 新 DRAFT` 重建，不能读取旧明文。

租户广告配置页的变化：

- 不再为共享 Taku 账号显示或轮换“展示收益回调地址”。
- 只读显示“继承平台共享账号回调：已提交/已激活/异常”。
- 奖励回调和奖励密钥仍按现有独立生命周期管理，不能因本设计取消或合并。

## 容量、限流与可观测性

一个地址聚合所有应用后，固定 120 次/分钟的 Key 限额必须移除。新策略为：

- 未知 Key 和异常 IP 保持严格速率限制。
- 对有效连接不使用会在正常业务峰值丢展示的低固定阈值；配额按账号预计展示峰值、突发系数和实测数据库吞吐配置，并显著高于正常峰值。
- 超过“物理上不可能”的异常量时触发保护、告警和捕获降级；阈值变更必须有容量证据。
- 加密和数据库写入链路做账号级压测，验证并发唯一键冲突和锁等待不会击穿 250ms p99 目标。

Taku 不重试使入口可用性直接等于逐展示数据完整性。备份只能恢复已提交数据，不能补回停机期间没收到的展示；提交真实地址前必须至少具备跨主机/故障域的双公网入口或托管 ALB、覆盖 Nginx 与主机健康摘除的路由、两个可独立滚动的应用实例、MySQL 高可用/自动故障切换，以及 Redis HA。

有效 provider connection 的正常捕获不能因 Redis 限流依赖故障而直接丢弃：连接级容量保护使用本地有界并发加 HA edge 限额，Redis 异常时按已压测上限退化并告警；未知 Key/IP 的防滥用仍可 fail closed。压测与 LB/Nginx、应用、Redis、MySQL 分层切换演练要求已提交 Inbox 的 RPO 为 0，并明确记录故障切换期间的最大不可捕获窗口。

运营缺口用日报 `report_impressions - attributable_s2s_attempts` 按连接/应用/广告位监控。首次提交前由产品和运维书面接受逐展示缺口预算；没有获批预算或冗余证据时，只能声明地址技术可达，不能声明逐次收益数据具备生产完整性。

核心指标不能把 Key、包名、广告位或请求 ID 直接作为无限基数标签：

- 请求数、200/4xx/5xx 和入口 p50/p95/p99；
- 持久化成功率、DB 超时与无重试缺口；
- 官方字段存在率矩阵，按低基数广告格式和广告源分组；
- 完全重复、载荷冲突、回退幂等和隔离原因；
- `CONTEXT_MATCHED / PLACEMENT_ATTRIBUTED / UNATTRIBUTED` 比例；
- S2S 展示数与日报展示数覆盖率、估算与报表实际差异；
- 原始载荷解密访问、过期销毁和密钥错误。

告警至少覆盖：连续无回调、5xx、持久化延迟、冲突激增、字段突然消失、未知应用/广告位激增、证书/DNS 到期和日报覆盖率异常。

## 分阶段交付

### 阶段 1：先得到可提交地址

1. 增加稳定账号连接、版本化 callback route、全局 route registry，以及全局 Inbox/Attempt。
2. 增加账号级路由解析、日志清洗、边界校验、加密持久化和固定 200 响应。
3. 增加超级管理员一次性签发与 `SUBMITTED/BLOCKED` 状态。
4. 在生产验证 HTTPS、Nginx、应用路由、数据库提交、日志脱敏和告警。
5. 只在上述验证完成后签发真实 Key，把唯一完整地址交给 Taku AM。

此阶段可以只有 capture-only worker：任何字段充分性或鉴权未知都进入隔离，但不能丢请求。

### 阶段 2：真实回调金丝雀

1. 选择一个可识别应用和每种实际启用的广告格式产生真实展示。
2. 验证 HTTP 200、Inbox 持久化和 Taku 后台生效证据。
3. 建立 `package_name`、`placement_id`、`show_custom_ext`、格式、广告源的实际存在率矩阵。
4. 检查是否有未公开签名/Header、参数编码差异、重复投递和 eCPM 精度。
5. 保持所有事件为 `UNSIGNED_PROVIDER_OBSERVATION`，除非 Taku 另行提供并验证正式鉴权协议。

### 阶段 3：归属与估算

1. 建立连接下应用、版本化逻辑广告位、格式和广告源的服务端映射。
2. 让五个逻辑广告位的展示上下文携带通用 `show_custom_ext`，不只覆盖激励视频。
3. 启用分级归属、金额 scale=8 归一化、隔离队列和租户聚合查询。
4. 对真实回调做回放测试后再打开收益事件发布。

### 阶段 4：报表对账与产品展示

1. 接入并验证 Taku 报表权限、时区和桶维度。
2. 启用追加式对账修订、suspense 和守恒分摊。
3. 管理端区分“展示预估收益”和“平台报表已对账分摊”。
4. 在真实日切数据连续通过覆盖率与守恒检查后，再讨论佣金账本开放范围。

任何阶段发生处理器故障，都允许关闭归属/估算 worker 并保留 capture-only 入口。已经交给 Taku 的公网地址不随业务开关改变。

## 测试与验收

### 自动化测试

- Key：43 字符格式、228-bit CSPRNG、全局 route registry 哈希唯一、历史租户 Key 回填、DRAFT/ISSUED/ABANDONED 状态及 SUBMITTED 后禁止轮换。
- 路由：registry 的 `PROVIDER_CALLBACK_ROUTE` Key 只进入账号展示 Inbox 且不能进入 reward，`TENANT_CALLBACK_KEY` 保持旧处理；覆盖未知 43 字符 Key、非 GET、畸形路径、错误长度和伪造租户 Header/query。
- 迁移：tenant Key 双写、存量回填、全集校验、shadow read、切读和强制 registry 六步均可重入；切换窗口并发轮换不会签发不可路由 Key。
- 解析：字段全量、字段缺失、未知字段、五种广告格式、重复参数、非法编码、超长值、设备标识和新枚举。
- Inbox：非空 dedupe hash、完整官方键、缺失键回退、完全重复、同键异载荷、每投递一 Attempt、20 路并发重复/冲突、迟到冲突追加失效、事务失败不返回 200。
- 金额：eCPM 除以 1000、scale=8、HALF_EVEN 边界、超大值、负值、科学计数法策略、币种隔离和禁止浮点。
- 归属：稳定 tenant application、binding 区间锁/重叠审计、包名/广告位/格式/network firm/network account/广告源/上下文全部一致、每一维冲突、映射版本变化和无唯一映射。
- 安全：Key、原始路径、query、设备 ID 不进入应用日志、Nginx 日志、API access log、异常、DTO `toString()`、MDC、APM、前端埋点或指标；一次性签发响应不缓存、不记录。
- MySQL：route slot/人工切换、route registry 回填、非空唯一约束、组合所有权外键、冲突 Attempt、全局 Inbox/观察/归属/租户 projection 分层、revenue XOR source、AEAD envelope、短处理租约、死信、report scale=8、对账后迟到失效重分配和整数守恒。
- 真实链路：Nginx + Tomcat 覆盖 32 KiB 边界、非法 percent-encoding、重复参数、错误方法和错误长度，明确哪些请求在应用前被拒绝。

### 提交给 Taku 前的生产门禁

必须逐项留下证据：

1. 公网 HTTPS 请求到固定路径能穿过 Nginx 和 Spring，不被其他租户路由截获。
2. 有效测试 Key 的请求返回 200，且对应 Inbox/Attempt 已提交到生产数据库。
3. 未知 Key 返回统一 602；生产日志中没有出现测试 Key 或 query 值。
4. 在生产等价环境用受控 repository failpoint 证明数据库提交失败时返回 503 并触发告警，没有“200 但无记录”。failpoint 只绑定专用 DRAFT 测试连接，不提供公网运行时开关，演练后验证代码/配置已移除；不以中断生产数据库来做门禁。
5. 账号预估峰值加突发系数的完整压测先在生产等价环境完成，入口 p99 低于 250ms，零丢失且幂等正确；生产只做有窗口、有限速、可立即终止的测试连接金丝雀。
6. 双公网入口/ALB、Nginx/主机摘除、两个应用实例、Redis 降级、MySQL HA 切换、DNS、证书续期、备份恢复、加密密钥和 capture-only 降级均通过运维检查，并记录逐展示缺口预算。
7. 对已提交 route 做 public-base/accepted-origin/path-version/ordered-macro-template 漂移负向测试，证明 callback contract fingerprint 不一致时生产 activation fail closed。
8. 超级管理员签发一次真实地址，并只记录已交付 Taku AM 的工单元数据与哈希指纹；普通接口无法再次读取明文。

在门禁完成前，只能说“URL 形状已确定”，不能把现有租户 Key 拼入地址交给 Taku。

### Taku 配置后的闭环门禁

- 至少一条真实展示在 Taku 侧触发，Skit 返回 200 且 Inbox 可查。
- 记录真实参数字段矩阵和 SDK/广告格式/广告源条件。
- 用 Taku 报表核对该日展示数量；字段不足的事件保持隔离。
- 明确证明展示回调没有触发任何 entitlement、reward 或 content unlock 写入。

## 已知未知项及处置

| 未知项 | 是否阻塞签发地址 | 当前处置 |
| --- | --- | --- |
| 三个路由字段是否每次必传 | 否 | 宽容持久化；字段不足进入 `UNATTRIBUTED`。 |
| `show_custom_ext` 的格式/广告源覆盖 | 否 | 金丝雀建立矩阵；不支持时降为广告位级或隔离。 |
| 是否存在未公开签名/Header/IP 白名单 | 否 | 一律标记 `UNSIGNED_PROVIDER_OBSERVATION`；阻塞可信结算和授奖。 |
| `req_id` 唯一范围和保留期 | 否 | 唯一键前置 `provider_connection_id`，永久保留哈希级幂等证据。 |
| AM 配置生效时延和紧急变更 SLA | 否 | `SUBMITTED` 状态与人工证据；配置后用真实金丝雀确认。 |
| eCPM 来源、精度和后续修正 | 否 | 保留词法值、固定 scale=8 估算，最终以追加式报表对账修订。 |

这些未知项不允许被写成已确认事实。它们不阻塞“先给出一个稳定的 capture-only 地址”，但必须持续阻塞错误的可信归属、结算或授奖声明。

## 实施后的最终业务语义

```text
一个 Taku 外部账号
  -> 一个长期固定的 connectionKey 回调地址
  -> 一个全局、先持久化的展示 Inbox
  -> 服务端映射后归属到多个租户/应用/广告位
  -> 每次展示形成可对账的预估收益
  -> 日报形成追加式对账分摊

奖励回调
  -> 独立租户/账号密钥与签名验证
  -> 独立内容权益路径
```

所以当前共享主账号使用一个地址不仅可以，而且是与 Taku 账号级能力一致的正确边界。关键不是继续增加 URL，而是让这个唯一 URL 在进入系统后先落到“账号连接”，再用服务器掌握的映射安全拆分到各租户。

## 代码落点参考

- `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackRoutingService.java`：当前租户 Key 解析边界，需要新增并列的账号连接解析器。
- `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackSecretSanitizingFilter.java`：保留 43 字符路径和 602 确定性拒绝，扩展 registry 分派与脱敏测试。
- `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/TakuCallbackCanonicalizer.java`：当前严格会话解析不能直接用于账号级宽容捕获。
- `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/RedisSkitCallbackRateLimiter.java`：当前 120/分钟的 Key 限额需要替换为连接容量模型。
- `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantAdCapabilityController.java`：共享账号展示回调签发必须迁出租户轮换接口。
- `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/reconciliation/SkitAdReportPullServiceImpl.java`：当前租户/单 App/激励视频报表 lease 需要迁成连接级多格式调度。
- `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitAdSchemaDdl.java`：新增全局连接、Inbox 与 Attempt，并保持引导 DDL 与正式 SQL 同步。
- `sql/mysql/skit-saas.sql` 与 `sql/mysql/ruoyi-vue-pro.sql`：版本化迁移、约束和基线 schema 必须同步。
