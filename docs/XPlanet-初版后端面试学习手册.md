# XPlanet 初版后端面试学习手册

> 适用对象：Java 后端初学者、秋招复习、项目深挖与模拟面试  
> 项目事实基线：Git 提交 `9bcaeb5`（简历对应的初版 XPlanet）  
> 技术栈：Java 17、Spring Boot 2.7、MyBatis-Plus、MySQL 8、Redis、Redisson、RocketMQ、Caffeine、Docker Compose  
> 核心原则：先理解请求如何流动，再理解中间件为什么存在，最后掌握故障、边界与演进。

## 0. 如何使用这份手册

这不是一份只用于背诵名词的八股合集，而是一份围绕真实项目建立知识网络的学习手册。推荐采用四轮学习法：

1. **第一轮看全貌**：先读第 1、2、12、13 章，能画出项目架构和四条主链路。
2. **第二轮补原理**：依次学习 Java、Spring、MySQL、Redis、RocketMQ 与缓存。
3. **第三轮练追问**：遮住“参考回答”，先自己回答每章的面试题。
4. **第四轮做实验**：按第 17 章亲手启动、制造故障、观察日志和数据变化。

掌握一个知识点至少要达到四个层次：

- 能用一句话说明它解决什么问题；
- 能解释项目中在哪里使用；
- 能说明不用它会发生什么；
- 能主动指出它的缺陷和下一步改进。

> 面试的高分答案通常不是“这个技术很好”，而是“当时遇到什么问题、为什么选择它、它保护了什么不变量、代价是什么、失败后如何恢复”。

## 1. 项目事实边界与简历口径

### 1.1 简历对应哪个版本

本手册只把初版 `9bcaeb5` 已实现的能力描述为项目事实：

- Article、Interaction、User 三个 Spring Boot 服务；
- Caffeine + Redis 二级缓存；
- Cache Aside、延迟双删、RocketMQ 广播清理多实例 L1；
- 空值缓存、Redisson 分布式锁、Double Check、TTL 随机抖动；
- 点赞状态先写 Redis，再按 userId 有序发送 RocketMQ；
- 消费端通过 actionId、状态比对和数据库唯一约束实现多层幂等；
- Redis Hash 使用 HINCRBY 聚合文章点赞 delta；
- 定时任务通过 RENAME 切换缓冲区并批量更新 MySQL；
- Spring AOP + Redis Lua 固定窗口限流；
- 简化 Token、ThreadLocal 用户上下文；
- Article 通过 RestTemplate 调用 User，并用 Caffeine 缓存和兜底名称降级。

初版没有实现：

- Spring Cloud Gateway、Nacos、Dubbo；
- Transactional Outbox；
- MySQL 持久化点赞 delta 投影；
- 标准 JWT 库和 BCrypt 密码校验；
- Sentinel、Seata；
- Agent、LangGraph、RAG；
- 经过完整压测验证的 QPS 或生产 SLA。

面试时可以把这些作为“后续演进”，但不能说成初版已经具备。

### 1.2 项目一句话介绍

> XPlanet 是一个面向开发者社区的 Java 后端项目，重点解决热点文章读多写少和点赞瞬时高并发两个问题：读取侧使用 Caffeine + Redis 二级缓存与缓存防护，写入侧使用 Redis 实时状态、RocketMQ 异步削峰和 Redis Hash 聚合批量落库，并通过幂等、限流、服务降级与多实例缓存广播提升系统稳定性。

### 1.3 四个简历亮点

1. **二级缓存与热点保护**：Caffeine 承接单实例热点，Redis 作为共享 L2；使用空值、分布式锁、Double Check、TTL 随机抖动处理穿透、击穿和雪崩。
2. **缓存一致性与多实例同步**：更新数据库后删除缓存，提交后执行延迟第二删，并通过 RocketMQ 广播清理所有 Article 实例的 Caffeine。
3. **点赞异步化与消费幂等**：Redis 保存实时状态和计数，按 userId 发送顺序消息；消费端使用 actionId、状态比对和唯一约束去重，再通过 Redis Hash 聚合 delta 并批量更新 MySQL。
4. **限流、鉴权与服务容错**：AOP + Redis Lua 实现固定窗口限流；Token + ThreadLocal 传递用户身份；用户服务调用使用本地缓存和降级结果。

### 1.4 面试中如何诚实说明项目规模

推荐说法：

> 这是一个围绕高并发核心链路做技术验证的秋招项目，不是已经承载真实海量用户的生产系统。我重点实现了缓存、异步削峰和幂等链路，并分析了 MQ 投递空窗、Redis 缓冲刷库崩溃窗口、简化鉴权等问题。对于没有真实压测的数据，我不会宣称具体 QPS。

错误说法：

- “系统完全保证强一致。”
- “RocketMQ 保证消息绝不重复。”
- “Redis 永远不会丢数据。”
- “使用了微服务，所以系统天然高可用。”
- “经过压测支持十万 QPS”，但没有压测环境、参数和结果。

## 2. 先建立后端系统的整体认知

### 2.1 一次请求经历什么

以查询文章详情为例：

~~~text
浏览器
  → HTTP 请求
  → Spring MVC Controller
  → ArticleService
  → Caffeine L1
  → Redis L2
  → Redisson 分布式锁
  → MyBatis-Plus Mapper
  → MySQL
  → 组装 ArticleDetailVO
  → JSON 响应
~~~

每一层的职责：

| 层 | 主要职责 | 不应该承担 |
|---|---|---|
| Controller | 参数接收、鉴权入口、返回响应 | 复杂事务、直接拼 SQL |
| Service | 业务规则、事务编排、跨组件协作 | HTTP 细节、数据库表结构泄漏 |
| Mapper | SQL 和数据访问 | 业务状态机 |
| Cache | 加速读取、削减下游压力 | 作为唯一可靠事实源 |
| MQ | 异步、削峰、解耦 | 默认提供 exactly-once |
| MySQL | 持久化业务事实和约束 | 承接所有热点瞬时请求 |

### 2.2 什么是系统不变量

不变量是无论并发、重试或故障如何发生，都希望保持成立的规则。

XPlanet 主要不变量：

- 同一用户对同一文章最多只有一条点赞关系；
- 只有点赞状态真实变化，文章计数才改变；
- 重复 MQ 消息不能重复增加点赞数；
- 缓存可以短暂旧，但最终要收敛到数据库；
- 缓存失效时不能让所有请求同时压垮 MySQL；
- 限流计数器必须自动过期；
- ThreadLocal 中的用户信息不能泄漏到下一个请求。

面试设计题先说不变量，再说实现，答案会比单纯罗列技术更成熟。

### 2.3 一致性、可用性和性能的权衡

初版 XPlanet 多数链路追求最终一致：

- 点赞接口先更新 Redis 并返回，MySQL 稍后落库；
- 文章更新后通过删除缓存和异步广播使缓存最终失效；
- User 服务失败时返回兜底名，优先保证文章可展示。

这意味着：

- 用户感知延迟更低；
- 系统更能承受突发流量；
- 但短时间内不同存储可能看到不同值；
- 必须设计去重、补偿、重试和可观测性。

## 3. Java 基础：从会写代码到能解释运行机制

### 3.1 面向对象四个核心概念

**封装**：隐藏内部实现，对外只暴露稳定接口。例如 `ArticleCacheManager.get()` 屏蔽了 L1、L2、锁和回源细节。

**抽象**：提炼共同能力。Service 接口描述“能做什么”，实现类决定“怎么做”。

**继承**：子类复用父类属性和行为。业务代码应谨慎使用深继承，优先组合。

**多态**：父类型引用可以指向不同实现，运行时选择具体行为，便于替换和测试。

面试题：为什么推荐组合优于继承？

参考回答：

> 继承形成编译期强耦合，父类变化容易影响全部子类，而且 Java 只支持单继承。组合通过持有接口对象复用能力，依赖更显式，也更方便注入 Mock 或切换实现。只有存在稳定的 is-a 关系时才优先继承。

### 3.2 equals 与 hashCode

如果两个对象 `equals()` 相等，它们的 `hashCode()` 必须相同，否则放入 HashMap/HashSet 后可能无法正确定位。

