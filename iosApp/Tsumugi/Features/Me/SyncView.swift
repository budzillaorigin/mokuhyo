import Shared
import SwiftUI
import UIKit

/// Optional, self-hostable sync (BRIEF §8): account, status, end-to-end encryption.
struct SyncView: View {
    @Environment(AppModel.self) private var app
    @State private var signedIn = false
    @State private var server = ""
    @State private var email = ""
    @State private var password = ""
    @State private var passphrase = ""
    @State private var message: String?
    @State private var status: SyncStatus?

    var body: some View {
        Form {
            Section {
                Text("Sync keeps your reviews, notes and lists in step across devices. It's optional; run your own server with `docker compose up` from the project's server/ folder, or use a hosted one.")
                    .font(.footnote)
                if let message { Text(message).font(.subheadline) }
            }
            if !signedIn {
                Section("Account") {
                    TextField("Server URL", text: $server).textInputAutocapitalization(.never).keyboardType(.URL)
                    TextField("Email", text: $email).textInputAutocapitalization(.never).keyboardType(.emailAddress)
                    SecureField("Password", text: $password)
                    Button("Sign in") { signIn() }.disabled(server.isEmpty || email.isEmpty || password.isEmpty)
                    Button("Create account") { register() }.disabled(server.isEmpty || email.isEmpty || password.count < 8)
                }
            } else {
                Section("Status") {
                    Text("Server: \(app.graph.syncAccount.baseUrl ?? "")")
                    if let status {
                        Text("\(String(describing: status.state).lowercased()) · \(status.pending) changes waiting")
                        if let error = status.error { Text(error).foregroundStyle(.red).font(.caption) }
                    }
                    Button("Sync now") {
                        run("Syncing") {
                            let result = try await app.graph.sync()?.sync()
                            return result.map { "Sent \($0.pushed), received \($0.pulled)." }
                        }
                    }
                }
                Section {
                    SecureField("Passphrase", text: $passphrase)
                    if app.graph.syncAccount.e2eEnabled {
                        Button("Unlock") {
                            let value = passphrase
                            run("Unlocking") {
                                let ok = try await app.graph.syncAccount.unlockE2e(passphrase: value).boolValue
                                if ok { passphrase = ""; _ = try await app.graph.sync() }
                                return ok ? "Unlocked." : "Wrong passphrase."
                            }
                        }
                    } else {
                        Button("Turn on encryption") {
                            let value = passphrase
                            run("Enabling encryption") {
                                try await app.graph.syncAccount.enableE2e(passphrase: value)
                                passphrase = ""
                                _ = try await app.graph.sync()
                                return "Encryption on."
                            }
                        }
                        .disabled(passphrase.count < 8)
                    }
                } header: {
                    Text("End-to-end encryption")
                } footer: {
                    Text("With a passphrase, the server stores only ciphertext it can't read. You'll need it on every device; it can't be recovered. Encrypted accounts don't appear on leaderboards.")
                }
                Section {
                    Button("Sign out", role: .destructive) {
                        run("Signing out") {
                            try await app.graph.syncAccount.logout()
                            app.graph.resetSync()
                            signedIn = false
                            return "Signed out. Your data stays on this device."
                        }
                    }
                }
            }
        }
        .navigationTitle("Sync")
        .task {
            signedIn = app.graph.syncAccount.isSignedIn
            server = app.graph.syncAccount.baseUrl ?? ""
            guard signedIn, let engine = try? await app.graph.sync() else { return }
            for await value in engine.status { status = value }
        }
    }

    private func signIn() {
        let (url, mail, pass) = (server.trimmingCharacters(in: .whitespaces), email.trimmingCharacters(in: .whitespaces), password)
        run("Signing in") {
            _ = try await app.graph.syncAccount.login(serverUrl: url, email: mail, password: pass, deviceName: UIDevice.current.name, platform: "ios")
            password = ""
            signedIn = true
            try await app.graph.syncIfConfigured()
            return "Signed in and synced."
        }
    }

    private func register() {
        let (url, mail, pass) = (server.trimmingCharacters(in: .whitespaces), email.trimmingCharacters(in: .whitespaces), password)
        run("Creating account") {
            _ = try await app.graph.syncAccount.register(serverUrl: url, email: mail, password: pass, displayName: nil)
            return "Account created. Now sign in."
        }
    }

    private func run(_ label: String, _ block: @escaping () async throws -> String?) {
        message = "\(label)…"
        Task {
            do { message = try await block() } catch { message = "\(label) failed: \(error.localizedDescription)" }
        }
    }
}
