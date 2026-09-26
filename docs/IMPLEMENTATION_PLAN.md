# InkLink 幼小衔接学习版 · 实现规则与功能清单

> **进度速览(2026-09-26)**:M1(资产管线+识字屋+协议+家长简报)✅ 已交付;M2(拼音森林/口算商店/古诗亭/复习谷/错题本/护眼防沉迷/舒尔特方格,三岛已全原生)✅ 已交付;M3(家长作业布置/学习报告页/习惯打卡)⬜ 未开工;英语字母岛 ⬜ 未开工。
> **架构决策**:识字/拼音/古诗为纯原生 Activity(WebView 链路已退役);笔顺描红暂缺(hanzi-writer 后置)。
> **资产再生成**:`python tools/learning/build_learning_assets.py`,依赖同级目录的 ref-hanzi-study / ref-joye-preschool / ref-studyword 三个开源参考仓库。

> 基底仓库:`inklink-android`(monorepo,含完整宠物系统)
> 参考资产:`ref-hanzi-study` / `ref-joye-preschool` / `ref-studyword` / `ref-psmath` / `ref-ziyin-island`

---

## 0. 结论速览

**现有资产对幼小衔接的覆盖度约 65%**,语文(识字/笔顺/古诗)、数学计算、激励系统、安全底座可直接复用;**缺口集中在:拼音系统教学、数学非计算模块(图形/时间/钱币/量)、复习调度、护眼防沉迷、学习报告、跟读评测、习惯打卡**。全部缺口列在 P0/P1 清单中,预计 M1-M4 四个里程碑补齐。

> **硬约束(2026-09-26 确定):零服务器、全资产离线内置**。不部署任何自有后端;学习内容/字体/音频/素材全部打进 APK;运行期依赖审计见第 10 节。

| 幼小衔接能力项 | 现有资产 | 覆盖判定 |
|---|---|---|
| 识字(目标 300-800 字) | hanzi-study 1303 字(带词组/例句/拼音)+ StudyWord 3000 字分级 | ✅ 超额,需分级重组 |
| 笔顺笔画 | hanzi-study `dataWriter.js` 全套 SVG 笔顺数据 + hanzi-writer | ✅ 直接用 |
| 古诗 | hanzi-study 114 首 + 朗读音频 | ✅ 直接用,需精选分级 |
| 拼音(23 声母/24 韵母/16 整体认读) | joye `syllables.ts` 474 条 + `questions.ts` 307 道配对题 | ⚠️ 有题库无"教",需补学习卡+发音方案 |
| 数学-计算(10/20 以内加减) | hanzi-study math 模块 + psmath 出题逻辑 | ✅ 逻辑参考,需原生重写生成器 |
| 数学-非计算(分与合/比大小/相邻数/数列/图形/钟表/钱币/量比较) | joye 题型代码(部分) | ❌ 主要缺口,程序化生成可补 70% |
| 复习与错题 | hanzi-study 有错题本雏形 | ❌ 缺艾宾浩斯调度,需新建 |
| 专注力(坐住 15 分钟) | 宠物记忆翻牌小游戏 | ⚠️ 缺舒尔特方格等专项 |
| 跟读评测 | ziyin-island `PronunciationEvaluator` 架构参考 | ❌ P1,可先只做"跟读不评分" |
| 习惯与生活准备 | monorepo 远程任务 + 每日目标系统 | ⚠️ 改造即可 |
| 激励系统 | 宠物全套(金币/经验/升级/商店) | ✅ 核心优势,零新建 |
| 家长监管 | 主控端全套 + PIN + 围栏 + 聊天 | ✅ 零新建,加学习报告 |
| 护眼防沉迷 | 无 | ❌ 全新,必须有(P0) |
| 英语启蒙(可选) | joye **191 词**(18 类,全带音标+中文+例句)+ **109 句型**(带分类配图) | ⚠️ 缺 26 字母卡与自然拼读;数据可转 JSON,发音换本地 TTS |

