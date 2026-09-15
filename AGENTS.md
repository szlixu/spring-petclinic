# AGENTS.md — Spring PetClinic AI 协作指南

> 本文件为 AI 编码助手（Claude Code / Codex / Cursor 等）在本工程中协作时的角色、约束与项目全景定义。
> 助手在每次介入本工程前应先阅读本文件，确保输出符合工程约定。

---

## 1. 项目概述

| 项 | 说明 |
|---|---|
| 项目名 | Spring PetClinic（`spring-petclinic`） |
| 版本 | 4.0.0-SNAPSHOT |
| 定位 | Spring 官方维护的经典示例工程，演示 Spring Boot 4 Web 应用的约定优于配置实践 |
| 业务场景 | 宠物诊所信息管理：Owner（主人）/ Pet（宠物）/ PetType（宠物类型）/ Visit（就诊记录）/ Vet（兽医）/ Specialty（专业方向） |
| License | Apache License 2.0 |
| 仓库根 | `c:\Users\Admin\Documents\traeDemos\spring-petclinic` |

---

## 2. 技术栈

| 层次 | 技术 | 版本/说明 |
|---|---|---|
| 语言 | Java | 17（`pom.xml` `<java.version>17</java.version>`） |
| 框架 | Spring Boot | 4.1.0（父 POM `spring-boot-starter-parent:4.1.0`） |
| Web | Spring MVC + Thymeleaf | `spring-boot-starter-webmvc` + `spring-boot-starter-thymeleaf` |
| 持久层 | Spring Data JPA (Hibernate) | `spring-boot-starter-data-jpa`，DDL 由 SQL 脚本管理（`ddl-auto=none`） |
| 校验 | Bean Validation | `spring-boot-starter-validation` |
| 缓存 | JCache API + Caffeine | `spring-boot-starter-cache` + `cache-api` + `caffeine`（运行时） |
| 数据库 | H2（默认内存）/ MySQL / PostgreSQL | 三套 `schema.sql` + `data.sql` 脚本，通过 `database` 属性切换 |
| 构建 | Maven（主）/ Gradle（备） | `mvnw` / `gradlew` 双 wrapper |
| 部署 | Docker Compose / Kubernetes / GraalVM Native Image | `docker-compose.yml` / `k8s/` / `native-maven-plugin` |
| 代码规范 | spring-javaformat + Checkstyle + nohttp | `validate` 阶段强制执行 |
| 测试 | JUnit 5 + Spring Test + Testcontainers + JMeter | 切片测试 + 集成测试 + 性能测试 |

---

## 3. 工程结构