HashMap 查询过程简化为：

~~~text
计算 hash
  → 定位数组桶
  → 比较 hash
  → equals 比较键
  → 返回值
~~~

面试题：只重写 equals 不重写 hashCode 会怎样？

参考回答：

> 逻辑相等的两个对象可能进入不同桶，HashSet 会认为它们不同，HashMap 也可能查询不到原来的键，违反集合契约。

### 3.3 String、StringBuilder、StringBuffer

- String 不可变，适合作为 Map Key，线程安全，便于字符串常量池复用；
- StringBuilder 可变、非线程安全，单线程拼接性能好；
- StringBuffer 方法带同步，线程安全但通常开销更高。

不要在循环中反复使用 `str = str + value` 构造大量中间对象。

### 3.4 ArrayList 与 LinkedList

| 维度 | ArrayList | LinkedList |
|---|---|---|
| 底层 | 动态数组 | 双向链表 |
| 随机访问 | O(1) | O(n) |
| 尾部追加 | 均摊 O(1) | O(1) |
| 中间插入 | 定位后仍需移动元素 | 定位 O(n)，修改指针 O(1) |
| CPU 缓存友好 | 较好 | 较差 |

大多数业务场景优先 ArrayList。不能只说 LinkedList 插入一定快，因为寻找插入位置仍可能是 O(n)。

### 3.5 HashMap 核心原理

Java 8 HashMap 使用数组 + 链表 + 红黑树：

- 通过 hash 定位桶；
- 冲突时形成链表；
- 链表长度达到阈值且数组容量足够时树化；
- 扩容通常变为原容量的 2 倍；
- 非线程安全，并发写可能丢数据或状态异常。

并发场景使用 ConcurrentHashMap，但复合操作仍需使用原子 API，如 `computeIfAbsent`，不能简单地“先 get 再 put”。

### 3.6 异常体系

- Checked Exception：编译器要求处理，如 IOException；
- RuntimeException：运行时异常，如 NullPointerException；
- Error：严重 JVM 问题，通常不应由业务代码捕获。

项目中业务异常应包含稳定错误码，由全局异常处理器转换为统一响应。

常见错误：

- `catch (Exception)` 后什么都不做；
- 记录日志后重复抛出造成多层重复日志；
- 把所有异常都转成“系统错误”，丢失业务语义；
- 在事务方法中吞掉异常导致事务提交。

### 3.7 泛型与类型擦除

泛型提供编译期类型安全，Java 运行时大多通过类型擦除实现。`List<String>` 和 `List<Integer>` 运行时通常都是 `List`。

`? extends T` 适合读取，`? super T` 适合写入，可记为 PECS：

- Producer Extends；
- Consumer Super。

### 3.8 反射与注解

Spring 通过反射读取 `@Service`、`@Transactional`、`@RateLimit` 等注解，并创建对象或代理。

反射优点是灵活、可扩展；缺点是：

- 编译期检查较弱；
- 调试更复杂；
- 存在一定调用开销；
- 私有成员访问可能破坏封装。

## 4. JUC、JMM 与线程安全

### 4.1 并发问题的三个来源

- **原子性**：一个操作是否不可分割；
- **可见性**：一个线程修改后，其他线程能否及时看到；
- **有序性**：编译器和 CPU 是否可能重排序。

`count++` 不是原子操作，它包含读取、加一、写回。

### 4.2 Java 内存模型 JMM

JMM 规定线程如何读写共享变量。每个线程可能在寄存器或 CPU 缓存中保留变量副本，因此需要同步规则建立可见性。

常见 happens-before 规则：

- 解锁 happens-before 后续对同一锁的加锁；
- volatile 写 happens-before 后续 volatile 读；
- 线程 start 之前的操作对新线程可见；
- 线程中的操作 happens-before 其他线程成功 join 之后；
- 传递性。

面试题：volatile 能保证什么？

参考回答：

> volatile 保证对该变量写入对其他线程可见，并限制相关指令重排序，但不保证 `count++` 这种复合操作的原子性。计数应使用锁或 AtomicInteger。

### 4.3 synchronized

`synchronized` 同时提供：

- 互斥；
- 可见性；
- 有序性。

锁对象可能是：

- 实例对象；
- Class 对象；
- 显式代码块中的任意对象。

锁范围过大会降低并发；锁对象使用不当会导致多个请求意外竞争同一把锁。

### 4.4 ReentrantLock

相对 synchronized，ReentrantLock 支持：

- `tryLock()`；
- 超时获取；
- 可中断等待；
- 公平锁；
- 多个 Condition。

必须在 finally 中 unlock：

~~~java
lock.lock();
try {
    // critical section
} finally {
    lock.unlock();
}
~~~

### 4.5 CAS 与原子类

CAS 比较内存值是否仍为预期值，如果相等则更新。优点是无阻塞竞争；问题包括：

- ABA；
- 自旋消耗 CPU；
- 单个 CAS 很难维护多个变量的不变量。

AtomicInteger 适合简单计数，复杂状态机仍可能需要锁或数据库事务。

### 4.6 ConcurrentHashMap

Java 8 主要使用 CAS + synchronized 控制桶级更新，读操作并发度高。

注意：

~~~java
if (!map.containsKey(key)) {
    map.put(key, value);
}
~~~

不是原子复合操作，应使用 `putIfAbsent()` 或 `computeIfAbsent()`。

### 4.7 线程池

ThreadPoolExecutor 七个关键参数：

- corePoolSize；
- maximumPoolSize；
- keepAliveTime；
- unit；
- workQueue；
- threadFactory；
- rejectionHandler。

执行顺序：

~~~text
线程数小于 core → 创建核心线程
否则进入队列
队列满且线程数小于 max → 创建非核心线程
仍无法处理 → 拒绝策略
~~~

常见拒绝策略：

- AbortPolicy：抛异常；
- CallerRunsPolicy：调用线程执行，形成反压；
- DiscardPolicy：直接丢弃；
- DiscardOldestPolicy：丢弃队列最旧任务。

初版点赞消费者自行创建单线程 ScheduledExecutorService，每 500ms flush。优点是串行化本实例的 flush；问题是：

- 多实例仍会同时 flush 共享 Redis Hash；
- 异常只记录日志时可能长期积压；
- 守护线程退出时不能替代可靠任务调度；
- 固定租期或长 SQL 可能造成超时。

### 4.8 ThreadLocal

ThreadLocal 为每个线程保存独立变量，适合请求作用域用户上下文。

典型流程：

~~~text
拦截器解析 Token
  → UserContext.set(userId)
  → Controller/Service 获取 userId
  → finally 中 UserContext.remove()
~~~

为什么必须 remove？

> Tomcat 线程池会复用线程。如果请求结束不清理，下一个请求可能读到上一个用户的数据；ThreadLocalMap 的 value 也可能长期被线程引用而造成内存泄漏。

## 5. JVM、类加载与垃圾回收

### 5.1 JVM 运行时内存

| 区域 | 保存内容 | 常见异常 |
|---|---|---|
| 堆 | 对象、数组 | OutOfMemoryError: Java heap space |
| 虚拟机栈 | 栈帧、局部变量、返回地址 | StackOverflowError |
| 元空间 | 类元数据 | OutOfMemoryError: Metaspace |
| 程序计数器 | 当前线程字节码位置 | 通常无 OOM |
| 本地方法栈 | Native 调用 | 与本地方法相关 |

### 5.2 对象创建过程

简化过程：

1. 检查类是否加载；
2. 在堆中分配内存；
3. 内存清零；
4. 设置对象头；
5. 执行构造方法。

高并发对象分配通常通过 TLAB 降低线程竞争。

### 5.3 类加载过程

加载、验证、准备、解析、初始化。

双亲委派：

> 类加载器收到请求后先委托父加载器，父加载器无法完成时子加载器才尝试，避免核心类被重复或恶意替换。

### 5.4 GC Roots 与可达性分析

对象不可达时才可能被回收。常见 GC Roots：

- 栈中的引用；
- 静态字段；
- 常量引用；
- JNI 引用；
- 活跃线程。

### 5.5 分代与常见 GC

多数对象朝生夕死，因此堆通常按代管理。需要理解：