---

## 1. 定位与使用场景

- **产品一句话**:装在儿童平板/手表上的"电子宠物 + 幼小衔接课程"App,孩子上课赚金币养宠物,家长远程布置作业、看报告、保安全。
- **目标用户**:5-7 岁(大班~一年级上);孩子端离线自学为主,家长端碎片化遥控。
- **设备假设**:孩子端优先 Android 平板(横竖屏),兼容手机;手表屏(圆屏)保留宠物页可看,学习模块 P2 再适配。主控端为家长手机。
- **设计底线**:全程语音引导(孩子不识字也能操作)——沿用 ziyin-island 原则,复用 monorepo `LocalTtsManager` + `PetTtsGate`。

---

## 2. 内容分级标准(贯穿所有模块)

| 级别 | 定位 | 识字 | 数学 | 拼音 |
|---|---|---|---|---|
| L1 启蒙 | 中班~大班上 | 120 高频字 | 5 以内加减、数数、形状 | 单韵母 6 个 |
| L2 衔接 | 大班下(核心档) | 500 字(幼小衔接高频) | 10 以内加减、分与合、比大小 | 声母+复韵母+两拼 |
| L3 准一年级 | 大班暑假 | 800 字 | 20 以内加减(进退位)、数列 | 三拼+整体认读+标调 |
| L4 拓展 | 一年级上 | 1200+ | 钱币/钟表/应用题 | 拼读句子 |

规则:所有岛默认按 L2 开放,L1/L3/L4 由家长在主控端切换;字表分级数据一次性整理进 `assets/learning/levels.json`。

---

## 3. 功能清单(P0 必做 / P1 应做 / P2 可选)

### P0-1 识字屋(识字主模块)
- **数据**:合并 hanzi-study 1303 字(字段 w=字/p=词组/s=例句/y=拼音)+ StudyWord 3000 字 → 去重后约 3400 字,按第 2 节规则分级;每字结构 `{char, pinyin, phrase, sentence, level, strokes(ref), audio:TTS}`
- **四种玩法**(每关 5 字,2-4 分钟):
  1. 认读卡:看字+听音+词组例句(TTS 朗读)
  2. 听音选字:播拼音/字音,四选一
  3. 看词选图 / 看图选字(emoji/素材图)
  4. 笔顺描红:hanzi-writer quiz 模式,WebView 内手指描画;低端机降级为"看笔顺动画→按顺序点笔画"
- **实现方式**:WebView 加载 hanzi-study 改造版(见 5.3),题目数据由原生注入 JSON
- **验收**:L2 关卡全部可玩;通关后 `learn_progress` 正确落库;金币正确入账;关卡结束宠物页播礼花+TTS 夸奖

### P0-2 口算商店(数学主模块,原生实现)
- **题型生成器**(纯程序化,无内容资产):10 以内加减 / 分与合(5=1+4) / 比大小(><=) / 相邻数 / 数列规律(顺数/倒数/跳数) / 20 以内进退位 / 应用题模板(购物/分配,emoji 呈现)
- 每关 10 题,答对音效+宠物小动作,答错进错题本;限时模式(口算 5 分钟 50 题)L3 开放
- 图形认知题(认形状/数方块)用内置图形素材,P1 补
- **验收**:10 以内加减关正确率统计入库;连续 3 关 90%+ 自动推荐升级 L3

### P0-3 拼音森林
- **数据**:joye `syllables.ts`(474 条,含声母/韵母/整体认读分类与 tips)+ `questions.ts`(307 道声调配对)转 JSON;字母学习卡 23+24+16 全覆盖
- **玩法**:字母卡(点击发音)→ 声调车(四声选择)→ 拼读合成(声母+韵母→音节,两拼/三拼动画)→ 配对练习(看图选拼音/听音选字)
- **发音规则**(关键决策,见 9.3):TTS 读整字代偿(b→播"波")优先;音素级真人音频 P1 录制
- **验收**:474 音节全部可发音;307 配对题可玩;L2 通关可完成两拼拼读

