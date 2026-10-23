# MySQL 专用结构与迁移准备

目标为 **MySQL 8.0.16+ 的全新 Lunaria 专用开发 schema**。本轮没有连接宿主 MySQL、创建数据库账号、读取其他项目凭据、修改数据库安全设置或安装容器运行时。

## 文件与执行边界

| 文件 | 用途 |
|---|---|
| `../schema.mysql.sql` | 全新空 schema 的完整结构，一次执行；不要与版本脚本重复执行 |
| `migrations/V001__baseline.sql` | 初始 11 张业务表及索引，不导入顾客或本金样本 |
| `migrations/V002__refund_processing_notes.sql` | 新增可选退款处理记录表，现有余额、卡、预约及流水保留 |

选择完整结构，或依序 V001 → V002，两者最终列结构一致。版本脚本刻意不使用 CREATE TABLE IF NOT EXISTS，重复/错误基线应失败，不能悄悄把旧表当作已迁移。文件为人工执行的版本化 SQL，**没有接入自动迁移工具，也没有在 MySQL 执行验收**；不要声称已建立可生产迁移管线。MySQL DDL 可能隐式提交，不能依靠应用事务回滚整份迁移。

当前应用启动闸门只允许独立 H2 demo/test。提供专用 MySQL 环境后，还需增加经过验证的独立配置、版本记录与正式初始化；不能直接用 demo 连接 MySQL 导入演示本金。

## 与 H2 的具体差异

| 项目 | 当前 H2 demo/test | MySQL 目标 |
|---|---|---|
| 引擎 | H2 文件/内存，MySQL 兼容模式 | InnoDB，实际锁/隔离行为仍需实测 |
| 时间 | TIMESTAMP，应用解释为上海墙上时间 | DATETIME(6)，另需确认 JDBC/会话上海时区 |
| 文本比较 | H2 既有行为 | 显式 utf8mb4 / utf8mb4_bin，避免操作编号大小写比较随库默认值变化 |
| 外键 | H2 接受列内 REFERENCES | MySQL 使用显式表级 FOREIGN KEY |
| 索引与重复执行 | 启动结构使用 IF NOT EXISTS | MySQL 版本脚本只执行一次，不使用 H2 索引扩展语法 |
| CHECK | H2 已测试 | 依赖 MySQL 8.0.16+ 执行检查约束，尚未实测 |

`MysqlMigrationCompatibilityTest` 只在 H2 MySQL 模式比较版本脚本/完整脚本列结构，并验证新增处理记录表保留已有本金和来源流水。H2 即使接受 MySQL 语法，也不能证明 InnoDB、排序规则、MySQL 驱动或并发事务行为正确。Gradle 已把 SQL 声明为测试输入，修改脚本不会被旧测试缓存跳过。

## 本轮未实测 MySQL 的确切原因

PATH 及标准安装位置未发现可调用 Docker/Podman；本机有 MySQL 程序，但没有已确认的 Lunaria 专用开发库、专用认证与权限边界。在“不创建账号/凭据、不改安全设置”的约束下，没有安全可用的临时 MySQL 验证路径，因此没有启动或连接它。

后续必须由外部提供：独立开发实例地址/端口、Lunaria 专用空 schema 名称、已准备好的认证方式、迁移/运行权限边界和连接时区。秘密值通过本机未提交配置提供。本脚本没有 CREATE USER、GRANT 或宿主配置修改。