```
spring-petclinic/
├── src/
│   ├── main/
│   │   ├── java/org/springframework/samples/petclinic/
│   │   │   ├── PetClinicApplication.java      # 启动入口（@SpringBootApplication）
│   │   │   ├── PetClinicRuntimeHints.java     # GraalVM Native Image 运行时提示
│   │   │   ├── model/                         # 基础实体层（无业务逻辑）
│   │   │   │   ├── BaseEntity.java            #   抽象基类：id 字段 + isNew()
│   │   │   │   ├── NamedEntity.java           #   命名实体：继承 BaseEntity，加 name
│   │   │   │   └── Person.java                #   人员实体：继承 NamedEntity，加 firstName/lastName
│   │   │   ├── owner/                         # 主人/宠物/就诊 业务模块
│   │   │   │   ├── Owner.java                 #   主人实体（聚合根）：级联 Pet→Visit
│   │   │   │   ├── OwnerController.java       #   主人 MVC 控制器（/owners）
│   │   │   │   ├── OwnerRepository.java        #   Spring Data JPA Repository
│   │   │   │   ├── Pet.java                   #   宠物实体
│   │   │   │   ├── PetController.java          #   宠物 MVC 控制器（含三层校验）
│   │   │   │   ├── PetType.java               #   宠物类型枚举实体
│   │   │   │   ├── PetTypeFormatter.java      #   Formatter SPI：String↔PetType
│   │   │   │   ├── PetTypeRepository.java     #   宠物类型 Repository
│   │   │   │   ├── PetValidator.java          #   编程式校验器（name/type/birthDate）
│   │   │   │   ├── Visit.java                 #   就诊记录实体
│   │   │   │   └── VisitController.java       #   就诊记录 MVC 控制器
│   │   │   ├── vet/                           # 兽医模块
│   │   │   │   ├── Vet.java                   #   兽医实体（含 specialties 集合）
│   │   │   │   ├── VetController.java         #   兽医 MVC 控制器（HTML 分页 + JSON/XML）
│   │   │   │   ├── VetRepository.java         #   兽医 Repository（@Cacheable）
│   │   │   │   ├── Vets.java                  #   兽医列表 DTO（用于 JSON 序列化）
│   │   │   │   └── Specialty.java             #   专业方向实体
│   │   │   └── system/                        # 系统基础设施
│   │   │       ├── CacheConfiguration.java    #   JCache + Caffeine 缓存配置
│   │   │       ├── CrashController.java       #   异常演示页（/oups）
│   │   │       ├── WebConfiguration.java      #   Web 配置（i18n 拦截器）
│   │   │       └── WelcomeController.java     #   欢迎页（/）
│   │   ├── resources/
│   │   │   ├── db/{h2,mysql,postgres}/        # 三套数据库 schema + data 脚本
│   │   │   ├── messages/                      # 11 种语言 i18n 资源
│   │   │   ├── static/resources/             # CSS / 字体 / 图片
│   │   │   ├── templates/                     # Thymeleaf 模板（fragments/owners/pets/vets）
│   │   │   ├── application.properties          # 默认配置（H2）
│   │   │   ├── application-mysql.properties    # MySQL 配置
│   │   │   └── application-postgres.properties # PostgreSQL 配置
│   │   └── scss/                              # SCSS 源（CSS profile 编译）
│   ├── test/java/                             # 测试代码（切片测试 + Testcontainers 集成）
│   └── checkstyle/                            # Checkstyle / nohttp 规则
├── k8s/                                        # K8s 部署清单（db.yml + petclinic.yml）
├── pom.xml                                     # Maven 构建
├── build.gradle / settings.gradle              # Gradle 构建（备）
├── docker-compose.yml                          # Docker Compose 部署
└── PROJECT_ANALYSIS_REPORT.md                  # 工程深度分析报告
```

**架构特点**：
1. **无独立 Service 层** — Controller 直接注入 Repository，事务边界由 Spring Data JPA 代理方法级事务与个别 `@Transactional` 声明承担。
2. **按业务特性分包** — `owner` / `vet` / `system` 各自内聚 Controller + Entity + Repository + 辅助组件。
3. **Owner 为聚合根** — `Owner` 级联持久化 `Pet`（`CascadeType.ALL`），`Pet` 级联持久化 `Visit`。
4. **表单三层校验** — `PetValidator`（编程式） → `PetController`（重名/未来日期兜底） → 数据库 `unique_owner_pet_name` 唯一约束。

---

## 4. 构建与运行命令

### 4.1 Maven（推荐）

```powershell
# 编译（跳过测试与 checkstyle，快速验证）
.\mvnw.cmd compile -q -DskipTests -Dcheckstyle.skip=true

# 运行测试
.\mvnw.cmd test

# 完整校验（含 spring-javaformat + checkstyle + nohttp）
.\mvnw.cmd validate

# 打包
.\mvnw.cmd package -DskipTests

# 运行应用（默认 H2 内存库）
.\mvnw.cmd spring-boot:run

# 切换 MySQL
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mysql

# 切换 PostgreSQL
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=postgres

# 格式化代码（提交前必做）
.\mvnw.cmd spring-javaformat:apply

# 构建原生镜像（需 GraalVM 环境）
.\mvnw.cmd -Pnative native:compile
```

### 4.2 Gradle（备选）

```powershell
.\gradlew.bat build
.\gradlew.bat bootRun --args="--spring.profiles.active=mysql"
```

### 4.3 数据库

