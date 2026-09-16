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

`CrewMateAgentSpec` exposes exactly these first-version tools:

- `find_contact`
- `get_conversation`
- `draft_message`
- `send_message`
- `request_user_input`

Unknown tools are rejected by `AgentHarness`.

## Communication state

Conversation state is first-class product data in `CommunicationSession`, not only model context.

Each `Message` stores:

- `id`
- `sender`
- `recipient`
- `content`
- `timestamp`
- `status`

Senders are:

- `USER`
- `MATE`
- `OTHER_PERSON`
- `SYSTEM`

The UI separates the private `You ↔ Mate` conversation from the external `Mate ↔ other person` timeline.

## Approval policy

The first version uses **ALWAYS_ASK**.

`draft_message` can run automatically. `send_message` cannot.

When the model requests `send_message`, `CrewMateToolRegistry` creates `PendingApproval` and deliberately leaves that tool invocation incomplete. The actual messaging backend is not called until the user chooses **Allow send**. The user may edit the exact outgoing text or cancel it.

There is no model-provided `user_approved` flag and no model-only path around the authorization boundary. A pending approval can be consumed once, preventing duplicate sends.

## Fake end-to-end flow

The first provider is `FakeMessagingBackend`:

```text
User: 幫我問 John 明天晚上有沒有空吃飯
        ↓
find_contact
        ↓
draft_message
        ↓
send_message
        ↓
WAITING_FOR_APPROVAL
        ↓ user approves
fake send
        ↓
John: 明天 7 點可以。
        ↓
EXTERNAL_MESSAGE injected back through AgentHarness
```

A real Telegram/LINE/email implementation only needs to replace `MessagingBackend`; it does not change `AgentHarness` or `CrewMateAgentSpec` orchestration.

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

`CrewMateHarnessIntegrationTest` covers:

- AgentSpec tool exposure
- undeclared tool rejection by the shared harness
- ToolResult call-id preservation
- duplicate completion suppression
- no send before approval
- approved message sends only once
- conversation ordering
- trace privacy
- fake John end-to-end flow

CI checks out `crew-agent-harness` at the pinned commit as a sibling and runs:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

## Run locally

From `crew-mate/`, with the sibling harness present:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Then install the debug APK normally with Android Studio or ADB. On launch, enter a Gemini API key, grant microphone permission, and start a voice session.

## Next provider milestone

To connect a real messaging provider, implement only:

1. contact lookup in `MessagingBackend.findContact`
2. thread fetch in `MessagingBackend.getConversation`
3. actual send + inbound reply delivery in `MessagingBackend.sendMessage`
4. provider authentication/account setup
5. stable provider message/contact IDs for deduplication

The shared agent loop and approval boundary should remain unchanged.
