# Taku 广告展示收益 S2S 回调核查

日期：2026-08-02

状态：协议事实及账号级配置边界已核查；字段必达与回调鉴权无法由当前商务答复确认

范围：Skit SaaS 需要按每次广告展示记录收益时，Taku 账号、应用、回调协议、金额口径、路由与安全边界

## 结论

1. 根据用户提供的 Taku 商务书面答复，广告展示收益 S2S 是**账号级**能力；账号接入后新增应用自动覆盖；一个账号只能使用一个统一回调地址，不能为不同应用配置不同地址。
2. Taku 官方公开协议规定以 `GET` 发起回调，接收端需要在 2 秒内返回 HTTP 200。超时或返回非 200 视为失败，并且 Taku 不重试。官方要求以 `(req_id, adsource_id)` 去重，并建议入口只接收数据、后续异步处理。[来源：广告展示收益服务端回调 API（S2S）](https://help.takuad.com/docs/9frd63)
3. `adsource_price` 是 **eCPM**，不是该次展示已经结算的收入。Taku 对预估收益的官方公式是 `eCPM × 展示数 / 1000`，因此单次展示的估算收益为 `adsource_price / 1000`；`currency` 在当前回调文档中注明暂时固定为 `USD`。[来源：回调参数表](https://help.takuad.com/docs/9frd63)、[基础数据指标](https://help.takuad.com/docs/5e6DnV)
4. 官方参数表列出了 `package_name`、`placement_id` 和 `show_custom_ext`，但参数表没有“是否必传”列，也没有说明缺失或空值规则；其中 `show_custom_ext` 仅注明 SDK 6.3.10 及以上支持。因此，公开文档能证明这些字段存在于协议中，不能证明统一账号回调的每一条事件都必定携带三个非空字段。
5. 当前公开参数表中的国家字段名是 `geo_short`，不是 `country`。当前页面没有列出 `country` 参数。
6. 当前展示收益回调页面没有列出 `sign`、签名算法、鉴权 Header、共享密钥、来源 IP 白名单或重放窗口。不能把 Open API 的 `X-Up-*` 调用鉴权自动套用到 Taku 发往开发者的展示回调上；在 Taku 补充书面安全协议前，该回调只能作为可对账的收益观察事实，不能作为防篡改凭据、最终结算凭据或用户授奖依据。[来源：展示收益回调](https://help.takuad.com/docs/9frd63)、[Open API 接口鉴权说明](https://help.takuad.com/docs/9NzgWP)
7. 用户进一步确认：账号级展示收益回调地址不能通过 Open API 查询、修改或轮换。Skit 必须先生成一个长期固定的账号级接收地址，再由 Taku AM 人工配置。公开的“服务端回调规则管理”接口管理的是**服务端激励**规则，字段为 `reward_url`、`reward_sec_key` 等，不能当成展示收益回调配置接口。[来源：展示收益回调开通说明](https://help.takuad.com/docs/9frd63)、[服务端回调规则管理](https://help.takuad.com/docs/F41YPT)

## 证据等级

| 等级 | 本文如何使用 |
| --- | --- |
| Taku 官方公开文档 | 用于确认 HTTP 协议、参数名和类型、SDK 版本条件、金额公式、超时、响应、重试与幂等要求。 |
| Taku 商务书面答复（由用户于 2026-08-02 提供） | 用于确认账号级开通、新增应用自动覆盖、账号统一回调，以及账号级地址不能通过 Open API 查询、修改或轮换。 |
| 推导或 Skit 设计约束 | 用于说明金额换算、租户路由、安全与对账处理；均明确标为推导，不冒充 Taku 的合同承诺。 |
| 未确认 | 商务没有回答，且公开文档不足以证明生产保证的事项。上线前需要 Taku 书面补充。 |

## Taku 官方公开协议事实

### 请求、响应和投递

| 项目 | 官方协议 |
| --- | --- |
| 请求方向 | Taku 请求开发者提供的接收地址。 |
| 请求方法 | `GET`。 |
| 超时 | 2 秒。 |
| 成功响应 | HTTP 状态码 `200`；文档没有规定响应 Body。 |
| 失败 | 超时或开发者服务器返回失败状态码。 |
| 重试 | 失败后不重新回调。 |
| 去重 | 开发者使用 `req_id` 和 `adsource_id` 组合去重。应实现为二元组唯一约束，而不是无分隔符拼接字符串。 |
| 推荐处理 | 入口只接收和持久化，业务处理异步执行。 |
| 开通方式 | 联系 AM 开通权限并向其提供接收地址。 |

来源：[Taku《广告展示收益服务端回调 API（S2S）》](https://help.takuad.com/docs/9frd63)（页面显示最近修改于 2026-05-20）。

### 回调参数

官方页面只有“参数、类型、说明”三列，没有说明任何字段是否必传、能否为空、最大长度或 URL 编码规则。下表中的“保证边界”是对该缺口的明确记录。

| 参数 | 类型 | 官方语义 | 保证边界 |
| --- | --- | --- | --- |
| `user_id` | `string` | 开发者设置的用户 ID，由 SDK 的 `tk.custom.user_id` 上报。 | 未说明必传或为空行为。不能用于可信租户路由。 |
| `req_id` | `string` | Taku 提供；需和 `adsource_id` 组合去重。 | 未说明账号内还是全局唯一、长度与重放期限。 |
| `geo_short` | `string` | 国家短码，例如 `CN`。 | 协议没有名为 `country` 的参数。 |
| `package_name` | `string` | 当前应用包名。 | 已列入协议，但未注明必传或非空。 |
| `adformat` | `int` | 广告类型：`0 Native`、`1 RewardedVideo`、`2 Banner`、`3 Interstitial`、`4 Splash`。 | 未说明未知值的前向兼容规则。 |
| `placement_id` | `string` | Taku 广告位 ID，由 SDK 上报。 | 已列入协议，但未注明必传或非空。 |
| `nw_firm_id` | `int` | 实际三方广告平台 ID。 | 精确字段名是 `nw_firm_id`，不是奖励协议常见的 `network_firm_id`。 |
| `adsource_id` | `int` | 广告源 ID，由 SDK 上报。 | 是官方去重键的一部分；未注明数值上限。 |
| `adsource_price` | `double` | 该次展示所带的 eCPM，官方示例为 `3.24`。 | 它是 eCPM，不是该次展示最终结算金额。 |
| `currency` | `string` | 货币单位；当前文档注明暂时一定是 `USD`。 | 仍应按事件保存字段值，不能在模型中永久硬编码单币种。 |
| `timestamp` | `int64` | SDK 端广告展示时间戳，单位毫秒。 | 未说明时钟可信度、允许漂移和时区；它是 Unix 毫秒时间戳。 |
| `client_ip` | `string` | 用户 IP。 | 属于个人数据；若收益归属不需要，不应默认长期保存。 |
| `gaid` | `string` | Google 广告设备 ID，仅 Android。 | 未说明必传；受平台权限与隐私选择影响。 |
| `oaid` | `string` | Android 设备 ID。 | 未说明必传。 |
| `imei` | `string` | Android 设备 ID。 | 未说明必传；高敏感度，不应作为收益路由必需字段。 |
| `idfa` | `string` | iOS 广告设备 ID。 | 未说明必传；受用户授权影响。 |
| `idfv` | `string` | iOS 设备 ID。 | 未说明必传。 |
| `amazon_id` | `string` | Amazon 设备 ID，Android 独有。 | SDK 6.2.50 及以上支持。 |
| `show_custom_ext` | `string` | 展示自定义扩展参数；官方示例是一个 JSON 文本字符串。 | SDK 6.3.10 及以上支持；未说明必传、长度、透传完整性或各广告类型覆盖。 |

来源：[Taku 回调参数表](https://help.takuad.com/docs/9frd63)。`nw_firm_id` 的平台枚举由该页直接链接到 [Taku《聚合平台概况》](https://help.takuad.com/docs/2KR6QU)。

## 单次展示金额口径

Taku 的基础指标文档区分了两类金额：

- “收益 API”是 Taku 通过广告平台报表 API 拉取的实际收益；
- “预估收益”按 eCPM 与 Taku 展示数计算，常规广告源公式为 `eCPM × 展示数 / 1000`，竞价广告源使用实时展示价格。

因此，对展示回调的正确解释是：

```text
estimated_impression_revenue = Decimal(adsource_price) / 1000
```

例如，`adsource_price=3.24` 且 `currency=USD` 时，该次展示的估算收益是 `0.00324 USD`。这是由 Taku 官方 eCPM 公式直接推导出的展示级估算值，不是 Taku 或下游广告平台承诺的最终结算金额。

Skit 应保留原始 `adsource_price`、`currency` 和推导后的高精度金额；实时看板可以汇总估算值，但财务口径需要用后续报表 API/账单对账并保留差异。不能将不同 `currency` 的事件直接相加，即使当前协议写明暂时固定 USD。

来源：[Taku《基础名词和数据指标入门》](https://help.takuad.com/docs/5e6DnV)、[Taku 展示收益回调参数表](https://help.takuad.com/docs/9frd63)。

## Taku 商务已确认事实

来源是用户在本次任务中提供的 Taku 商务书面问答，不是从公开网页推断：

| 用户问题 | 商务答复 | 当前结论 |
| --- | --- | --- |
| 广告展示收益 S2S 权限是账号级还是应用级？ | “账号级” | 一个 Taku 账号开通一次。 |
| 账号开通一次后，后续新增应用是否自动生效？ | “只要接入，新增的应用都会” | 按问题上下文，新增应用自动被账号级能力覆盖。 |
| 每个应用是否可以配置不同的展示收益回调地址？ | “不可以，回调为账号统一回调” | 每个账号只有一个统一回调地址，不能按应用配置不同 URL。 |

这些答复解决了开通范围和 URL 数量。用户随后补充确认账号级地址没有 Open API 查询、修改或轮换能力；统一回调中字段的必达保证和回调鉴权仍无法回答。

## 商务问题 4：应用区分字段

问题：统一账号回调是否会回传 `package_name`、`placement_id`、`show_custom_ext` 供区分应用？

当前证据是“两层结论”：

1. **官网事实**：三个字段都在公开参数表中；`show_custom_ext` 需要 SDK 6.3.10 及以上。
2. **仍未确认**：参数表不标必传，商务没有回答，因此不能声称每条统一账号回调都保证三个字段存在且非空，也不能声称 `show_custom_ext` 在所有广告形式、所有三方广告源中都原样透传。

生产路由不能只信任客户端自报的租户 ID。若 Taku 书面确认字段保证，Skit 仍应以账号连接为第一层边界，再交叉核对服务端维护的 `package_name -> 应用`、`placement_id -> 租户/逻辑广告位` 映射和服务端生成的 `show_custom_ext -> 展示上下文`。字段缺失或三者冲突的事件应进入隔离/待对账状态，而不是猜测租户。

需要 Taku 书面补充：

- `package_name`、`placement_id` 是否对该账号下每条展示回调必传且非空；
- `show_custom_ext` 在 SDK 6.3.10+ 下是否对 Native、激励视频、Banner、插屏、开屏全部支持；
- 哪些下游广告平台不支持或会截断该字段；
- 字符串最大长度、字符集、URL 编码、JSON 文本是否会被重新序列化；
- 旧 SDK、字段缺失、未知 `adformat` 时的实际行为。

## 商务问题 5：Open API 或批量配置

问题：是否支持通过 Open API 或批量方式配置新增应用的展示收益回调？

当前结论是：**已确认不支持通过 Open API 查询、修改或轮换账号级展示收益回调地址。**

- 展示收益回调页明确写的是联系 AM 开通并提供接收地址，没有给出创建、查询、修改或删除展示收益回调配置的 endpoint。[来源](https://help.takuad.com/docs/9frd63)
- Open API 的应用管理接口支持通过 `items[]` 批量创建应用，但请求字段中没有展示收益回调地址；这只能证明应用可批量创建，不能证明展示回调可配置。[来源：应用管理新版 v3](https://help.takuad.com/docs/U5VHzbUc)
- Open API 的“服务端回调规则管理”提供 `GET/POST/PUT/DELETE /v3/advanced/reward_rule`，但对象明确是服务端**激励**规则，字段是 `reward_name`、`reward_number`、`reward_url`、`reward_sec_key` 等。[来源](https://help.takuad.com/docs/F41YPT)

账号级能力加上“新增应用自动覆盖”的商务答复，意味着新增应用不需要再次配置展示回调。Skit 只签发一个长期固定的账号级地址，由 Taku AM 人工完成首次设置；提交后禁止 Skit 本地轮换或删除。若发生泄露、域名迁移或地址不可用，系统只能进入阻断状态并通过 Taku AM 人工协调，不能假设自动查询或修改能力。不得把激励规则 API 当作展示收益回调管理 API。

## 签名与安全边界

展示收益回调页面没有公开以下内容：

- 回调签名字段或签名 Header；
- 验签算法、共享密钥或公钥；
- 时间戳重放窗口与 nonce；
- Taku 出口 IP/CIDR 白名单；
- 强制 HTTPS、mTLS 或证书要求；
- 测试回调协议。

Taku 的 Open API 鉴权文档描述的是开发者调用 `openapi.toponad.com` 时使用的 `X-Up-Key`、`X-Up-Timestamp`、`X-Up-Signature`，不能据此假设入站展示回调会携带相同 Header。[来源：接口鉴权说明](https://help.takuad.com/docs/9NzgWP)

在 Taku 提供展示回调专用鉴权规范以前，Skit 应把它定位为“未经密码学证明的实时收益观察”：可以快速持久化、去重、归属和汇总，但需要报表/账单对账；不能用它解锁剧集、发积分或作为不可抵赖结算凭证。

当前商务无法回答是否存在未公开的签名、固定 Header、账号密钥、IP 白名单或 mTLS 方案。该未知项不阻塞先签发“只接收、持久化、隔离”的账号级地址，但会阻塞把展示回调提升为可信结算或授奖证据。

## Skit 接收端必须满足的协议约束

以下是基于官方协议的实现约束，不代表本文件已实施代码：

1. 入口在 2 秒预算内完成最小校验和持久化并返回 200，后续归属、汇总、对账异步执行。
2. 官方去重键是 `(req_id, adsource_id)`；SaaS 同时接多个 Taku 账号时，应在数据库唯一键前加服务端已知的账号连接 ID，形成 `(provider_connection_id, req_id, adsource_id)`，避免不同账号命名空间碰撞。该账号前缀是 Skit 的多账号设计推导，不是 Taku 文档原文。
3. 使用十进制定点精度保存 eCPM 和 `eCPM / 1000`，不要使用二进制浮点作为财务账本金额。
4. 保存原始协议字段与解析结果，但默认不长期保存 `client_ip`、GAID、OAID、IMEI、IDFA、IDFV、Amazon ID；租户路由和收益计算不应依赖设备标识。
5. 账号连接、包名、广告位、广告格式、广告源和 `show_custom_ext` 的所有权交叉检查失败时隔离事件，不自动归给某个租户。
6. 展示收益事件只形成 estimated/reconcilable revenue，最终金额由官方报表或账单对账更新；它与签名激励奖励回调保持完全独立。

## 先行交付决策

当前共享 Taku 主账号只使用一个长期固定的展示收益回调地址：

```text
https://www.yunque8.top/app-api/skit/ad-callback/taku/{connectionKey}/impression
```

`connectionKey` 标识 Taku 账号连接，不标识租户、应用或广告位。它只签发一次；真实值不进入文档、日志、工单或普通管理 API。外部 URL 形状沿用现有 HTTPS/Nginx 前缀，但内部必须先从现有“租户广告账号 Key”改为“账号连接 Key”。在账号级入口部署并通过有效 Key 公网验收之前，不能把现有租户回调地址交给 Taku。

字段必达和回调鉴权无法回答时，入口按以下方式先行：

1. 有效账号连接 Key 且请求大小合法时，先加密持久化，再返回 HTTP 200；不能因为 `package_name`、`show_custom_ext` 或鉴权信息缺失而在持久化前丢弃。
2. 字段充分且所有权交叉校验一致时，异步归属到租户应用；字段不足或冲突时进入 `UNATTRIBUTED / UNVERIFIED` 隔离状态。
3. 第一批真实展示作为字段矩阵和鉴权证据的金丝雀；未知项不阻塞地址签发，只阻塞可信归属、资金分配和授奖。

## 当前无法通过商务答复解决的观察项

1. 统一账号回调中 `package_name`、`placement_id` 是否每次必传、非空且与 Taku 后台配置一致？
2. `show_custom_ext` 是否在 SDK 6.3.10+ 的五种广告格式和所有已接入广告源中都原样回传？不支持清单是什么？
3. Taku AM 完成人工配置后的生效时延、确认方式和紧急变更流程是什么？
4. 展示收益回调是否有未公开的签名、固定鉴权 Header、账号密钥、出口 IP 白名单或 mTLS？
5. `req_id` 的唯一性范围和保留期限是什么？同一展示是否可能因多广告源、竞价更正或平台调整产生多条事件？
6. `adsource_price` 对常规、竞价、Taku ADX、直投和交叉推广分别来自哪里，精度和舍入规则是什么，是否会后续修正？
7. 哪些应用、广告格式、广告源、SDK 版本或地区不会产生该回调？

这些问题不再作为账号级地址签发的前置条件。Skit 通过捕获第一批真实回调建立字段存在性矩阵；在归属字段或鉴权证据不足时，只保留未验证观察和聚合对账，不宣称可信多租户归属、自动化配置或回调真实性已经具备生产保证。

## 官方来源

- [广告展示收益服务端回调 API（S2S）](https://help.takuad.com/docs/9frd63)
- [基础名词和数据指标入门](https://help.takuad.com/docs/5e6DnV)
- [Open API 接口鉴权说明](https://help.takuad.com/docs/9NzgWP)
- [应用管理（新版 v3）](https://help.takuad.com/docs/U5VHzbUc)
- [服务端回调规则管理](https://help.takuad.com/docs/F41YPT)
- [聚合平台概况 / Network Firm ID](https://help.takuad.com/docs/2KR6QU)
