# AutoX 插件化工作日志

> 项目：AutoX（安卓自动化脚本引擎）Web 远程控制台插件化改造
> 分支：`setup-v7`（suncV7 渠道）｜服务端口：9317
> 方法论：android-ws-remote-plugin skill（双轨：A 前端还原 + B 后端实现）

---

## 一、项目目标

把 AutoX 改造成"手机自己是服务器"的 Web 远程控制台：浏览器局域网直连 `http://手机IP:9317`，
覆盖原 App 可视化界面的全部功能（脚本管理、运行控制、循环运行、定时任务、文件上传下载等），
同时保证对原项目改动面最小（利于上游 merge）。

## 二、架构决策

- **复用内置 DevPlugin 通道**：Ktor Netty 同端口提供 HTTP 控制页 + WebSocket 命令（端口 9317），
  不新增独立服务，不引入 NanoHTTPD。
- **命令帧格式**：`{"type":"command","data":{"command":"xxx",...}}`（AutoX RootRouter 把 data 原样
  传给子 Router，命令键必须在 data 内部）。
- **握手**：客户端回 `{"type":"hello","data":"ok","version":"11090",...}`（version≥11090 才期待 "ok"）。
- **改动面**：4 源码文件 + 1 assets + 2 构建文件。

## 三、提交链（setup-v7）

| 提交 | 内容 |
|---|---|
| `8636463` | web remote via devplugin（控制页 + WS 通道 + MainActivity 拉起） |
| `1a8110e` | command frame 格式修正 |
| `39c6675` | run_path 直接运行原文件 + preBuild 挂 v6modules |
| `152be6c` | UI 校准：文件夹/文件分区 + 大小显示（真机截图对照） |
| `e0f87fb` | 构建加 x86_64 ABI（模拟器适配） |
| `d0bdecfb` | 修复点击运行跑两次（stopPropagation） |
| `ec677c1` | **web 控制台全覆盖**：12 个新命令 + /download /upload + 控制页全功能重写 |
| `55398aa` | 修复上传：FormData multipart 被当文件内容写入 → raw body；新增"粘贴文本"上传 |

## 四、功能覆盖对照（App 全功能 → Web）

### 已承接 ✅

| App 功能 | Web 实现 | 后端命令 |
|---|---|---|
| 文件夹/文件列表、进入目录 | 两分区 + 路径导航 | `list_scripts` |
| 排序（名称/大小/时间）+ 折叠 | 分区头排序按钮 + 折叠 | list_scripts 返回 mtime |
| 运行脚本 | 行"▶ 运行" / 点行运行 | `run_path` |
| 编辑脚本 | 内置编辑器（读→改→保存/保存并运行） | `read_file` / `write_file` |
| 新建文件/文件夹 | FAB 菜单 → 表单 | `create_file` / `create_dir` |
| 导入文件 / 粘贴文本 | FAB"导入文件"（raw body 上传）/ "粘贴文本" | `/upload` / `write_file` |
| 下载文件 | 行下拉"下载" | `/download` |
| 循环运行 | 行下拉"循环运行"（次数+间隔） | `run_loop` |
| 定时任务（每日/每周/一次性） | 行下拉"定时任务"表单 + 任务面板 | `timed_task_add` / `timed_task_remove` / `timed_tasks` |
| 删除/重命名 | 行下拉 | `delete` / `rename` |
| 任务管理（运行中+定时两组） | 任务面板 + 停止/停止全部 | `tasks` / `stop` / `stopAll` |
| 抽屉开关类 | （后端已备） | `pref_get` / `pref_set` |

### 未承接 ⏸（系统级 / 独立 Activity，web 无法等价实现）

- 新建项目（独立 ProjectConfigActivity）
- 创建快捷方式、用其他应用打开、打包 APK（系统 Intent）
- 重置示例
- Documentation 文档阅读页（WebView 加载）
- 抽屉：通知权限/录制/悬浮窗/音量键/自动备份/连接电脑/USB 调试/项目地址/反馈/检查更新/退出

## 五、关键问题与修复记录

### 1. 脚本无法运行（三个根因）
1. 命令帧格式错误：命令键必须在 `data` 内部（`{"type":"command","data":{"command":"run_path",...}}`），
   否则子 Router 收到空 data，命令被静默丢弃。
2. APK 缺 v6modules：`buildV6Api` 未挂 preBuild → 加 dependsOn。
3. `run_path` 必须直接运行原文件（不复制到 cache），保留 require 依赖。

### 2. 点击运行跑两次
行点击事件冒泡到按钮 → 按钮 onclick 加 `event.stopPropagation()`，headless 点击冒烟验证 SENDS=1。