### P0-4 复习谷 + 错题本(所有模块共用)
- 错题自动入册 `{module, itemId, payload, wrongCount, lastWrongTs}`
- **艾宾浩斯调度**:`nextReviewTs = lastReviewTs + [1, 2, 4, 7, 15][stage] 天`;每日学习页顶部出现"今日复习 N 个"卡,复习通过则 stage+1,答错回 stage 0
- 复习关奖励:金币 0.5x、经验 2x(引导复习优先)
- **验收**:隔天打开 App 能看到昨日错字复习卡;全部掌握后卡片消失

### P0-5 护眼与防沉迷(硬规则,家长可调严不可调松)
| 规则 | 默认值 | 实现 |
|---|---|---|
| 单次连续学习 | 20 分钟 → 强制休息页 5 分钟 | `SessionTimer` 前台计时;休息页宠物做护眼操引导(远眺动画);倒计时结束才能继续 |
| 每日总时长 | 40 分钟(家长可 15-90) | `daily_stats.minutes` 累计;到限后宠物打哈欠"要睡觉",学习入口置灰,宠物页/聊天/接听保留 |
| 夜间禁用 | 21:00-6:30 学习模块关闭 | `QuietHours` 检查 |
| 姿势提醒 | P1 | 每次休息页播"坐直小口诀" |
- **验收**:连续玩 20 分钟必触发休息页;改系统时间不能绕过(以 elapsedRealtime 计)

### P0-6 学习进度上报(协议扩展)
- 新增码位(沿用 monorepo 30-46 宠物扩展风格):
  - `47 LEARN_PROGRESS`:孩子→家长,JSON `{module, level, itemsDone, correctRate, minutesToday, coinsToday, weakTop5}`(每关结束+每日汇总双粒度)
  - `48 HOMEWORK_ASSIGN`:家长→孩子,`{taskId, module, type, count, params, deadline}` → 复用 `TaskQueueManager` 队列与 TTS 播报
  - `49 HOMEWORK_ACK`:孩子→家长,`RECEIVED/DONE + score` → 主控端弹"是否奖励金币"(复用 46→44 激励闭环)
- **验收**:孩子通关后 3s 内主控端 Dashboard 设备卡显示最新统计;下发作业孩子端收到并播报

### P1-1 学习报告页(主控端)
- 今日/本周:各岛进度条、识字量曲线、正确率、薄弱字 Top10、学习时长、金币收支
- 实现:接收 47 累积进 Room 表 `learn_report`;纯本地展示,无后端

### P1-2 习惯打卡(生活准备)
- 每日 3 项可选任务(自己整理书包/早睡早起/帮忙做家务),家长主控端勾选下发(走 48),孩子端"今日任务"卡打勾→金币
- 复用宠物每日目标系统(feed≥2/play≥1/clean≥1 → 60 币)的风格,加 `life_task` 类型

### P1-3 专注力训练
- 舒尔特方格:5×5 乱序数字按序点完,计时+历史最佳;每日 1 局奖励
- 宠物记忆翻牌(已有)计入专注力时长

### P1-4 古诗亭升级
- 114 首精选 L1-L3 各 10/20/10 首;新增"背诵填空"模式(挖 2-4 个关键字四选一);跟读模式(录音回放自评,不机器评分)

### P1-5 跟读评测(语音)
- 阶段一:跟读+回放,无评分(原生 `MediaRecorder`,复用 ziyin-island `AudioPracticeManager` 思路)
- 阶段二:接腾讯云 SOE 评测 SDK(ziyin-island 已有 `TencentEvaluationSandbox` 参考实现)——需账号与 Key,列入 9.3 决策;且属在线功能,默认关闭(见 10.1)

