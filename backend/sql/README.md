# 独立 MySQL 目标

`schema.mysql.sql` 与当前 MyBatis 映射对应，目标 MySQL 8.0.16+，必须新建专用开发库（建议 lunaria_dev）及最小权限账号。不得复用 AquaFlow 库、账号、AppID、配置或凭据。

本轮没有建立账号、执行此脚本或连接 MySQL；H2 MySQL 模式通过测试不等于 MySQL 已验收。当前代码主动拒绝其他数据源，切换需要显式开发工作：专用库/环境变量、审查启动闸门、schema 初始化、把现有业务测试在独立 MySQL 实测。不要关闭闸门后指向现有真实库。

新建库后只初始化结构；演示 seed 不可导入生产。生产还需要正式身份、密钥管理、迁移、备份、支付与对账，当前禁止生产启动。

本轮静态核对并修正 MySQL DDL：索引不再使用 H2 的 IF NOT EXISTS 语法，关系使用表级 FOREIGN KEY、InnoDB，预约等墙上时间使用 DATETIME(6)。这是新专用库一次性脚本，不是可在已有库反复执行的迁移。依据 [MySQL CREATE INDEX](https://dev.mysql.com/doc/refman/8.0/en/create-index.html) 与 [FOREIGN KEY 文档](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)；修正语法不代表已经在 MySQL 执行通过。

现有 H2 演示库升级只新增 refund_negotiations 表，保留既有客户、余额、卡、预约与流水。该表按 revision 追加协商，不改写原退款原因与原试算。正式 MySQL 接入必须再核对会话上海时区、约束、索引及事务测试。本机虽可见 MySQL 服务，但没有已确认的 Lunaria 专用账号，本轮没有连接它或读取任何账号配置。
