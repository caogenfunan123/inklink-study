# tools 资产管线

五个独立的 Python 工具目录，负责把参考素材加工成 APK 内置资产。产物入库、脚本入库、中间产物（out/、assets_draft/、preview/raw）均被 .gitignore。设计原则：确定性可复现（固定随机种子）、自带规范自检、零版权风险。

## 结构

```
tools/
├── learning/     # 学习内容 JSON 生成（字库/拼音/古诗/英语）
├── pet_sprite/   # 宠物精灵图流水线（程序化出图→抠底→配准→成品）
├── pet_blob/     # 云朵宠动作帧 + 250x250 场景底图
├── pet_audio/    # 10 条 chiptune 音效合成
└── pet_arcade/   # 街机 240 帧白底图批量转换
```

## 各管线

### learning/build_learning_assets.py

- **输入**：同级参考仓库 `ref-hanzi-study`（识字/古诗/笔顺）、`ref-joye-preschool`（拼音/英语）、`ref-studyword`（3000 字分级），路径可用环境变量 REF_HANZI/REF_JOYE/REF_STUDYWORD 覆盖
- **加工**：TS/JS 字面量手工转 JSON（裸键补引号/去尾逗号/括号配平）；分级规则 StudyWord 频率序 [0,120)→L1、[120,500)→L2、[500,1300)→L3、其余 L4；古诗按正文长度分 1/2/3 级；逐项断言校验（声母 23/韵母 24 等），失败 exit 1
- **输出**：`app-host/src/main/assets/learning/` 下 hanzi/pinyin/pinyin_questions/poems/english_words/english_sentences 六个 JSON
- **注意**：会重新生成已退役的 `lib/strokes.json` 与 `lib/hanzi-writer.min.js`（WebView 时代产物），重跑后手动剔除 lib/

### pet_sprite（见 README.md）

- `sprite_author.py`：解析 PixelSpecies.kt 矢量表 LUT 程序化出 59 张 96x96 草稿
- `sprite_pipeline.py`：七步流水线——品红色键抠底 → alpha 最大连通域定身体 → 统一比例（目标 72px，NEAREST）→ 与 idle 掩膜互相关配准（IOU<0.35 放弃）→ 呼吸帧派生（仅 cat/dog/rabbit）→ alpha 二值化 → 自检（与 CI 同规则）
- `sprite_ascii.py`：降采样 24x24 ASCII 字符画供无视觉环境核对造型
- `make_review_sheet.py`：生成人工审阅 contact_sheet
- **产物**：out/ 成品 + _manifest.json 台账；终检后人工拷入 `res/drawable-nodpi/`

### pet_blob

- `make_scenes.py`：250x250 LCD 像素风场景底图（bedroom/living/window，地面线 y=226）
- `make_actions.py` / `make_actions250.py`：96x96 与 250 坐标系两版动作帧合成（底图+宠物帧+道具叠层）
- `deploy_assets.py`：落库 19 张 cloud_*.png + 3 张 scene_*.png 到 drawable-nodpi（alpha ≥128 二值化）
- preview/ 目录 60+ 张已入库供审查对照

### pet_audio/generate_sfx.py

- numpy 加法谐波限带合成，固定种子 20260810，零版权
- 硬性规范自检：16bit PCM / 44100Hz / 单声道 / 0.5-1.5s / 峰值 -1dBFS / 无直流 / 首静音 ≤5ms / 尾静音 ≤30ms
- 10 条配方：alert_call/alert_notify/alert_warn/sound_ack（系统）+ pet_feed/pet_touch/pet_happy/pet_hungry/pet_sleep/pet_wakeup（宠物）
- 用法：`generate_sfx.py [--deploy] [--check DIR]`；--deploy 拷入 res/raw；--check 供 CI 同源校验

### pet_arcade/convert_arcade.py

- 输入白底 AI 生成图；8-邻域 flood-fill 从四边抠底（HSV 亮度+饱和度判据防误抠）→ 保留面积 ≥200px 连通域 → 输出 256x256 RGBA（alpha 仅 0/255）
- 用法：`convert_arcade.py <src_dir> <out_dir>`，产物进 `assets/pixel_arcade/<物种>/`

## 规范

- 一切成品必须能被对应契约测试通过（PetSpriteAssetContractTest/PetArcadeAssetContractTest/RawSoundContractTest），契约明细见 INTERFACES.md 第 8 节
- 新增素材先改脚本配方（可复现），人工只做终检拷贝；手绘/外部图必须先过抠底与归一化流水线
- 中间产物不入库；_manifest.json 台账与 preview 审阅图可入库
- Python 依赖：numpy（pet_audio）、Pillow（pet_blob/pet_arcade/pet_sprite）
