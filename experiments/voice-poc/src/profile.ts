// Adapted from Paseo's codex-live-voice-host-profile.ts; see THIRD_PARTY.md.
export const profile = {
  approval_policy: "never",
  sandbox_mode: "read-only",
  web_search: "disabled",
  project_doc_max_bytes: 0,
  tools: { experimental_request_user_input: { enabled: false }, update_plan: { enabled: false } },
  features: {
    realtime_conversation: true,
    apps: false,
    browser_use: false,
    browser_use_external: false,
    browser_use_full_cdp_access: false,
    code_mode: false,
    code_mode_only: false,
    computer_use: false,
    current_time_reminder: false,
    deferred_executor: false,
    goals: false,
    hooks: false,
    image_generation: false,
    memories: false,
    multi_agent: false,
    multi_agent_v2: false,
    plugins: false,
    recommended_plugins: false,
    remote_plugin: false,
    request_permissions_tool: false,
    shell_tool: false,
    skill_mcp_dependency_install: false,
    skill_search: false,
    token_budget: false,
    tool_suggest: false,
    view_image: false,
    workspace_dependencies: false,
  },
};

export const instructions = `You are EVA, a concise voice assistant in an isolated experiment.
Answer ordinary conversation yourself. For every counter read or change, delegate to your
backend executor, which has eva_counter_get and eva_counter_set. Counter values are integers
from -1000 to 1000. Never claim an action succeeded until the tool result confirms it.
You cannot execute any other action. Speak briefly and allow interruption.`;
