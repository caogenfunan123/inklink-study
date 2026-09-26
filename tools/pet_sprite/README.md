# 宠物 PNG 素材投料说明（阶段4 交接面）

引擎侧已经支持 `PIXEL_PNG`（三级回退），**素材没投也不会崩**，只会整体回退到程序化像素。
所以这一步是纯增量：你投多少，我接多少。

## 1. 目录与命名

```
tools/pet_sprite/assets_draft/<物种code>_<状态>.png
```

- 物种 code（14 个）：`cat dog rabbit penguin hamster panda fox dragon sheep hedgehog frog pig owl snake`
- 状态闭集：`idle hungry happy sleep blink idle_a idle_b`（全小写下划线，**不要**多余前后缀、不要大写）
- 应投 **59 张**：14 物种 × 4 张核心帧（idle/hungry/happy/sleep） + 猫/狗/兔各 1 张 `blink`
- `idle_a`/`idle_b`（呼吸双帧）**不用投**，脚本会从 `idle` 确定性派生；你要是手绘了更好的呼吸帧，投进来即可覆盖派生结果

点货（只查缺漏，不做加工）：

```bash
python3 tools/pet_sprite/sprite_pipeline.py --expect          # 全物种
python3 tools/pet_sprite/sprite_pipeline.py --expect --species cat
```

## 2. 画布与背景要求

| 项 | 要求 | 说明 |
| --- | --- | --- |
| 尺寸 | 任意（推荐 Aseprite 直接 96×96） | 脚本会整物种统一比例缩放，不必自己凑 96 |
| 背景 | **透明底**最佳；或纯色 `#FF00FF` 品红 | 品红会被色键抠掉；其他背景走"四边洪水填充"回退 |
| 地面/投影 | 不要 | 投影会被算进身体，锚点必歪 |
| 道具（碗/球/音符） | 要小，且**不与身体相连** | 连上了就会被并进"身体"参与锚点，整帧偏移；脚本会告警但仍需人工拆 |
| 姿态基线 | 同一物种各帧体型尽量一致 | 脚本按"最大身体"统一缩放，某一帧画太大只会被缩下去 |

锚点契约（附录A.1）：最终成品身体质心必须压在 **96×96 的 (48,48)**。你在 Aseprite 里已经摆正的话，脚本位移为 0；不确定就别管，交给流水线预对齐 + 人工终检两轮。

## 3. 各状态姿态口径

| 状态 | 姿态 | 用途 |
| --- | --- | --- |
| `idle` | 站立/坐姿，正面，闭嘴，眼睛睁开 | 默认帧 + 呼吸派生基准 + 缺帧兜底 |
| `hungry` | 抬头乞食/蔫坐，可带一只小空碗 | HUNGRY 状态 |
| `happy` | 跳跃/眯眼笑，可有 1-2 个小音符 | HAPPY、玩耍、升级庆祝 |
| `sleep` | 蜷睡闭眼，一个小气泡（别写字母） | 睡眠 |
| `blink` | 与 `idle` **完全同姿势**，只把眼睛画成一条横线 | 眨眼帧（仅猫/狗/兔） |

`SAD / ANNOY / SICK` 三态**不要投素材**：引擎按设计走"二级回退"——用 `idle` 本体 + 程序化头顶角标（乌云/怒气十字/汗滴）。

## 4. 投料后我这边做什么

```bash
python3 tools/pet_sprite/sprite_pipeline.py                 # 全量
python3 tools/pet_sprite/sprite_pipeline.py --species cat   # 单物种试跑
```

产出：

- `tools/pet_sprite/out/<code>_<state>.png` —— 96×96 透明底 PNG-32，alpha 已二值化
- `tools/pet_sprite/out/_preview/<code>.png` —— 该物种全帧联排预览（4× 放大、暗品红底、锚点黄十字），给你做 Aseprite 终检对照
- `tools/pet_sprite/out/_manifest.json` —— 逐张台账：抠图方式、缩放比、配准位移、`anchor_error`、身体质心
- 控制台会打印**需要回 Aseprite 人工修的清单**（锚点偏差 >0.6px、姿态差异过大放弃配准、半透明杂点等）

然后：你按预览做语义终检（尾/翅/长耳整体挪、清杂点）→ 成品拷进 `app-host/src/main/res/drawable-nodpi/` → 我回填 `PetSpriteResMap` → CI 的 `PetSpriteAssetContractTest` 做最终机械把关。

## 5. 为什么中间产物不入库

`assets_draft/` 与 `out/` 已 `.gitignore`：草稿体积大且会被反复重跑，仓库只收**终检后**的 `drawable-nodpi/` 成品。