### P1-6 英语字母岛(数据现成,成本低,从 P2 提级)
- **已有数据**(joye,ts→JSON 脚本转换):191 个启蒙高频词(动物/食物/颜色/数字/家庭等 18 类,每词带音标+中文释义+例句);109 个场景句型(带 emoji 配图);配套闪卡/看词选义/看义选词/听音选词/句型展示五种页面代码可参考
- **需新补**(程序化生成,零外部资产):26 字母学习卡(大小写+代表词+emoji+TTS 读字母名与单词);字母顺序/大小写配对小游戏
- **本地化改造(铁律相关)**:joye 原版发音走"有道词典在线 TTS"——**必须替换**为本地系统 TTS 英文引擎(`LocalTtsManager` 已有英文兜底路径,首次使用前检测英文引擎可用性,不可用则引导安装本地 TTS 包)
- **仍缺(明确后置)**:自然拼读 phonics(音素音频 TTS 读不出,需真人音频资产)、字母描红(26×2 手写轨迹数据,可后置)→ 归入 P2
- 范围:字母卡+单词闪卡+听音选词进 M3;句型跟读后置

### P2-1 绘本阅读
- 用"已学字表"过滤 hanzi-study 例句扩写成 20 篇微型绘本(手工+AI 辅助);分级展示,生字点击发音
### P2-2 自然拼读与字母描红(英语深化)
- phonics 音素音频(63 个左右,需真人录制资产);26×2 字母书写描红轨迹数据;CVC 拼读游戏(cat/bat/hat)
### P2-3 儿歌磨耳朵
- 需音频资产,先以 TTS 朗读儿歌文本过渡,真音频后置
### P2-4 手表圆屏适配学习模块

### 明确不做
- 直播/社区/UGC(内容安全);拍照搜题;真人死亡(宠物已有纪念册机制,保留);AI 对话(成本与安全,宠物 AI 保持规则行为树)

---

## 4. 内容资产清单与加工规则

| 资产 | 来源 | 加工规则 | 产出 |
|---|---|---|---|
| 字库 ~3400 字 | hanzi-study `dataList.js` + StudyWord `character_sets.json` | 按 char 去重(hanzi-study 优先,字段全);分级标注 L1-L4;笔画数据从 `dataWriter.js` 抽取 | `assets/learning/hanzi.json`(数组)+ `levels.json` |
| 笔顺 SVG | hanzi-study `dataWriter.js`(`window.writerData`) | 逐字抽出,存 `assets/learning/strokes/<char>.json`;抽查 50 字与规范笔顺核对(来源若与规范不符以教育部规范为准,错误的丢弃改用 hanzi-writer CDN 数据打包) | 每字一文件 |
| 拼音库 | joye `syllables.ts`/`vowels.ts`/`initials.ts`/`questions.ts` | TypeScript → JSON 脚本转换(ts-node 一段脚本);保留 category/tips/声调/配图 emoji | `pinyin.json` / `pinyin_questions.json` |
| 古诗 | hanzi-study `poem/data.js` + `audio/` | 解析 data.js(验证过非 title 字段格式,需按实际字段写解析);音频 mp3 直接拷贝;标注难度分级 | `poems.json` + audio 目录 |
| 英语词库 | joye `words.ts` + `SentencesPage.tsx` 内嵌句型数组 | ts→JSON 转换;191 词(音标/释义/例句/分类)+ 109 句型(分类/emoji) | `english_words.json` / `english_sentences.json` |
| 口算/应用题 | 无需资产 | 生成器规则(见 3-P0-2) | 程序生成 |
| 宠物素材 | monorepo 现成(65 帧+240 街机图) | 不动 | — |
| TTS 音素(可选 P1) | 自录或收购 | 63 个声韵母真人音频 ≤30KB/个 | `assets/learning/pinyin_audio/` |

**版权红线**:hanzi-study 为 MIT;StudyWord 仓库无 License(只用其字表,字表本身无著作权);joye 无 License(数据结构参考,数据重建或联系作者);ziyin-island Apache-2.0(可引用代码,保留声明)。