- Young GC；
- Old/Mixed GC；
- Full GC；
- Stop-The-World；
- 吞吐量与停顿时间权衡。

Java 17 常用 G1。G1 将堆划分为多个 Region，按收益回收，目标是更可预测的停顿。

### 5.6 项目中的 JVM 风险

- Caffeine 如果没有 maximumSize，可能占满堆；
- 无限线程池或无界任务队列可能 OOM；
- 大量 JSON 序列化产生短命对象，增加 Young GC；
- ThreadLocal 不清理会形成长生命周期引用；
- 缓存大对象会进入老年代；
- 日志中拼接巨大响应可能增加内存和 I/O 压力。

面试题：如何排查 Java 进程内存持续增长？

参考回答：

> 先看 GC、堆使用和进程指标，区分堆内、堆外和线程问题；使用 jcmd/jstat 观察 GC，导出 heap dump 后用 MAT 分析 dominator tree 和 GC Roots；同时检查缓存容量、ThreadLocal、静态集合、线程池队列和未关闭资源。不能只靠增加 `-Xmx` 掩盖泄漏。

## 6. Spring、Spring Boot 与 Spring MVC

### 6.1 IoC 与依赖注入

IoC 将对象创建权交给 Spring 容器。构造器注入优点：

- 依赖明确；
- 字段可设为 final；
- 对象创建后即处于完整状态；
- 单元测试容易直接构造。

不推荐大量字段 `@Autowired`，因为依赖隐藏、测试困难，也容易形成过大的类。

### 6.2 Bean 生命周期

简化过程：

~~~text
实例化
  → 属性注入
  → Aware 回调
  → BeanPostProcessor before
  → @PostConstruct
  → BeanPostProcessor after
  → 使用
  → @PreDestroy
~~~

初版点赞消费者在 `@PostConstruct` 中启动定时 flush，在 `@PreDestroy` 中关闭线程池并尝试最后一次 flush。

### 6.3 AOP 与代理

AOP 把限流、事务、日志等横切逻辑从业务代码分离。

核心概念：

- Join Point；
- Pointcut；
- Advice；
- Aspect；
- Proxy。

Spring 常见代理：

- 有接口时可使用 JDK 动态代理；
- 无接口时通常使用 CGLIB 子类代理。

### 6.4 AOP 自调用问题

~~~java
public void outer() {
    this.inner();
}

@Async
public void inner() {}
~~~

`this.inner()` 没有经过代理，`@Async` 不生效。初版延迟第二删放在独立 `CacheDelayTask` Bean 中，通过跨 Bean 调用确保异步代理生效。

同理，`@Transactional` 自调用也可能失效。

### 6.5 Spring 事务

ACID：

- Atomicity；
- Consistency；
- Isolation；
- Durability。

常见传播行为：

| 传播行为 | 含义 |
|---|---|
| REQUIRED | 有事务加入，没有创建 |
| REQUIRES_NEW | 挂起原事务并创建新事务 |
| SUPPORTS | 有事务加入，没有则非事务运行 |
| MANDATORY | 必须存在事务，否则报错 |
| NOT_SUPPORTED | 挂起事务，非事务执行 |
| NEVER | 存在事务则报错 |
| NESTED | 使用保存点实现嵌套回滚 |

事务常见失效原因：

- 方法不是通过 Spring 代理调用；
- 同类自调用；
- 方法不可代理；
- 异常被吞；
- 默认回滚规则与异常类型不匹配；
- 数据库引擎不支持事务；
- 多线程切换后事务上下文没有传播。

### 6.6 为什么在事务提交后发 MQ 和第二删

初版文章更新：

~~~text
事务内更新 DB
  → 第一删当前节点 L1 + Redis L2
  → 注册 afterCommit
事务真正提交
  → 发缓存失效广播
  → 异步延迟第二删
~~~

意图：

- 避免事务未提交时其他请求回源读到旧数据；
- 避免 sleep 占用数据库连接；
- 只有提交成功才执行后续动作。

局限：

> afterCommit 回调不是持久化任务。数据库已经提交后，如果进程在发送 MQ 前崩溃，广播和第二删会丢失。这是后续引入 Transactional Outbox 的原因。

### 6.7 Spring MVC 请求链

~~~text
请求
  → Filter
  → DispatcherServlet
  → HandlerInterceptor.preHandle
  → Controller
  → Service
  → 返回值转换为 JSON
  → postHandle
  → afterCompletion 清理 ThreadLocal
~~~

Filter 属于 Servlet 规范，适合通用请求处理；Interceptor 更接近 Spring MVC Handler，适合用户上下文、权限等。

## 7. MySQL：索引、事务、锁与项目表设计

### 7.1 InnoDB 与 B+ 树

B+ 树适合数据库：

- 高扇出，树高低；
- 减少磁盘随机 I/O；
- 叶子节点有序，支持范围查询；
- 非叶子节点只存键和指针，可容纳更多索引项。

### 7.2 聚簇索引与二级索引

InnoDB 主键索引叶子节点保存整行数据。二级索引叶子节点保存二级键和主键。

二级索引查询其他列时：

~~~text
查二级索引得到主键
  → 再查主键索引取得整行
~~~

称为回表。若索引已经包含查询需要的全部列，则是覆盖索引。

### 7.3 联合索引和最左前缀

索引 `(user_id, article_id)` 可支持：

- `user_id = ?`；
- `user_id = ? and article_id = ?`；
- 某些以 user_id 开始的范围。

通常不能有效支持只按 `article_id` 查询。

### 7.4 索引失效常见原因

- 对索引列做函数或计算；
- 隐式类型转换；
- 联合索引跳过最左列；
- 前导 `%` 模糊查询；
- 选择性太低，优化器判断全表扫描更便宜；
- OR 两侧无法有效使用索引。

### 7.5 MVCC

MVCC 通过隐藏版本信息、Undo Log 和 Read View 实现一致性读，使普通 SELECT 尽量不阻塞写。

需要区分：

- 快照读：普通 SELECT；
- 当前读：SELECT FOR UPDATE、UPDATE、DELETE；
- 当前读通常需要加锁并读取最新已提交版本。

### 7.6 隔离级别

| 隔离级别 | 可能问题 |
|---|---|
| READ UNCOMMITTED | 脏读、不可重复读、幻读 |
| READ COMMITTED | 不可重复读、幻读 |
| REPEATABLE READ | InnoDB 默认，配合 MVCC/Next-Key Lock |
| SERIALIZABLE | 隔离最强，并发最低 |

### 7.7 行锁、间隙锁和死锁

索引条件准确时更可能锁定少量记录；没有合适索引可能扫描并锁住更多数据。

死锁常见原因：

- 不同事务以不同顺序锁资源；
- 大事务持锁时间长；
- 缺索引导致锁范围扩大。

处理方式：

- 固定访问顺序；
- 缩短事务；
- 添加合适索引；
- 捕获死锁并有限重试；
- 查看 InnoDB 死锁日志。

### 7.8 点赞关系唯一约束

`article_like` 应对 `(user_id, article_id)` 建唯一约束。作用不只是防重复行，更是并发竞争下的最终正确性屏障。

消费者逻辑：

~~~text
查询当前状态
  → 状态相同则跳过
  → 状态不同则 upsert
  → 唯一约束防止并发插入两行
~~~

“先查再写”本身不是原子的，因此必须依赖数据库约束兜底。

### 7.9 分页

初版使用 MyBatis-Plus Page，适合普通分页。

深分页：

~~~sql
SELECT * FROM article ORDER BY id LIMIT 100000, 20;
~~~

数据库仍需跳过大量记录。大数据量可改为游标/Keyset：

~~~sql
SELECT * FROM article
WHERE id < ?
ORDER BY id DESC
LIMIT 20;
~~~

## 8. Redis：数据结构、缓存、原子性与可靠性

### 8.1 Redis 为什么快

- 内存访问；
- 核心命令执行路径简单；
- 单线程串行执行命令，减少锁竞争；
- I/O 多路复用；
- 高效数据结构。

“单线程”主要指命令执行模型，持久化、网络等可能使用其他线程。

### 8.2 项目使用的数据结构

