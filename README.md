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


## Deterministic briefing finalize

The first-stage briefing no longer depends on the model guessing when the user is done speaking.

When the user taps **我交代完了 · 請 Mate 整理**, the runtime sends an explicit `FINALIZE_TASK_CONSENSUS` product event. Before that turn may finish, Mate must either:

1. update the shared consensus with `ready=true`, or
2. update it as incomplete and ask exactly one highest-priority clarification question.

If the model finishes without doing either, the runtime retries once. If the retry still produces no terminal consensus state, the product falls back to a visible clarification question instead of leaving the UI stuck indefinitely.

New private context invalidates the prior ready state and uses the same finalize path before handoff or before resuming an external conversation.


## Stage-two dialogue UI

Once the user hands the task to Mate, the external conversation becomes the primary screen:

- the large green "Mate is talking" card is hidden during normal live dialogue
- the full consensus card collapses into a compact header with target, language, goal, and payment preference when relevant
- the external transcript receives the remaining vertical space
- bottom controls are reduced to **補充**, **我要自己說**, and **結束**
- if Mate needs a consequential user decision, a single decision card interrupts the conversation instead of stacking multiple status banners

## Payment decision boundary

Task consensus can store a preferred payment method and any explicitly authorized fallback.

A different payment method is consequential by default. For example, if the user specified **credit card** and the merchant says **cash only**, Mate must call `request_user_input` unless cash was already explicitly authorized as a fallback. Mate must not assume the user has cash or accept a new payment method on the user's behalf.

The same escalation principle applies to deposits, identity documents, materially different prices, timing changes, and substitutions outside the shared consensus.


## Audio output mode

Crew Mate supports two playback modes for Gemini Live audio:

- **媒體**: uses Android media playback attributes (`USAGE_MEDIA`) and the normal media audio path.
- **通話**: uses communication playback attributes (`USAGE_VOICE_COMMUNICATION`) with `MODE_IN_COMMUNICATION`.

The selected mode is persisted globally and can be switched while a Live session is active. Switching recreates the `AudioTrack` with the new attributes without recreating the task or Gemini session. Closing the Live session restores Android's normal audio mode.

The compact top control displays `🔊 媒體` or `☎ 通話`; it remains available during stage two while the language control is hidden to preserve dialogue space.


## Live echo protection

Crew Mate uses a turn-taking guard around Gemini playback so Mate does not hear its own speaker output as new external speech:

- Android `AcousticEchoCanceler` is enabled when available on the active `AudioRecord` session.
- Android `NoiseSuppressor` is enabled when available.
- microphone frames are not uploaded while Mate audio is actively playing.
- a short playback-tail guard remains after speech to absorb speaker/room echo.

This intentionally favors stable in-person turn taking over barge-in while Mate is speaking.

## Optional payment consensus

Payment is not a required field for every task. `payment_preference` and `payment_fallback` stay empty for tasks that do not involve payment, and the UI hides payment rows entirely. The agent must not ask payment questions solely to fill the task consensus.

When payment is relevant, changing away from an explicitly selected payment method remains a consequential decision and must be escalated unless the fallback was already authorized.


## Context supplements keep the Live session connected

Second-stage private supplements are audience-routing changes inside the existing Gemini Live websocket.

- `EXTERNAL_WITH_MATE -> PRIVATE_TO_MATE` keeps the same local microphone/player when both modes own live audio.
- `PRIVATE_TO_MATE -> MATE_HANDLING -> EXTERNAL_WITH_MATE` may stop/restart local capture while Mate updates consensus, but does not send provider `audioStreamEnd`.
- Product-level interrupt / USER_DIRECT does not send `audioStreamEnd`.
- `audioStreamEnd` is reserved for closing the whole Gemini Live session.

This prevents a quick private context update from terminating the stage-two realtime conversation.


## One-shot private supplements

During stage two, tapping **補充** now starts a one-shot private context capture instead of a persistent private mode.

- the UI clearly shows that Mate is privately listening
- after the user stops speaking for about 1.4 seconds, the private transcript is committed
- Crew Mate switches to local handling and lets the current Gemini audio turn finish
- if the model already updates consensus, the app resumes the external conversation immediately
- otherwise the runtime forces a consensus finalize after that turn
- if clarification is needed, the conversation pauses for the user's decision instead of silently resuming
- once consensus is ready, the app automatically returns to `EXTERNAL_WITH_MATE`

The top call indicator distinguishes **AI 通話中**, **私下補充中**, and **Mate 正在更新補充** so websocket connectivity is not confused with conversational state.


## Spoken stage-one alignment

Stage one is a real private voice conversation between the user and Mate, not a text-only setup form.

- `PRIVATE_TO_MATE` remains active while Mate is clarifying the task.
- pressing **我交代完了** finalizes the current brief without muting Mate playback.
- if Mate needs more information, `request_user_input` still records the question in product state, but Mate must also say the same question aloud in the user's language.
- typed briefs also enter the private voice channel so Mate can answer aloud.
- only when task consensus becomes ready does the UI leave `PRIVATE_TO_MATE` and move to the silent confirmation screen before stage two.
