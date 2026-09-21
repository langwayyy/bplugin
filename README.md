# Quiet Crypto

独立的 IntelliJ IDEA 加密货币行情插件。沿用 Quiet Portfolio 的灰色极简风格，独立插件 ID `com.kkk.bplugin`、配置文件 `quiet-crypto.xml` 和快捷键，可以与原插件同时安装。

## 功能

- 币安现货公共行情，无需账户、API Key 或 Cookie。
- 按名称/交易对搜索，按 USDT、USDC、BTC、ETH 或全部报价币筛选。
- 本地自选列表：最新价、滚动 24h 涨跌幅、报价币成交额；点击表头排序，上下移动恢复手动顺序。
- 自选支持本地分组、分组筛选和备注；可以用制表符或逗号分隔文本从剪贴板批量导入，并将完整名单复制导出。
- 内置本地模拟交易账户：支持 USDT 现货市价单、限价单、撤单、持仓成本、手续费、滑点及实时盈亏；模拟订单不会发送到币安。
- 右键交易对：显示在状态栏、打开 K线浮窗、设为编辑器背景、加入轮播、移除自选。
- 状态栏最多六个交易对，支持仅价格、名称与价格、名称与涨跌幅、完整信息；点击打开 K线，右键打开列表。
- 固定、可拖动和缩放的 K线浮窗；1分、5分、15分、1时、4时、日、周周期，MA5/MA10/MA20、成交量、鼠标十字线及 OHLC 详情。
- K线支持鼠标滚轮缩放、按住左键拖动历史区间及重置视图；周期增加30分、2时、6时和12时。
- 编辑器背景 K线默认关闭，不接收鼠标事件；可调整透明度，并可每30秒轮播本地列表。
- WebSocket 实时更新价格和当前蜡烛；REST 负责首次加载、历史 K线与至少每分钟一次的校准。支持断线退避、45秒无消息重连和连接定期重建。
- 5秒、10秒、30秒或手动 REST 校准；默认开启实时流，支持 IDEA 非前台暂停。失败时保留本次会话旧数据，限流按 Retry-After 暂停。
- 价格使用十进制解析，自动保留小价格精度。默认灰色，可选择涨红跌绿。
- 本地静默提醒支持价格上穿/下穿和滚动24h涨幅/跌幅阈值，按穿越阈值触发并遵守冷却时间，只在状态栏和行情页显示。

## 使用

在 IDEA 的 `Settings → Plugins → 齿轮 → Install Plugin from Disk` 中选择 `build/distributions/bplugin-1.4.0.888888-SNAPSHOT.zip`，按 IDE 提示重启。

K线浮窗可通过右下角「关闭」按钮关闭，焦点位于浮窗内时也可按 Esc。关闭后可从行情列表或状态栏重新打开。

通过 `Tools → 打开 Quiet Crypto` 或状态栏 `Crypto` 进入。首次等待交易对加载后，搜索并添加自选。状态栏不可见时，在状态栏右键启用 **Quiet Crypto**。

设置入口：`Settings → Tools → Quiet Crypto`。连接使用 IDEA 的 HTTP 代理设置；连接测试会主动请求币安公共接口。停用行情后不再发起自动请求，已在途的响应不会更新界面数据。

快捷键采用两段输入：先按 `Ctrl+Alt+Shift+B`，松开后按：

| 第二段 | 功能 |
|---|---|
| P | 行情列表 |
| W | 固定 K线浮窗 |
| H | 编辑器背景开关 |

所有图表窗口共享当前选中的交易对和周期。自选、轮播和状态栏名单分别管理。只缓存会话内行情，重启后重新加载；配置和名单持久化。API 获取的是币安现货价格，24h 指滚动24小时，USDT 不标成美元。

在行情列表中右键交易对可创建或删除提醒。跌幅阈值使用负数，例如 `-5`；提醒规则持久化在本地配置中，不会发送到币安。

自选导出格式为 `symbol<TAB>group<TAB>note`。导入同时兼容每行一个交易对以及逗号分隔格式；旧版本自选会自动显示在“默认”分组。

行情窗口中的“模拟交易”按钮会打开本地模拟账户。初始资金、手续费和市价滑点可在 Quiet Crypto 设置中调整；调整初始资金后，需要在模拟交易窗口中重置账户才会生效。

## 构建

需要 JDK 21。PowerShell 中执行：

```powershell
./gradlew.bat test buildPlugin
./gradlew.bat runIde
```

目标平台 IDEA 2025.1.3。产物位于 `build/distributions/`。原 `kplugin` 项目不参与编译或运行。

如果本机 Windows/JBR 构建出现 `Unable to establish loopback connection`，可执行以下本机兼容构建。该选项仅为构建进程关闭有问题的 Unix-domain sockets，不会写入安装包或更改系统配置：

```powershell
./scripts/build.ps1 -JavaHome 'C:/Users/Administrator/.jdks/jbr-21.0.11' -WindowsSocketWorkaround
```

测试包含数据解析、精度以及 IDEA 服务与 Swing 界面渲染；测试预览输出到 `build/ui-previews/`。

## 数据接口

使用 `https://data-api.binance.vision` 的 `exchangeInfo`、`ticker/24hr`、`klines` 和 `ping`。交易对目录每小时按需更新，行情批量查询；网络请求在后台执行，结果在 UI 线程交付。

K线边界采用币安默认 UTC，图中时间标签按本机时区显示。

当前版本采用 REST + WebSocket：REST 加载交易对、历史 K线并定期校准，WebSocket 推送实时 ticker 和当前 K线。范围仍为现货公共行情，不包含账户资产和交易。