---

## 5. 系统实现规则

### 5.1 仓库与模块
```
inklink-study/                    ← 从 inklink-android monorepo 切出,git 保留历史
├─ app-host/                      ← 孩子端(改造)
│  └─ java/com/inklink/host/
│     ├─ pet/ ...                 ← 宠物系统全部保留,只加 addReward 入口
│     ├─ learning/                ← 新增包
│     │  ├─ LearningManager.kt    ← 进度/复习调度/统计唯一入口(对齐 PetStateManager 单例风格)
│     │  ├─ generator/            ← 口算/数列/应用题生成器(纯函数,可单测)
│     │  ├─ web/                  ← WebView 容器 + JSBridge
│     │  └─ session/              ← SessionTimer/护眼/夜间/QuietHours
│     ├─ data/                    ← Room 加 3 张表(见 5.4)
│     └─ ui/LearningHubActivity.kt / LessonActivity.kt / RestScreenActivity.kt
├─ app-controller/                ← 家长端(小改)
│  ├─ ui/LearnReportActivity.kt / HomeworkActivity.kt
│  └─ Dashboard 设备卡加"学习简报"行 + "布置作业"按钮
├─ common/                        ← MessageType 47/48/49 + LearnPayloads.kt
└─ assets-learning/               ← 见第 4 节(全部 APK 内置,无远程拉取)
   ├─ fonts/                      ← 缝合怪像素字体 + 霞鹜文楷(Typeface.createFromAsset 加载)
   ├─ hanzi.json / levels.json / strokes/<char>.json
   ├─ pinyin.json / pinyin_questions.json / pinyin_audio/(可选)
   ├─ poems.json + poem_audio/
   └─ english_words.json
```

> 原 monorepo 的 `server/`(自建 WS 中继)**删除**,不迁移。

### 5.2 数值口径(与宠物系统对接的唯一入口)
- `PetStateManager.addReward(coin: Int, exp: Int, reason: String)`——所有学习奖励只走这一个方法,保证升级/事件日志/礼花一致触发
- 经验映射:学习关卡 exp 10-35(对齐现有 `learn()` 的 35),复习 2x;宠物等级节奏 level×100 不改
- `learn()` 原按钮行为保留(快捷读书 +35exp),学习乐园通关额外结算

### 5.3 WebView 与 JSBridge(核心集成点)
- 容器:`LessonActivity` 内 `WebView`(禁 JS 弹窗、禁文件访问、只加载 `file:///android_asset/learning/`),设备支持则启用硬件加速
- 内容改造:把 hanzi-study 拆为每岛单页(index-hanzi/index-math/index-pinyin/index-poem),去掉其 math/poem 的独立 shell 重复代码;字体与素材本地化
- **JSBridge API(原生注册 `window.InkBridge`,白名单方法)**:
  | 方法 | 方向 | 实现 |
  |---|---|---|
  | `speak(text, opts)` | JS→原生 | `PetTtsGate` 门控 + `LocalTtsManager` 朗读(**解决 WebView 无 speechSynthesis 的坑**) |
  | `reward(coin, exp, reason)` | JS→原生 | `PetStateManager.addReward`,返回余额 |
  | `reportProgress(json)` | JS→原生 | 写 Room + 发 47 码 |
  | `playSfx(id)` / `haptic(ms)` | JS→原生 | `SoundEffectManager` / Vibrator |
  | `getLessonData(module, level)` | JS→原生(带回调) | 原生出题(口算)或读 assets JSON(识字/拼音),注入题目 |
  | `sessionHeartbeat()` | JS→原生 | SessionTimer 续期,防 JS 页后台空转 |
  | `finishLesson(result)` | JS→原生 | 结算:错题入册、复习调度、休息页倒计时启动 |
- 安全校验:仅 `file://` scheme 允许调用;每次调用校验 referer

