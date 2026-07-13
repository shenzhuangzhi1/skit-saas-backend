# 广告服务端验奖、收益监控与多层分成设计

日期：2026-07-14

状态：待书面批准

范围：`skit-saas-backend`、`skit-saas-frontend`、`skit-saas-app`、每个代理商独立的 Taku/穿山甲配置

采用方案：A —— 服务端验奖后即时解锁，收益先冻结；平台报表对账后转为可结算并追加差额调整

## 目标

建立一条由广告平台服务端证明驱动的生产闭环：监控每个会员的广告请求、展示、完成、奖励与异常关闭；只有服务端验签通过的广告才能解锁内容；每次有效展示产生的收入按代理商当前已发布的规则分配给广告会员、师傅、师祖及更高层邀请人，剩余部分归代理商；比例调整只影响未来事件，历史账目可审计、不可篡改。

本设计中的“广告消费”指会员对广告的请求、展示、观看、完成、关闭和奖励消费，不指广告主投放预算。如果以后接入买量成本，将作为独立的广告支出域处理，不能与本次展示收益混写。

## 方案 A 的产品口径

1. 广告平台服务端奖励回调验签成功后，立即授予本次约定的剧集权益；不等待次日财务报表。
2. 展示级收入先作为“预估冻结收益”展示，不可提现。
3. 次日及后续平台真实报表完成对账后，追加释放冻结、正式结算或差额冲正分录；只有可结算余额可以进入未来的提现流程。
4. 第 0 层是看广告的会员本人，第 1 层是其直接邀请人（师傅），第 2 层是师傅的邀请人（师祖），依次支持任意层级。
5. 所有已配置会员层级比例之和可以小于 100%，未配置比例、缺失祖先、策略快照时无资格的祖先以及舍入余额均归代理商。
6. 停用会员从停用后创建的新广告会话起不再获得上级分成，其对应比例归代理商；已锁定快照和已形成分录不追溯修改。
7. 分账的权威业务时点是后端创建广告会话并成功锁定策略快照的时间 `policy_snapshot_at`，不是设备时间、平台迟到回调时间或次日报表时间。每笔事件固定使用该时点的分成方案版本、邀请链和受益资格；后续改比例、换状态或新增邀请人都不改变历史。
8. 一次广告只解锁后端根据剧目配置计算出的连续 `unlock_size` 集；权益绑定租户和会员、逐集永久有效，已经拥有目标权益时不再创建广告会话。普通财务调整不撤销已授予权益。

## 当前实现与必须替换的风险

现有多租户、手机号会员、邀请码闭包树、版本化分成方案和事件/账本唯一键可以保留，但广告主链仍是客户端预估原型：

- App 在本地 Taku `onReward` 后直接把剧集写入本地存储，再异步上报 `completed`、`requestId` 和 eCPM；上报失败不会回滚。
- 本地解锁键没有绑定租户或会员，换账号、换租户和篡改本地存储都可能继承权益。
- 穿山甲 HBuilder 原生播放器存在没有真实广告仍直接调用奖励成功的 fallback，必须删除；广告不可用只能失败。
- Taku bridge 没有在加载前设置会员 UserID、UserCustomData 或服务端会话标识，也没有返回/校验平台 `providerShowId`。
- 客户端可以自行调用预估收益接口并提交金额、完成状态和事件 ID。即使当前只记为 `ESTIMATED`，仍会污染监控和冻结账本。
- 后台广告记录和首页财务指标仍混有通用 JSON 样例、硬编码和接口失败后的模拟数据，不能作为真实经营看板。
- 当前只有 `LEDGER_AVAILABLE` 常量，没有 S2S 验签、报表拉取、对账、拒绝、冲正或状态推进实现。

本次改造不在这些入口上继续叠加判断，而是建立新的广告会话、回调收件箱、权益与追加式账本；旧客户端上报接口降级为不产生资金的遥测兼容入口，并在强制升级完成后移除。

## 权威来源与信任边界

### 可信来源

- 当前登录令牌提供的原始租户和会员身份；客户端不得提交 `tenantId` 或替换会员身份。
- 后端签发、不可预测、短时有效且一次性消费的广告会话。
- 按代理商广告账号保存的服务端密钥验签成功的奖励 S2S，认证级别为 `SIGNED_REWARD`。
- 后端主动获取且完成完整性校验的广告平台官方报表。
- 数据库中已发布的分成方案版本、事件时邀请闭包和会员状态。

### 不可信来源

- App 的 `onReward`、`onClose`、播放时长、eCPM、设备时间和本地解锁缓存。
- WebView、JavaScript bridge、root/Hook 环境和客户端返回的任意 `completed` 布尔值。
- 回调 URL 中未经验签的租户、会员、广告位、金额和自定义参数。
- Taku 展示 S2S 没有签名，认证级别只能是 `UNSIGNED_PROVIDER_OBSERVATION`；它即使通过 callback key、会话关联和防重，也只能形成冻结预估，不能授予权益或直接结算。
- Taku ILRD 作为奖励发放依据。ILRD 只可用于行为排查；展示收益使用展示 S2S，最终金额使用官方报表。
- 后台可编辑的通用广告记录或人工填写的财务数字。

### Taku 对接依据

Taku 服务端激励要求在加载前传入 UserID 和 UserCustomData，回调包含唯一 `trans_id`、广告位、广告源及签名；`trans_id` 对应客户端展示信息中的 `getShowId()`，服务端应验签并做幂等。Taku 同时建议核对客户端奖励、第三方平台服务端奖励和 Taku 服务端奖励的一致性：<https://help.takuad.com/docs/msbnkj>。

展示收益使用 Taku 广告展示 S2S，并按 `req_id + adsource_id` 防重；该回调不重试，因此入口必须快速持久化并立即响应：<https://help.takuad.com/docs/9frd63>。

Taku 报表 API 用于获取正式数据，定时任务串行执行并避开官方限制的高峰分钟：<https://help.takuad.com/en/docs/Reporting-API>。