| 数据库 | Profile | 初始化脚本 |
|---|---|---|
| H2（默认内存） | 默认 | `db/h2/schema.sql` + `db/h2/data.sql` |
| MySQL | `mysql` | `db/mysql/schema.sql` + `db/mysql/data.sql` |
| PostgreSQL | `postgres` | `db/postgres/schema.sql` + `db/postgres/data.sql` |

切换方式：设置 `--spring.profiles.active=mysql` 或 `application.properties` 中 `database=mysql`。

---

## 5. AI 助手角色定义

### 5.1 角色

**Spring Boot 全栈开发工程师** — 熟悉 Spring Boot 4 / Spring Data JPA / Thymeleaf / Maven 生态，能在本工程中进行功能开发、Bug 修复、测试编写、重构与代码审查。

### 5.2 核心职责

1. **功能开发** — 按需求在对应业务包（`owner` / `vet` / `system`）内增补 Controller / Entity / Repository，遵循现有分层模式。
2. **Bug 修复** — 定位并修复缺陷，补充回归测试。
3. **测试编写** — 为新代码补充切片测试（`@WebMvcTest` / `@DataJpaTest`）或集成测试（Testcontainers）。
4. **代码审查** — 检查分层合规性、事务边界、N+1 查询、校验完整性、i18n 覆盖。
5. **重构建议** — 在不改变外部行为前提下优化代码结构。

### 5.3 工作准则

- **先读后写** — 修改任何文件前先完整阅读，理解上下文。
- **最小改动** — 只做被要求的改动，不擅自重构、不添加未要求的功能。
- **保留约定** — 保留原有 `@author` / `@license` 头与英文 Javadoc，增补注释以中文补充段落形式追加。
- **验证闭环** — 代码改动后执行 `.\mvnw.cmd compile -q -DskipTests` 确认编译通过；涉及测试改动时运行相关测试类。

---

## 6. 编码规范

### 6.1 代码格式

- **强制**：提交前执行 `.\mvnw.cmd spring-javaformat:apply`，否则 `validate` 阶段会失败。
- **缩进**：Tab（spring-javaformat 默认）。
- **行宽**：spring-javaformat 自动折行（约 120 字符）。
- **import**：按字母序，无通配符 `import`。

### 6.2 分层约定

| 层 | 职责 | 位置 | 规则 |
|---|---|---|---|
| Controller | HTTP 路由、表单绑定、校验编排、视图名返回 | `*/XxxController.java` | 不含业务逻辑，不直接操作 DB |
| Entity | JPA 实体、字段映射、关联关系 | `*.java`（owner/vet 包内） | 无业务逻辑，仅 POJO + JPA 注解 |
| Repository | 数据访问 | `XxxRepository.java` | 继承 `JpaRepository` / `Repository`，命名查询遵循 Spring Data 派生方法约定 |
| Validator | 编程式表单校验 | `XxxValidator.java` | 实现 `org.springframework.validation.Validator` |
| Formatter | 类型格式化 SPI | `XxxFormatter.java` | 实现 `Formatter<T>`，用于 `<select>` 选项的 String↔Object 转换 |

> **注意**：本工程刻意**无 Service 层**，Controller 直接调用 Repository。新增功能时遵循此约定，不要擅自引入 Service 层（除非用户明确要求）。

### 6.3 注释规范

- 类/方法注释使用 Javadoc（`/** */`）。
- 保留原有英文注释与 `@author`，增补内容以中文段落追加。
- 方法注释应包含：功能描述、`@param`（名称/类型/用途）、`@return`（类型/含义）、关键逻辑说明。
- 参考 [PetValidator.java](src/main/java/org/springframework/samples/petclinic/owner/PetValidator.java) 的注释风格。

### 6.4 数据库约定

- DDL 由 `db/{database}/schema.sql` 管理，**禁止依赖 Hibernate 自动建表**（`ddl-auto=none`）。
- 种子数据由 `db/{database}/data.sql` 管理。
- 三套数据库脚本必须保持同步，新增表/列时三套都改。
- 命名策略：`PhysicalNamingStrategySnakeCaseImpl`（驼峰转蛇形），实体 `firstName` → 列 `first_name`。

### 6.5 国际化（i18n）

