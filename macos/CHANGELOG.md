# 版本记录

## 0.5.2 (2026-09-02)
- 问答卡片/弹窗内容区限高可滚动：题目和选项多时底部提交按钮始终可达（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isPhantomUserText 补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isPhantomUserText 补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isPhantomUserText 补充 <notification 前缀（三端同步）

## 0.5.0 (2026-09-02)
- 审批 UI：轮询 GET /sessions/{id}/approvals?status=pending（5s 一轮），agent 卡审批时显示审批卡片（工具名/动作/摘要 + 批准/拒绝按钮），
  POST /approvals/{approval_id} 提交 decision（approved/rejected）；agent 在 manual 权限下发起需审批工具调用时挂起，本端决策后服务端恢复运行
- 问答 UI：轮询 GET /sessions/{id}/questions?status=pending（5s 一轮），单选 RadioButton 组渲染 questions[]，allow_other 时追加"其他"+文本输入框，
  "跳过"按钮；answers 为 问题id → 答案对象 的映射（kind=single 带 option_id / other 带 text / skipped）；
  POST /questions/{question_id} 提交后 agent 恢复运行，turn 继续
- 中断按钮：busy 时聊天头部显示"停止"按钮，调 POST /sessions/{id}:abort（实测返回 {"aborted":true}）中断当前 turn
