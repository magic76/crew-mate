# Crew Mate

Crew Mate is a voice-first AI assistant for **in-person communication**. The user privately briefs Mate, then hands the phone to the other person. Mate listens through Gemini Live and responds aloud while keeping the user's private goal and constraints in context.

## Product flow

```text
User selects their language and the other person's language
    ↓
User privately briefs Mate by voice or text
    ↓
Mate builds a visible shared task consensus
    ↓
If material details are missing, Mate asks a clarification question
    ↓
User answers; Mate updates the same consensus
    ↓
Only after the consensus is ready can the user explicitly start the handoff
    ↓
OTHER_PERSON ↔ MATE live voice conversation
    ↓
User may tap "補充 context" at any time
    ↓
PRIVATE_TO_MATE accepts the new private context, then returns to the conversation
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

- `PRIVATE_TO_MATE`: the user explicitly opened briefing/context-update mode. Only this mode can add or change private user context, and Mate uses the selected user language.
- `EXTERNAL_WITH_MATE`: the other person is speaking to Mate. External microphone speech never becomes user context, and Mate replies in the selected other-person language.
- `MATE_HANDLING`: no microphone speaker is assigned.
- `USER_DIRECT`: the user has taken over the human conversation; Mate does not listen or speak.
- `IDLE`: no live conversation is active.

When `EXTERNAL_WITH_MATE` begins, Mate stays silent until the other person actually speaks. Model output produced before real external speech is neither played nor persisted as a conversation turn. To add constraints or corrections while the conversation is active, the user must explicitly enter `PRIVATE_TO_MATE` through the "補充 context" control.

## Crew Mate tools

`CrewMateAgentSpec` exposes only the tools needed for the in-person flow:

- `update_task_consensus`
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


## Conversation languages

Each task stores two independent language preferences:

- user language
- other-person language

The compact language control uses the form `🌐 user → other person`. Either side can use automatic detection. Language changes are pushed into the active Gemini Live session without recreating the task.


## Task consensus

Before Crew Mate can enter the external conversation, the user and Mate must share an explicit task consensus containing:

- target person
- purpose / desired outcome
- constraints and limits
- the boundary for when Mate must return to the user for a decision
- user and other-person languages

The agent may ask multiple concise clarification questions during the private briefing phase. Target + goal alone are not sufficient to make a task ready when material constraints remain ambiguous.

Any new `PRIVATE_TO_MATE` text or speech immediately invalidates the previous ready state. Mate must update the shared consensus again before the external handoff button is enabled. External speech never modifies private task consensus.