## 总体流程

```text
会员请求解锁
  -> 后端鉴权并创建一次性广告会话
  -> App 用后端返回的广告位、UserID、CustomData 加载广告
  -> 客户端上报生命周期遥测（不解锁、不入可结算账）
  -> Taku/第三方 S2S 回调进入快速收件箱
  -> 验签、幂等、会话/用户/广告位/providerShowId 一致性校验
  -> 奖励通过：写入服务端剧集权益，App 轮询成功后解锁
  -> 展示 S2S：形成展示级预估收入和冻结分成
  -> D+1/D+2 官方报表：按对账桶分配真实收入
  -> 追加冻结释放、正式结算和差额调整分录
  -> 管理端展示漏斗、异常、冻结余额、可结算余额与各层贡献
```

奖励确认和金额确认是两个相关但独立的事实：奖励回调可以先到，展示收益回调也可以先到。系统必须支持乱序，不得要求客户端按固定顺序补齐。

## 广告会话与内容权益

### `skit_ad_session`

每次需要观看广告的业务动作创建一条会话，核心字段如下：

- `id`、全局随机 `session_id`、只保存哈希的 `session_token_hash`
- `tenant_id`、`member_id`、`ad_account_id`
- `provider`、`placement_id`、`scenario_id`
- `business_type`、`drama_id`、`episode_from`、`episode_to`、规范化 `unlock_scope`
- `pseudonymous_user_id`，供 SDK UserID 使用，不暴露手机号
- 会话创建时锁定的 `plan_id`、`rule_version` 和邀请/受益资格快照引用
- 相互独立的 `client_lifecycle_status`、`reward_verification_status`、`entitlement_status`、`revenue_status`
- `load_expires_at`、`reward_accept_until`、`reward_verified_at`、`entitled_at`、CAS `version`
- `sdk_request_id`、`provider_show_id`、`provider_transaction_id`
- 客户端最后事件、平台网络 ID、失败原因、创建与更新时间

约束：

- `session_id` 全局唯一。
- `(ad_account_id, provider_transaction_id)` 在非空时唯一。
- `(ad_account_id, provider_show_id)` 在非空时唯一。
- 一个会话只绑定一个会员、一个租户、一个广告账号、一个广告位和一个不可扩大范围的解锁目标。
- 会话默认短时有效。过期会话可以接收并保存迟到的合法平台回调供审计，但不会扩大原定权益。
- 创建会话时必须校验租户、代理商、会员和广告账号启用；归档后不得创建新会话。
- 会话创建时固定分成方案与邀请链。会话有效期内发生的合法广告沿用该快照；会话过期后不能借旧比例重新播放。
- 后端在同一事务读取当前生效方案、邀请闭包和会员状态，写入不可变策略快照并设置 `policy_snapshot_at`。平台展示时间只决定报表日期桶，不会重新选择分成版本。
- `load_expires_at` 默认创建后 5 分钟，App 必须在此之前开始加载；`reward_accept_until` 默认创建后 20 分钟，按平台最长广告与有限重试留出余量。两个时限均由服务端创建并记录，不接受客户端时间。
- 相同会员与相同规范化解锁范围同时最多一个活动会话。已拥有全部目标权益时返回 `ALREADY_ENTITLED`；尚在奖励接收窗口内时返回原 `sessionId`；窗口结束后才允许重新观看。

### 正交状态机

客户端生命周期、奖励验证、权益和收益是四个正交事实，禁止用一个 `status` 相互覆盖：

- `client_lifecycle_status`：`CREATED / LOADING / SHOWN / CLIENT_REWARDED / CLOSED / FAILED / LOAD_EXPIRED`
- `reward_verification_status`：`PENDING / SIGNED_VERIFIED / REJECTED / VERIFY_TIMEOUT`
- `entitlement_status`：`NONE / GRANTED / SECURITY_REVOKED`
- `revenue_status`：`NONE / IMPRESSION_PENDING_REWARD / FROZEN / RECONCILING / RECONCILED / SUSPENSE`

所有推进使用当前状态与 `version` 的条件更新。客户端 close/fail 永远不能覆盖已经完成的服务端奖励、权益或收入事实。

```text
client_lifecycle_status:
CREATED -> LOADING -> SHOWN -> CLIENT_REWARDED -> CLOSED
   |          |         |             |
   +----------+---------+-------------+-> FAILED / LOAD_EXPIRED

reward_verification_status:
PENDING --窗口内合法签名 S2S--> SIGNED_VERIFIED
PENDING --无效签名或绑定冲突--> REJECTED
PENDING --超过 reward_accept_until--> VERIFY_TIMEOUT

entitlement_status:
NONE --SIGNED_VERIFIED--> GRANTED --确认安全欺诈且全审计--> SECURITY_REVOKED

revenue_status:
NONE -> IMPRESSION_PENDING_REWARD -> FROZEN -> RECONCILING -> RECONCILED
                            |                         |
                            +-------------------------+-> SUSPENSE
```

客户端事件只帮助监控状态，不是状态推进到 `REWARD_VERIFIED` 的充分条件。平台 S2S 可以在客户端事件之前到达，服务端仍应正常完成验证。

### `skit_content_entitlement`

`skit_content_entitlement` 保存当前逐集权益，每一集使用 `(tenant_id, member_id, drama_id, episode_no)` 唯一；追加式 `skit_entitlement_grant` 保存每次合法广告会话、平台交易、授予结果和已有权益命中情况。这样即使会员已拥有该集，也不会丢失后续合法回调的审计证明，但服务端会在创建会话前阻止为已拥有范围重复看广告。

App 不再把本地存储当作授权事实：

