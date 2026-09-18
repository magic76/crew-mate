# Crew Mate

Crew Mate is a voice-first AI assistant for **in-person communication**. The user privately briefs Mate, then hands the phone to the other person. Mate listens through Gemini Live and responds aloud while keeping the user's private goal and constraints in context.

## Product flow

```text
User privately briefs Mate
    ↓
Mate identifies the person and communication goal
    ↓
User taps "開始幫我談"
    ↓
Phone is handed to the other person
    ↓
Mate waits for that person to actually speak
    ↓
OTHER_PERSON ↔ MATE live voice conversation
    ↓
Mate asks the user privately only when a new decision is required
    ↓
Task completes with a factual outcome
```

Crew Mate does **not** currently integrate with Telegram, LINE, email, or other chat/messaging apps. There is no remote message send, background polling, fake reply provider, or outbound approval workflow.

## Shared Agent Harness

Crew Mate is an external consumer of the shared `crew-agent-harness` runtime.

```gradle
implementation 'com.github.magic76:crew-agent-harness:ba4f03a408'
```

The boundary is:

- **Agent Harness**: agent loop, tool dispatch, normalized events, interruption, model abstraction.
- **Crew Mate**: in-person communication state, speech audience, Gemini Live adapter, persistence, and UI.

Crew Mate does not copy `agent-core` source.

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
CommunicationSession
```

## Speech audience boundary

The physical handoff is explicit:

- `PRIVATE_TO_MATE`: the user is speaking privately to Mate.
- `EXTERNAL_WITH_MATE`: the other person is speaking to Mate.
- `MATE_HANDLING`: no microphone speaker is assigned.
- `USER_DIRECT`: the user has taken over the human conversation; Mate does not listen or speak.
- `IDLE`: no live conversation is active.

When `EXTERNAL_WITH_MATE` begins, Mate stays silent until the other person actually speaks. Model output produced before real external speech is neither played nor persisted as a conversation turn.

## Crew Mate tools

`CrewMateAgentSpec` exposes only the tools needed for the in-person flow:

- `find_contact`
- `request_user_input`
- `complete_task`

There is no `get_conversation`, `draft_message`, or `send_message` tool.

## Product state

`CommunicationSession` persists:

- target person
- communication goal
- private and external spoken turns
- pending user decision
- direct-control state
- task status
- outcome summary

Each visible conversation turn records sender, recipient, content, timestamp, and a simple received/info status.

## Voice turns

Gemini Live transcription and model text deltas are aggregated into complete turns before being persisted. Interrupted Mate speech is discarded rather than stored as a misleading half-message.

The external timeline is evidence-based:

1. real input transcript creates `OTHER_PERSON → MATE`
2. Mate may then answer aloud
3. that actual model response creates `MATE → OTHER_PERSON`

No other-person speech is invented.

## Trace privacy

`CrewMateRuntime` records metadata-only Agent Harness trace entries. Private message bodies and transcripts are not serialized into debug trace.

## Tests and build

CI resolves the remote Harness and runs:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

The tests cover:

- shared-Harness tool allowlisting
- in-person task/person resolution
- user-decision and completion tools
- speech audience microphone/playback policy
- private transcript routing
- real external transcript routing
- blocking model-only fake external turns
- fresh external-speech requirement after handoff changes
- turn aggregation and interruption behavior
- persisted completed / needs-user-input state
- trace privacy

## Run locally

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Then install the debug APK with Android Studio or ADB, enter a Gemini API key in Settings, brief Mate, and start an in-person handoff.
