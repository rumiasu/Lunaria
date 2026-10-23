# MySQL 结构与隔离引擎验证

目标为 **MySQL 8.0.16+ 的全新 Lunaria 专用 schema**。本轮已在独立临时 **MySQL 8.4.8 / InnoDB / REPEATABLE-READ** 上执行 9 项定向测试；没有连接宿主既有业务库、读取其他项目凭据、注册服务或修改宿主配置。步骤见 [云端后端接棒](../../../docs/云端后端接棒.md)。

## 迁移边界

| 文件 | 用途 |
|---|---|
| `../schema.mysql.sql` | 全新空 schema 的完整结构，一次执行；不要与版本脚本重复执行 |
| `migrations/V001__baseline.sql` | 初始 11 张业务表及索引，不导入顾客或本金样本 |
| `migrations/V002__refund_processing_notes.sql` | 新增可选退款处理记录表，保留现有资产及流水 |
| `migrations/V003__wechat_identity_binding.sql` | 新增已验证微信身份关联，不保存 code/session_key/token，不改资产 |

选择完整结构，或依序 V001 → V002 → V003；两者已在真实 MySQL 核对最终列结构、类型与排序规则一致，共 13 张 InnoDB 表。版本脚本不使用 CREATE TABLE IF NOT EXISTS，重复/错误基线应失败。V001 的跨列卡次数约束已在首次 MySQL 验证时修正为表级 `ck_cards_usage`；此前未部署 MySQL，没有伪造已应用版本或增加空迁移。

仍是人工执行的版本 SQL，**没有自动迁移工具、应用版本记录或生产迁移管线**。MySQL DDL 可能隐式提交，不能依靠应用事务回滚整份迁移。应用启动闸门仍仅允许 H2 demo/test，不允许把 demo 连接 MySQL；引擎测试直接装配 MyBatis/事务服务和显式合成夹具，不放宽应用限制。

## 真实差异与已测范围

| 项目 | MySQL 8.4.8 实际结果 |
|---|---|
| CHECK | 列级 `used <= total` 被拒绝；改为表级后建表及越界拒绝通过。H2 原先接受该写法 |
| 异常类型 | CHECK 返回 3819 / HY000，Spring 可归类为 UncategorizedSQLException；测试核对真实错误码 |
| 事务/竞争 | 四线程同键只购卡一次；不同键不能超扣；发卡 SQL 失败后余额、卡、防重与审计一同回滚；容量竞争只确认一单 |
| 金额 | BIGINT 超过浮点安全整数的分值精确往返；负余额拒绝；9501 分五次卡及乱序冲正后守恒 |
| 唯一/外键 | 操作键大小写区分；AppID/openid 和顾客关联唯一；多个未绑定 NULL 允许；未知顾客外键拒绝 |
| 时间 | DATETIME(6) 微秒和上海墙上时间往返；用固定 `+08:00`，避免新实例缺命名时区表而连接失败 |

显式使用 utf8mb4 / utf8mb4_bin。`MysqlMigrationCompatibilityTest` 保留 H2 的 2 项兼容检查；`MysqlEngineTest` 属于独立 `mysqlTest` 任务，普通 `test` 不执行它。SQL 已纳入测试输入，MySQL 测试每次真实执行，不复用上次结果。

## 安全复现

```powershell
# 仓库根目录；需要已有 MySQL server/client 与 Java 17
.\scripts\test-mysql-isolated.ps1 -MySqlBin '<MySQL程序目录>\bin'
# Gradle 依赖已缓存可加 -Offline
```

脚本禁用宿主默认配置与客户端 login-path，使用新的 `backend/build/mysql-it-*` 数据目录、随机端口和随机临时 root 口令，仅监听回环、禁用 MySQL X，并用 `--no-monitor` 保持 Windows 单进程。测试只创建随机 `lunaria_it_*` schema，结束停止本次进程并清理数据/口令。不存在可复用持久账号；不能改为连接业务实例。

CI 使用一次性 MySQL 8.4 服务和 tmpfs，运行相同 `mysqlTest`。CI 文件已准备，实际云端结果以对应 Actions 为准，不能用本机结果代替。正式 MySQL 启动配置、专用身份、迁移权限与版本记录仍需在 Lunaria 独立环境验收。