- 登录、切换租户、进入剧集和 App 恢复前台时，从后端刷新权益。
- 本地只缓存服务端返回且绑定当前租户、会员和版本的权益，用于 UI；缓存不能让后端受保护内容放行。
- 如果视频是平台原生播放器，原生插件必须等待后端会话变为 `ENTITLED` 后才调用奖励成功。
- 如果视频 URL 由本平台控制，播放地址必须由后端根据权益签发短时 URL；公开固定 URL 无法仅靠客户端防止破解。
- 服务端逐集权益永久有效；重复观看同一集不再消费广告。财务报表后续降额或冲正不影响普通会员已经获得的内容权益，只有确认账号盗用或伪造平台证明的安全事件才允许 `super_admin` 通过独立、全审计流程撤销。
- 离线缓存只能播放已由服务端签发且尚未过期的受保护缓存；完整离线下载和 DRM 不在本次范围。

页面内播放可以直接使用会员 OAuth 令牌创建会话。DJX 独立原生播放器不能接收或长期保存完整会员令牌：JS 打开原生播放器前先申请一个短时 `native_player_grant`，只允许为固定租户、会员和剧目创建广告会话及查询权益；原生活动用该 grant 完成同一套服务端流程，退出或过期后失效。

### App 统一协议

JavaScript 请求后端创建会话后，只把服务端结果传给原生：

```json
{
  "protocolVersion": 1,
  "sessionId": "server-generated-id",
  "provider": "TAKU",
  "placementId": "tenant-placement",
  "userId": "opaque-user-id",
  "customData": "opaque-one-time-session-token",
  "scene": "drama_unlock"
}
```

原生返回严格、版本化的遥测，不允许宽松识别 `type=complete` 等别名：

```json
{
  "protocolVersion": 1,
  "sessionId": "server-generated-id",
  "provider": "TAKU",
  "placementId": "tenant-placement",
  "sdkRequestId": "sdk-request-id",
  "providerShowId": "taku-show-id",
  "networkFirmId": 66,
  "adsourceId": "adsource-id",
  "callbackSequence": 3,
  "nativeState": "CLOSED",
  "clientRewardObserved": true,
  "closed": true
}
```

`customData` 是至少 128-bit 加密安全随机数的短 base64url token，数据库只保存哈希。它只用于把已验签的平台回调关联到既有会话，不能单独作为 bearer proof 授奖；一个会话只能绑定一个 provider transaction。原值不进入日志、客户端遥测、管理端响应或导出。奖励回调用 `extra_data` 查 token，并验证 `trans_id == providerShowId`；展示回调用 `show_custom_ext == sessionId` 关联，并以 `req_id + adsource_id` 防重。

同一广告的 started、reward 和 close 遥测必须携带相同 `sessionId/providerShowId`，`callbackSequence` 单调递增。SDK readiness 也必须返回其绑定的 `sessionId/placementId`；全局 `LOADED` 不能证明当前会话的广告已经准备好。

Taku 原生层必须为每个会话新建广告实例，在每次 `load()` 前通过 `setLocalExtra` 设置 `ATAdConst.KEY.USER_ID` 和 `ATAdConst.KEY.USER_CUSTOM_DATA`，然后使用 `ATShowConfig.Builder.showCustomExt(sessionId)` 展示，并从 `ATAdInfo.getShowId()` 读取 `providerShowId`。当前内置 Taku 6.6.22 已支持这些 API，因此它们是强制路径，不是可选兼容项。删除匿名 `preload()`、关闭后自动预加载及任何跨会话缓存；Taku 奖励回调必须满足 `trans_id == providerShowId`，展示回调通过 `show_custom_ext == sessionId` 关联会话。

原生桥独立暴露 `UNINITIALIZED / INITIALIZING / LOADING / LOADED / SHOWING / ERROR`，不再把“桥方法存在”当作“广告可展示”。

## 平台回调入口

### 回调地址与租户解析

每个代理商广告账号生成至少 128-bit 随机的 `callback_key`，数据库只保存哈希。它是展示 S2S 的 bearer secret 和奖励 S2S 的附加路由秘密，不能代替奖励签名。后台展示以下可复制地址和最近一次验证状态：

- `/app-api/skit/ad-callback/taku/{callbackKey}/reward`
- `/app-api/skit/ad-callback/taku/{callbackKey}/impression`
- 对应第三方广告平台的独立奖励回调地址

回调控制器忽略普通租户上下文，只能通过 `callback_key` 找到广告账号。奖励回调再使用该账号加密保存的服务端密钥验签并确定租户；展示回调标记为未签名观察。请求中的 `tenantId`、会员 ID 或 visit-tenant header 永远不参与授权判断。

callback key 与服务端密钥都带版本。轮换时新会话只使用新版本，旧版本只在既有会话的 `reward_accept_until` 内并行接受；宽限期结束立即吊销。泄露处置会停止新会话、轮换 key/密钥、标记受影响时间窗并重放审计，不在日志、遥测或管理端回显原值。

### `skit_ad_callback_inbox`

公网回调先进入收件箱，再异步/短事务处理：

- provider、callback type、ad account、transaction/show/request ID
- canonical payload hash、`authentication_level`、签名结果、HTTP 接收时间、处理状态与错误码
- 必要的规范化字段；原始数据加密或脱敏保存并设置有限保留期
- canonical inbox 统一唯一键 `(ad_account_id, callback_type, idempotency_key)`；奖励 key 是 `trans_id`，展示 key 是规范化 `req_id + adsource_id`

另建追加式 `skit_ad_callback_attempt` 保存每次投递；canonical inbox 只保存首个事实。MySQL 使用 `INSERT ... ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)` 取得 canonical ID，再 `SELECT ... FOR UPDATE` 比较 payload hash，避免 `REPEATABLE READ` 下普通“先查、插入失败、再查”的不可见问题。相同 key 不同 payload 保留 attempt 并标记安全冲突，不覆盖原事实。

Taku 奖励回调按官方字段顺序计算 MD5，使用常量时间比较签名。`trans_id` 必须幂等且等于客户端 `providerShowId`。成功持久化或确认幂等才返回 200，无效签名返回 601，确定的业务拒绝返回 602；由于 601/602 不会触发重试，瞬时数据库或基础设施故障不得伪装成确定失败，应让请求超时/连接失败以触发平台有限重试。`is_test=1` 且占位符未替换的配置探测走独立健康检查路径，永不创建会话、权益、收益或账本。

