# Lunaria 首版后端 API

仅本机独立开发版：`http://127.0.0.1:18081/api`。技术栈 Java 17 / Spring Boot 4.0.6 / MyBatis 4.0.1 / Gradle 9.4.1，MySQL 为目标，当前实际使用独立 H2；前端与视觉暂停，不影响本清单。

## 通用约定

- 请求 JSON 用 camelCase，数据库视图响应用 snake_case。金额均为整数分，不接受小数元作为金额字段。时间为 `2026-10-25T09:00` 这种无偏移的上海本地时间。
- 先 GET `/session` 获取 `csrf` 与会话 Cookie，再带 `X-CSRF-TOKEN` POST `/demo/login`；登录后再次 GET `/session`。后续请求携带会话 Cookie，所有 POST 需要 CSRF。
- 有状态操作额外带 `Idempotency-Key`，8–100 位字母/数字/冒号/下划线/横杠。相同身份、编号与内容复用原结果；同编号不同内容 409。断网结果未知时复用原编号，不能盲目产生新编号再扣款。
- 新业务防重指纹使用带类型与长度的字段编码，备注中的逗号、等号不会混淆卡 ID 或退款证据，JSON 字段顺序不影响重试。升级前已保存的购卡等单字符串请求可继续复用原结果；旧版多字段指纹无法证明原字段边界，返回 409 并要求先查看原结果，保留原资产与记录，不能自动换编号重提。
- 顾客只能通过服务端会话取得本人身份，不能在购卡、预约或退款中指定其他 customerId。店长只存在一个受控演示账户，没有员工/角色管理接口。
- 演示口令见 README。演示登录仅 demo 注册；prod、混合 profile、非回环绑定、非隔离数据源在初始化前拒绝。微信 code 客户端与绑定已实现但默认关闭且未真实联调；商户支付、外部通知 HTTP 仍关闭，不能通过更换地址“投入生产”。
- 200 为成功；400 为缺少/错误输入；401 未登录；403 角色或 CSRF 不符；404 不存在或非本人记录；409 状态、容量、权益或操作编号冲突。失败用 `{ "message": "中文原因" }`，不会把数据库异常当业务成功。
- 外部服务未配置为 503，附 `code=INTEGRATION_NOT_CONFIGURED` / feature / enabled=false；详见 [外部服务契约与禁用边界](外部服务契约与禁用边界.md)。没有身份、支付或发送成功的假回退。

## 路由清单

下表 `K` 表示需要 Idempotency-Key；所有 POST 仍须 CSRF。

