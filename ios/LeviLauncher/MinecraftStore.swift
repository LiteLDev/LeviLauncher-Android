import Foundation
import ZIPFoundation

struct MCItem: Identifiable, Hashable, Sendable {
    let url: URL
    let name: String
    let modified: Date?
    var id: URL { url }
}

enum MCError: LocalizedError {
    case noFolder
    case notMinecraft
    case unknownFile

    var errorDescription: String? {
        switch self {
        case .noFolder:
            return "Choose the Minecraft folder first."
        case .notMinecraft:
            return "That folder does not contain Minecraft data (no minecraftWorlds folder found). Pick On My iPhone > Minecraft."
        case .unknownFile:
            return "No world (level.dat) or pack (manifest.json) was found in that file."
        }
    }
}

@MainActor
final class MinecraftStore: ObservableObject {
    @Published var root: URL?
    @Published var worlds: [MCItem] = []
    @Published var resourcePacks: [MCItem] = []
    @Published var behaviorPacks: [MCItem] = []
    @Published var backups: [MCItem] = []
    @Published var busy = false
    @Published var message: String?

    private let bookmarkKey = "mcFolderBookmark"
    private var scopedURL: URL?

    init() {
        restoreBookmark()
        refresh()
    }

    // MARK: Folder access

    func setFolder(_ picked: URL) {
        let started = picked.startAccessingSecurityScopedResource()
        guard let resolved = Self.resolveRoot(picked) else {
            if started { picked.stopAccessingSecurityScopedResource() }
            message = MCError.notMinecraft.localizedDescription
            return
        }
        scopedURL?.stopAccessingSecurityScopedResource()
        scopedURL = started ? picked : nil
        if let data = try? picked.bookmarkData() {
            UserDefaults.standard.set(data, forKey: bookmarkKey)
        }
        root = resolved
        refresh()
    }