| 数据结构 | 初版用途 |
|---|---|
| String | 文章详情 JSON、实时点赞数、限流计数、幂等键 |
| Set | 用户已点赞文章集合 |
| Hash | articleId 到累计点赞 delta |

### 8.3 SADD/SREM 的业务幂等

点赞：

~~~text
SADD userLikedSet articleId
返回 1 → 状态发生变化
返回 0 → 已经点赞，幂等返回
~~~

取消：

~~~text
SREM userLikedSet articleId
返回 1 → 真正取消
返回 0 → 本来就未点赞
~~~

优点是状态判断与集合修改由一个 Redis 命令原子完成。

### 8.4 HINCRBY 为什么适合点赞聚合

Hash：

~~~text
key   = xp:like:buffer
field = articleId
value = accumulated delta
~~~

HINCRBY 对单 field 原子累加，多消费者并发处理同一文章不会发生普通“读-改-写”丢更新。

### 8.5 Lua 脚本

固定窗口限流需要：

~~~text
INCR key
如果 count == 1，则 EXPIRE key
返回 count
~~~

Lua 在 Redis 内原子执行，避免 INCR 成功而 EXPIRE 未执行导致永久 Key。

需要注意：

- Lua 执行期间会阻塞其他命令；
- 脚本不能做长时间运算；
- Cluster 下多个 Key 必须满足同槽要求。

### 8.6 过期删除与淘汰

Redis 过期 Key 通常采用惰性删除 + 定期删除。内存达到上限时根据 maxmemory-policy 淘汰。

缓存系统必须设置容量和淘汰策略，否则内存耗尽会拒绝写入或造成抖动。

### 8.7 RDB 与 AOF

- RDB：定期快照，恢复快，但可能丢失快照后的数据；
- AOF：记录写命令，数据更完整，但文件更大、恢复更慢；
- 可组合使用。

初版把未落库 delta 放在 Redis，比进程内 Map 更耐应用实例崩溃，但不能等价为“绝不丢失”。Redis 本身故障或持久化滞后仍可能丢数据。

### 8.8 缓存穿透、击穿、雪崩

| 问题 | 含义 | 初版方案 |
|---|---|---|
| 穿透 | 查询数据库不存在的数据，缓存永远 miss | 空值缓存 60s |
| 击穿 | 单个热点 Key 失效，大量请求同时回源 | Redisson 锁 + Double Check |
| 雪崩 | 大量 Key 同时失效或 Redis 故障 | TTL 随机抖动、限流降级 |

不要混淆：

- 穿透关注“不存在”；
- 击穿关注“一个热点”；
- 雪崩关注“大面积失效”。

### 8.9 Redisson 分布式锁

初版流程：

~~~text
L1 miss + L2 miss
  → tryLock(等待 200ms，租期 3s)
  → 抢到后 Double Check L2
  → DB 回源并写缓存
  → finally 解锁
~~~

初版显式租期 3 秒的风险：

> 如果数据库查询或序列化超过 3 秒，锁提前过期，第二个线程可能进入重建，形成并发回源。后续可不显式指定 lease，使用 Redisson watchdog 续期，或确保任务上界小于租期。

### 8.10 热 Key 与大 Key

热 Key：请求集中在少量 Key，可能压垮单个 Redis 节点或网络。

大 Key：单 Key 包含过多数据，删除、迁移、序列化耗时。

`xp:like:buffer` 把全部文章 delta 放在一个 Hash，简单但存在：

- 单 Key 过热；
- HGETALL 随规模增长阻塞；
- 全量迁移和删除开销；
- Cluster 中负载集中。

可按 articleId 分片或使用持久化事件表改进。

## 9. Caffeine 二级缓存

### 9.1 为什么 Redis 前还要 Caffeine

Caffeine 位于 JVM 内：

- 无网络往返；
- 对热点文章读取极快；
- 降低 Redis QPS；
- Redis 短暂波动时可提供部分数据。

代价：

- 每实例一份；
- 数据可能不一致；
- 占用 JVM 堆；
- 服务重启后丢失；
- 必须设置容量和 TTL。

### 9.2 初版配置

~~~text
maximumSize = 10,000
expireAfterWrite = 30 秒
~~~

短 TTL 的意义：

- 即使 MQ 广播丢失，旧值窗口也有上界；
- 限制长时间脏缓存；
- 但命中率比长 TTL 低。

### 9.3 L1/L2 读取流程

~~~text
查 Caffeine
  → 命中直接返回
  → miss 查 Redis
  → Redis 命中则回填 Caffeine
  → Redis miss 进入锁重建
~~~

这里 Caffeine 存的是序列化 JSON。优点是与 Redis 数据格式一致；代价是每次命中仍需反序列化。也可以直接缓存 VO，但需要权衡对象可变性和两层格式差异。

### 9.4 多实例一致性

~~~text
Article A 本地 Caffeine
Article B 本地 Caffeine
共享 Redis
~~~

删除共享 Redis 不能自动清除 A、B 的本地缓存，所以通过 RocketMQ 广播，使每个实例清自己的 L1。

广播消费必须理解：

- 集群模式：同组只一个实例消费；
- 广播模式：每个实例消费。

缓存失效要求广播模式。

## 10. Cache Aside 与缓存一致性

### 10.1 Cache Aside

读：

~~~text
查缓存
  → miss 查数据库
  → 写缓存
~~~

写：

~~~text
更新数据库
  → 删除缓存
~~~

### 10.2 为什么删除而不是更新

- 并发写缓存容易覆盖；
- 缓存结构可能是多个表和远程结果的组合；
- 写少读多时，更新一个暂时无人读取的缓存是浪费；
- 删除后由下一次读统一重建，逻辑简单。

### 10.3 为什么通常先更新数据库再删缓存

先删缓存再更新数据库的竞态：

~~~text
T1 删除缓存
T2 miss 后读取数据库旧值
T2 写回旧缓存
T1 更新数据库
最终：DB 新，缓存旧
~~~

先更新数据库再删缓存发生脏缓存的概率通常更低。

### 10.4 延迟双删

即使更新 DB 后删缓存，也可能发生：

~~~text
T1 缓存 miss，读取 DB 旧值，尚未回填
T2 更新 DB 并删除缓存
T1 把旧值写回缓存
~~~

延迟第二删用于清理 T1 写回的旧值。

关键追问：延迟多久？

参考回答：

> 延迟应覆盖一次最慢读请求从数据库查询到缓存回填的时间，并留一定余量，但固定时间只能基于监控估计，无法形成严格正确性证明。初版使用 1 秒是工程折中，不是通用答案。

### 10.5 初版一致性方案的故障窗口

- 事务内第一删后如果事务回滚，缓存被无谓删除，但下一次可重建；
- afterCommit 前进程崩溃，广播和第二删丢失；
- MQ 发送失败未持久化；
- 延迟任务线程池拒绝或进程退出，第二删丢失；
- 广播消费者异常，某些实例 L1 继续保留旧值；
- 固定延迟无法覆盖所有慢请求。

演进方案：

- Transactional Outbox；
- CDC/Canal 订阅数据库变更；
- 缩短 L1 TTL；
- 缓存版本号；
- 对极强一致数据不使用普通 Cache Aside。

## 11. RocketMQ：异步削峰、顺序和幂等

### 11.1 为什么使用消息队列

- 异步：主请求不用等待慢操作；
- 削峰：突发流量先进入队列；
- 解耦：生产者不直接依赖消费者处理速度；
- 重试：消费失败可再次投递；
- 广播：通知多个实例清理本地缓存。

代价：

- 最终一致；
- 重复消息；
- 消息积压；
- 运维复杂度；
- 故障排查跨多个组件。

### 11.2 点赞生产流程

~~~text
校验 userId/articleId
  → SADD 用户点赞集合
  → 若返回 0，重复点赞，直接返回
  → Redis 实时点赞计数 +1
  → 生成 actionId
  → 按 userId 发送 RocketMQ 顺序消息
  → 接口返回
~~~

取消使用 SREM 和 DECR。

### 11.3 为什么按 userId 选择队列

同一用户可能执行：

~~~text
点赞 → 取消 → 再点赞
~~~

