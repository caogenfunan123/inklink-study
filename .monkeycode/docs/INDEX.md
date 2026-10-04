# InkLink 文档

InkLink 是双端安卓项目：孩子端「电子宠物 + 幼小衔接学习乐园」+ 家长端远程管控与关怀。本文档面向接手开发的 AI 与开发者，目标是读文档即可定位代码，避免全项目逐文件通读。

**快速链接**: [架构](./ARCHITECTURE.md) | [接口协议](./INTERFACES.md) | [开发者指南](./DEVELOPER_GUIDE.md) | [仓库关联总览](../../../REPOSITORIES.md)

---

## 核心文档

### [架构](./ARCHITECTURE.md)
系统设计、技术栈、三大业务子系统（管控安全/宠物游戏化/学习乐园）、数据持久化、关键设计决策、里程碑进度。从这里开始了解系统如何运作。

### [接口](./INTERFACES.md)
52+99 码位协议全表、InkMessage 载荷、语音二进制帧格式、三传输参数、UdpDiscovery、外部 Web API、素材契约、工具脚本接口。集成或扩展协议的参考。

### [开发者指南](./DEVELOPER_GUIDE.md)
环境搭建、构建测试、CI、常见任务（新增码位/学习模块/物种/音效）、架构红线、已知坑。贡献者必读。

### [全面复盘报告（2026-10-04）](./history-sync/global-review-2026-10-04.md)
历史补传四批交付后的全量复审台账：Blocker/Major/Minor 分级修复记录、误报澄清、遗留项、AI 接手必读六条。

---

## 模块

| 模块 | 描述 | README |
|------|------|--------|
| `common/` | 协议/传输/音频/定位/工具公共库 | [README](./模块/common.md) |
| `app-host/` | 孩子端：宠物+学习+管控受控侧 | [README](./模块/app-host.md) |
| `app-controller/` | 家长端：设备管理/地图/宠物关怀 | [README](./模块/app-controller.md) |
| `tools/` | 五条资产加工管线 | [README](./模块/tools-资产管线.md) |

---

## 核心概念

理解这些领域概念有助于导航代码库：

| 概念 | 描述 |
|------|------|
| [宠物系统](./专有概念/宠物系统.md) | PetBag/PetItem 状态机、双时钟衰减、生命闭环、经济系统 |
| [学习乐园](./专有概念/学习乐园.md) | 五岛课程、艾宾浩斯复习、护眼防沉迷、学习数据表 |
| [传输三模式](./专有概念/传输三模式.md) | LOCAL/RELAY/ABLY 抽象、pending 重放、好友频道白名单 |

---

## 入门路径

### 项目新人？

1. **[架构](./ARCHITECTURE.md)** - 了解全局与三大子系统
2. **[核心概念](#核心概念)** - 学习领域术语
3. **[开发者指南](./DEVELOPER_GUIDE.md)** - 搭建环境、跑通构建
4. **[已知坑](./DEVELOPER_GUIDE.md#8-已知坑与待办接手开发先看)** - 接手前先看

### 继续开发 M3（家长作业闭环）？

1. **[DEVELOPER_GUIDE 已知坑](./DEVELOPER_GUIDE.md#8-已知坑与待办接手开发先看)** 的 M3 开发入口
2. **[app-controller 模块](./模块/app-controller.md)** 的 M3 开发入口
3. **[接口文档 47-49 码位](./INTERFACES.md)** - HomeworkAssignPayload/HomeworkAckPayload 已就绪

### 扩展协议或素材？

1. **[新增码位登记规则](./INTERFACES.md#10-新增码位登记规则)**
2. **[素材契约](./INTERFACES.md#8-资产契约有单测钉死改素材必跑)**
3. **[工具管线](./模块/tools-资产管线.md)**

---

## 快速参考

### 命令

```bash
./gradlew test                     # 全量单测
./gradlew assembleDebug            # 调试构建
./gradlew :app-host:testDebugUnitTest  --console=plain   # CI 门禁同款
python tools/learning/build_learning_assets.py           # 重生成学习资产
python tools/pet_audio/generate_sfx.py --deploy          # 重生成音效并落库
```

### 重要文件

| 文件 | 目的 |
|------|------|
| `docs/IMPLEMENTATION_PLAN.md` | 实现规则与功能清单，进度唯一口径 |
| `gradle/libs.versions.toml` | 全部依赖版本单点管理 |
| `local.properties`（不入库） | sdk.dir 与三个密钥 |
| `.github/workflows/android.yml` | CI：单测门禁 + Release |
| `CHECKLIST_PET_AUDIO.md` / `CHECKLIST_PET_SPRITE.md` | 素材两轮迭代的验收清单与待办 |

### 当前进度速览（2026-09-26 口径，2026-10-04 全面复审后）

- 已交付：M1（资产管线+识字屋+协议47+简报）、M2（拼音/口算/古诗/复习谷/错题本/护眼/舒尔特，全原生）、历史轨迹补传四批（设备管理/围栏可视化/轨迹回放/离线历史拉取）
- 2026-10-04 全面复审：3 Blocker + 16 Major 已修，12 Minor 遗留（见[复盘报告](./history-sync/global-review-2026-10-04.md)）
- 未开工：M3（作业布置 48/49 UI、学习报告页、习惯打卡、英语字母岛）、笔顺描红、P2 项
