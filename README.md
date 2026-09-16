# Crew Mate

Crew Mate is a voice-first AI communication secretary and an external consumer of the shared `crew-agent-harness` runtime.

The product boundary is deliberate:

- **Agent Harness** owns the provider-neutral agent loop, tool dispatch, normalized events, interruption, and model abstraction.
- **Crew Mate** owns `CrewMateAgentSpec`, communication state, approval/delegation policy, messaging providers, Gemini Live adaptation, persistence, and UI.

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

For local Harness development only, a Composite Build override is available explicitly:

```text
workspace/
├── crew-agent-harness/
└── crew-mate/
```

```bash
gradle -PcrewHarnessLocal=true :app:testDebugUnitTest :app:assembleDebug
```

Without `-PcrewHarnessLocal=true`, including in CI, Gradle resolves the remote JitPack artifact.

## Runtime architecture

```text
Mate UI
    ↓
CrewMateRuntime / CrewMateAgentSpec
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

`GeminiLiveModelSession` adapts the realtime Gemini transport to the provider-neutral `ModelSession` contract. Provider-specific transport does not enter the shared Harness.

## Task-first secretary UX

Crew Mate is intentionally not a generic AI chat screen.

The main flow is:

```text
User privately briefs Mate
    ↓
Mate resolves the target person and goal
    ↓
User approves the first external message
    ↓
Mate carries routine in-goal conversation forward
    ↓
User watches the complete external timeline
    ↓
Mate returns only for a consequential decision or approval
    ↓
Mate completes the task with an outcome
```

The home screen focuses on handing off a task. API keys and provider controls live under Settings. Once a task exists, the external `Mate ↔ other person` conversation is the primary surface; the private `User ↔ Mate` brief is visually secondary and never copied verbatim to the other person.

## Delegated secretary behavior

The user first privately briefs Mate with a person, desired outcome, preferences, and constraints. Mate then carries the external conversation forward while the app exposes the complete `Mate ↔ other person` timeline.

The first outbound message is user-approved. After that first approval, the task becomes delegated and routine follow-ups within the same goal can continue automatically. Messages involving payment, cancellation, booking, contracts, sensitive information, or other material commitments return to the user for approval or a decision.

This policy belongs to Crew Mate, not `agent-core`.

## Crew Mate tools

`CrewMateAgentSpec` exposes:

- `find_contact`
- `get_conversation`
- `draft_message`
- `send_message`
- `request_user_input`
- `complete_task`

Unknown tools are rejected by the shared `AgentHarness`.

## Product state

`CommunicationSession` is first-class product state and persists independently from model context. It owns:

- target person
- communication goal
- delegated authorization state
- complete visible message timeline
- pending approval
- pending user decision
- task status
- outcome summary

Each `Message` stores id, sender, recipient, content, timestamp, and status. Senders are `USER`, `MATE`, `OTHER_PERSON`, and `SYSTEM`.

## Messaging providers

`MessagingBackend` is product-owned. Current implementations are:

- `FakeMessagingBackend` for deterministic end-to-end testing
- `TelegramMessagingBackend` for the first real provider

Telegram Bot API requires the other person to have started the bot before Crew Mate can resolve them. Observed Telegram history is cached locally because Bot API does not expose arbitrary private-chat history.

Messaging provider code is not part of the shared Harness.

## Voice turns and persistence

Gemini Live transcription/model deltas are aggregated into complete human-readable turns before being persisted. Interrupted Mate speech is discarded rather than stored as a misleading half-message.

Communication sessions are persisted locally. A restart never restores authority for a specific pending send; pending/sending messages come back as drafts.

## Background continuity

For a delegated Telegram task in `WAITING_FOR_REPLY`, ending the voice runtime hands the task to a short-lived Android foreground continuation service.

The service does **not** run another agent loop and does not keep Gemini Live running. It only watches the target Telegram chat for the next inbound reply, persists that reply into the existing `CommunicationSession`, posts a notification, and stops. Opening that notification restores the exact task. When the user resumes Mate, the same shared `AgentHarness` runtime is rehydrated from the persisted task and conversation state.

The voice runtime must not overwrite `WAITING_FOR_REPLY`, `REPLY_RECEIVED`, `NEEDS_USER_INPUT`, `WAITING_FOR_APPROVAL`, or `COMPLETED` merely because the realtime model session stopped.

## Trace privacy

`CrewMateRuntime` listens to `AgentHarness.Listener` and records metadata-only trace entries. Private message bodies and transcripts are intentionally not serialized into debug trace.

## Tests and build

CI resolves the remote Harness directly and runs:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

The test suite covers shared-Harness integration, tool allowlisting, call-id preservation, duplicate completion suppression, delegated approval boundaries, conversation ordering, trace privacy, turn aggregation, completed outcomes, runtime shutdown state preservation, and fake end-to-end communication.

## Run locally

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Then install the debug APK with Android Studio or ADB. For Telegram, enter a Bot API token and have the target person send `/start` to the Crew Mate bot once before asking Mate to contact them.