按 userId 哈希到同一队列，让同一用户的操作在生产和消费链路中保持相对顺序。

为什么不按 articleId？

> 按 articleId 会让热点文章的全部消息集中到一个队列，降低并行度；业务需要保证的是同一用户状态变化顺序，所以选择 userId 更符合约束。

顺序消息不等于全局有序，也不能替代幂等。

### 11.4 初版 MQ 投递空窗

Redis 已更新后，异步 MQ 发送可能失败：

~~~text
SADD 成功
INCR 成功
MQ 发送失败
~~~

结果：

- 用户看到已点赞；
- MySQL 永远没有对应明细和计数；
- 代码只记录日志，缺少可靠补偿。

这是初版最重要的缺陷之一。改进：

- Redis 操作后写可靠待发送记录；
- 更推荐把关系事实和 Outbox 放在同一个 MySQL 本地事务；
- 后台 relay 重试；
- 消费端继续保证幂等。

### 11.5 至少一次与幂等

消息队列常见语义是至少一次：

- 消息不会轻易丢；
- 但可能重复；
- 消费者必须幂等。

“恰好一次”通常需要业务层幂等和事务边界配合，不能只依赖 MQ 宣传语。

### 11.6 消息积压

可能原因：

- 消费者处理慢；
- MySQL 慢 SQL；
- Redis 超时；
- 消费失败不断重试；
- 消费线程数不足；
- 单个热点队列。

处理思路：

1. 观察生产速率、消费速率和积压量；
2. 找到慢点，不要盲目加线程；
3. 消费逻辑批量化；
4. 水平扩容消费者；
5. 检查下游连接池容量；
6. 对坏消息设置重试上限和死信处理；
7. 保证扩容后仍满足顺序约束。

## 12. 初版 XPlanet 功能与模块

### 12.1 模块

| 模块 | 端口 | 职责 |
|---|---:|---|
| xplanet-common | - | 响应、错误码、常量、Token、限流、用户上下文 |
| xplanet-api | - | 跨模块 DTO/VO |
| xplanet-article | 8081 | 文章、二级缓存、缓存一致性、评论、点赞消费者 |
| xplanet-interaction | 8082 | 点赞/取消、Redis 实时状态、MQ 生产 |
| xplanet-user | 8083 | 用户查询和简化登录 |

### 12.2 为什么初版没有 Gateway 和 Nacos

初版只有三个服务、固定端口和本地演示，直接配置 URL 可以跑通核心链路。没有 Gateway/Nacos 是控制范围的取舍，不等于它们没有价值。

达到以下条件再考虑引入：

- 服务实例动态扩缩容；
- 需要服务发现和配置中心；
- 需要统一鉴权、CORS、路由、灰度；
- 多环境配置管理复杂；
- 入口治理成为真实痛点。

面试回答：

> 初版目标是把缓存和点赞链路做深，因此没有为了技术数量引入网关和注册中心。代价是固定地址、缺少统一入口和动态发现；当实例数和部署环境增加时，再引入 Gateway + Nacos 更合理。

### 12.3 文章功能

- 发布文章；
- 更新文章；
- 删除文章；
- 文章详情；
- 文章列表分页；
- 作者名远程查询；
- 二级缓存；
- 缓存失效广播。

### 12.4 评论功能

- 发布评论；
- 查询文章评论；
- 两级嵌套展示；
- 用户身份来自 Token/ThreadLocal。

两级评论通常比无限递归更容易控制查询和展示复杂度。需要防止：

- parentId 指向其他文章；
- 回复已删除评论；
- 内容过长或包含危险 HTML；
- 分页缺失导致热门文章一次返回过多评论。

### 12.5 用户功能

- 根据用户 ID 查询；
- 简化登录签发 Token；
- Article 调用 User 获取作者名称。

初版登录只根据用户名，不校验密码；Token 为简化 HMAC 实现。面试必须主动说明生产方案：

- BCrypt/Argon2 密码哈希；
- 标准 JWT/JWS 库；
- 强密钥外部注入和轮换；
- 过期、刷新、注销；
- 登录限流与审计。

## 13. 四条核心请求链路

### 13.1 文章详情读取

~~~text
GET /api/article/{id}
  → 参数校验
  → Caffeine.getIfPresent
  → Redis GET
  → Redisson tryLock
  → 锁内 Redis Double Check
  → MySQL selectById
  → UserClient 获取作者名
  → null 写空值缓存 60s
  → 非空写 Redis 30~35min
  → 回填 Caffeine 30s
  → 返回 VO
~~~

每一步的原因：

- 参数校验：挡住非法请求；
- L1：最低延迟；
- L2：多实例共享；
- 锁：防热点击穿；
- Double Check：等待锁期间其他线程可能已经重建；
- 空值：防穿透；
- TTL 抖动：防雪崩；
- VO：不直接暴露数据库 Entity。

### 13.2 文章更新与删除

~~~text
验证文章存在和作者权限
  → 事务内 UPDATE/DELETE MySQL
  → 第一删当前 L1 + 共享 L2
  → 注册事务 afterCommit
提交后：
  → RocketMQ 广播所有实例清 L1
  → 独立 Bean 异步等待约 1s
  → 第二次删除 L1/L2
~~~

高频追问：为什么第一删在事务内，却说“更新 DB 后删缓存”？

参考回答：

> SQL 已经在事务中执行，但尚未提交。第一删用于尽快让当前节点缓存失效；提交后再通过回调广播和第二删。它仍存在事务回滚时无谓失效、提交后动作丢失等窗口，所以属于最终一致的折中方案。

### 13.3 点赞生产者

~~~text
POST /api/like/{articleId}
  → Token 拦截器解析 userId
  → SADD userLikedSet
  → 已存在则幂等返回 false
  → Redis 实时 likeCount +1
  → 生成 UUID actionId
  → asyncSendOrderly，hashKey=userId
  → 回调记录发送成功或失败日志
~~~

这里存在两个 Redis 命令和 MQ 发送，三者没有共同事务。可能出现：

- SADD 成功，INCR 失败；
- SADD/INCR 成功，MQ 失败；
- 取消时 SREM 成功，DECR 失败；
- Redis 状态与 MySQL 明细不一致。

### 13.4 点赞消费者与批量落库

~~~text
收到 LikeMessage
  → 解析 actionId/userId/articleId/delta
  → SETNX actionId，TTL 10 分钟
  → 查询 article_like 当前状态
  → 状态未变化则跳过
  → upsert 关系，唯一约束兜底
  → HINCRBY xp:like:buffer articleId delta
  → 每 500ms 触发 flush
  → RENAME 主缓冲为临时 Key
  → HGETALL 临时 Hash
  → 逐文章 UPDATE like_count = like_count + delta
  → 成功删除临时 Key
  → 失败时把 delta 加回主缓冲
~~~

## 14. 点赞幂等、HINCRBY 与 RENAME 深挖

### 14.1 actionId SETNX 是否去重

会去重，但只是一层快速过滤：

~~~text
SET xp:mq:like:idem:{actionId} 1 NX EX 600
~~~

不能作为最终保证：

- TTL 到期后可再次处理；
- Redis 数据可能丢失；
- SETNX 成功后业务未完成就崩溃，会误拦 MQ 重试；
- 不同 actionId 仍可能表达同一业务动作。

异常时删除幂等 Key 是为了让 MQ 重试，但仍不能覆盖进程突然终止。

### 14.2 为什么要查询和更新 like 状态

重复点赞消息不能直接 `+1`。只有状态变化才产生 delta：

| 当前状态 | 消息目标 | 是否更新关系 | delta |
|---|---|---|---:|
| 未点赞 | 点赞 | 是 | +1 |
| 已点赞 | 点赞 | 否 | 0 |
| 已点赞 | 取消 | 是 | -1 |
| 未点赞 | 取消 | 否 | 0 |

状态比对是业务幂等，唯一约束是并发兜底。

### 14.3 为什么 HINCRBY

假设 500ms 内文章 100 收到：

~~~text
+1 +1 -1 +1 +1 -1
~~~

最终 delta 为 +2，只需要一次数据库更新。

HINCRBY 的价值：

