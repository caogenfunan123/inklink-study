---
name: ui-animation
description: 移动端 60-120fps 流畅动效审查与规范 Skill。约束动画时长、缓动曲线与硬件加速生命周期。
rules:
  - "时长规范：微交互 150-250ms，转场与弹窗 250-400ms，禁止无意义的冗长阻塞动画。"
  - "插值曲线：杜绝硬线性插值(Linear)，优先采用 FastOutSlowInInterpolator 或 SpringAnimation 弹簧阻尼。"
  - "生命周期：Activity 在 onPause 阶段必须暂停所有 Rive 渲染循环、补间动画与粒子发射器，防止后台耗电。"
  - "掉帧防御：严禁在 onDraw 内部执行内存分配(new Paint/Path/Rect)与重耗时计算。"
---
