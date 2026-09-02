# 版本记录

## 0.5.2 (2026-09-02)
- 问答卡片/弹窗内容区限高可滚动：题目和选项多时底部提交按钮始终可达（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示在发送侧：系统注入过滤器补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示在发送侧：系统注入过滤器补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示在发送侧：系统注入过滤器补充 <notification 前缀（三端同步）

## 0.5.0 (2026-09-02)
- 新增问答支持（pending_interaction="question" 场景）：服务端发 AskUserQuestion 时，ChatActivity 每 5s 轮询 GET /questions?status=pending（与审批共用 5s 轮询定时器），有 pending 时弹窗：每题渲染 RadioGroup 单选（label — description），allow_other 时追加"其他（自定义回答）"+文本输入；提交 {"kind":"single","option_id":...} / {"kind":"other","text":...}；"跳过"按钮发送 {"kind":"skipped"}；未作答完不关闭弹窗（手动接管提交按钮校验）；多条 pending 逐条处理（同审批）