    private func restoreBookmark() {
        guard let data = UserDefaults.standard.data(forKey: bookmarkKey) else { return }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &stale) else { return }
        let started = url.startAccessingSecurityScopedResource()
        guard let resolved = Self.resolveRoot(url) else {
            if started { url.stopAccessingSecurityScopedResource() }
            return
        }
        scopedURL = started ? url : nil
        if stale, let fresh = try? url.bookmarkData() {
            UserDefaults.standard.set(fresh, forKey: bookmarkKey)
        }
        root = resolved
    }

    nonisolated private static func resolveRoot(_ picked: URL) -> URL? {
        let candidates = [
            picked,
            picked.appendingPathComponent("games/com.mojang"),
            picked.appendingPathComponent("com.mojang")
        ]
        return candidates.first {
            FileManager.default.fileExists(atPath: $0.appendingPathComponent("minecraftWorlds").path)
        }
    }

    // MARK: Listing

    nonisolated static var backupsDir: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("Backups")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func refresh() {
        backups = Self.list(Self.backupsDir, directoriesOnly: false) { $0.lastPathComponent }
        guard let root else {
            worlds = []; resourcePacks = []; behaviorPacks = []
            return
        }
        worlds = Self.list(root.appendingPathComponent("minecraftWorlds")) { Self.worldName($0) }
        resourcePacks = Self.list(root.appendingPathComponent("resource_packs")) { Self.packName($0) }
        behaviorPacks = Self.list(root.appendingPathComponent("behavior_packs")) { Self.packName($0) }
    }

    nonisolated private static func list(_ dir: URL, directoriesOnly: Bool = true, name: (URL) -> String) -> [MCItem] {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .isDirectoryKey]
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])) ?? []
        return urls.compactMap { url -> MCItem? in
            let values = try? url.resourceValues(forKeys: Set(keys))
            if directoriesOnly && values?.isDirectory != true { return nil }
            return MCItem(url: url, name: name(url), modified: values?.contentModificationDate)
        }
        .sorted { ($0.modified ?? .distantPast) > ($1.modified ?? .distantPast) }
    }

    nonisolated private static func worldName(_ dir: URL) -> String {
        let file = dir.appendingPathComponent("levelname.txt")
        if let text = try? String(contentsOf: file, encoding: .utf8) {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { return trimmed }
        }
        return dir.lastPathComponent
    }

    nonisolated private static func manifest(_ dir: URL) -> [String: Any]? {
        guard let data = try? Data(contentsOf: dir.appendingPathComponent("manifest.json")) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    nonisolated private static func packName(_ dir: URL) -> String {
        if let header = manifest(dir)?["header"] as? [String: Any],
           let name = header["name"] as? String,
           !name.isEmpty, name != "pack.name" {
            return name
        }
        return dir.lastPathComponent
    }

    nonisolated private static func isBehaviorPack(_ dir: URL) -> Bool {
        let modules = manifest(dir)?["modules"] as? [[String: Any]] ?? []
        return modules.contains {
            let type = ($0["type"] as? String ?? "").lowercased()
            return type == "data" || type == "script"
        }
    }

    // MARK: Actions

    private func run(_ work: @escaping @Sendable () throws -> String) {
        busy = true
        Task {
            do {
                message = try await Task.detached(priority: .userInitiated, operation: work).value
            } catch {
                message = error.localizedDescription
            }
            busy = false
            refresh()
        }
    }

    func backup(_ world: MCItem) {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let safeName = world.name
            .components(separatedBy: CharacterSet(charactersIn: "/\\:?*\"<>|"))
            .joined(separator: "_")
        let dest = Self.backupsDir
            .appendingPathComponent("\(safeName)-\(formatter.string(from: Date())).mcworld")
        run {
            try FileManager.default.zipItem(at: world.url, to: dest, shouldKeepParent: false)
            return "Backed up \"\(world.name)\"."
        }
    }

    func deleteBackup(_ item: MCItem) {
        try? FileManager.default.removeItem(at: item.url)
        refresh()
    }

    /// Imports a .mcworld, .mcpack, .mcaddon or .zip file into the Minecraft folder.
    func importFile(_ file: URL) {
        guard let root else {
            message = MCError.noFolder.localizedDescription
            return
        }
        run {
            let fm = FileManager.default
            let started = file.startAccessingSecurityScopedResource()
            defer { if started { file.stopAccessingSecurityScopedResource() } }

            let tmp = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let unpacked = tmp.appendingPathComponent("unpacked")
            try fm.createDirectory(at: unpacked, withIntermediateDirectories: true)
            defer { try? fm.removeItem(at: tmp) }

            let local = tmp.appendingPathComponent("import.zip")
            try fm.copyItem(at: file, to: local)
            try fm.unzipItem(at: local, to: unpacked)

            // The content may sit at the archive root or one folder down.
            var dirs = [unpacked]
            let children = (try? fm.contentsOfDirectory(
                at: unpacked, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])) ?? []
            dirs.append(contentsOf: children.filter { $0.hasDirectoryPath })

            func install(_ source: URL, into folder: String, named: String) throws {
                let parent = root.appendingPathComponent(folder)
                try fm.createDirectory(at: parent, withIntermediateDirectories: true)
                try fm.copyItem(at: source, to: parent.appendingPathComponent(named))
            }

            if let world = dirs.first(where: {
                fm.fileExists(atPath: $0.appendingPathComponent("level.dat").path)
            }) {
                let id = String(UUID().uuidString.prefix(12))
                try install(world, into: "minecraftWorlds", named: id)
                return "Imported world \"\(Self.worldName(world))\"."
            }

            let packs = dirs.filter {
                fm.fileExists(atPath: $0.appendingPathComponent("manifest.json").path)
            }
            guard !packs.isEmpty else { throw MCError.unknownFile }
            for pack in packs {
                let folder = Self.isBehaviorPack(pack) ? "behavior_packs" : "resource_packs"
                let id = String(UUID().uuidString.prefix(12))
                try install(pack, into: folder, named: id)
            }
            return "Imported \(packs.count) pack(s)."
        }
    }
}
