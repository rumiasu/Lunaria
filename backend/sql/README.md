# 独立 MySQL 目标

目标 MySQL 8.0.16+；独立临时 MySQL 8.4.8 / 8.4.13 的真实引擎验证已完成，见 [迁移与差异](mysql/README.md) 和 [云端接棒](../../docs/云端后端接棒.md)。H2 兼容检查与真实 MySQL 引擎检查分别记录，不混同。

全新空 schema 可选择完整 `schema.mysql.sql`，或依序执行 V001–V005，不应重复执行。V004 只新增店长联系资料表，保留旧顾客、本金、卡、预约与流水。版本脚本校验和在 `mysql/migrations/SHA256SUMS`；CI 实际核对文件摘要。mysql-it 已有应用内版本/校验记录，并完成 V1→V5 启动升级及数据保留实测；未建立生产迁移管线，MySQL DDL 可能隐式提交。

demo/test 仍只允许回环 H2。mysqlTest 直接装配服务验证引擎；mysqlAppTest 启动完整应用/打包 jar，并经实际 HTTP 验收。mysql-it 只允许本次实例标记下的随机回环 MySQL schema，结束清理，无演示身份或 seed。不得把 demo 指向已有业务库，演示 seed 不可导入生产。

正式 MySQL 运行仍需 Lunaria 专用身份、初始化与迁移版本记录、备份和正式认证独立验收。没有创建持久账号或生产部署，没有复用其他项目的库、账号、AppID、配置或凭据。
