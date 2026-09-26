# InkLink-Pet 游戏化改造 Tasklist（阶段八 / 九 / 十）

> 基准：`design.md` 第九章 V1.1 终稿。7 项裁决全部锁定。每阶段推送触发 GitHub Actions 构建验证。
> 优先级：P0 内核 → P1 表现 → P2 社交闭环。依赖自上而下。

## 阶段八 · 数值 & AI 引擎内核（P0）
依赖：现有 PetStateManager / PetAiEngine 底座

- [x] 8.1 `PetPayloads.kt` 扩字段：`PetItem.playfulness/affection/finalForm/sceneId/energyPauseUntilTs/moodPauseUntilTs`；`PetBag.itemStock/unlockedDecorations`（`pillCount` 保留仅迁移读取）。Gson 向后兼容。
- [x] 8.2 双时钟速率控制器 `PetClock`：前台全速 / 后台·灭屏 ÷20 + 地板30 / 睡觉同规则；`App.visibility` 判定活跃态。
- [x] 8.3 `recalculateState()` 重写：按双时钟结算 hunger/happiness/clean/energy；health 侵蚀(hunger<20||clean<20 -1/30s) + 睡眠恢复(+1/60s)；`buff` 暂停衰减(energyPauseUntilTs/moodPauseUntilTs)生效时该属性不衰减；`health<=0→isAlive=false`。
- [x] 8.4 道具库存化：`itemStock` + 7 道具消耗 API（feed→food_normal/premium、play→toy、learn→book、clean→shower_gel、sleep→sleep_potion、heal→potion_heal），库存不足返回拒绝。
- [x] 8.5 性格系统：`playfulness/affection` 增减（play↑、抚摸↑、冷落↓，参考 GooseDroid `PetPersonality`），随快照同步。
- [x] 8.6 `PetAiEngine` 重写为 Selector/Sequence 行为树（参考 GooseDroid `BehaviorTree`）：IDLE 8-20s 自主姿态；低数值抱怨气泡台词；受惊抑制。
- [x] 8.7 触摸三手势：单击转头看点击处+回弹 / 长按抚摸(亲密↑心情↑) / 快速连点(≥8/2s)烦躁(心情↓，首次仅警告)。
- [x] 8.8 Room 持久化：`room-*` + KSP 插件；`PetEntity`/`EventLogEntity`/DAO/DB；`allowMainThreadQueries`（玩具 App，配 8.10 节流规避 ANR）；首启从 `pet_bag.json` 导入，`pillCount→itemStock["potion_heal"]`。
- [x] 8.9 事件日志：`EventLogEntity(ts, desc, type)` + 写入 API + 📜环形裁剪。
- [x] 8.10 工程纪律：`lastPersistTs` 判定 **10-30s 节流写库**；事件日志 **上限 500 条**自动淘汰。
- [x] 8.11 单元测试：双时钟衰减边界、health 侵蚀/睡眠恢复、buff 暂停、道具消耗、性格增减、日志环形裁剪（Robolectric 或纯 JVM 时钟注入）。

## 阶段九 · 部件像素渲染 + 物种 + 游戏化 UI（P1）
依赖：阶段八

- [x] 9.1 `PetSpriteView` 重构为部件 Rig 引擎：body/head/limbs/eyes/mouth 独立 transform，程序化像素(96画布整数放大 NEAREST)；姿态 LERP + Easings + squash-stretch + blink 计时；可打断切动作。
- [x] 9.2 LUT 换色机制 + 三件套插槽(头饰/背饰/尾饰)：`PetSpecies` 10 枚举，每种一套 贴片参数+调色表。
- [x] 9.3 生命周期 5 段 + 成年 4 形态(`finalForm`)装饰层切换(无/头巾/眼镜/压暗)。
- [x] 9.4 AI 行为树对接部件姿态：idle/blink/张望/伸懒腰/打哈欠/蹦跳/翻书/甩水/受惊/烦躁/开心 全部姿态化；状态符号(饿/脏头顶图标)。
- [x] 9.5 像素 UI：分段胶囊进度条(低值红闪) / 自定义像素气泡弹窗全面替换原生 Toast / HUD 文案游戏化 / 像素场景背景层(卧室·客厅·窗台，等级解锁)。
- [x] 9.6 随机事件系统：正向捡道具/负向变脏生病，写事件日志 + 气泡/TTS 通知。
- [x] 9.7 里程碑 + 每日目标：孵化/成年/解锁背景/集齐形态 → 奖励；每日喂食2·玩耍1·清洁1 → 金币；🏆页。
- [x] 9.8 背包：卡片长按改名弹窗；道具库存展示；场景切换入口；商店 10 物种预览 + 新建宠物选择全物种。
- [x] 9.9 8-bit 音效接入 `SoundEffectManager`（进食/玩耍/翻书/流水/呼噜/呻吟/金币/礼花/礼物）。
- [x] 9.10 主控端 `PetDetailActivity`：展示性格/形态/事件摘要；远程赠送按钮组（道具/装饰/buff）。

## 阶段十 · 社交赠送闭环 + 联调（P2）
依赖：阶段九

- [x] 10.1 `PetRemoteGiftPayload` 加 `giftType`(item/deco/buff)，44 分支处理：入库/解锁装饰/即时 buff+暂停计时；补 ACK 幂等回执。
- [x] 10.2 受控端接收流程：`receive_gift` 部件动画 + 音效 + 像素气泡；buff 走 8.3 暂停逻辑。
- [x] 10.3 串门拜访双向动画（访客宠物 overlay 碰头）+ 逗弄透传（43 `event=pet_tease`）。
- [x] 10.4 好友禁赠校验复核（FriendPolicy 已排除 44，补 UI 侧不可见）。
- [x] 10.5 金币经济再平衡（**用户裁定修订：保留 3 款本地小游戏**，为其补齐交互动画——猜拳弹簧定格/翻牌 3D+配对庆祝/转盘落针缓动；来源扩充 每日目标/里程碑/随机事件/赠送）
- [ ] 10.6 双通路联调：局域网 + Ably 公网 完整赠送/社交/状态同步。（**协议层自动化代理已完成**：`PetProtocolRoundTripTest` 8 用例锁定 LAN/Ably 共用同一 `MessageCodec.encodeText` 文本帧——44 giftType×4 / 43 subType×4 / ACK petSnapshot / 旧端缺省字段兼容 / giftType 鸭子类型分流，全绿即数据面等价；剩余为双真机端到端点验：赠礼到账·串门碰头·buff 生效·ACK 回显，需人工操作）
- [x] 10.7 边界测试：离屏减速·地板(衰减测×3)、虚弱沉睡(睡眠冻结+toggleSleep拦截+heal复活)、buff 超时(applyBuff+暂停豁免+过期清零恢复衰减)、库存不足(feed拒绝)、日志 500 溢出(环形裁剪)、节流写库(persist 窗口标脏+flush 可恢复) —— PetDecayEngineTest 12 例 + PetGamifyKernelTest 10 例全覆盖。

## 后置（不阻塞）
- [ ] P.1 Aseprite PNG 部件图集 → manifest 插槽替换程序化渲染（业务零改）。
- [ ] P.2 Rive 评估接入（依赖已在 Gradle）。
