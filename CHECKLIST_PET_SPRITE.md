# InkLink-Pet｜PNG 素材迭代 全流程检查清单

> 使用规则：每完成一个阶段对照打勾；全部勾完 + 复盘完毕，才允许 Git 推送 + CI 构建。
> 分工：AI=引擎/脚本/素材产出与合规门禁；用户=看 `out/_preview/*.png` 判观感风格。
> 铁律：Android 运行时禁止 offset 补丁修素材；素材问题在素材流水线解决；业务层零改动（仅 PetCatalog 静态追加）。

## 阶段1：design.md 文档附录落地

- [x] 画布契约：96×96 固定、透明底、禁止裁剪、仅移动图层
- [x] 锚点契约：统一锚点 (48,48)，所有物种视觉重心压十字；多帧身体像素对齐，仅五官变化
- [x] 像素契约：邻近缩放；PNG-32 RGBA；Alpha 仅 0/255
- [x] 命名契约：`物种code_状态.png` 全小写下划线；状态集合 idle/hungry/happy/sleep/idle_a/idle_b/blink
- [x] 三级回退链：精确PNG → idle-PNG+程序化头顶角标(sad/annoy/sick) → 完整程序化
- [x] 生长阶段：EGG/CUB/JUVENILE/ADOLESCENT 全程序化；ADULT 启用 PNG
- [x] 枚举说明：VECTOR_OLD / PIXEL_RETRO / PIXEL_PNG（内置自动回退）
- [x] CI 校验规则：96×96、中心 5×5 不透明、**身体(最大连通域)质心**相对 idle 偏移 ≤2px（道具不参与）
- [x] 素材流水线：AI草稿 → PIL预处理 → Aseprite 终检导出
- [x] 铁律与回退链写入
- [x] 通读自查：无矛盾、数值无误（附录 A.5/A.6 已按实现回写：身体=最大连通域、物种统一比例、后缀最长匹配）

## 阶段2：引擎侧代码（不依赖素材，空映射可安全合入）

- [x] 枚举新增 PIXEL_PNG；旧值兼容
- [x] PetSpriteResource 数据类（7 可空字段）
- [x] PetSpriteResMap：14 物种全 null 初始映射
- [x] 加载工具：inScaled=false + debug 断言 96×96 + LruCache 按需解码
- [x] 绘制参数 isFilterBitmap/isDither=false
- [x] 整数倍缩放 + 余数居中绘制
- [x] PIXEL_PNG 渲染分支 + 三级回退链（角标复用程序化符号）
- [x] 呼吸 idle_a/idle_b + blink 动画驱动，缺帧回退静态 idle
- [x] 两处设置 UI 三档（家长抽屉 + 受控端👾外观）
- [x] JVM CI 素材校验单测骨架
- [x] 局部复盘：无 offset 补丁 / 判空完备 / 旧分支未破坏 / 存档兼容

## 阶段3：业务&渲染层补充（新增4物种）

- [x] PetCatalog 追加 frog70 / pig100 / owl150 / snake190
- [x] VECTOR_OLD：PixelSpecies 表追加 4 物种（插槽组合 + 必要新插槽）
- [x] VECTOR_OLD：蛇无四肢标志 noLimbs（渲染层字段）
- [x] PIXEL_RETRO 物种区分度：复用 def.palette 换体色（见报告：该模式原不分物种）
- [x] bellyVisible 等物种集合补齐
- [x] 复盘：仅静态 catalog + 渲染代码，业务/状态机/DB 零改动

## 阶段4：素材流水线（引擎合入后并行）

- [x] 素材源改道（原计划文生图，实测 `insufficient balance` 额度不可靠，不能当关键路径）：`sprite_author.py` 解析 `PixelSpecies.kt` LUT 程序化出图，风格与三档渲染同源、锚点由构造保证；外部草稿保留 `--adapt` 兜底 + 品红底要求
- [x] 草稿 59 张（14物种×4 核心 + 狗/猫/兔 blink）；`idle_a/idle_b` 由流水线派生，共 65 张成品。画师侧七条硬门禁（idle 单连通域 / 道具净空2格 / 道具安全框 / 五官 y 由头心推导 / 真外描边 / 五官固定 BLACK / 状态五官不得被特征分支互斥吃掉）逐条抓到真 bug：owl 耳羽簇悬空、penguin 扇尾悬空、hedgehog 道具与身体合并顶偏锚点 4px、snake 五官落在盘身、sleep 闭眼被内描边吃掉、`pal["line"]` 与轮廓同色导致闭眼隐形、pig/penguin/frog 状态表情被特征分支短路；另备 `PROP_LADDER` 道具降级阶梯作安全网（本轮 0 触发）
- [x] PIL 流水线（默认无损直通，scale 恒 1.0）：抠背景 → 身体整数平移回中 + 道具分层原位贴回 → 帧间零漂移硬校验 → 呼吸帧派生(仅猫狗兔) → alpha二值化 → 起手清 out/ + 收尾断言产出集合==闭集 → 半成品PNG + 联排预览 + manifest 台账。实测自适应缩放会把 80px 身体压成 18px 并碎片化，故禁止缩放
- [ ] 用户判观感（唯一无法自动化的环节，模型侧无图像输入能力）：看 `out/_contact_sheet.png` / `review.html`
  - **首轮已评审**：通过项 = 风格统一、帧间零漂移、14 物种可辨；4 项整改 = snake sleep 闭眼弱 / penguin hungry 嘴小 / pig happy 区分度低 / hedgehog sleep 闭眼淡
  - **二轮整改已实施**（前 4 项 + 自查多修 2 项：frog 全程睁眼睡、`outline()` 实为内描边会吃五官；闭眼加厚为 2×2 + 外侧下垂眼角，墨色统一 `BLACK`；pig 补 ∪ 形宽笑、penguin 补下喙张开楔形）→ 格级 dump 五处逐条确认生效；因外描边使剪影外扩 1 格，65 张全量重生成
  - **待用户确认**：`make_review_sheet.py --against <旧目录>` 的左右对照图（`out/_compare_sheet.png`，130 格）；不接受外描边则回退 `outline()` 一行即可（但边缘处闭眼/垂角会重新被吃掉，故不建议）