### 5.4 数据库(Room,`PetDatabase` 升 v2 加表)
```sql
learn_progress(childId, module, itemId, status, correctCount, wrongCount,
               lastTs, reviewStage, nextReviewTs, level)   -- 主键(module+itemId)
wrong_book(id, module, payloadJson, wrongCount, lastWrongTs, resolvedTs)
daily_stats(date, module, minutes, itemsDone, correctRate, coinsEarned, expEarned)
```
规则:与 `pet_bag` 同一 Room 库;`daily_stats` 按 date 主键 upsert;所有写操作走 `LearningManager` 单线程协程,不学宠物库的 `allowMainThreadQueries`。

### 5.5 保活与生命周期
- 学习计时必须用 `SystemClock.elapsedRealtime()`(防改系统时间);锁屏暂停 SessionTimer(`onPause` 记录)
- 学习页不注册前台服务;现有 `InkForegroundService`(定位/传输)照常后台运行,学习会话结束时统一上报 47

---

## 6. 数值与运营规则(防刷与平衡)

1. **金币经济**:来源=学习关卡(5-15 币/关,正确率 ≥90% 1.5x、<60% 0.5x 且不保底重复刷同一关)+ 复习(0.5x)+ 每日目标(60,已有)+ GPS 寻宝(已有)+ 小游戏(前 8 局,已有)+ 家长奖励(44,已有)。消耗=宠物商店(已有物价 5-280 币)。
2. **每日学习金币上限 150**:超限关卡照玩,金币照常显示但不再发放(TTS 说明"今天金币存满啦")——防无限刷关。
3. **重复刷关**:同一关卡 24h 内第 3 次起奖励减半,第 5 次起为 0(进度照记)。
4. **升级门槛不因学习膨胀**:宠物 exp 来源增多后,若测试发现 Lv5 成年过快,将学习 exp 系数调至 0.6,不动物品价格。
5. **复习优先级**:当日复习未完成时,新关卡入口显示"先复习"引导(不强制锁),复习完成奖励即时到账。

---

## 7. 界面清单与规格(孩子端)

| # | 界面 | 状态 | 规格 |
|---|---|---|---|
| 1 | 宠物主页 | 改造 | 保留像素暗底;互动行下新增"今日学习"任务卡(今日复习 N+作业 1+推荐关卡);`btnLearn` 改为跳学习乐园 |
| 2 | 学习乐园 | 新 | 像素地图风:识字屋/拼音森林/口算商店/古诗亭/复习谷 五岛+L2 锁(L1/L3 由家长切);顶部今日时长进度(太阳图标 40min) |
| 3 | 关卡列表 | 新 | 每岛内课程网格,已通关星(1-3 星=正确率),当前关高亮,复习关卡置顶 |
| 4 | 关卡页 | 新 | 原生壳(题面+四选一+金币栏+暂停)内嵌 WebView 内容区;顶部暂停按钮→护眼提示 |
| 5 | 休息页 | 新 | 全屏护眼:绿色远景+宠物远眺动画+倒计时+口诀朗读;5min 内返回键无效 |
| 6 | 复习谷 | 新 | 错题/到期复习列表,分模块过滤 |
| 7 | 学习报告(孩子版) | 新 | 极简:本周星星总数+最棒的科目+宠物成长对比("小宠物因为你升级啦") |
| 8-14 | 背包/商店/小游戏/好友圈/聊天/家长后台(PIN)/管控待机 | 保留 | 不动;家长后台加"学习设置"组(时长/夜间/分级) |

家长端:Dashboard 设备卡加学习简报两行;新增 `LearnReportActivity`(P1-1)、`HomeworkActivity`(题型+数量+截止时间三步表单)。

---