| 方法与 /api 后路径 | 身份 | K | 功能 |
|---|---|---|---|
| GET /session | 可匿名 | — | 会话状态、CSRF、角色、演示模式、上海时区 |
| POST /demo/login | 可匿名，仅 demo | — | `{username,password}`，会话登录；不是微信身份 |
| POST /auth/wechat | 可匿名 | — | `{code}`；默认 503；已验证未绑定返回 409 和 binding_id；绑定后仅登录对应顾客 |
| POST /owner/wechat-bindings/{id} | 单店长 | K | `{customerId,reason}`；关联已验证身份与已有顾客，拒绝覆盖，不合并资产 |
| POST /logout | 已登录 | — | 注销，204 |
| GET /catalog | 已登录 | — | 项目、套餐及单套餐等级价、营业与会员策略说明 |
| GET /slots?serviceId=&date=&cardId= | 已登录 | — | 可约时段；可选 cardId 必须为本人卡，按售卡时长查询 |
| GET /me | 顾客 | — | 本人余额、卡、预约、流水、退款与分口径摘要 |
| GET /me/cards/{id} | 顾客 | — | 本人单卡与不可变权益快照 |
| POST /me/purchases | 顾客 | K | 余额扣款和发卡在同一事务 |
| POST /me/appointments | 顾客 | K | 申请 PENDING，可选 cardId 保留旧卡时长 |
| POST /me/appointments/{id}/cancel | 顾客 | K | 本人取消，不扣次 |
| POST /me/refund-quote | 顾客 | — | 单卡本金试算，只读 |
| POST /me/refunds | 顾客 | K | 单卡退款申请，卡进入 REVIEW 暂停核销 |
| GET /me/refunds/{id} | 顾客 | — | 本人退款、原试算、分版协商证据 |
| POST /me/balance-refunds | 顾客 | K | 独立储值本金申请，与退卡分开 |
| GET /owner/dashboard | 店长 | — | 预约、客户摘要、配置、退款、审计 |
| GET /owner/customers/{id} | 店长 | — | 指定顾客资产、来源流水与分口径摘要 |
| POST /owner/recharges | 店长 | K | 仅本金记账，保留收款来源说明，无赠送金额 |
| POST /owner/appointments/{id}/confirm | 店长 | K | 确认前事务内复核暂停、休息和峰值容量 |
| POST /owner/appointments/{id}/cancel | 店长 | K | 取消并留原因，不扣次 |
| POST /owner/appointments/{id}/move | 店长 | K | 调整时间，保留原时段和原因 |
| POST /owner/appointments/{id}/complete | 店长 | K | 服务结束后核销一次，拒绝短于售卡权益的服务 |
| POST /owner/ledger/{id}/reverse | 店长 | K | 仅核销可冲正一次，恢复余次并追加 REVERSAL |
| GET /owner/refunds/{id} | 店长 | — | 退款与协商历史 |
| POST /owner/refunds/{id}/negotiations | 店长 | K | 保存单卡人工处理/争议说明，其他字段可选；不审批、不付款 |
| POST /owner/refunds/{id}/review | 店长 | K | 明确 approve，人工审核登记；不会执行外部退款 |
| POST /owner/settings | 店长 | K | 营业、休息、容量、暂停、演示门槛；已有预约保留 |
| POST /owner/services | 店长 | K | 按 id 新增/更新项目，不改历史快照 |
| POST /owner/packages | 店长 | K | 按 id 新增/更新同项目次数卡及独立 VIP 价 |
| GET /integrations/status | 已登录 | — | 默认三项关闭；完整身份配置为 CONFIGURED_UNVERIFIED，production_ready=false |
| POST /payments/wechat/prepay | 顾客、需 CSRF | — | 503；不造单、不扣款、不发卡 |
| POST /owner/integrations/wechat/notifications | 店长、需 CSRF | — | 503；不发送、不假记成功 |

## 主流程请求体

购卡：

```json
{"packageId":"facial5"}
```

响应卡保存 `paid_cents/total/duration/list_cents/tier_snapshot/qualification_snapshot/rule_snapshot`。价格由服务端选默认价或该套餐显式等级价，不采用顾客提交价格。新配置不重算旧卡。

预约与核销：

```json
{"serviceId":"facial","startsAt":"2026-10-25T09:00","note":"安静休息","cardId":"本人已有卡ID，可省略"}
```

cardId 省略时采用当前项目时长；选旧卡时采用售卡项目名称/时长快照。核销体 `{ "cardId": "卡ID" }`；服务项目须一致，预约时长须不小于卡快照。确认不扣次，取消不扣次，服务结束后才扣次。调整体 `{ "startsAt":"2026-10-25T14:00", "reason":"与顾客沟通后调整" }`；取消或冲正体 `{ "reason":"原因" }`。

本金充值：

```json
{"customerId":"alice","amountCents":10000,"reference":"演示收款来源/凭据编号"}
```

每笔本金 1–100000000 分，操作编号防重；只是隔离记账，未接银行/微信收款核验。购卡不是第二次充值，核销不再次扣余额。

## 退款与人工协商

申请/试算共用：

```json
{"cardId":"本人卡ID","reason":"CONSUMER","algorithm":"PROPORTIONAL","evidence":"本人申请及原始约定说明"}
```