- 原子累加；
- 跨实例共享；
- 合并热点写；
- 应用实例崩溃后数据仍在 Redis。

### 14.4 为什么 RENAME

错误方案：

~~~text
HGETALL 主缓冲
  → 处理期间新 delta 写入主缓冲
  → DEL 主缓冲
  → 新 delta 一起被删，丢失
~~~

RENAME 原子切换：

~~~text
xp:like:buffer
  --RENAME-->
xp:like:buffer:processing:{timestamp}

新消息继续写一个新建的 xp:like:buffer
~~~

这样当前批次和下一批次被隔离。

### 14.5 RENAME 方案仍有哪些问题

**问题一：MySQL 成功、临时 Key 删除前崩溃。**

重启后再次处理临时 Key，计数可能重复增加。

**问题二：部分文章更新成功。**

若第 1～10 篇成功，第 11 篇失败，把整批重新加回会让前 10 篇重复。

**问题三：多实例 flush。**

多个消费者实例可能同时判断主 Key 存在并执行 RENAME，需要额外协调；临时 Key 命名和恢复也要设计。

**问题四：HGETALL 大 Key。**

文章数量增长后可能阻塞 Redis。

**问题五：Redis 故障。**

尚未写 MySQL 的增量仍可能丢失。

更可靠的演进：

~~~text
每条状态变化事件写入持久化 delta 表
  → eventId 唯一约束
  → 批处理事务锁定一组 pending 事件
  → 按 articleId 聚合
  → 更新计数和标记 applied 同事务提交
~~~

## 15. 限流、鉴权、远程调用与容错

### 15.1 AOP 固定窗口限流

Key 示例：

~~~text
xp:rl:{apiKey}:{ip}:{windowNumber}
~~~

windowNumber：

~~~text
currentTimeMillis / windowMillis
~~~

Lua 原子执行 INCR 和首次 EXPIRE。

固定窗口边界问题：

> 在前一个窗口最后一秒通过 limit 次，下一个窗口第一秒又通过 limit 次，短时间可达到约 2 倍限制。

改进：

- 滑动日志；
- 滑动窗口计数；
- 令牌桶；
- 漏桶。

### 15.2 IP 限流的代理头风险

初版直接读取 `X-Forwarded-For`，客户端可以伪造该请求头绕过限流。

正确做法：

- 只有请求确实来自可信反向代理时才信任转发头；
- 网关覆盖而非追加外部头；
- 配置可信代理列表；
- 否则使用 remoteAddr。

### 15.3 Token 与 ThreadLocal

简化链路：

~~~text
Authorization Token
  → 拦截器验签并解析 userId
  → ThreadLocal.set
  → Controller 获取当前用户
  → afterCompletion remove
~~~

鉴权和授权区别：

- 鉴权 Authentication：你是谁；
- 授权 Authorization：你能做什么。

Token 合法不代表可以修改他人的文章，Service 仍需校验 authorId。

### 15.4 RestTemplate 远程调用

Article 调 User 获取作者名，初版使用固定 base URL。

优点：

- 简单；
- 依赖少；
- 适合固定三服务演示。

问题：

- 默认超时如果未配置可能长时间阻塞；
- 地址固定，不支持动态发现；
- 文章列表逐条调用形成 N+1；
- 下游慢会耗尽上游线程；
- fallback 名称可能被 Caffeine 缓存 5 分钟。

改进：

- 配置连接/读取超时；
- 批量查询用户；
- 使用 OpenFeign 改善契约可读性；
- 引入服务发现；
- 熔断和隔离；
- 不缓存失败降级值，或给降级值更短 TTL。

### 15.5 降级、限流、熔断、超时的区别

| 机制 | 目的 |
|---|---|
| 超时 | 不无限等待下游 |
| 重试 | 应对短暂故障，但可能放大流量 |
| 限流 | 保护系统不接收超出能力的请求 |
| 熔断 | 下游持续失败时快速失败，避免资源耗尽 |
| 降级 | 返回简化结果，保留核心功能 |
| 隔离 | 不让一个依赖耗尽全部线程/连接 |

## 16. 项目可能遇到的问题与完整改进思路

### 16.1 缓存问题

| 问题 | 后果 | 初版缓解 | 更进一步 |
|---|---|---|---|
| 穿透 | MySQL 被无效 ID 打满 | 空值缓存 | Bloom Filter、参数空间限制 |
| 击穿 | 热 Key 失效并发回源 | Redisson + Double Check | watchdog、逻辑过期 |
| 雪崩 | 大量 Key 同时失效 | TTL 抖动 | 多级降级、预热、限流 |
| L1 不一致 | 不同实例返回不同值 | MQ 广播 + 短 TTL | Outbox/CDC、版本缓存 |
| 热 Key | Redis 单点压力 | L1 承接 | 分片、副本、本地热点保护 |

### 16.2 点赞问题

| 问题 | 根因 | 改进 |
|---|---|---|
| Redis 成功 MQ 失败 | 跨组件无事务 | Transactional Outbox |
| SETNX 提前成功后崩溃 | 去重标记与业务非原子 | 数据库 inbox/eventId |
| flush 重复 | DB 成功与 DEL 非原子 | 持久化 delta + 事务状态 |
| flush 部分成功 | 批次无统一事务边界 | 每批数据库事务、逐事件状态 |
| Redis 状态丢失 | Redis 不是可靠事实源 | MySQL like_relation 事实源 |
| 实时值与 DB 不同 | 异步最终一致 | 明确读模型、对账和监控 |

### 16.3 MQ 问题

- 重复：消费幂等；
- 丢失：生产确认、Outbox、Broker 持久化；
- 顺序：同业务键同队列，消费者顺序模式；
- 积压：监控、扩容、批量、优化下游；
- 毒消息：有限重试、死信、人工处理；
- 消费后宕机：业务提交与 ACK 边界必须明确。

### 16.4 数据库问题

- 热点文章计数更新形成热点行；
- 缺索引导致慢查询和大范围锁；
- 长事务占用连接；
- 远程调用放在事务内；
- 批量更新没有事务导致部分成功；
- 计数可能减成负数；
- 明细与汇总不一致，需要定期对账。

### 16.5 安全问题

- 初版登录不校验密码；
- 自实现 Token 容易出现签名和过期漏洞；
- 密钥可能硬编码；
- 信任任意 X-Forwarded-For；
- 缺少输入长度、HTML 清理；
- 内部服务无统一认证；
- 日志可能泄露 Token 或个人信息。

### 16.6 可观测性问题

至少应增加：

- 请求 TraceId；
- 缓存 L1/L2 命中率；
- DB 回源次数；
- 锁等待/失败次数；
- MQ 生产失败、消费失败、积压量；
- Redis 缓冲字段数和 delta 总量；
- flush 批次、耗时和失败次数；
- 远程调用延迟、超时和降级次数；
- JVM 堆、GC、线程池、连接池。

## 17. 环境、测试与故障实验

### 17.1 环境组件

~~~text
Java 17
Maven
Docker / Docker Compose
MySQL 8
Redis 7
RocketMQ NameServer + Broker
Article 8081
Interaction 8082
User 8083
~~~

### 17.2 测试金字塔

- 单元测试：不启动中间件，测试纯业务判断；
- 组件测试：测试 Redis Lua、Mapper SQL、序列化；
- 集成测试：启动 MySQL/Redis/RocketMQ；
- API 测试：从登录到文章/点赞链路；
- 并发测试：验证击穿、幂等和批处理；
- 故障测试：停止 MQ/Redis/User，观察降级和恢复。

### 17.3 必做实验一：缓存命中

步骤：

1. 清理文章详情缓存；
2. 第一次请求文章，观察 DB 回源；
3. 第二次请求，观察 Caffeine 命中；
4. 重启 Article 后请求，观察 Redis 命中；
5. 等待 L1 TTL，确认仍可从 L2 获取。

要回答：

- 如何证明命中的是 L1，而不是 L2？
- 如何统计命中率？
- 为什么重启后 L1 消失而 L2 还在？

### 17.4 必做实验二：缓存击穿

步骤：

