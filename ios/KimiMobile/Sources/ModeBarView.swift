import SwiftUI

/// 会话模式栏：折叠时显示当前生效模式的 chips，展开后可修改。
/// 修改即 POST /sessions/{id}/profile 生效；失败由 ChatViewModel toast 提示并回滚。
struct ModeBarView: View {
    @ObservedObject var vm: ChatViewModel
    @State private var expanded = false
    @State private var goalInput = ""
    /// 切到「完全放权」前的二次确认弹窗（每次切换都弹，不记住选择）
    @State private var showAutoConfirm = false

    private var cfg: AgentConfig { vm.agentConfig ?? AgentConfig() }

    var body: some View {
        VStack(spacing: 0) {
            collapsedRow
            if expanded {
                Divider()
                expandedPanel
            }
        }
        // 计划模式开启时的明显视觉提示：整条模式栏染品牌蓝紫渐变
        .background(cfg.planMode ? AnyView(Theme.brandGradient.opacity(0.18)) : AnyView(Color.clear))
        .background(Theme.assistantBubble.opacity(0.6))
        // 切到「完全放权」的二次确认：仅在确认后才 updateProfile；取消则 Picker 回显原模式
        .confirmationDialog("⚠️ 完全放权", isPresented: $showAutoConfirm, titleVisibility: .visible) {
            Button("确定开启", role: .destructive) {
                vm.updateProfile(fields: ["permission_mode": "auto"]) { $0.permissionMode = "auto" }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("agent 可直接修改、删除文件并执行任意命令，不再逐条征求你的同意。确定开启？")
        }
    }

    // MARK: - 折叠行

    private var collapsedRow: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
        } label: {
            HStack(spacing: 6) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        if cfg.planMode {
                            chip("计划模式", icon: "list.bullet.clipboard.fill", prominent: true)
                        }
                        if cfg.swarmMode {
                            chip("Swarm", icon: "point.3.connected.trianglepath.dotted", prominent: true)
                        }
                        chip(permissionLabel, icon: "hand.raised")
                        chip(shortModel, icon: "cpu")
                        if !cfg.goalObjective.isEmpty {
                            chip("目标模式", icon: "scope", prominent: true)
                        }
                        if vm.agentConfig == nil {
                            Text("配置加载中…")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }

    private func chip(_ text: String, icon: String, prominent: Bool = false) -> some View {
        Label(text, systemImage: icon)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundColor(prominent ? .white : Theme.primary)
            .background(prominent ? AnyView(Theme.brandGradient) : AnyView(Theme.primary.opacity(0.1)))
            .cornerRadius(10)
    }

    private var permissionLabel: String {
        switch cfg.permissionMode {
        case "manual": return "权限: 每步确认"
        case "yolo": return "权限: 常规自动"
        case "auto": return "权限: 完全放权（危险）"
        default: return "权限: \(cfg.permissionMode)" // 未知值原样展示
        }
    }

    /// 「常规自动」的后端差异说明（GET /api/v1/meta 的 server；失败/未知按 kimi 通用文案）
    private var yoloPickerLabel: String {
        switch vm.serverType {
        case "claude": return "常规自动（只自动接受编辑）"
        case "codex": return "常规自动（失败才询问）"
        default: return "常规自动（敏感仍询问）"
        }
    }

    private var shortModel: String {
        cfg.model.components(separatedBy: "/").last ?? cfg.model
    }

    // MARK: - 展开面板

    private var expandedPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 计划模式 / Swarm
            HStack(spacing: 16) {
                Toggle(isOn: planBinding) {
                    Label("计划模式", systemImage: "list.bullet.clipboard.fill")
                        .font(.subheadline.weight(cfg.planMode ? .bold : .regular))
                }
                .toggleStyle(.button)
                .tint(cfg.planMode ? Theme.primary : .secondary)

                Toggle(isOn: swarmBinding) {
                    Label("Swarm", systemImage: "point.3.connected.trianglepath.dotted")
                        .font(.subheadline)
                }
                .toggleStyle(.button)
                .tint(cfg.swarmMode ? Theme.primary : .secondary)
            }

            // 权限模式
            VStack(alignment: .leading, spacing: 4) {
                Text("权限模式")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Picker("权限模式", selection: permissionBinding) {
                    Text("每步确认").tag("manual")
                    Text(yoloPickerLabel).tag("yolo")
                    Text("完全放权（危险）").tag("auto")
                }
                .pickerStyle(.segmented)
            }

            // 模型
            VStack(alignment: .leading, spacing: 4) {
                Text("模型")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Picker("模型", selection: modelBinding) {
                    ForEach(modelOptions, id: \.id) { opt in
                        Text(opt.label).tag(opt.id)
                    }
                }
                .pickerStyle(.menu)
                .tint(Theme.primary)
            }

            // 目标模式
            VStack(alignment: .leading, spacing: 6) {
                Text("目标模式")
                    .font(.caption)
                    .foregroundColor(.secondary)
                if cfg.goalObjective.isEmpty {
                    HStack {
                        TextField("输入目标（Goal）…", text: $goalInput)
                            .textFieldStyle(.roundedBorder)
                            .font(.subheadline)
                        Button("设定") { setGoal() }
                            .disabled(goalInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                      || vm.profileSaving)
                            .tint(Theme.primary)
                    }
                } else {
                    Text(cfg.goalObjective)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                    HStack(spacing: 10) {
                        goalControlButton("暂停", "pause.circle", "pause")
                        goalControlButton("恢复", "play.circle", "resume")
                        goalControlButton("取消", "xmark.circle", "cancel")
                    }
                }
            }

            if vm.profileSaving {
                HStack(spacing: 6) {
                    ProgressView().scaleEffect(0.7)
                    Text("保存中…").font(.caption2).foregroundColor(.secondary)
                }
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .disabled(vm.agentConfig == nil)
    }

    // MARK: - 绑定与动作

    private var planBinding: Binding<Bool> {
        Binding(get: { cfg.planMode }, set: { on in
            vm.updateProfile(fields: ["plan_mode": on]) { $0.planMode = on }
        })
    }

    private var swarmBinding: Binding<Bool> {
        Binding(get: { cfg.swarmMode }, set: { on in
            vm.updateProfile(fields: ["swarm_mode": on]) { $0.swarmMode = on }
        })
    }

    /// 切到 auto（完全放权）不立即生效：先弹二次确认，确认后才 updateProfile；
    /// 取消则什么也不做，Picker 绑定 get 仍读 cfg.permissionMode，自然回显原模式
    private var permissionBinding: Binding<String> {
        Binding(get: { cfg.permissionMode }, set: { mode in
            guard mode != cfg.permissionMode else { return }
            if mode == "auto" {
                showAutoConfirm = true
            } else {
                vm.updateProfile(fields: ["permission_mode": mode]) { $0.permissionMode = mode }
            }
        })
    }

    private var modelBinding: Binding<String> {
        Binding(get: {
            cfg.model.isEmpty ? Constants.defaultModel : cfg.model
        }, set: { m in
            vm.updateProfile(fields: ["model": m]) { $0.model = m }
        })
    }

    /// 模型选项：服务端动态列表（display_name 优先，标注上下文上限）；
    /// 拉取失败/为空回退 Constants.availableModels 预设；
    /// 当前选中模型不在列表时补进去，避免 Picker 选中态落空
    private var modelOptions: [(id: String, label: String)] {
        var opts: [(id: String, label: String)]
        if vm.serverModels.isEmpty {
            opts = Constants.availableModels.map { m in
                (id: m, label: m.components(separatedBy: "/").last ?? m)
            }
        } else {
            opts = vm.serverModels.map { item in
                var label = item.displayName.isEmpty
                    ? (item.id.components(separatedBy: "/").last ?? item.id)
                    : item.displayName
                if item.maxContextSize > 0 {
                    label += "（上下文 \(item.maxContextSize / 1024)k）"
                }
                return (id: item.id, label: label)
            }
        }
        let current = modelBinding.wrappedValue
        if !current.isEmpty && !opts.contains(where: { $0.id == current }) {
            opts.append((id: current, label: current.components(separatedBy: "/").last ?? current))
        }
        return opts
    }

    private func setGoal() {
        let goal = goalInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !goal.isEmpty else { return }
        vm.updateProfile(fields: ["goal_objective": goal]) { $0.goalObjective = goal }
        goalInput = ""
    }

    private func goalControlButton(_ title: String, _ icon: String, _ action: String) -> some View {
        Button {
            // goal_control 是控制命令，不回显；本地状态等服务端 profile 反映
            vm.updateProfile(fields: ["goal_control": action]) { cfg in
                if action == "cancel" { cfg.goalObjective = "" }
            }
        } label: {
            Label(title, systemImage: icon)
                .font(.caption.weight(.medium))
        }
        .buttonStyle(.bordered)
        .tint(action == "cancel" ? Theme.error : Theme.primary)
        .disabled(vm.profileSaving)
    }
}
