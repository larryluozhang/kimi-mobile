# 版本记录

## 0.5.2 (2026-09-02)
- 问答卡片/弹窗内容区限高可滚动：题目和选项多时底部提交按钮始终可达（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isSystemInjected 补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isSystemInjected 补充 <notification 前缀（三端同步）

## 0.5.1 (2026-09-02)
- 修复后台任务通知（<notification ...>）被当作用户消息显示：isSystemInjected 补充 <notification 前缀（三端同步）

## 0.5.0 (2026-09-02)
- 新增审批 UI：轮询 GET /sessions/{id}/approvals?status=pending（data.items[] 含 approval_id/tool_name/action/tool_input_display.summary），审批卡片显示工具名·动作+摘要，批准/拒绝按钮 POST /sessions/{id}/approvals/{approval_id}（body {"decision":"approved"|"rejected"}）；提交时乐观移除卡片，失败回滚轮询
- 新增问答支持：轮询 GET /sessions/{id}/questions?status=pending（data.items[] 含 question_id、questions[]（每题 id/question/header/options/allow_other））；问答卡片逐题单选（kind=single/option_id），allow_other=true 时提供"其他"文本框（kind=other/text）；提交 POST /sessions/{id}/questions/{question_id}（body {"answers":{"<问题id>":{...}}}），另有"跳过"按钮（全部题目 kind=skipped）
- 新增中断按钮：busy 时输入栏显示红色停止按钮，点击调 POST /sessions/{id}:abort（冒号后缀语法，服务端实测返回 {"aborted":true}），发送按钮保留可用（busy 时仍可排队）
- 审批/问答随历史调和同一周期轮询（busy 15s / 空闲 60s，startBusyPolling 同节奏），onAppear 首次即拉一次；拉取失败不清空已有卡片避免闪动
