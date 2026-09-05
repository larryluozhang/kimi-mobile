import SwiftUI

/// 会话模式栏：折叠时显示当前生效模式的 chips，展开后可修改。
/// 修改即 POST /sessions/{id}/profile 生效；失败由 ChatViewModel toast 提示并回滚。
struct ModeBarView: View {
    @ObservedObject var vm: ChatViewModel
    @State private var expanded = false
    @State private var goalInput = ""

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
        case "yolo": return "权限: YOLO"
        case "auto": return "权限: 自动"
        default: return "权限: 手动"
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
                    Text("手动确认").tag("manual")
                    Text("自动").tag("auto")
                    Text("YOLO").tag("yolo")
                }
                .pickerStyle(.segmented)
            }

            // 模型
            VStack(alignment: .leading, spacing: 4) {
                Text("模型")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Picker("模型", selection: modelBinding) {
                    ForEach(Constants.availableModels, id: \.self) { m in
                        Text(m.components(separatedBy: "/").last ?? m).tag(m)
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

    private var permissionBinding: Binding<String> {
        Binding(get: { cfg.permissionMode }, set: { mode in
            vm.updateProfile(fields: ["permission_mode": mode]) { $0.permissionMode = mode }
        })
    }

    private var modelBinding: Binding<String> {
        Binding(get: {
            cfg.model.isEmpty ? Constants.defaultModel : cfg.model
        }, set: { m in
            vm.updateProfile(fields: ["model": m]) { $0.model = m }
        })
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