### 3. Ktor 2.0.3 路由 API 踩坑
- get/post handler 的 receiver 是 `PipelineContext<Unit, ApplicationCall>`，lambda **无参数**；
  `context` 成员就是 `ApplicationCall`（`context.request.queryParameters["x"]`）。
- 不要写 `{ call -> }`（Unresolved call）；`queryParameters` 是成员无需 import；
  `receiveChannel` 扩展 receiver 不符 → 用 `receiveStream()`（HttpApi 同款）。

### 4. 上传脚本失败（multipart 污染）
浏览器 FormData 是 multipart 编码，后端 `receiveStream` 原样写盘会写入边界头 → 乱码。
修复：前端 `fetch(body: File)` 直接传 raw body，与后端对齐（MD5 字节级校验通过）。

### 5. 行内下拉菜单被列表 overflow 裁剪
`.dd` absolute 定位在 `.list{overflow-y:auto}` 内被裁掉。修复：toggle 时
`getBoundingClientRect()` 改 `position:fixed` 定位到按钮下方。

### 6. 循环运行日志不回推
`Scripts.runRepeatedly` 走 engine execute 无 listener → 日志不回推。改为协程手动循环 +
`EngineController.runScript(file, listener)`（onLog 走全局 console），`mLoopRunning[path]` 标记
支持 stop/stopAll 中断。注意：引擎未预热时 interval 太短会排队，端到端测试先 run_path 预热。

### 7. uiautomator dump 对 Compose 失效
只出根节点 → 改用 `input tap` + `screencap` + 视觉识别逐元素遍历；FAB 等无文本元素靠源码定位。

### 8. Netty native transport 缺失（模拟器）
`No implementation found for kqueue/epoll Native` 是噪声（回退 NIO），不影响监听；
但 App 冷启动服务需 15s+ 才可访问，curl 太早会 000。

## 六、验证记录

- **真机（小米 M2007J1SC）**：握手→list_scripts→run_path 3 帧日志→tasks→stop/stopAll 全通。
- **模拟器（API28_Android9, x86_64）**：
  - 12 新命令全通（含中文路径）；
  - `/upload` raw body → MD5 字节级一致 → `run_path` 运行出日志帧；
  - `write_file` 文本上传 → `read_file` 内容一致；
  - `run_loop` 预热后 3 次循环 3 组日志；
  - 定时任务增删成功；
  - 控制页 headless mock：FAB 菜单/行下拉/编辑器/任务面板/粘贴文本模态渲染正常，无横向溢出。

## 七、产物

- 真机 APK：`/Users/apple/Documents/workProject/安卓/AutoX-suncV7-debug.apk`（arm64）
- 模拟器 APK：`/Users/apple/Documents/workProject/安卓/AutoX-suncV7-x86_64-debug.apk`
- 控制页源码：`app/src/main/assets/control.html`
- 后端扩展：`app/src/main/java/org/autojs/autojs/devplugin/DevPluginResponseHandler.kt`、`KtorWebSocket.kt`

## 八、未完成 / 待办

- [ ] 抽屉开关前端 UI（pref_get/pref_set 已备后端，接前端开关）
- [ ] Documentation 文档阅读页 web 版
- [ ] 定时任务"暂停/恢复"（App 有取消调度态）
- [ ] 控制页登录鉴权（当前局域网直连无鉴权）

## 九、connect_compute（连接电脑）+ 控制台地址显示（2026-09-21）

### 需求

1. 手机通过 WS 主动连 PC 端 AutoX（`ws://电脑IP:9317`），对开发脚本有帮助（PC 侧 VSCode 插件/AutoX 桌面端可经此通道管理手机脚本）。
2. App 界面显示 Web 与 WS 访问地址。

### 实现（提交 2fb2c31，4 文件 +142/-1）

- **后端 `connect_compute` 命令**（DevPluginResponseHandler.kt，Router command 链内）：
  - `{action:"connect",url:"ws://..."}`：校验 `^ws://|wss://` → `Pref.saveServerAddress` + `DevPlugin.connect(url)`（手机作为 WS 客户端）；
  - `{action:"disconnect"}`：仅当本次会话发起过 PC 连接才 `DevPlugin.close()`；
  - `{action:"status"}`（缺省）：回当前连接状态（0 未连接/1 连接中/2 已连接/3 失败/4 重连中/5 超时）。
- **DevPlugin.kt 增加 `@Volatile var currentState`**：`connectState` 是 `MutableSharedFlow(replay=0)`，无订阅者时 `replayCache` 恒空，读不到当前值；`emitState` 同步快照变量供查询。
- **控制页**：statusbar 增加「连接电脑」按钮 → 模态（地址输入、状态、刷新/断开/连接/关闭）。
- **抽屉**：「其他」分组加「远程控制台（Web/WS 地址）」弹窗（`http://IP:9317` 与 `ws://IP:9317`，长按复制）。

