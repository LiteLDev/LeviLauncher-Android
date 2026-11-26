import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    private enum PickerMode {
        case folder, file
    }

    @StateObject private var store = MinecraftStore()
    @State private var pickerMode = PickerMode.folder
    @State private var showPicker = false

    var body: some View {
        NavigationStack {
            List {
                if store.root == nil {
                    setupSection
                } else {
                    worldsSection
                    packSection("Resource packs", store.resourcePacks)
                    packSection("Behavior packs", store.behaviorPacks)
                }
                backupsSection
            }
            .navigationTitle("LeviLauncher")
            .refreshable { store.refresh() }
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        open(.file)
                    } label: {
                        Label("Import", systemImage: "square.and.arrow.down")
                    }
                    .disabled(store.root == nil)

                    Button {
                        open(.folder)
                    } label: {
                        Label("Folder", systemImage: "folder")
                    }
                }
            }
            .overlay {
                if store.busy {
                    ProgressView("Working...")
                        .padding(24)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .disabled(store.busy)
        }
        .fileImporter(
            isPresented: $showPicker,
            allowedContentTypes: pickerMode == .folder ? [.folder] : [.item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                if pickerMode == .folder {
                    store.setFolder(url)
                } else {
                    store.importFile(url)
                }
            case .failure(let error):
                store.message = error.localizedDescription
            }
        }
        .alert("LeviLauncher", isPresented: Binding(
            get: { store.message != nil },
            set: { if !$0 { store.message = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(store.message ?? "")
        }
    }

    private func open(_ mode: PickerMode) {
        pickerMode = mode
        showPicker = true
    }

    // MARK: Sections

    private var setupSection: some View {
        Section {
            Text("Choose the Minecraft folder so the app can read your worlds and packs. In the picker, go to On My iPhone > Minecraft and tap Open.")
                .font(.callout)
            Button("Choose Minecraft folder") { open(.folder) }
        } footer: {
            Text("If Minecraft is not listed in Files, set Settings > Storage > File Storage Location to External inside the game.")
        }
    }

    private var worldsSection: some View {
        Section("Worlds (\(store.worlds.count))") {
            if store.worlds.isEmpty {
                Text("No worlds found").foregroundStyle(.secondary)
            }
            ForEach(store.worlds) { world in
                HStack {
                    ItemLabel(item: world, icon: "globe")
                    Spacer()
                    Button {
                        store.backup(world)
                    } label: {
                        Image(systemName: "arrow.down.doc")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Back up \(world.name)")
                }
            }
        }
    }

    private func packSection(_ title: String, _ packs: [MCItem]) -> some View {
        Section("\(title) (\(packs.count))") {
            if packs.isEmpty {
                Text("None installed").foregroundStyle(.secondary)
            }
            ForEach(packs) { pack in
                ItemLabel(item: pack, icon: "shippingbox")
            }
        }
    }

    private var backupsSection: some View {
        Section {
            if store.backups.isEmpty {
                Text("No backups yet").foregroundStyle(.secondary)
            }
            ForEach(store.backups) { backup in
                HStack {
                    ItemLabel(item: backup, icon: "archivebox")
                    Spacer()
                    Button {
                        store.importFile(backup.url)
                    } label: {
                        Image(systemName: "arrow.uturn.backward")
                    }
                    .buttonStyle(.borderless)
                    .disabled(store.root == nil)
                    .accessibilityLabel("Restore as new world")

                    ShareLink(item: backup.url) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .buttonStyle(.borderless)
                }
                .swipeActions {
                    Button(role: .destructive) {
                        store.deleteBackup(backup)
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        } header: {
            Text("Backups (\(store.backups.count))")
        } footer: {
            Text("Backups are .mcworld files in this app's folder in Files. Restoring adds a new world and never overwrites one.")
        }
    }
}

private struct ItemLabel: View {
    let item: MCItem
    let icon: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).lineLimit(1)
                if let date = item.modified {
                    Text(date.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } icon: {
            Image(systemName: icon)
        }
    }
}