## 8. 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M1 最小闭环**(第 1-2 周) | monorepo 切仓库;资产加工脚本(第 4 节全部转换);WebView 嵌识字屋;JSBridge 四个核心方法(speak/reward/reportProgress/finishLesson);学习乐园入口;47 码位;主控端简报 | 孩子端通 1 关识字→宠物吃金币升级→家长端看到记录 |
| **M2 内容齐**(第 3-4 周) | 口算生成器(原生)+拼音森林+古诗亭;错题本+艾宾浩斯复习谷;护眼三规则 | 五岛可玩;20 分钟必休息;隔日复习出现 |
| **M3 家长闭环**(第 5 周) | 48/49 作业下发;学习报告页;习惯打卡;每日金币上限;L 级切换;英语字母岛(26 字母卡+191 词闪卡+听音选词) | 家长下发 10 道口算→孩子完成→奖励到账→报告正确 |
| **M4 打磨**(第 6 周+) | 舒尔特方格;跟读回放;绘本/自然拼读(P2);圆屏适配;压力测试(低端平板 WebView 帧率) | 全量验收清单过一遍 |

---

## 9. 风险与待决策项

1. **低端设备 WebView 性能**:hanzi-writer 描红在小内存手表上可能卡顿 → M1 期间在目标设备实测,预设降级方案(点选笔画序)。
2. **版权**:见第 4 节红线;joye 数据建议重构造而非直接搬。
3. **拼音发音质量(待决策)**:系统 TTS 读声母"b"会读成"哔"类噪声。方案 A(默认):用代表字代读(b→播),零成本;方案 B(P1):录 63 个真人音素(约 1MB 资产);方案 C:腾讯 SOE(依赖 Key)。
4. **47-49 码位与好友频道白名单冲突**:FriendPolicy 只放行 33/34/43 进好友频道,47-49 只走主控频道,无冲突,但需在 `AblyRelayTransport` 白名单注释中显式声明。
5. **多孩子档案**:当前宠物单档案;若需二胎档案,P2 引 profileId 贯穿三张新表(现设计已留 childId 字段)。
6. **家长端报告无后端**:报告数据随 47 码实时推送+主控端 Room 暂存,清 App 丢历史——接受(与宠物数据同命),如需云备份另立项目。

---

## 10. 零服务器与全资产内置(硬约束审计)

铁律:**不部署任何自有服务器;孩子端完全离线可用(飞行模式全功能);主控端外网依赖仅限两个"免运维第三方云"且均有离线替代路径。**

### 10.1 运行期依赖审计表

| 依赖 | 用途 | 是否需要自有服务器 | 断网可用? | 处置 |
|---|---|---|---|---|
| 学习内容(字库/笔顺/拼音/古诗/词库/题目) | 核心教学 | 否 | ✅ | 全部打进 APK `assets/`,构建期脚本生成,运行期零拉取 |
| 字体(缝合怪像素/霞鹜文楷) | 全 UI | 否 | ✅ | 字体文件入 `assets/fonts/`,禁止运行时在线下载 |
| TTS 朗读 | 发音/引导 | 否 | ✅ | 系统 TTS 引擎(本地);拼音发音用代表字代偿或内置音频,不用云端 TTS |
| WebView 内容页 | 关卡 UI | 否 | ✅ | 只加载 `file:///android_asset/learning/`,禁外链(网络白名单为空) |
| 宠物素材/音效 | 激励 | 否 | ✅ | 已内置(res/drawable + res/raw) |
| 礼花/动画库 | 反馈 | 否 | ✅ | konfetti 等为打包依赖,无运行时资源拉取 |
| 设备间传输(聊天/作业/进度) | 双端通信 | 否 | ⚠️ 分模式 | **LOCAL 局域网 WS 直连 = 默认模式,零任何云**;Ably 保留为"跨网备选"(第三方 SaaS 免费额度,非自建服务器,不注册也仅影响跨网场景);原 `server/` 自建中继删除 |
| GPS 定位/围栏/寻宝 | 安全+激励 | 否 | ✅ | 纯本机 LocationManager,无云端 |
| 学习报告 | 家长查看 | 否 | ✅ | 47 码经设备间通道推送,主控端 Room 本地存,无后端 |
| 腾讯地图(主控端) | 轨迹显示/围栏选点 | 否 | ❌ 需外网拉地图瓦片 | **标记为"仅在线功能"**:无网时地图区灰置+提示,围栏/轨迹数据本地缓存不丢;不做离线地图包(体积与许可不划算) |
| 腾讯驾车路线 API | 路线规划 | 否 | ❌ | 同上,在线功能,断网隐藏按钮 |
| GitHub Actions | APK 构建 | 否 | — | 仅构建期,与运行无关 |
| 跟读评测(P1 可选,腾讯 SOE) | 发音打分 | 否 | ❌ 云 API | 如启用则默认关闭+标注需网络;阶段一(录音回放)本地可用 ✅ |