原因：`CONSUMER / SHOP / OTHER_NON_CONSUMER / DISPUTED`。默认 `PROPORTIONAL` 按原实付分摊，95 元 5 次用 2 次，本金 5700 分。核销冲正按原流水金额恢复，剩余分值重新分摊到尚未使用次数，避免乱序冲正制造或丢失 1 分。

`ORIGINAL_PRICE` 只允许消费者原因，且必须已有售卡时资格/规则证据及合理原价记录；当前默认售卡没有该资格，不能临时按退款时等级赋予。非消费者原因不可原价追扣；不得追收超过预付的原价差额。

普通退卡不需要新增协商步骤，可直接沿原试算审核登记。需要联系店长、保存争议或处理进展时，最小请求仅需说明：

```json
{"note":"已联系顾客，退款原因仍在核对，暂不结算"}
```

以下是可选的拟议处理信息示例，利息、证据和顾客确认标志均不是普通退卡必填项：

```json
{
  "resolvedReason":"SHOP",
  "algorithm":"PROPORTIONAL",
  "principalCents":5800,
  "interestCents":100,
  "agreementEvidence":"双方约定或确认的证据编号与说明",
  "note":"核对原因、更有利约定与利息后登记",
  "customerAcknowledged":true
}
```

保存会追加审计 revision，原原因、试算和旧记录不改写，不要求多轮流程。只补充说明时沿用上一份拟议信息；首次省略利息默认为 0。`customerAcknowledged` 只是可选登记，不是审核门槛，也不代表线上签署。原因有争议、拟议少退或算法不适用的记录仍能保存；只有结算被阻止，不自动选择少退。店长记录原因处理结论后，审核再校验适用规则。本金不能超过原预付款，独立利息可选并由人工核定，不自动计算利率。

审核体（无协商时直接使用原试算金额即可）：

```json
{"approve":true,"amountCents":5800,"note":"已核对最新双方确认与利息依据"}
```

approve 和 amountCents 必须明确提供，不能因字段缺失默认拒绝/批准。金额匹配最新有效协商本金；无协商则匹配原试算。已登记卡款只回原演示余额，利息另记 REFUND_INTEREST，不算本金充值或履约参考值。协商、审批、卡关闭、余额及流水保留关联并受事务/防重保护。重复操作不重复回款。

储值本金退款体 `{ "amountCents":5000,"evidence":"说明" }`。批准后只预留本金，状态 `RESERVED_NO_PAYOUT`，没有外部转账或“已付款”接口。退款行 `amount_cents/max_cents` 保留最初试算，后续协商以 `negotiations` 中 revision 倒序记录及最终关联流水为准。

## 项目、套餐和营业配置

```json
{"id":"facial","name":"舒缓面部护理","duration":60,"listCents":2500,"active":true}
```

```json
{"id":"facial5","serviceId":"facial","name":"面护五次卡","total":5,"priceCents":10000,"vipPriceCents":9500,"active":true}
```

项目 15–240 分钟，套餐 1–100 次，同一个项目。priceCents 至少等于次数，保证每次可分摊至少 1 分。vipPriceCents 仅对该套餐，null/省略表示移除未来专属价，不叠加全店折扣。active 必须明确；此接口为完整配置更新，不是局部 PATCH。

```json
{"opens":"09:00","closes":"19:00","breakStart":"12:00","breakEnd":"13:00","capacity":1,"paused":false,"threshold":10000}
```

营业/休息顺序、容量 1–5、暂停和门槛均校验。设置改变不删除既有预约，响应 `preserved_appointments` 供逐项协调。threshold 只是演示门槛；默认 `lunaria.membership.mode=TRACK_ONLY`，不自动升降级。显式 `DEMO_FULFILLED_VALUE` 仅用作开发示例。

`/me` 与店长客户详情中的 summary 把充值本金、购卡、履约参考、卡退款、退款利息、储值退款预留分开列示，不能相加当消费。正式累计认定和资格时点以唯一规则待定清单为准。