展示回调没有签名且没有重试，入口只做大小限制、callback key、账号/广告位、必填 `show_custom_ext`、防重和高可用持久化并尽快返回 HTTP 200。缺失或无法匹配 `show_custom_ext == sessionId` 的观察进入 `UNMATCHED/SUSPENSE`，不得分配给会员。后续匹配与计算失败进入内部重试队列和告警，不能要求 Taku 重发。

Taku 后台健康检查强制奖励回调 URL 包含并实际回传 `user_id、extra_data、trans_id、placement_id、adsource_id、network_firm_id、reward_amount、reward_name、sign`；展示回调必须包含 `user_id、req_id、placement_id、adsource_id、adsource_price、currency、timestamp、show_custom_ext`。缺少任一关联、幂等或验签字段时，广告账号状态不得进入生产就绪。

一期明确采用 Taku 作为唯一激励广告主控：穿山甲只是 Taku 内的 ADN，穿山甲 DJX SDK只负责短剧内容。仓库中不存在的 `SkitGroMoreAd` 以及当前无广告直接奖励的 Pangle fallback 不作为生产路线；fallback 在新 APK 发布前删除并由静态测试永久禁止。若以后要支持 Pangle/GroMore 直连，必须另行完成原生桥、服务端奖励、唯一交易 ID、收益回调和报表能力后才能启用。

奖励权威按 `network_firm_id` 配置并由后台 capability 门禁：

| 网络类型 | 奖励权威 | Taku S2S 用途 | 生产条件 |
|---|---|---|---|
| Taku ADX 66、直投 67、交叉推广 35 | Taku 签名 S2S | 权威 | 所需占位符、签名和唯一交易全部通过 |
| 穿山甲及其他第三方 ADN | 该 ADN 自身 S2S | 仅一致性证据 | ADN 能回传会话、用户、稳定交易 ID 并通过真机测试 |
| 无可靠第三方 S2S 的 ADN | 无 | 仅遥测 | 从解锁专用流量分组中排除，禁止生产解锁 |

`skit_ad_network_capability` 记录 network firm、奖励权威、UserID/custom data 支持、稳定交易 ID、展示收益、报表能力、验证时间和启用状态。Taku 与第三方两套回调只能确认同一奖励事实，不能重复授予权益或生成两笔收入。

### 防作弊与异常标记

以下情况保存证据、拒绝奖励或进入人工审计：

- 无效签名、未知 callback key、未知广告位或广告账号不匹配
- 会话 token、伪匿名 UserID、会员、广告位、场景或解锁范围不一致
- 同一 transaction/show/request ID 被不同会员或不同租户重放
- 客户端声称奖励但规定时间内没有服务端奖励
- 服务端奖励存在但客户端从未展示、异常快速关闭或设备事件序列明显缺失
- 同设备/账号在短时间内超出可配置频率、并发会话过多或持续失败重试
- 回调载荷过大、非法编码、时间偏差异常或同一载荷哈希高频重复

风控只可以阻止新会话或把事件送审，不能静默修改平台已确认的收入。人工操作必须限 `super_admin`，记录原因、操作者和前后状态；不提供直接编辑交易或账本金额的能力。

## 收益事件、对账与追加式账本

### 展示级预估收入

奖励回调决定是否解锁，展示 S2S 决定是否创建展示级预估收入。预估金额来自平台展示回调的 eCPM/展示收益字段，按平台定义转换为单次展示金额，并保存原始精度、币种和计算方法。客户端 eCPM 只用于排障对比，不参与资金计算。

分成资格采用明确口径：合法展示都会计入代理商广告收入；只有在 `reward_accept_until` 内通过权威奖励 S2S 的展示，才按会话策略快照分给会员本人和上级。展示回调先到时收入进入 `IMPRESSION_PENDING_REWARD` 暂挂；奖励随后通过则形成会员/代理商冻结分成，窗口结束仍无奖励则本次展示收入 100% 归代理商并标记 `NON_REWARDED_IMPRESSION`。用户提前关闭或偷偷跳过不会获得内容权益，也不会获得本人/师徒分成。

如果奖励已经验证但展示回调尚未到达，会话显示 `已解锁 / 待收益数据`；展示观察必须匹配合法会话，未签名观察本身不能授予权益或转为可结算。

### 扩展 `skit_ad_revenue_event`

在保留旧 ID 和审计历史的前提下增加：

- `session_id`、`callback_inbox_id`、`source_type`
- `provider_transaction_id`、`provider_show_id`、`sdk_request_id`、`adsource_id`
- `source_amount`、`source_currency`、`estimated_amount`
- `reconciled_amount`、`reconciliation_status`、`reconciled_at`
- `plan_id`、`rule_version`、邀请链/资格快照引用
- `payload_hash`、`verification_status`、`verified_at`

收入事件不用单一状态混合多个事实，而是分别保存 `match_status`、`source_verification_status` 和 `reconciliation_status`。典型值包括 `UNMATCHED/MATCHED`、`UNSIGNED_OBSERVATION/REPORT_CONFIRMED/REJECTED`、`FROZEN/RECONCILING/RECONCILED/SUSPENSE/REVERSED`；状态转换使用版本号 CAS 和数据库唯一键。

金额永远携带 ISO 4217 币种，并以整数 `amount_units + amount_scale` 参与分配和守恒运算；展示层再转换为十进制字符串。不同币种不得直接求和，前端按币种分组显示。若以后需要统一人民币结算，必须新增带来源、日期和版本的外汇转换事件，不能在查询时使用隐式实时汇率。

### 官方报表对账

每个代理商使用自己的 Taku 账号拉取聚合报表，并按已验证 capability 接入第三方 ADN 报表。定时任务必须：