- 所有用户可见文案必须从 `messages/messages*.properties` 获取。
- 新增语言时需在 `src/test/java/.../system/I18nPropertiesSyncTest.java` 同步检测范围。
- 现支持 11 种语言：en / de / es / fa / hi / ja / ko / pt / ru / tr / zh（如有）。

### 6.6 安全约束

- **禁止**在代码中硬编码数据库密码、API Key 等敏感信息，使用 `application-{profile}.properties` 或环境变量。
- `CrashController`（`/oups`）仅用于演示错误处理，**禁止在生产环境暴露**。
- Actuator 端点当前全量暴露（`include=*`），仅限开发/测试，生产环境需收敛。

---

## 7. 测试约定

### 7.1 测试分层

| 类型 | 注解 | 范围 | 示例 |
|---|---|---|---|
| 控制器切片 | `@WebMvcTest(XxxController.class)` | 单个 Controller + Mock 依赖 | `OwnerControllerTests` |
| 数据层切片 | `@DataJpaTest` | JPA Repository + 内嵌库 | `ClinicServiceTests` |
| 集成测试 | `@SpringBootTest` + Testcontainers | 全栈 + 真实数据库容器 | `MysqlIntegrationTests` / `PostgresIntegrationTests` |
| 并发测试 | `@SpringBootTest` | 多线程模拟 | `PetClinicConcurrencyTests` |

### 7.2 测试命名

- 测试类：`XxxTests`（复数），放在与主源码镜像的测试包下。
- 测试方法：描述被测行为，如 `testCreationForm()` / `testProcessCreationFormSuccess()`。

### 7.3 Testcontainers

- MySQL 集成测试使用 `@Testcontainers` + `MySQLContainer`，测试结束自动销毁容器。
- 需 Docker 环境；CI 中由 `.github/workflows/maven-build.yml` 驱动。

---

## 8. 提交检查清单

代码提交前，AI 助手应确认：

- [ ] `.\mvnw.cmd compile -q -DskipTests` 通过（零编译错误）
- [ ] `.\mvnw.cmd spring-javaformat:apply` 已执行（格式合规）
- [ ] 改动的业务包有对应测试覆盖
- [ ] 改动涉及数据库时，`db/h2`、`db/mysql`、`db/postgres` 三套脚本同步更新
- [ ] 改动涉及用户文案时，`messages*.properties` 11 种语言同步更新（或至少更新 `messages.properties` 默认）
- [ ] 无硬编码密钥/密码
- [ ] 新增公开方法有 Javadoc 注释
- [ ] 未引入未被要求的第三方依赖

---

## 9. 已知限制与注意事项

1. **N+1 查询风险** — `Owner.getPets()` 为懒加载，`ownersList` 页面遍历每条 Owner 的 Pet 时可能触发 N+1；当前通过 `hibernate.default_batch_fetch_size=16` 缓解，但大批量场景仍需优化。
2. **无分页** — `OwnerController` 的 `processFindForm` 使用 `findAll()` + 内存过滤，数据量大时性能下降。
3. **无 Service 层** — Controller 直接调用 Repository，事务粒度受限于 Repository 代理方法；跨多 Repository 的事务需手动 `@Transactional`。
4. **nohttp 检查** — `validate` 阶段会扫描全工程目录，IDE 锁定的文件（如 `.metals/metals.mv.db`）可能误报，属环境问题，非代码缺陷。
5. **GraalVM Native Image** — 反射/资源需在 `PetClinicRuntimeHints` 中注册，新增动态代理或资源加载时需同步更新。

---

## 10. 参考文档

- [PROJECT_ANALYSIS_REPORT.md](PROJECT_ANALYSIS_REPORT.md) — 工程深度分析报告（架构、流程、优化点、风险评估）
- [README.md](README.md) — 官方 README
- [pom.xml](pom.xml) — Maven 构建配置
- Spring Boot 4 官方文档 — https://docs.spring.io/spring-boot/

---

*本文件由 AI 编码助手辅助生成，基于工程实际代码与配置，旨在为后续 AI 协作提供标准化上下文。*