### 踩坑（本轮三次重写）

1. **Router 链闭合位置**：`pref_set` 尾 `})` 已闭合 command 链，connect_compute 曾插到 RootRouter 层——命令帧走 command 链永远查不到，Router 只打印 `handle` 不回包、无任何异常（静默丢弃），极易误判。
2. **`connectState.first()` 挂起**：replay=0 无订阅者时 `first()` 永久挂起（旧实现），改 `replayCache.firstOrNull()` 仍读不到 → 最终用 `currentState` 变量。
3. **DevPlugin.connection 单槽**：控制页连接也占用 `connection`，未连 PC 时调 `disconnect` 会 `DevPlugin.close()` 误关控制页连接（onError: Job was cancelled + onFinally）→ 用 `mPcConnectedUrl` 记录本次会话是否发起过 PC 连接，仅当时才 close。
4. **adb forward 方向**：`forward tcp:9318 tcp:9318` 是 PC→设备方向，手机 `10.0.2.2:9318` 被拦 → 需 `forward --remove` 后手机直连宿主机。

### 实测（模拟器 x86_64，PC 端用本地 websockets 模拟 server）

- status（未连接）→ `0 未连接` ✓
- connect `ws://10.0.2.2:9318` → PC server 收到手机 hello（device_name google Android SDK）→ 握手回 ok → 状态 `2 已连接` ✓
- PC 连接期间 `list_scripts` 仍通 ✓（控制页命令未受影响）
- disconnect → `0 未连接`，断开后 `list_scripts` 仍通 ✓（控制页连接未被误杀）
- 无效地址 `http://bad` → `地址必须以 ws:// 或 wss:// 开头` ✓
- 附：PC 端模拟 server 脚本 `/tmp/pc_mock_server.py`（websockets 15，回 hello ok/pong，记录帧）

### 产物

- APK（已含 connect_compute）：`/Users/apple/Documents/workProject/安卓/AutoX-suncV7-debug.apk`（arm64）、`AutoX-suncV7-x86_64-debug.apk`
- 提交：`2fb2c31 feat: 连接电脑（connect_compute）+ 控制台地址显示`

## 十、测试方法论 + 技能库安装（2026-09-21）

### 1. 安装 android-testing-skills（全局）

- 仓库：`https://github.com/skydoves/android-testing-skills`（skydoves，Apache-2.0，54 个 Agent Skill）
- 结构：7 组 = `compose/`(25, Compose UI 测试) + `jvm-tests/`(6, JUnit4/MockK/runTest/Turbine/Robolectric) + `instrumentation/`(6, AndroidJUnit4/ActivityScenario/Espresso/UiAutomator) + `adb/`(10, ADB E2E: 连接/screencap/logcat/CI 脚本) + `fundamentals/`(5, 测试金字塔/test doubles/Given-When-Then) + `kotlin/`(1) + `platform/`(1)
- 安装：`git clone` → `scripts/install-skills.sh <扁平目标目录>`（symlink 扁平化，每个 slug 一个目录含 SKILL.md）→ **本项目用 cp 复制进全局技能库**（避免 /tmp symlink 断链）：`/Users/apple/Library/Application Support/DoubaoWork/Default/.doubaowork/agent_mode/workspace/.skills/`（54 个目录，无同名冲突，SKILL.md 已验证）
- 磁盘占用：全库 36MB（54 个 SKILL.md 各 16–26KB）；内存 0 常驻（按需读取）
- 生效：下个会话 skill 列表注入；用哪个 skill 前先 Read 对应 SKILL.md

### 2. 测试分层方案（针对 AutoX 插件化，下次执行）

| 层 | 对象 | 工具（对应 skill） | 状态 |
|---|---|---|---|
| JVM 单测 | Router 命令分发、connect_compute action 分支/URL 校验/disconnect 保护、协议帧解析、状态机 | JUnit4 + MockK + kotlinx-coroutines-test(runTest) + Turbine（测 flow） | **待搭 app/src/test** |
| Compose UI | 抽屉地址弹窗等插件 UI | createComposeRule + testTag（compose/ 组） | 可选，先 E2E 截图 |
| ADB E2E | 13 条命令冒烟 + 装机 + screencap/logcat | 现有 python websocket 脚本 + adb/ 组 | 已有，系统化 |
| headless 前端 | 控制页布局 | Chrome + DOM 测量 | 已有 |

