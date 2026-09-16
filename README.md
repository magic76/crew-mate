# Crew Mate

Crew Mate is a voice-first AI communication assistant and an external consumer of the shared `crew-agent-harness` runtime.

The product boundary is deliberate:

- **Agent Harness** owns the provider-neutral agent loop, tool dispatch, normalized events, interruption, and model abstraction.
- **Crew Mate** owns prompt behavior, communication state, approval policy, messaging providers, Gemini Live adaptation, persistence, and UI.

## Shared Agent Harness dependency

Production and CI resolve the released Harness directly from JitPack:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.magic76:crew-agent-harness:ba4f03a408'
}
```

Crew Mate does not copy or maintain `agent-core` source.

For local Harness development only, a Composite Build override remains available explicitly. With sibling repositories:

```text
workspace/
├── crew-agent-harness/
└── crew-mate/
```

run:

```bash
gradle -PcrewHarnessLocal=true :app:testDebugUnitTest :app:assembleDebug
```

Without `-PcrewHarnessLocal=true`, including in CI, Gradle resolves the remote JitPack artifact.

## Runtime architecture

```text
CrewMate UI
    ↓
CrewMateRuntime + CrewMateAgentSpec
    ↓
AgentHarness
    ↓
ModelSession
    ↓
GeminiLiveModelSession
    ↓
Gemini Live

AgentHarness
    ↓ tool calls
CrewMateToolRegistry
    ↓
CommunicationSession / Approval Policy / MessagingBackend
```

`GeminiLiveModelSession` adapts the realtime Gemini WebSocket/audio path to the provider-neutral `ModelSession` contract. Gemini-specific transport does not enter the shared Harness.

## Crew Mate tools

`CrewMateAgentSpec` exposes:

- `find_contact`
- `get_conversation`
- `draft_message`
- `send_message`
- `request_user_input`
- `complete_task`

Unknown tools are rejected by the shared `AgentHarness`.

## Communication state

Conversation state is first-class product data in `CommunicationSession`, not only model context.

Each `Message` stores id, sender, recipient, content, timestamp, and status. Senders are `USER`, `MATE`, `OTHER_PERSON`, and `SYSTEM`.

The UI separates the private `You ↔ Mate` conversation from the external `Mate ↔ other person` timeline. Completed tasks keep their factual `outcomeSummary` even after the realtime voice runtime stops.

## Approval policy

The current main branch uses an explicit send approval boundary. `draft_message` can run automatically; `send_message` pauses until the user chooses **Allow send**. The user may edit the exact outgoing text or cancel it.

There is no model-provided `user_approved` flag and no model-only path around the authorization boundary. A pending approval can be consumed once, preventing duplicate sends.

## Messaging providers

The app supports provider modes behind the product-owned `MessagingBackend` contract.

- `FakeMessagingBackend` is the deterministic local end-to-end test provider.
- `TelegramMessagingBackend` is the first real provider.

Telegram Bot API requires the target person to open the Crew Mate bot and send `/start` (or another message) before the bot can resolve and message that chat. Observed Telegram history is cached locally because Bot API does not expose arbitrary private-chat history.

Messaging provider code remains in Crew Mate and is not part of the shared Harness.

## Voice turn aggregation and persistence

Gemini Live transcription/model deltas are aggregated into complete human-readable turns before being written into the conversation UI. Interrupted Mate speech is discarded rather than persisted as a misleading half-message.

Communication sessions are persisted locally. Restarting the app restores history, but never restores authority for a specific pending send: pending/sending messages become drafts after process restart.

## Trace privacy

`CrewMateRuntime` listens to `AgentHarness.Listener` and records metadata-only trace entries. Private message bodies and transcripts are intentionally not serialized.

## Tests and build

CI resolves the released Harness remotely and runs:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

No sibling `crew-agent-harness` checkout is required for production or CI builds.

## Run locally

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Then install the debug APK with Android Studio or ADB. On launch, enter a Gemini API key, choose a messaging provider, and start a voice session.