- 按账号串行、限流、错峰执行，凭证不进入日志。
- 保存报表请求范围、响应哈希、平台修订版本、拉取时间和处理结果。
- D+1 首次对账，按平台数据成熟度在 D+2/D+3 继续修订；只有达到平台定义的稳定状态才标为最终。
- 报表通常不是逐展示最终金额时，按租户、账号、App、报表时区日期、专用广告位、network、广告源和币种组成对账桶，并保存 `report_timezone`。
- 报表 revision 唯一键为 `(ad_account_id, bucket_key, report_date, revision_hash)`；相同响应重复拉取不生成新 revision。
- 分成广告必须使用只服务于 `drama_unlock` 的独立激励视频广告位，不能与开屏、插屏、普通激励或其他 App 流量共用。报表查询维度必须至少能隔离账号、该广告位、激励视频格式、广告源、日期/账号时区和币种；不能隔离时整桶保持 `UNMATCHED`，不得推算会员收入。

对账桶内设预估总单位 `E_units`、平台实际总单位 `A_units`、平台展示数 `N`、全部已匹配合法会话的展示数 `K`，以及其中通过奖励验证的展示集合 `R`。`K` 包含未奖励展示：它们对账后 100% 归代理商，不会被错误放入暂挂。先验证报表范围与专用广告位一致，再计算可归属实际单位 `A_d_units`：

1. `N = K` 时 `A_d_units = A_units`；
2. `0 < K < N` 且报表维度完整时，使用向下取整的整数除法 `A_d_units = (A_units * K) / N`，`A_units - A_d_units` 全部保留为 `UNMATCHED`，不能分给已匹配会员；
3. `N = 0`、`K > N`、缺少展示数或报表范围混入其他流量时，整桶进入差异审计，不创建正式结算分录；
4. 在可归属部分内，对每个展示事件计算整数商 `actual_i_units = (A_d_units * estimate_i_units) / E_units`，再把整数除法余数按稳定事件 ID 顺序每次分配一个 `amount_scale` 记账单位，使全部 `K` 个事件严格等于 `A_d_units`；
5. 对 `R` 中事件按会话策略快照分成；`K - R` 中事件的 `actual_i_units` 100% 记给代理商；
6. `E_units = 0` 且 `A_d_units > 0` 时创建 `UNMATCHED` 差异，不猜测事件金额；
7. 平台后续修订只追加新 reconciliation revision 和调整分录。

每个桶必须满足 `matched_actual + unattributed_actual = report_actual A`。无法归属部分进入代理商 `SUSPENSE` 暂挂，不计入代理商可结算留存，也不分给会员；只有后续补齐展示事实或有明确、审计过的归属策略才能释放。

### 追加式 `skit_commission_ledger`

账本不覆盖旧金额。扩展字段包括 `entry_type`、`balance_bucket`、`currency`、`amount_units`、`amount_scale`、`reversal_of_id`、`reconciliation_id/revision` 和事件快照引用：

- `ESTIMATE`：按展示预估收入创建正数冻结分录。
- `ESTIMATE_RELEASE`：对账时创建等额负数冻结分录，使冻结余额归零。
- `SETTLEMENT`：按实际收入和会话创建时锁定的规则快照创建正数可结算分录。
- `ADJUSTMENT`：平台修订产生正数或负数可结算调整。
- `REVERSAL`：平台明确撤销或确认欺诈时，引用原分录追加冲正。

同一事件同一受益人同一分录类型和 reconciliation revision 唯一。冻结、可结算和已冲正余额通过追加分录求和，不通过人工更新历史金额得到。

每个 reconciliation revision 保存“截至本版本的累计目标金额”。首次对账为每个原始冻结分录追加且只追加一次 `ESTIMATE_RELEASE`，再写 `SETTLEMENT`；D+2/D+3 修订重新计算事件和受益人的累计目标，只追加 `target_current - target_previous` 的 `ADJUSTMENT`。报表正常下调只使用 `ADJUSTMENT`；`REVERSAL` 仅表示独立的欺诈或平台撤销事实，二者不得处理同一减少额。

账本持续满足以下守恒式：

- `Σ ESTIMATE = estimated_amount`
- `Σ ESTIMATE_RELEASE = -estimated_amount`
- `Σ SETTLEMENT + ADJUSTMENT + REVERSAL = current_reconciled_amount`
- `Σ 全部受益人分录 + suspense = 权威收入事件或报表桶金额`

### 分成计算

分成方案继续以基点表示比例：

- `level 0`：广告会员本人
- `level 1`：师傅
- `level 2`：师祖
- `level N`：向上第 N 级邀请人
- `level -1`：代理商留存，仅作为计算和展示结果，不由用户编辑层级号

发布规则时总会员比例不得超过 100%。后台始终显式展示“代理商当前留存 = 100% - 已配置会员比例”，并提供假设收入的分配预览。计算过程对每个受益人向下截断到统一精度，全部未分配余额归代理商，保证每个事件按币种严格守恒。

广告会话创建时固化方案 ID、版本、各层会员 ID、状态资格和比例；收入事件与冻结分录只引用该快照。正式结算和后续调整复用同一快照，不重新读取当前邀请树或当前规则。

## 邀请关系完整性

广告分成依赖邀请链，因此同时补充 `skit_invite_code_registry`：

- `code` 全局唯一，记录 owner type、tenant、owner、状态和轮换时间。
- 代理商根邀请码和会员邀请码在同一张注册表抢占，消除两张业务表分别查询造成的并发碰撞。
- 代理商轮换、会员注册、邀请码认领和闭包写入使用一致事务。
- 已使用的邀请码关系不可改绑；禁用邀请码只阻止新注册，不改变历史师徒关系。

管理端师徒树按直属下级分页懒加载，搜索会员后展示祖先面包屑、直属/全量后代数、广告次数、预估收益、可结算收益和各级贡献，禁止一次加载整棵租户树。

## API 设计

### App 会员 API