- 关键收益：**Router 链闭合位置类 bug（本轮踩过、静默丢弃零日志）纯 JVM 单测当场可抓**，不用每次改 Kotlin 重装模拟器。
- 内存/磁盘评估（16GB RAM / 113GB 盘 可用 9.7GB）：
  - 单测额外内存 <1GB（test worker 512MB–1GB 峰值，跑完释放，共用 Gradle daemon 4GB）
  - 单测额外磁盘 30–80MB 依赖下载 + 几 MB 产物
  - 可省内存：构建传参 `-Dorg.gradle.jvmargs="-Xmx2048m"`；单测 maxHeapSize 512m；构建时暂关模拟器
  - 可省磁盘：`./gradlew clean` 清 app/build（6.1GB）；清 ~/.gradle 旧版本缓存（4.6GB 总量）

### 3. 待办（下次）

- [x] 在 AutoX 搭 `app/src/test` 单测骨架（JUnit4 + MockK + runTest + Turbine），先覆盖 DevPluginResponseHandler 的 router 分发与 connect_compute 逻辑
- [x] 单测接入后跑 `./gradlew :app:test`（限堆 2GB）验证

## 十一、JVM 单测骨架落地（2026-09-21，提交 1953a04）

### 1. 依赖与配置

- `gradle/libs.versions.toml` 新增：mockk 1.13.9（Kotlin 1.9.25 兼容）、kotlinx-coroutines-test 1.6.4（与项目 coroutines 1.6.2 同族）、turbine 0.12.1
- `app/build.gradle.kts`：`testOptions.unitTests { isReturnDefaultValues = true; maxHeapSize = "512m" }` + 三个 testImplementation

### 2. 测试文件（app/src/test/java/org/autojs/autojs/devplugin/）

| 文件 | 覆盖 |
|---|---|
| `RouterTest.kt` | 帧格式（命令键必须在 data 内）回归、RootRouter data 缺失/非对象返回 false、未知命令 false、**链层级回归**（命令挂在 RootRouter 层 vs command 链层永不命中）、bytes_command 平级链 |
| `DevPluginResponseHandlerTest.kt` | handler 层帧格式回归、list_scripts 目录优先+lowercase 排序+字段、create_file 重名/扩展名、create_dir/rename/delete/read_file（含 >2MB 空内容）/write_file（中文路径+内容） |
| `ConnectComputeTest.kt` | action 三分支（connect/disconnect/status）、URL 校验（ws/wss 接受、http/大写 WS/裸 ws:// 拒绝）、**disconnect 保护回归**（未连 PC 不调 DevPlugin.close）、connect→disconnect close 恰好一次 |

### 3. MockK 踩坑（本轮三次修正，均为 JVM 单测特有）

1. **`mockkStatic(cls) { block }` 块版本在块结束即还原 mock**：Pref 类加载期调用 `GlobalAppContext.get()` 需 mock 常驻 → 必须无块版本 `mockkStatic(cls)` + 单独 `every`，tearDown `unmockkAll()` 统一清理。
2. **`@JvmStatic` 方法需 `mockkStatic`，`mockkObject` 只 mock 实例方法**：`GlobalAppContext.get()` 编译为静态方法，mockkObject 不生效 → 真实执行抛 "Call GlobalAppContext.set()"。
3. **`Dispatchers.Main` 在 JVM 不存在**：GlobalAppContext 初始化 `CoroutineScope(Dispatchers.Main)` 直接类初始化失败 → 每个测试 @Before 先 `Dispatchers.setMain(StandardTestDispatcher())`，@After `resetMain()`。
4. **static mock 的 verify 会重放类加载期调用**：`mockkStatic(Pref)` 首次触发类加载，`getSharedPreferences/def/getString` 等初始化调用混入 every 记录，`verify { Pref.saveServerAddress(...) }` 报 "Recorded calls count differ between runs" → @Before 先 `Class.forName("org.autojs.autojs.Pref")` 预加载再 mock，且不对 Pref 做正数 verify（由响应回显 + DevPlugin.connect 副作用覆盖）。
5. **MockKMatcherScope 成员优先级**：`every { get() }` 内 `get()` 解析到 matcher scope 的成员 → 必须写 `every { GlobalAppContext.get() }` 显式限定。
6. **suspend 返回 Unit? 的 stub**：`DevPlugin.close()` 类型为 `Unit?`（`connection?.close()`），`just Runs` 不匹配 → `coEvery { DevPlugin.close() } returns null`。

### 4. 验证结果

- `./gradlew :app:testSuncV7DebugUnitTest`：33 测试全过
- `./gradlew :app:test --no-parallel -Dorg.gradle.jvmargs="-Xmx2048m"`：**8 变体（common/v7/v7_mini/suncV7 × debug/release）264 测试全过，0 失败**
- 收益兑现：Router 链闭合/帧格式类 bug（静默丢弃零日志）现在 JVM 单测当场可抓，不再每次改 Kotlin 重装模拟器
