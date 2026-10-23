# Lunaria · 单店美容服务开发版

为一位店长兼美容师打造的独立项目。顾客和店长均使用**原生微信小程序**，当前是本机功能骨架，美术尚待讨论。没有微信身份、在线支付、真实退款或外部通知，也没有生产部署。

## 技术与隔离

- 与实际核对的 AquaFlow 同栈：Java 17 / Spring Boot 4.0.6 / Gradle 9.4.1 / MyBatis 4.0.1 / MySQL 目标数据库 / 原生微信小程序 JS + WXML + WXSS。
- 本轮明确使用隔离测试存储：demo 为 `backend/data/lunaria-demo.mv.db`，自动测试为独立内存 H2；MySQL 驱动和可迁移 DDL 已提供，**尚未连接或验收 MySQL**。
- 只监听 `127.0.0.1:18081`。必须显式 `demo` 或 `test` 单一 profile。无 profile、prod、混合 profile、非回环地址和其他数据库 URL 均提前拒绝启动，避免演示身份流入生产。
- AquaFlow 只读了获准的构建与工程清单；没有读取配置、凭据、业务数据或操作其浏览器，没有修改其文件。这里无远程 Git 仓库。

## 启动

需要 Java 17、Node（脚本无 npm 依赖）。首次 Gradle 构建需从官方 Gradle / Maven Central 获取依赖；已提供锁文件与 SHA256 依赖校验清单。

```powershell
cd D:\backend\project\Lunaria
node scripts/sync-miniapps.mjs
cd backend
.\gradlew.bat test bootJar
java -jar build\libs\lunaria-dev.jar --spring.profiles.active=demo
```

首次启动自动创建**独立演示库**：林女士本金 300 元、陈女士本金 200 元；护理项目与套餐均为演示报价。后续启动保留数据，不重复初始化。请勿把真实个人资料导入。启动脚本 `scripts/start-demo.ps1` 从固定 backend 目录启动，避免相对路径漂移。

在微信开发者工具分别导入 `miniapp-customer`、`miniapp-owner`。默认 `touristappid` 是待替换的游客开发占位，不复用 AquaFlow AppID。开发工具本地调试时允许不校验本机请求域名；真机连接、合法 HTTPS 域名、正式 AppID、微信授权与发布均待独立接入。当前没有实际微信工具编译或真机验收，不能把 Node 脚本检查当作微信验收。

演示首页有角色入口，登录仍经过服务端身份认证、会话与 CSRF。店长：`owner / lunaria-owner-demo`；顾客：`alice` 或 `bob / lunaria-customer-demo`。这些公开口令只用于回环演示，由生产启动闸门隔离。

## 当前能做什么

- 顾客：项目套餐、专属套餐价、余额购卡与卡包、预约申请和本人预约、卡详情、退款试算申请、本金流水、独立储值退款入口。
- 店长：待确认/后续预约、确认与容量复核、取消/调整留痕、服务结束后扣次、客户搜索和历史、手动本金充值、项目与套餐配置、退款人工登记、营业时间/休息/暂停/容量。
- 资金：分为整数精度；余额扣减与发卡同一事务；操作防重持久化；同一预约只核销一次；核销冲正保留原流水；售卡名称、项目、次数、价格、等级及规则快照不会被新配置覆盖。
- 退款：95 元 5 次用 2 次，默认本金 57 元。单卡申请时暂停核销；人工确认后仅把演示卡款退原余额。储值本金审核只预留金额，状态明确未付款。争议不能自动批准，原价扣已用需要不可变售前证据，当前普通售卡默认更有利比例规则。

## 验证与下一步

```powershell
# 无外部服务的原生页面脚本、路由、事件、金额与重试意图检查
node scripts/test-miniapps.cjs
# 后端已启动时，向独立演示库写入一次真实 HTTP 验收购卡和预约
node scripts/smoke-http.mjs
```

请先读 [验收记录](docs/验收记录.md) 的证据与未验收项，再读 [首版规则与待定项](docs/首版规则与待定项.md)、[页面清单](docs/页面清单.md) 和 [学习阅读路线](docs/学习阅读路线.md)。

**下一检查点是美术讨论**：由主线程组织 2–3 种高端美容品牌风格代表屏，确定顾客端气质和信息层级后再扩全量。当前统一 token 只是可替换线框，未制作最终背景/海报。