- `POST /skit/member/player-grants`：为固定会员、租户和剧目签发短时原生播放器权限，不包含完整 OAuth 能力。
- `POST /skit/member/ad-sessions`：创建一次性广告会话并返回原生协议参数。
- `POST /skit/member/ad-sessions/{sessionId}/client-events`：批量/幂等记录客户端遥测，不产生权益或资金。
- `GET /skit/member/ad-sessions/{sessionId}`：获得验证、权益和收益数据状态，供关闭广告后轮询及断网恢复。
- `GET /skit/member/entitlements`：按剧目查询当前会员的服务端权益。
- 旧 `/skit/member/ad-revenue/report`：过渡期只记客户端遥测并返回 deprecated 标志，不再创建收益或账本。

创建会话只能从登录 token 派生租户和会员；provider、广告位及账号由服务端按租户配置解析。客户端不能扩大服务端返回的 `unlockScope`。

### 广告平台回调 API

- Taku 奖励 S2S 与展示 S2S。
- 穿山甲/聚合内其他生产网络的独立服务端奖励回调。
- 回调健康测试只验证路由和配置，不授予权益、不创建收益。

### 管理端 API

- `/skit/tenant/ad-analytics/overview`：请求、展示、客户端奖励、服务端验证、跳过、失败、独立会员、冻结/已结算收益、各层分成和代理商留存。
- `/skit/tenant/ad-analytics/timeseries`：按小时/日和币种返回趋势。
- `/skit/tenant/ad-events/page` 与详情：查看事件、会话、回调轨迹、验签、对账和分录。
- `/skit/tenant/reconciliation/page` 与差异详情：查看报表拉取、未匹配和修订。
- `/skit/tenant/commission-plans/current`、`history/page` 和 `preview`：显示版本、发布时间、历史差异与分配预览；保存带 `expectedVersion` 防并发覆盖。
- `/skit/tenant/member/{id}/children`、`ancestors`、`subtree-summary`：师徒树懒加载和贡献汇总。

管理 API 响应统一返回稳定状态枚举、`asOf`、账号 `timezone`、ISO 币种和十进制金额字符串；列表使用稳定游标或明确排序分页。错误码区分配置未就绪、签名失败、会话过期、验证中、已授权、幂等冲突和报表过期。大数据导出创建异步导出任务，继承发起人的租户/字段脱敏权限并生成短时下载地址，不能只导出当前前端页。

所有管理 API 继续使用原始登录租户判定，权限固定如下：

| 能力 | `tenant_admin` | `super_admin` |
|---|---|---|
| 代理商创建、归档、恢复、App 发布 | 无 | 可写 |
| 广告账号、密钥、callback key | 仅绑定租户可写 | 可代管任意租户 |
| 分成规则发布与预览 | 仅绑定租户可写 | 可代管任意租户 |
| 广告监控、师徒树、分成账本 | 仅绑定租户只读 | 任意租户/全局只读 |
| 脱敏回调与报表状态 | 仅绑定租户只读 | 任意租户只读 |
| 原始回调、设备证据、跨租户异常 | 无 | 脱敏后按需查看 |
| 安全撤销权益、重放 canonical inbox | 无 | 可操作，必须填写原因 |
| 普通同步任务重试 | 仅绑定租户、单任务限次 | 任意租户、单任务或受控批量 |

回调明细、账本和平台报表不可由任何管理员增删改；代管写操作与任务重试全部记录目标租户、原因和操作者。`super_admin` 的“审计”默认只读，只有表中明确列出的代管操作可以写。

## 管理端界面

全部新功能继续放在“短剧 SaaS”下，不新增顶级菜单；“首页”保留并改为真实汇总。

| 顶级/子菜单 | 位置 | `tenant_admin` 默认范围 | `super_admin` 默认范围 |
|---|---|---|---|
| 首页 | 顶级保留 | 自动锁定本租户 | 全代理商汇总，可筛选单租户 |
| 代理商管理 | 短剧 SaaS 子菜单 | 不显示 | 全部租户 |
| 广告监控 | 短剧 SaaS 子菜单 | 本租户 | 全部租户或所选租户 |
| 会员与师徒 | 短剧 SaaS 子菜单/代理详情 | 本租户 | 所选租户 |
| 分成规则 | 代理详情标签 | 本租户 | 所选租户 |
| 分成账本 | 代理详情标签 | 本租户 | 所选租户 |

1. “广告监控”替换现有可编辑样例广告记录：展示漏斗、平台健康、异常率、收益状态、事件表和对账差异。
2. “分成规则”显示当前版本、发布时间、本人/师傅/师祖等层级、代理商显式留存、100 元收益预览和历史版本。
3. “分成账本”支持会员、受益类型、状态、平台、币种、时间和事件 ID 筛选，分别显示冻结、可结算、调整和冲正。
4. “会员与师徒”增加懒加载关系树和节点贡献抽屉，保留现有手机号分页管理。
5. 代理商详情显示独立 Taku/穿山甲回调 URL、密钥是否配置、最近回调、最近报表、广告数、收益、分成和留存；密钥永不回显。
6. 首页移除硬编码经营数字和接口失败后的模拟收益。加载失败显示错误，空数据就是零并标记数据时间，绝不伪造成功数据。本次没有买量成本，因此删除“利润、ROAS”等 KPI，只显示请求/奖励漏斗、预估冻结收益、已对账收益、会员分成和代理商留存。
7. 删除旧系统配置、抖音配置或通用记录里与真实分成引擎重复的“本人佣金/代理佣金”等字段和入口，分成方案成为唯一事实来源。

`tenant_admin` 在自己的租户页拥有广告接入标签，可维护本租户账号、复制回调地址并执行健康测试；`super_admin` 可以跨租户完成相同操作。只有 `super_admin` 可以创建、归档或恢复代理商，广告配置能力不会扩大代理商生命周期权限。

广告接入就绪状态逐项展示：App/账号与密钥、解锁专用广告位、callback key、奖励回调占位符、最近真实签名奖励、最近 `show_custom_ext` 展示回调、network capability、报表权限、最近同步、新 App 版本和最低版本门禁。任何必需项失败时生产解锁开关保持禁用。