1. 删除热点文章 L1/L2；
2. 并发请求同一 articleId；
3. 在 DB loader 记录调用次数；
4. 验证大多数请求没有同时回源；
5. 模拟 loader 超过锁租期，观察是否重复回源。

### 17.5 必做实验三：重复消息

步骤：

1. 构造固定 actionId；
2. 连续发送两次相同消息；
3. 验证第二次被 SETNX 或状态比对拦截；
4. 删除幂等 Key 后重发；
5. 验证状态比对仍不产生重复 delta。

### 17.6 必做实验四：MQ 发送失败

步骤：

1. 停止 Broker；
2. 发起点赞；
3. 观察 Redis Set 和计数是否已经改变；
4. 检查 MQ 回调错误；
5. 恢复 Broker；
6. 验证初版是否自动补发。

预期结论：

> 初版不会可靠补发，这正是 Outbox 演进依据。

### 17.7 必做实验五：flush 崩溃窗口

步骤：

1. 在 MySQL 更新后、临时 Key 删除前人为抛异常；
2. 检查 MySQL 已增加、临时 Hash 是否仍存在；
3. 再次执行 flush；
4. 观察是否重复增加；
5. 解释为什么补偿 HINCRBY 不能解决所有部分成功问题。

### 17.8 必做实验六：ThreadLocal 污染

步骤：

1. 临时注释请求结束的 remove；
2. 使用小线程池连续发送不同用户请求；
3. 观察是否可能复用旧 userId；
4. 恢复 finally 清理；
5. 写单元测试验证。

## 18. 高频后端八股问答

### 18.1 Java/JUC/JVM

#### Q1：HashMap 为什么线程不安全？

参考回答：

> 并发 put、扩容和桶内修改没有完整同步，可能丢更新或读到不一致结构。并发场景使用 ConcurrentHashMap，但“先检查再修改”等复合逻辑仍要使用原子 API。

#### Q2：volatile 和 synchronized 区别？

参考回答：

> volatile 提供可见性和有序性，不提供复合操作互斥；synchronized 同时提供互斥、可见性和有序性。状态只需发布时可用 volatile，涉及复合不变量通常需要锁。

#### Q3：线程池为什么不建议 Executors 默认工厂？

参考回答：

> 某些工厂使用无界队列或几乎无上限线程数，容易 OOM。生产中应显式设置核心线程、最大线程、队列、线程名和拒绝策略，并监控活跃线程与队列长度。

#### Q4：CAS 有什么问题？

参考回答：

> ABA、自旋耗 CPU、只适合单变量原子更新。可通过版本戳处理 ABA，竞争激烈或多变量约束时使用锁或事务。

#### Q5：什么对象可以被 GC？

参考回答：

> 从 GC Roots 不可达的对象才具备回收条件；不可达不代表立刻回收。GC Roots 包括栈引用、静态字段、JNI 引用和活跃线程等。

#### Q6：如何定位 Full GC 频繁？

参考回答：

> 先结合 GC 日志和监控判断原因，是老年代增长、元空间、晋升失败还是大对象；再用 heap dump 分析对象占用与引用链，检查缓存、静态集合、ThreadLocal、队列和类加载。

### 18.2 Spring

#### Q7：IoC 解决什么问题？

参考回答：

> 把对象创建、依赖装配和生命周期交给容器，使业务依赖接口、降低耦合，并为代理、配置和测试替换提供统一基础。

#### Q8：AOP 为什么会失效？

参考回答：

> Spring AOP 依赖代理。对象内部 `this.method()` 没有经过代理，private/final 等不可代理场景也可能失效；异常吞掉或方法不符合事务代理条件同样会造成误判。

#### Q9：@Transactional 默认回滚什么？

参考回答：

> 默认对 RuntimeException 和 Error 回滚。若需要 checked exception 回滚，可配置 `rollbackFor = Exception.class`。更关键的是异常不能被业务层吞掉。

#### Q10：事务中能否调用远程服务？

参考回答：

> 技术上可以，但不推荐长时间远程调用占用数据库连接和锁。应设置超时，尽量把远程读取移到事务外；跨服务写一致性则使用 Saga、Outbox 等模式，而不是把本地事务误认为分布式事务。

### 18.3 MySQL

#### Q11：为什么 B+ 树适合索引？

参考回答：

> 高扇出降低树高和磁盘 I/O，所有数据在叶子节点，叶子有序便于范围扫描，非叶子可容纳更多键。

#### Q12：什么是回表？

参考回答：

> 二级索引找到主键后，再到聚簇主键索引读取完整行。覆盖索引包含所需列时可以避免回表。

#### Q13：唯一索引和业务幂等有什么关系？

参考回答：

> 应用层判断在并发下可能同时通过，唯一索引是数据库层最终竞争裁决。应用捕获冲突后转成幂等成功或业务提示。

#### Q14：MVCC 是否完全不加锁？

参考回答：

> 不是。普通快照读主要依赖版本；UPDATE、DELETE、SELECT FOR UPDATE 等当前读仍会加锁。隔离级别和索引条件会影响锁范围。

#### Q15：如何优化慢 SQL？

参考回答：

> 先使用慢日志和 EXPLAIN 定位，检查扫描行数、索引、回表、排序和临时表；再结合业务减少返回列、建立合适联合索引、改写分页或拆分查询。不能只靠“加索引”，索引还增加写成本。

### 18.4 Redis与缓存

#### Q16：Redis 单线程为什么还快？

参考回答：

> 内存访问、命令执行简单、I/O 多路复用并避免复杂锁竞争。单线程也意味着大 Key、慢 Lua 会阻塞其他请求。

#### Q17：缓存穿透、击穿、雪崩区别？

参考回答：

> 穿透是查不存在数据；击穿是单个热点 Key 失效；雪崩是大量 Key 同时失效或缓存整体不可用。对应空值/Bloom、互斥重建、TTL 抖动与限流降级。

#### Q18：为什么锁内要 Double Check？

参考回答：

> 等锁期间前一个线程可能已完成重建。拿到锁后再次检查 Redis，避免每个等待者依次重复查数据库。

#### Q19：缓存与数据库如何强一致？

参考回答：

> 普通 Cache Aside 很难提供严格强一致，只能缩小窗口并最终收敛。强一致场景应减少缓存、串行化写、使用版本校验或让读取直接走权威存储，不能把延迟双删说成强一致。

#### Q20：分布式锁有哪些风险？

参考回答：

> 租期过短导致锁提前释放、持锁进程停顿、Redis 故障切换、误解锁、等待线程堆积。要使用唯一 owner、只释放自己的锁、合理租期/watchdog、超时降级，并保证被保护操作本身尽量幂等。

### 18.5 RocketMQ与幂等

#### Q21：MQ 为什么会重复？

参考回答：

> 生产重试、Broker 重投，或消费者业务已提交但 ACK 前崩溃，都会重复。因此至少一次语义下消费者必须幂等。

#### Q22：SETNX actionId 是否足够？

参考回答：

> 不够。TTL 会过期、Redis 可能丢数据、SETNX 后业务前崩溃会误拦重试。它适合快速过滤，最终应依赖业务状态和持久化唯一约束。

#### Q23：顺序消息为什么还要幂等？

参考回答：

> 顺序只解决同一键的相对先后，不解决重复投递、业务超时重试和消费者崩溃，因此两者是不同维度。

#### Q24：如何保证数据库和 MQ 一致？

参考回答：

> 初版没有完全保证。常见方案是 Transactional Outbox：业务数据和待发送事件同一数据库事务提交，后台 relay 发送，消费端幂等吸收重复。

#### Q25：消息积压如何处理？

参考回答：

> 先比较生产消费速率并定位瓶颈，再优化慢 SQL/外部调用、批量消费、增加消费者和队列；同时确认下游连接池可承受扩容，避免只把压力转移到数据库。

### 18.6 项目综合追问

#### Q26：为什么使用二级缓存，不只用 Redis？

参考回答：

> 热点文章会产生大量重复读取，Caffeine 消除网络往返并减少 Redis QPS；Redis 负责多实例共享和更大容量。代价是 L1 一致性复杂，因此使用 MQ 广播和短 TTL。

#### Q27：为什么 HINCRBY 后不立即写数据库？

参考回答：

