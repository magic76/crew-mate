# Crew Mate

Crew Mate is a voice-first AI communication assistant and the first external consumer of the shared `crew-agent-harness` runtime.

The product boundary is deliberate:

- **Agent Harness** owns the agent loop, tool dispatch, normalized events, and orchestration.
- **Crew Mate** owns prompt behavior, communication state, approval policy, messaging providers, Gemini Live adaptation, and UI.

## Workspace layout

Crew Mate uses a Gradle Composite Build. Keep both repositories as siblings:

```text
workspace/
├── crew-agent-harness/
└── crew-mate/
```

The integration is pinned and tested against harness commit:

```text
b92902e7cf7f2b05cb8281ab408297dacf8347bb
```

If the harness is not present locally:

```bash
cd <workspace>
git clone https://github.com/magic76/crew-agent-harness.git
cd crew-agent-harness
git checkout b92902e7cf7f2b05cb8281ab408297dacf8347bb
```

`crew-mate/settings.gradle` substitutes the external module:

```gradle
includeBuild('../crew-agent-harness') {
    dependencySubstitution {
        substitute module('com.magic76.crew:agent-core') using project(':agent-core')
    }
}
```

The app depends on:

```gradle
implementation 'com.magic76.crew:agent-core:0.1.0-SNAPSHOT'
```

No `agent-core` source is copied into Crew Mate.

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

`GeminiLiveModelSession` adapts the existing Crew Teacher-style realtime WebSocket/audio path to the provider-neutral `ModelSession` contract. Reasoning/orchestration remains in the shared harness; Gemini-specific transport does not leak into `agent-core`.

## Crew Mate tools

`CrewMateAgentSpec` exposes:

- `find_contact`
- `get_conversation`
- `draft_message`
- `send_message`
- `request_user_input`
- `complete_task`

`complete_task` stores a concise outcome summary and marks the product session `COMPLETED`. Unknown tools are rejected by `AgentHarness`.

## Communication state

Conversation state is first-class product data in `CommunicationSession`, not only model context.

Each `Message` stores:

- `id`
- `sender`
- `recipient`
- `content`
- `timestamp`
- `status`

Senders are `USER`, `MATE`, `OTHER_PERSON`, and `SYSTEM`.

The UI separates the private `You ↔ Mate` conversation from the external `Mate ↔ other person` timeline. Completed tasks keep their factual `outcomeSummary` even after the realtime voice runtime stops.

## Approval policy

The first version uses **ALWAYS_ASK**.

`draft_message` can run automatically. `send_message` cannot.

When the model requests `send_message`, `CrewMateToolRegistry` creates `PendingApproval` and deliberately leaves that tool invocation incomplete. The actual messaging backend is not called until the user chooses **Allow send**. The user may edit the exact outgoing text or cancel it.

There is no model-provided `user_approved` flag and no model-only path around the authorization boundary. A pending approval can be consumed once, preventing duplicate sends.

## Messaging providers

The app currently supports two provider modes behind the same `MessagingBackend` contract.

### Fake

`FakeMessagingBackend` is the deterministic local end-to-end test provider. No real external message leaves the device.

### Telegram Bot API MVP

`TelegramMessagingBackend` is the first real provider. Enter a Bot API token in the app and switch the provider from **Fake** to **Telegram**.

Important Telegram constraints:

- A bot cannot initiate a private conversation with an arbitrary Telegram user.
- The target person must first open the Crew Mate bot and send `/start` (or another message).
- Crew Mate learns those contacts from Bot API updates and can then resolve them by display name, `@username`, or Telegram chat id.
- Telegram Bot API does not provide arbitrary private-chat history. `get_conversation` therefore returns messages that this Crew Mate provider has locally observed and cached.
- The current Telegram implementation uses foreground long polling. If the voice/runtime session ends, the provider polling stops. Background receiving/webhooks are a later milestone.

The same `ALWAYS_ASK` approval boundary applies to Telegram sends. Selecting Telegram never gives the model direct send authority.

## Voice turn aggregation and persistence

Gemini Live transcription/model deltas are aggregated into complete human-readable turns before being written into the conversation UI. Interrupted Mate speech is discarded rather than persisted as a misleading half-message.

Communication sessions are persisted locally. Restarting the app restores history, but never restores send authorization: pending/sending messages become drafts after process restart.

## Trace privacy

`CrewMateRuntime` listens to `AgentHarness.Listener` and records metadata-only trace entries for:

- `STARTED`
- `TOOL_REQUESTED`
- `TOOL_COMPLETED`
- `TOOL_FAILED`
- `INTERRUPTED`
- `TURN_COMPLETED`
- `STOPPED`
- `ERROR`

Trace entries include session id, tool name, call id, success/failure, and duration. Private message bodies and transcripts are intentionally not serialized.

## Tests

The JVM test suite covers shared-harness integration, tool allowlisting, call-id preservation, duplicate completion suppression, approval gating, exactly-once sends, conversation ordering, trace privacy, voice-turn aggregation, fake end-to-end communication, and completed task outcomes.

CI checks out `crew-agent-harness` at the pinned commit as a sibling and runs:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

## Run locally

From `crew-mate/`, with the sibling harness present:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Then install the debug APK with Android Studio or ADB. On launch:

1. Enter a Gemini API key.
2. Choose **Fake** for local simulation, or **Telegram** and enter a Bot API token.
3. For Telegram, have the target person send `/start` to your bot once.
4. Grant microphone permission and start a voice session.
5. Every real outbound message still appears as a pending approval before it can be sent.

## Next milestones

- Android foreground/background service for communication tasks that outlive the voice screen.
- Telegram background receive or webhook relay so replies can resume tasks when the app is not open.
- Contacts/person identity layer across multiple providers.
- Calendar/reminder follow-up from completed task outcomes.
- Additional providers such as email, LINE, and WhatsApp where their platform constraints fit Crew Mate's delegation model.