运维告警至少覆盖回调入口延迟/错误率、验签失败率、未匹配展示、客户端奖励但无 S2S、收件箱/重试队列积压、冻结超龄、报表过期和对账差异。阈值按租户和平台配置，通知 `tenant_admin` 及平台运维；单任务和批量重试均有限次、退避、幂等和审计原因。

金额组件读取接口返回的币种，不再无条件显示人民币符号。首页与跨租户聚合遇到多币种时按币种分组展示，不做隐式相加。师徒贡献固定要求日期范围、账号时区、币种和统计口径；直属下级使用 `(parent_id, create_time, id)` 稳定游标分页。

## App 与更新策略

本次原生改造包含 Taku local extra、严格 bridge 协议、平台 `providerShowId`、SDK 状态和 WebView origin 限制，需要发布一次新的基础 APK。该版本之后：

- 会话、比例、监控、对账和大部分交互通过后端及热更新包迭代。
- 原生 bridge 采用版本化向后兼容协议；新增可选字段不要求重新发包，破坏性协议才提升 major version。
- 每个代理商继续绑定独立 Taku 和穿山甲账号与白标构建档案；一期激励由 Taku 主控，穿山甲账号用于 Taku ADN/短剧内容。SaaS 后台生成回调地址、能力检查与发布状态。
- 每个租户具备 `OFF / SHADOW_TEST_USERS / ENFORCED` 能力开关。只有密钥、专用广告位、所需回调占位符、真实奖励/展示回调、报表权限、最近同步、新 APK 和最小版本全部就绪，才能从测试账号影子验证原子切换到 `ENFORCED`。
- 切换 `ENFORCED` 与提高 `minNativeVersion` 是同一个后台操作；服务端同时拒绝旧版本的 player grant、受保护内容 URL 和广告会话，避免后端与 App 切换窗口。

仅本地打包资产 origin 可以持有原生 `JavascriptInterface`；原生 bridge 每次调用再次校验当前顶层 URL。所有外部 HTTP/HTTPS，包括 SaaS 页面，都交给系统浏览器；SaaS API 只供本地页面 XHR 调用，不作为可持有 bridge 的顶层文档。

热更新包使用原生内置公钥验证的签名清单，清单绑定租户、applicationId、bundle hash、协议版本和单调递增版本号；原生拒绝签名不正确、租户/applicationId 不匹配和版本回滚。仅由下载 URL 同时提供 SHA-256 不构成信任。

旧 APK 的本地奖励代码无法只靠后端热更新消除。正式切换前必须为新 APK 使用新的或已轮换的 Taku 应用/专用广告位配置，禁用旧广告位，并在穿山甲/DJX 平台撤销或轮换旧应用的 license/内容访问能力；服务端内容只接受新权益。如果供应商无法远程撤销旧 DJX 配置，则该旧 APK 仍可能本地调用 fallback，这是发布阻塞风险，不能宣称已经封死，直到真机证明旧 APK 无法访问受保护剧集。

## 数据库与迁移

使用现有版本化 `SkitSchemaInitializer` 和 bootstrap SQL 同时维护最终结构，迁移至少包括：

- 新建策略快照、广告会话、callback attempt/canonical inbox、network capability、当前内容权益/追加式授予、报表拉取、对账桶/修订、租户能力开关和邀请码注册表。
- 扩展收益事件与追加式账本字段、状态、唯一键和查询索引。
- 可行的租户一致性外键/复合约束、金额非负或分录允许负数的类型约束、状态约束和币种格式约束。
- 为存量邀请码建立全局注册表；发现代理商根邀请码与会员邀请码冲突时迁移失败并输出冲突审计，不静默改绑。
- 存量 `ESTIMATED` 客户端事件标记为 `LEGACY_UNVERIFIED`，永远不可转为可结算；不删除历史。

迁移可重复启动，checksum 不匹配或数据冲突时阻止应用在半升级结构上运行。

## 并发、失败与恢复

- 相同回调并发 20 次只能形成一个 inbox 事实、一个收益事件、一组权益和一套分录。
- 使用唯一键、行锁/条件更新和事务后读取处理幂等，不能依赖 MySQL `REPEATABLE READ` 下的普通“先查再插再查”。
- 回调、客户端关闭、展示回调和报表可以任意乱序；匹配任务最终收敛。
- 广告关闭后 App 以 0.5s、1s、2s、3s、3s 退避短轮询，最多交互等待约 10 秒；未收到 S2S 时显示“奖励验证中”并允许用户离开。App 关闭、断网或回调延迟时，把 `sessionId` 加入当前账号待验证队列，恢复前台后继续查询；迟到成功发站内提示并刷新权益。`reward_accept_until` 前不允许相同范围重新观看，超时后才显示重试入口；客服只能查看轨迹或升级安全工单，不能直接改账。
- 已归档租户不能创建新会话；归档前创建且仍在有效期内的会话可以由合法 S2S 完成原定权益和入账，过期会话只保存迟到回调供审计。归档前已展示广告的后续正式报表仍需完成财务对账。
- 报表 API 暂时失败保留冻结余额并重试，不把预估金额提前转为可结算。
- 负向调整导致可结算余额不足时保留负余额和风控状态，不删除历史分录。

## 安全与隐私

- App Key 和服务端 Secret 分开保存；服务端 Secret 加密、write-only、不进入构建产物或日志。
- 会话 token 使用加密安全随机数，数据库只存哈希；UserID 使用租户内稳定伪匿名值，不传手机号。
- 回调原文按最小必要原则保存，敏感设备标识散列/脱敏。默认保留：原始回调密文 90 天、投递 attempt 180 天、设备风险散列 180 天、导出文件 24 小时；财务事件、对账 revision、权益授予和账本长期保留。`tenant_admin` 只能看本租户脱敏字段，`super_admin` 按安全审计权限查看必要明细，手机号、token、密钥和完整设备标识永不导出。
- 签名比较、payload canonicalization、URL 解码和重复参数采用固定实现并覆盖安全测试。
- 公网回调限制方法、请求大小、参数长度、速率和日志字段，未知 callback key 不泄露租户存在性。
- 所有人工重试、配置更新、规则发布和异常处理进入操作审计。

