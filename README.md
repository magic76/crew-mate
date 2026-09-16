# Crew Mate

Crew Mate is a voice-first AI communication assistant. You tell Mate what outcome you want; Mate communicates on your behalf while keeping the external conversation visible and your private instructions separate.

## MVP flow

1. Speak privately to Crew Mate through Gemini Live.
2. Gemini calls `create_task` with the contact, goal, and private context.
3. Mate drafts and sends external messages through a `ChannelAdapter`.
4. `MockChannel` returns a deterministic simulated reply.
5. The external reply is injected back into the live session as trusted `EXTERNAL_MESSAGE` context.
6. Mate either continues the task, asks the user for a decision, or completes the task.
7. `DecisionGate` blocks money, sensitive data, cancellation, contracts, and other important commitments until explicit approval.

## Architecture

```text
User voice
   |
   v
MateLiveClient (Gemini Live)
   | function calls
   v
MateAgent
   |---- DecisionGate
   |---- CommunicationTask
   |
   v
ChannelAdapter
   |
   +---- MockChannel (MVP)
   +---- Telegram / Email / LINE adapters later
```

The app deliberately separates two surfaces:

- **Private voice conversation:** User <-> Crew Mate. This contains goals, preferences, and private constraints.
- **External conversation:** Crew Mate <-> Contact. Everything actually sent or received is shown here.

## Current tools

- `create_task`
- `update_task_context`
- `draft_message`
- `send_message`
- `ask_user`
- `complete_task`

## Run

Open the project in Android Studio, sync Gradle, and run the `app` module on Android 7.0+.

On first launch:

1. Enter a Gemini API key.
2. Tap **Start** and grant microphone permission.
3. Say something like: `幫我跟 Kevin 約明天下午的 meeting，最好三點以後。`
4. Watch the external conversation panel. The mock contact will reply automatically.

The Live implementation follows the same direct WebSocket/audio approach used in Crew Teacher: 16 kHz PCM microphone input, Gemini Live audio responses, input/output transcription, and function calls.

## Safety boundary

The model does not directly own communication side effects. `MateAgent` and `DecisionGate` decide whether a requested action is allowed. High-risk drafts remain visibly unsent until the user explicitly approves them.

## Next milestones

- Persist multiple tasks locally instead of one in-memory active task.
- Add foreground session/service handling and session resumption comparable to Crew Teacher.
- Add Contacts integration and contact disambiguation.
- Implement the first real channel adapter (Telegram or email).
- Add follow-up/reminder scheduling and task notifications.
- Add richer approval policies per contact/task.