### 10.2 APK 体积预算

| 资产 | 估算 | 削减策略(超 150MB 时启用) |
|---|---|---|
| 代码+依赖(现有基线) | ~40MB | R8 混淆+资源收缩(默认开) |
| 宠物素材(87 帧+240 街机图+音频) | ~15MB | 不动(现成) |
| 字体:缝合怪像素(全 CJK) | 5-12MB | 子集化(fonttools 只留 3500 常用字,可压至 ~3MB) |
| 识字数据+笔顺 JSON(3400 字) | 8-20MB | 笔顺只打包 L1-L3 的 1200 字(L4 字不描红只认读) |
| 古诗音频 114 首 mp3 | 20-30MB | 重编码 64kbps 单声道(儿童朗读够用,约减半) |
| 拼音音素音频(可选) | ~1MB | 方案 A(代表字代偿)则为 0 |
| **合计** | **约 90-120MB** | 目标红线 150MB |

### 10.3 离线自检(每里程碑必跑)

飞行模式实测清单:五个学习岛全流程可玩;宠物喂养/衰减/小游戏正常;复习谷正常;护眼计时正常;TTS 正常;主控端开热点、孩子端连热点(纯局域网)时聊天/作业/进度上报/围栏正常;地图页正确灰置且恢复网络后自动重载。

---

## 附:A. 验收总清单(够不够幼小衔接的最终判定)

- [ ] **离线自检:飞行模式下 10.3 清单全过(零服务器铁律的最终验收)**
- [ ] 识字:L2 500 字关卡全通,抽查 20 字能认读
- [ ] 拼音:23/24/16 全部学过;两拼正确率 ≥80%
- [ ] 数学:10 以内加减 50 题/5 分钟正确率 ≥90%;20 以内进退位能做
- [ ] 古诗:L1+L2 共 30 首能跟读
- [ ] 复习:错字隔天必重现,15 天周期后掌握率上升可统计
- [ ] 专注:能连续完成一节 15 分钟课程(舒尔特/关卡)
- [ ] 防沉迷:20 分钟强制休息、40 分钟日限、21 点夜禁三项实测生效
- [ ] 家长:布置作业→完成→奖励→报告 四步全通
- [ ] 安全:PIN 锁学习设置;孩子无法从学习页退出到系统桌面(屏蔽返回/Home 需设备管理器或 kiosk 模式,见待决策)
- [ ] 宠物:所有学习奖励走 addReward,无第二入账路径

## 附:B. 协议码位登记表(新增部分)

| 码 | 名称 | 方向 | 载荷 |
|---|---|---|---|
| 47 | LEARN_PROGRESS | 孩子→家长 | module/level/itemsDone/correctRate/minutesToday/coinsToday/weakTop5 |
| 48 | HOMEWORK_ASSIGN | 家长→孩子 | taskId/module/type/count/params/deadline |
| 49 | HOMEWORK_ACK | 孩子→家长 | taskId/state(RECEIVED|DONE)/score |

> 既有 1-21(管控)、22/23(音频)、30-46(宠物)、50-52(心跳)码位不动。
