# 批次 D 复盘：设备详情页（单设备聚合视图）

日期：2026-10-04　提交：批次 D（feat 链 4/4）

## 交付内容

1. **DeviceDetailActivity**（app-controller/ui/）：单设备聚合页
   - 头部：昵称（fallback deviceId）、设备 ID、在线态 + 是否当前目标、最新位置（延迟秒数）
   - 围栏：该设备已存档围栏列表（名称+半径，来自 DeviceEntity.fences）
   - 操作：设为当前设备（selectDevice）/ 远程响铃-停止（sendRing/sendStopRing 按 isRingingByDevice 态切换）/ 编辑围栏 / 跳地图回放（EXTRA_FOCUS_DEVICE 聚焦本设备）
   - 拉取：1/3/7 天历史补传对话框 + 进度条（received/total/inserted）+ 取消
   - 监听：onResume 注册 ControllerState.Listener、onPause 移除（notifyChanged 走 mainHandler.post，主线程回调安全）
2. **FenceEditActivity 改造**：支持 `EXTRA_DEVICE_ID` 显式设备（详情页穿透下发，不依赖全局选中态）；初始地图中心与预加载围栏改读本页 target；submit 改 dest = target（null 时提示）
3. **入口**：DeviceManageActivity 长按菜单新增「查看详情」（index 3）；DeviceDetailActivity 注册进 Manifest
4. **布局/文案**：activity_device_detail.xml（ScrollView 分组按钮式布局，与既有页面风格一致）；strings 新增 14 条

## 自检发现的 bug（已修复）

1. **完成态结果不可见**：进度面板按 received<total 显隐的设计下，任务完成/失败后面板隐藏，用户刚退出对话框就看不到合并点数 → 改为完成/失败后面板保留结果文本，进度条隐藏，按钮文案切「关闭」，用户点关闭才收起
2. **关闭按钮语义混淆**：取消与关闭共用一个按钮 → 增加 close 文案，按任务态切换；点击时进行中才 cancelHistory，完成态仅关面板
3. **详情页→围栏页目标丢失**：FenceEditActivity 原读全局 selectedDeviceId，从详情页进入时目标设备未必是全局选中 → 引入本页 target 字段，Intent 显式优先；onCreate 中先于 submit 构造，无时序问题

## 设计取舍

- 进度按 `historyProgress.deviceId == 本页设备` 过滤：多设备依次拉取时，其他设备进度不干扰本页
- 入口位置保留 DeviceManageActivity 的「拉取历史轨迹」直拉（C 批临时入口，与详情页拉取互补：快速路径 vs 带进度的完整体验）
- 围栏入口同样支持双路径：详情页（显式设备）/ 地图页（全局选中，向后兼容）

## 验证

- 全部引用字段/方法/字符串逐一核对存在（ControllerState.HistoryProgress/gpsByDevice/lastSeenByDevice/isRingingByDevice/isOnline、DeviceEntity.nickname/fences、Application.requestHistory/cancelHistory/sendRing/sendStopRing/selectDevice、14 条 strings）
- 本地无 JDK/SDK，编译验证走 CI