## 服务目标

- 奖励/展示回调正常负载下服务端响应 P95 小于 500ms，且不超过平台 2 秒时限。
- 已成功持久化的签名奖励到权益可见 P95 小于 2 秒、P99 小于 5 秒。
- 回调收件箱正常积压小于 1 分钟，超过 5 分钟触发告警。
- D+1 报表在账号时区次日 12:00 前完成首次同步；超过 24 小时标记数据过期。
- 师徒直属下级查询和管理端聚合在典型租户数据量下 P95 小于 1 秒；大范围导出走异步任务。

## 测试策略

### 后端单元与数据库集成

- 先写失败测试，再实现会话状态机、Taku 签名、回调 canonicalization、分成守恒和对账比例分配。
- 使用 Testcontainers MySQL 验证真实唯一键、事务、锁和并发：同交易 20 次、防重载荷冲突、回调乱序、失败回滚和规则并发发布。
- 覆盖错误 callback key、签名、账号、广告位、会员、`sessionId`、`providerShowId`、transaction ID、过期、归档租户和跨租户攻击。
- 覆盖本人/师傅/师祖/任意层级、缺失或停用祖先、比例变更、舍入、零金额、多币种、负向调整和严格金额守恒。
- 覆盖 D+1 对账、报表修订、`E=0/A>0`、未匹配、重复报表和部分任务失败。
- 覆盖邀请码轮换与会员注册并发冲突。

### App 与原生

- Taku `onReward -> close`、未奖励关闭、加载/播放失败、重复 reward/close、销毁、超时和迟到 S2S。
- 缺失或篡改协议版本、`sessionId`、`providerShowId`、UserID、custom data、provider、广告位或解锁范围全部失败。
- 删除所有“广告不可用仍奖励成功”的 fallback，并增加静态回归测试禁止无平台证明调用 `onRewardVerify(true)`。
- A 会员/A 租户权益不能被 B 会员或 B 租户继承；本地存储修改不能让后端内容接口放行。
- 外部网页无法调用任何 `Skit*` 原生 bridge。
- 页面播放与 DJX 原生播放器复用同一广告会话和服务端权益语义。
- 反编译正式 APK 确认没有服务端 Secret；验证 `setLocalExtra` 一定先于 `load`、匿名 preload 不存在、真实展示 S2S 回传 `show_custom_ext`、`getShowId == trans_id`。
- 真机逐个验证解锁专用流量组内的 Taku ADX 和每个第三方 ADN capability；旧 APK、旧热更新包和降级安装均不能访问受保护剧集。

### 管理端与端到端

- `tenant_admin` 只能看到本租户，`super_admin` 可以跨租户查看但不能编辑不可变流水。
- 广告漏斗与数据库事实一致；筛选、分页和导出均由服务端执行。
- 接口失败不出现模拟数据；多币种不混算；规则版本和代理商留存预览准确。
- 完整 E2E：创建会话 -> 客户端播放 -> 模拟签名回调 -> 服务端权益 -> 冻结分成 -> 模拟报表 -> 可结算分录 -> 管理端可追溯。

## 发布顺序与验收

1. 后端先发布数据库、会话、回调、权益、监控和兼容接口，所有租户能力保持 `OFF`，旧客户端金额立即停止进入新资金闭环。
2. 为每个代理商配置 Taku 解锁专用广告位、所需回调和报表权限；第三方 ADN 逐个通过 capability 检查，未完成的网络从解锁流量组排除。
3. 发布新基础 APK，删除本地奖励 fallback，验证 UserID、custom data、`providerShowId`、本地资产 bridge、签名热更新和新 player grant。
4. 对测试账号开启 `SHADOW_TEST_USERS`，完成正常看完、提前关闭、重复回调、错误签名、断网恢复、展示匹配和次日报表对账。
5. 禁用/轮换旧 Taku 广告位及旧穿山甲/DJX 应用能力，真机确认旧 APK 无法访问受保护剧集。
6. 单次后台操作同时提高 `minNativeVersion`、拒绝旧版本内容/player grant 并把租户切换到 `ENFORCED`；不存在旧本地解锁与新服务端校验并行窗口。
7. 发布管理前端并切换首页、广告监控、分成、账本和师徒树到真实 API。

上线验收必须同时证明：

- 没有 `SIGNED_REWARD` 就不能获得服务端权益或会员/师徒分成；没有官方报表确认，任何收入都不能成为可结算收益。
- 本地 `onReward`、篡改 completed/eCPM、复制 `sessionId/providerShowId`、换会员和换租户均无法重复解锁或分账。
- 同一合法广告只产生一次权益、一次收入事件和严格守恒的一套冻结/结算分录。
- 比例修改后新事件使用新版本，旧事件与后续调整始终使用旧快照。
- 首页和广告监控只显示真实数据，失败和空数据状态可辨认。
- `tenant_admin` 无法跨租户，`super_admin` 能审计全部代理商；每个代理商使用自己的广告账号、回调和报表。
- 旧 APK、旧热更新包、降级安装和外部网页均不能调用奖励 fallback 或访问受保护剧集；如果供应商旧 license 无法撤销，该租户不得标记发布完成。

## 明确不在本次范围

- 用户提现、支付通道、税务和财务打款审批；本次只提供可信的冻结与可结算账本。
- 广告主投放预算、买量成本、ROAS 和素材投放优化。
- DRM 产品采购。若自有视频 URL 需要抵抗专业抓包和二次分发，应另行引入签名 CDN 或 DRM。
- 将不同币种自动换算成人民币；该能力需要独立、版本化的汇率与会计设计。