- [x] 文件入 res/drawable-nodpi/：65 张、命名合规、96×96、Alpha 仅 0/255（变异测试证明门禁会红：塞 `cat_idle_c.png` + 改半透明 → FAILED；恢复后全绿）
- [x] 填充 PetSpriteResMap：14 物种核心 4 帧全齐，cat/dog/rabbit 另填 blink/idleA/idleB；合计 36.9KB，业务层零改动
- [x] 遍历 65 张核对：`PetSpriteAssetContractTest` 由「空目录也通过」收紧为硬断言文件集合==契约闭集，与流水线收尾断言同源；testDebugUnitTest(22 用例) + assembleRelease 双绿（APK 32.77MB）

## 阶段5：单元测试 & CI 校验

- [x] JVM 单测扫描 drawable-nodpi：宽高==96
- [x] 中心 5×5 非透明
- [x] 同物种帧 **身体(最大连通域)质心** 偏移 ≤2px 粗筛（与 PIL 侧同源规则，已用合成素材双侧交叉验证判绿）
- [x] 失败即阻断（断言汇总成一条 AssertionError 信息，逐张列违规）
- [x] 补充：状态后缀**最长匹配**解析（`idle_a/idle_b` 双下划线陷阱，Python 与 Kotlin 两侧同步修正）

## 阶段6：本地真机完整复盘

- [ ] 14 物种切换全部居中，无漂移跳动
- [ ] idle/hungry/happy/sleep 精确 PNG 正常
- [ ] sad/annoy/sick：PNG 本体保留 + 头顶角标叠加，不闪回积木风
- [ ] 狗/猫/兔呼吸眨眼正常；其余物种静态 idle
- [ ] 幼年全程序化；进化 ADULT 切 PNG，动效过渡
- [ ] 三档切换正常；老存档不丢失
- [ ] 人为置 null 验证回退链
- [ ] 新 4 物种 VECTOR/PIXEL_RETRO 正常渲染，不回退 cat
- [ ] 内存：按需解码，无全量预载

## 阶段7：Git 推送 & CI 前最终全局复盘

- [x] 通读 diff：无运行时 offset 补丁 —— 实证 `PetSpriteView.kt:1044-1047` PNG 路径为「居中 + 整数倍放大贴 96×96、`inScaled=false`」，全文件无按物种硬编码位移；`offsetY` 只服务既有跳跃/受击动效，角标叠加走 1049 行「固定头顶槽位（注释已标明非素材 offset 补丁）」
- [x] 可空判空完备 —— 实证 `resId == null → return null`、解码 `runCatching` 包裹、`res == null → drawPixelPet(canvas,w,h); return` 三级回退、`res.sleep ?: res.idle` 空安全链
- [x] 业务零改动，**由 git 数据证明**：4 个整改 commit（0ab1a59/02cffb4/3eeb79b/0e71f2f）的 `.kt` 改动文件数 = 0，仅 `tools/pet_sprite/*.py` + `res/drawable-nodpi/*.png` + 文档
- [x] design A.5 已补门禁 ⑤⑥⑦ 与「核对口径用格级 dump、不信 ASCII 图例」；实现与之一致
- [ ] 清单全勾
- [x] 4 个 commit 各一类：`0ab1a59` 画师工序 / `02cffb4` 审阅工具 / `3eeb79b` 65 张素材资产 / `0e71f2f` 文档
- [x] 已推送 master（origin 同步，工作区干净）
- [x] run 33342631607 = success；Release 链 `v1.2.0-36/37/38` 均含双 APK（`InkLink-Host-release.apk` + `InkLink-Controller-release.apk`）

> 任一阶段失败：退回修复，重新过清单，禁止带病推送。
