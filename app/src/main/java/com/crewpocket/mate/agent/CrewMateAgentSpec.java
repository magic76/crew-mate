package com.crewpocket.mate.agent;

import com.magic76.crew.agent.AgentSpec;
import com.magic76.crew.agent.ToolSpec;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Crew Mate owns product behavior and tool exposure; the shared harness owns orchestration. */
public final class CrewMateAgentSpec implements AgentSpec {
    private final List<ToolSpec> tools = Collections.unmodifiableList(Arrays.asList(
            new ToolSpec(
                    "find_contact",
                    "Resolve the person the user wants Crew Mate to communicate with and capture the communication goal.",
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"goal\":{\"type\":\"string\"}},\"required\":[\"query\",\"goal\"]}"),
            new ToolSpec(
                    "get_conversation",
                    "Load the current external conversation thread with the resolved person.",
                    "{\"type\":\"object\",\"properties\":{}}"),
            new ToolSpec(
                    "draft_message",
                    "Prepare an exact outbound message. This never sends anything.",
                    "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"}},\"required\":[\"content\"]}"),
            new ToolSpec(
                    "send_message",
                    "Request that the current draft be sent. Crew Mate will hold this tool call until the user explicitly approves it.",
                    "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"}},\"required\":[\"content\"]}"),
            new ToolSpec(
                    "request_user_input",
                    "Ask the user for missing information, a decision, or clarification before continuing.",
                    "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"}},\"required\":[\"question\"]}"),
            new ToolSpec(
                    "complete_task",
                    "Mark the communication goal complete and save a concise outcome summary after the external conversation actually reached the goal.",
                    "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"]}")
    ));

    @Override public String id() { return "crew-mate"; }

    @Override
    public String systemPrompt() {
        return "You are Crew Mate, the user's private communication assistant. "
                + "The user tells you what they want to achieve; you help carry the conversation forward while the app shows every external message.\n\n"
                + "BOUNDARIES:\n"
                + "- The user-to-Mate conversation is private. Do not expose private instructions verbatim unless needed for the external goal.\n"
                + "- External messages and replies are product state, not hidden model context. Never invent an external reply.\n"
                + "- Start communication work by calling find_contact with the target person and a concise goal.\n"
                + "- Call get_conversation when existing thread context may matter.\n"
                + "- Always call draft_message before send_message.\n"
                + "- send_message is authorization-gated by the product. A send request may remain pending until the user approves it. Never claim it was sent before the tool succeeds.\n"
                + "- If information or a decision is missing, call request_user_input instead of guessing.\n"
                + "- After an EXTERNAL_MESSAGE system event, continue pursuing the approved goal, but any new outbound message still requires approval.\n"
                + "- When the communication goal is genuinely achieved, call complete_task with a concise factual outcome summary.\n"
                + "- Keep spoken turns short. The app already displays the full external conversation.\n"
                + "- Never put provider-specific behavior, approval rules, or product state assumptions into the shared agent runtime.";
    }

    @Override public List<ToolSpec> tools() { return tools; }
}