> 目标是把大量单次热点行 UPDATE 合并为按文章的一次增量 UPDATE，降低 DB QPS 和行锁竞争。代价是最终一致和缓冲恢复复杂度。

#### Q28：为什么 RENAME 后消费者还能继续写？

参考回答：

> RENAME 原子地把旧主 Key 移到临时 Key，原主 Key 不再存在。后续 HINCRBY 会自动创建新的主 Hash，因此刷库和新写入分离。

#### Q29：初版最严重的问题是什么？

参考回答：

> 点赞 Redis 更新与 MQ 发送之间没有可靠事务，MQ 失败只记日志；其次 Redis Hash 刷库在 DB 成功但删除临时 Key 前崩溃、部分成功时可能重复。这两个问题都需要持久化 Outbox/Inbox 和事务投影演进。

#### Q30：为什么不一开始就上所有中间件？

参考回答：

> 项目目标是验证缓存和高并发点赞链路。组件越多，部署、故障和认知成本越高。只有当统一入口、动态发现、分布式事务或治理成为真实需求时再引入，技术选型应由问题驱动。

## 19. 项目表达模板

### 19.1 30 秒版本

> XPlanet 是一个开发者社区后端，我主要解决热点文章读取和高并发点赞。读取侧采用 Caffeine + Redis 二级缓存，并通过空值、Redisson 锁、Double Check 和 TTL 抖动处理缓存三大问题；写入侧使用 Redis 保存实时点赞状态，通过 RocketMQ 异步削峰，消费端做多层幂等，并用 Redis Hash 聚合 delta 后批量更新 MySQL。

### 19.2 2 分钟版本

> 项目拆成 Article、Interaction、User 三个服务。文章详情属于典型读多写少场景，所以先查 Caffeine，再查 Redis，双 miss 后使用按 articleId 的 Redisson 分布式锁重建，锁内 Double Check，数据库不存在时写 60 秒空值，正常数据设置 30 分钟加随机抖动。文章更新采用 Cache Aside，数据库更新后删除缓存，提交后通过 RocketMQ 广播清除多实例 Caffeine，并异步延迟第二删。
>
> 点赞属于瞬时高并发写。接口先通过 Redis Set 原子判断用户状态并更新实时计数，再按 userId 发送 RocketMQ 顺序消息。消费者先用 actionId SETNX 快速去重，再比较 MySQL 点赞关系状态，只有状态真实变化才 HINCRBY 聚合文章 delta；定时任务通过 RENAME 原子切换缓冲区并批量更新 MySQL，减少热点行写入。
>
> 我也复盘了初版局限：Redis 与 MQ 没有事务，发送失败只记日志；SETNX 有提前标记风险；Hash 刷库存在部分成功和崩溃重复窗口。因此后续可以演进到关系表事实源、Transactional Outbox 和持久化 delta 投影。

### 19.3 STAR 难点表达

**Situation**：热点文章缓存失效时会有大量请求同时回源；点赞高峰会产生大量单行数据库更新。

**Task**：在不堆砌组件的前提下，降低读延迟和数据库写压力，并处理重复消息与多实例缓存一致性。

**Action**：

- 使用 Caffeine + Redis；
- 使用 Redisson 锁、Double Check、空值、TTL 抖动；
- 使用 Cache Aside、延迟双删、MQ 广播；
- 使用 Redis Set、RocketMQ 顺序消息、多层幂等；
- 使用 HINCRBY + RENAME 双缓冲批量落库；
- 使用 AOP + Lua 限流和服务降级。

**Result**：

> 建立了完整可运行链路并减少理论上的重复 DB 写，但未用真实生产容量验证具体 QPS，所以只描述机制和测试现象，不编造性能数字；同时识别了投递和刷库可靠性窗口并给出 Outbox 演进方案。

## 20. 七天复习计划

### 第 1 天：项目全貌

- 画模块图；
- 讲四条核心链路；
- 背熟事实边界；
- 完成 30 秒和 2 分钟介绍。

### 第 2 天：Java、JUC、JVM

- HashMap/ConcurrentHashMap；
- volatile/synchronized/CAS；
- 线程池/ThreadLocal；
- JVM 内存/GC Roots/G1；
- 完成六道口述题。

### 第 3 天：Spring

- IoC/Bean 生命周期；
- AOP 代理与自调用；
- 事务传播、回滚失效；
- MVC Filter/Interceptor；
- 结合 afterCommit 和 @Async 源码讲解。

### 第 4 天：MySQL

- B+ 树、聚簇/二级索引；
- MVCC、隔离级别、锁；
- 唯一约束与幂等；
- 分页、慢 SQL；
- 画出 article_like 表约束。

### 第 5 天：Redis与缓存

- 数据结构；
- RDB/AOF；
- 穿透/击穿/雪崩；
- Redisson 与 Double Check；
- Cache Aside、延迟双删；
- 亲手做缓存命中和击穿实验。

### 第 6 天：RocketMQ与点赞

- 至少一次、顺序、重复、积压；
- SETNX/状态比对/唯一约束；
- HINCRBY/RENAME；
- 故障窗口和 Outbox；
- 亲手做重复消息和 MQ 停机实验。

### 第 7 天：模拟面试

- 不看文档讲项目 5 分钟；
- 回答第 18 章全部问题；
- 每个回答控制在 30～90 秒；
- 对不会的问题记录“概念、项目位置、风险、改进”；
- 再做一轮压力式追问。

## 21. 面试前最终检查清单

- [ ] 能明确说简历是初版 `9bcaeb5`，不混入后续 Agent/Gateway/Outbox 实现。
- [ ] 能手画 Article、Interaction、User、MySQL、Redis、RocketMQ。
- [ ] 能完整解释文章详情读取流程。
- [ ] 能解释 Double Check，而不只会背名称。
- [ ] 能区分穿透、击穿和雪崩。
- [ ] 能画出延迟双删竞态时间线。
- [ ] 能解释为什么多实例 Caffeine 要 MQ 广播。
- [ ] 能解释 SADD/SREM 的返回值和业务幂等。
- [ ] 能解释按 userId 顺序，而不是按 articleId。
- [ ] 能解释 SETNX 为什么只是第一层。
- [ ] 能列出状态比对和唯一约束的职责。
- [ ] 能解释 HINCRBY 聚合和 RENAME 双缓冲。
- [ ] 能主动指出 flush 部分成功与崩溃窗口。
- [ ] 能解释 INCR + EXPIRE 为什么要 Lua。
- [ ] 能说明固定窗口边界问题。
- [ ] 能解释 ThreadLocal 为什么 remove。
- [ ] 能说明 RestTemplate 固定地址、超时、N+1 和降级缓存风险。
- [ ] 能解释 Spring AOP 自调用和事务失效。
- [ ] 能回答 MySQL B+ 树、MVCC、唯一索引、死锁。
- [ ] 能回答 JVM 内存、GC Roots、G1 和内存排查。
- [ ] 不宣称未经验证的 QPS、强一致、零丢失或生产高可用。
- [ ] 能用 Outbox 和持久化投影解释后续演进，但不说成初版已实现。

## 22. 最后的答题方法

遇到任何项目问题，都可以使用六句式：

1. **场景**：当时遇到什么业务压力或一致性问题；
2. **目标**：要保护什么系统不变量；
3. **方案**：使用了哪些组件和数据结构；
4. **流程**：请求每一步如何流动；
5. **边界**：失败、并发和重试时哪里仍有风险；
6. **演进**：规模扩大后如何升级。

示例：

> 热点文章缓存同时失效时，大量请求会回源 MySQL。我的目标是保证同一 articleId 同一时间尽量只有一个线程重建缓存，因此对文章维度加 Redisson 分布式锁，拿锁后 Double Check Redis，再查询数据库并回填。抢锁失败的线程短暂等待后读 Redis，仍没有则降级直接查库。这个方案能减少击穿，但初版固定 3 秒租期可能早于慢查询结束，后续会使用 watchdog 或调整重建超时，并监控锁等待和 DB 回源次数。

真正掌握的标志不是背出术语，而是面试官连续追问“为什么、失败会怎样、还能怎么做”时，你仍能沿着不变量和时间线继续分析。
