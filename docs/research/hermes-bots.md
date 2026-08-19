# Hermes Agent Bot Mode: capabilities, configuration, and operating guidance

Research date: 2026-08-19
Primary-source snapshot: Nous Research `hermes-agent` `main` at commit [`d07be6e`](https://github.com/NousResearch/hermes-agent/commit/d07be6e1650abaf68408e671946c445df9defcb8), whose package version is [`0.20.4`](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/pyproject.toml#L1-L6).

The Hermes mechanics below are verified against first-party Nous Research documentation and source. The corrected product interpretation also uses official Cursor and xAI documentation, Hermes maintainer posts on X, and clearly labelled community examples. Hermes is changing quickly, so the commit above is the local-source verification boundary.

## Correction after Grok and live online research

The initial version of this note understood the implementation correctly but designed the wrong
user experience. It treated Bots like a least-privilege engineering org chart: an Ops Lead routes
work to Reliability and Release specialists, backed by a standing control room and Kanban. That is
possible, but it is not the best mental model for Bot Mode and is not the recommended ISDM setup.

The product's own framing is simpler:

- Bot Mode is an alternative to Sessions mode: one canonical, persistent chat per profile/Bot.
- The Bots roster behaves like a contact list of durable relationships. Sessions remain the place
  for scratch conversations and unlimited parallel contexts.
- A Bot may have a job, but its main value is the context, memory, personality, tools, and history
  that accumulate in one forever-chat.
- Skills are the procedure layer. Bot identities should not duplicate project skills or Codex
  custom-agent roles.
- SOUL defines who the Bot is and how it thinks. It is guidance, not a security boundary or a
  substitute for project instructions.
- Group rooms are short, bounded deliberations. Kanban and routines are separate advanced
  primitives to adopt only when an actual durable workflow or schedule needs them.

This interpretation is explicit in maintainer posts: Bot Mode is “one chat with each agent profile”
and “just a UX” for profiles. The canonical chat never forks; Sessions mode is available when more
sessions are needed.

### Correct ISDM recommendation

Keep the existing default Hermes profile for general use and create **one ISDM Bot now**:

| Field | Recommendation |
|---|---|
| Profile id | `companion` |
| Display title | `ISDM Companion` |
| Relationship | The continuing product teammate for Android, LMS, Supabase, beta telemetry, incidents, implementation, explanation, and releases |
| Working directory | `/home/vishwasamsung/Linux Repos/isdm companion` |
| Procedures | Load the repository's existing `.agents/skills`; do not copy them into SOUL |
| Tools | The normal tools needed to research, diagnose, code, and verify; retain existing human approval boundaries for destructive device, release, and outward-message actions |
| Memory style | Keep project history together; explicitly distinguish confirmed facts, inference, and missing evidence |

Suggested SOUL direction:

```text
You are ISDM Companion: the continuing product teammate for the ISDM Companion Android/LMS beta.
Keep the product's history connected across design, incidents, implementation, telemetry, testing,
and releases. Be evidence-first. Separate confirmed facts, inference, and missing evidence. Prefer
the smallest verified change. Remember relevant versions, devices, testers, source-of-truth rules,
and unresolved risks. Ask before destructive phone changes, signing/deployment, Git publication,
or any outward message. Use the repository's project instructions and skills for procedures.
```

Do **not** instruct Companion to refuse coding or route every task to fictional job-title Bots. The
same relationship should be able to follow an incident into a fix, tests, and an honest release
decision without losing the product's oral history.

An optional second Bot, `skeptic`, is useful only if an independent cognitive stance proves valuable.
It should share the project context and procedures but challenge convenient diagnoses, weak evidence,
and premature release decisions. Mention it from Companion for a second opinion; do not treat it as
a ticket queue.

Do not initially create `isdm-ops`, `isdm-reliability`, `isdm-release`, `isdm-product`, a standing
Control Room, Kanban pipelines, or scheduled routines. Add one of those only after repeated use shows
that a genuinely separate durable relationship or workflow is needed.

### What people are doing on X

These posts are usage evidence, not product guarantees:

- [Teknium, 13 August 2026](https://x.com/Teknium/status/2088003994904113614) and
  [17 August 2026](https://x.com/Teknium/status/2089430781668303090): describes Bot Mode as an
  alternative to Sessions mode, with one chat per profile/Bot.
- [Teknium, 17 August 2026](https://x.com/Teknium/status/2089446733197738048): states that the
  underlying profiles already existed and Bot Mode is their UX.
- [0xz80, 18 August 2026](https://x.com/0xz80/status/2089809523288527266): uses a main Hermes agent
  broadly but created a work-only Bot so work memory, Slack, and email do not mix with personal
  context. This is the strongest directly transferable pattern for ISDM.
- [IBuzovskyi, 17 August 2026](https://x.com/IBuzovskyi/status/2089435972991656202): demonstrates
  that existing profiles appear as Bots with their config, skills, memory, and history intact.
- [witcheer, 18 August 2026](https://x.com/witcheer/status/2089611712198508791): shares an
  experimental racing-game setup where an HR Bot generates department Bots. This demonstrates the
  upper end of the concept, not a recommended starting fleet.
- [Teknium, 18–19 August 2026](https://x.com/Teknium/status/2089784829961458062): shows named group
  chats and profile pictures. These are useful for occasional decisions, not durable task routing.

### Cursor and Grok Bot comparison

“Cursor bot” currently conflates several different products:

| Product | Actual primitive | Relationship to Hermes Bot Mode |
|---|---|---|
| [Grok Bot](https://x.ai/news/introducing-grok-bot) | xAI's persistent named AI teammate distributed with eligible Grok/Cursor plans | Closest product analogy: a named continuing teammate |
| [Cursor Bugbot](https://cursor.com/docs/bugbot) | Pull-request reviewer that comments on bugs, security issues, and code quality | Review automation, not a forever-chat relationship |
| [Cursor Cloud Agents](https://cursor.com/docs/cloud-agent) | Disposable/parallel coding runs in isolated cloud VMs | Execution jobs, not durable Bot identities |
| [Cursor Automations](https://cursor.com/docs/cloud-agent/automations) | Scheduled or event-triggered Cloud Agent runs | Comparable to routines, not Bot Mode itself |
| [Cursor Subagents](https://cursor.com/docs/subagents) | Specialized delegated contexts with their own prompts, tools, and models | Comparable to Codex custom agents; closer to procedures/workers than Hermes Bots |

The important transfer is from Grok Bot: make a teammate worth returning to. Do not copy the cloud
VM, PR-review, or subagent execution model into the meaning of a Hermes Bot.

The old three-Bot design in sections 13–19 is retained below only as a superseded alternative for a
future high-volume multi-profile operation. It is **not** the current recommendation.

## Executive conclusion

Hermes Bot Mode is not a second agent framework or a new `bots:` configuration schema. A Bot **is a Hermes profile**. The desktop plugin adds a roster, one canonical Bot Chat per profile, routines, group rooms, avatars, and bot-to-bot delivery over the existing profile/session/cron primitives. Each Bot's real source of truth remains its profile directory under `~/.hermes/profiles/<name>/`, with its own config, identity, memory, skills, sessions, credentials, cron jobs, and state database. Bot Mode is built into Hermes Desktop and enabled by default; disabling the plugin removes only the UI, not the underlying data. [Official Bot Mode guide](https://hermes-agent.nousresearch.com/docs/user-guide/bot-mode), [official Profiles guide](https://hermes-agent.nousresearch.com/docs/user-guide/profiles/).

The most useful mental model is:

| Need | Hermes primitive | Why |
|---|---|---|
| A continuing relationship with its own identity and accumulated context | Bot/profile | Durable SOUL, memory, tools, credentials, history, and one canonical chat |
| A recurring responsibility | Bot routine / cron job | Scheduled execution attached to the responsible Bot |
| A bounded discussion among specialists | Bot Mode group chat | At most 2–6 Bots, three serial rounds, ten messages per send |
| A one-off handoff | `@bot` or `hermes peer dm` | Runs the recipient and returns an attributed reply |
| Durable multi-agent engineering work with dependencies, retry, review, and audit | Hermes Kanban | SQLite-backed work queue designed to survive restarts and human intervention |

The distinction in the last two rows matters. Hermes itself says Kanban, rather than ephemeral delegation, is for work that crosses agent boundaries, must survive restarts, may need human input, or needs a durable audit trail. [Official Kanban comparison](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/kanban.md#L22-L53).

## 1. What a Bot actually contains

A profile is a separate `HERMES_HOME`. The documented per-profile layout is conceptually:

```text
~/.hermes/profiles/<bot>/
├── config.yaml       # model, provider, toolsets, terminal, memory and other settings
├── .env              # secrets: API keys, external messaging bot tokens
├── SOUL.md           # primary identity and standing instructions
├── profile.yaml      # profile/display metadata, including Bot UI metadata
├── memories/         # MEMORY.md and USER.md
├── skills/           # this Bot's installed/created skills
├── sessions/         # compatibility/session material; canonical store is state.db
├── cron/             # this Bot's jobs and execution history
├── state.db          # session messages, metadata, routing and FTS5 search
└── logs/             # profile-local logs
```

The exact profile boundary and file roles are documented in the [Profiles guide](https://hermes-agent.nousresearch.com/docs/user-guide/profiles/) and [configuration directory map](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/configuration.md#L13-L31). Bot appearance metadata is stored under the Bot plugin's namespace in `profile.yaml`; avatar image bytes use the profile asset store rather than the list payload. [Official plugin source](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/apps/desktop/src/plugins/hermes-bots/plugin.js#L209-L281).

There is therefore no single declarative Bot manifest. Configuring a Bot means configuring several profile-owned surfaces:

| Concern | Source of truth | Desktop Bot Mode control |
|---|---|---|
| Canonical id | Profile directory/name | **Name** at creation |
| Human-facing identity | `profile.yaml` UI metadata | **Title**, description, avatar/pet |
| Role/persona | `SOUL.md` | Custom SOUL editor |
| Model | `config.yaml` | Model/provider pin, or inherit |
| General capabilities | `config.yaml` toolsets | Per-toolset toggles |
| Procedures | `skills/` | Per-skill toggles; optionally create empty |
| External integrations | `mcp.json` / profile MCP config and secrets | Per-MCP-server toggles |
| Persistent facts | `memories/MEMORY.md`, `USER.md` | Learned in use; editable/gateable separately |
| Conversations | `state.db` | Canonical Bot Chat plus profile sessions |
| Scheduled work | `cron/` | Routines pane |
| External chat identities | `.env` and gateway config | Configured outside the Bot creation dialog |

The Bot creation dialog exposes clone/fresh, empty skills, model/provider, full SOUL, individual skills, toolsets, MCP servers, and credential sharing; the same surface can edit a Bot later. [Official Bot creation documentation](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L30-L57).

## 2. User surfaces and supported channels

### Bot Mode's native surfaces

- **Hermes Desktop:** the Bots roster, Bot Chat, group rooms, Routines pane, New Agent/Edit Profile, multi-connection roster, and avatars. The plugin is built in and on by default. [Bot Mode overview](https://hermes-agent.nousresearch.com/docs/user-guide/bot-mode).
- **CLI:** every Bot remains accessible as a profile: `hermes -p <bot> chat`; its routines are ordinary `hermes cron` jobs; profile creation and inspection use `hermes profile`. [CLI parity table](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L141-L152).
- **Connected Hermes instances:** a Desktop can inventory profiles from local runtimes, remote gateways, SSH hosts, and Hermes Cloud connections. The work and state remain on the machine that owns the profile. [Bots across machines](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L127-L133).
- **Headless peer-to-peer:** `hermes peer dm` can address a remote gateway's default or named profile through its API server, without Desktop mediating. [Peer commands](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/reference/cli-commands.md#L443-L476).

### External messaging gateway platforms

A Bot/profile can also run a Hermes messaging gateway. This is separate from Desktop Bot Mode: it makes that same agent reachable from external chat platforms. The current official comparison lists:

Telegram, Discord, Slack, Google Chat, WhatsApp, WhatsApp Cloud API, Signal, SMS, Email, Home Assistant, Mattermost, Matrix, DingTalk, Feishu/Lark, WeCom, WeCom Callback, Weixin, BlueBubbles, Photon/iMessage, QQ, Yuanbao, Microsoft Teams, LINE, ntfy, Raft, IRC, Buzz, and SimpleX. Feature support for voice, images, files, threads, reactions, typing, and streaming differs by adapter. [Official platform capability table](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/messaging/index.md#L17-L54).

One gateway process can run several configured platform adapters at once. Each adapter routes messages into a per-chat session store, invokes the same agent runtime, and shares the gateway's cron scheduler. [Gateway architecture](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/messaging/index.md#L56-L117).

## 3. Configuration and commands

### Desktop creation path

1. Open **Bots** → **New Agent**.
2. Supply **Name**, **Title**, and **Description**.
3. Open **Advanced** to select clone/fresh, empty/bundled skills, model/provider, SOUL, individual skills, toolsets, MCP servers, and shared keys.
4. With multiple connections, use **Create on** to choose the machine that owns the profile.
5. Right-click → **Edit Profile** to change capabilities later. [Official creation flow](https://hermes-agent.nousresearch.com/docs/user-guide/bot-mode#creating-a-bot).

### CLI equivalents

```bash
# Create and inspect
hermes profile create researcher --description "Primary-source research and evidence reports"
hermes profile create release-sentinel --no-skills
hermes profile create tester --clone-from researcher
hermes profile list
hermes profile show researcher

# Address a Bot/profile
hermes -p researcher setup
hermes -p researcher chat
hermes -p researcher config set terminal.cwd "/absolute/project/path"
hermes -p researcher tools

# Gateway for external messaging (optional)
hermes -p researcher gateway setup
hermes -p researcher gateway install
hermes -p researcher gateway status

# Lifecycle
hermes profile rename researcher evidence-scout
hermes profile export evidence-scout
hermes profile delete evidence-scout   # destructive, confirms; default cannot be deleted
```

These commands and clone semantics are documented in the [Profiles guide](https://hermes-agent.nousresearch.com/docs/user-guide/profiles/) and [Profile Commands reference](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/reference/profile-commands.md).

### Configuration precedence

Hermes resolves ordinary settings as CLI arguments → profile `config.yaml` → profile `.env` → built-in defaults. Secrets belong in `.env`; model, terminal, memory, and toolset settings belong in `config.yaml`. [Official configuration precedence](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/configuration.md#L34-L71).

## 4. Per-Bot prompt and identity design

The main per-Bot identity control is `SOUL.md`. Hermes loads it as slot #1 of the system prompt at session start. `USER.md` and `MEMORY.md` are separate frozen memory snapshots; project instructions such as `.hermes.md`, `AGENTS.md`, `CLAUDE.md`, or `.cursorrules` are discovered from the working directory. Only one project-context type wins, while SOUL is always independent. [Official file-role map](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/which-file-does-what.md#L9-L44).

A useful `SOUL.md` should define the stable role, sources of truth, allowed actions, required evidence, escalation conditions, and response style. It should not be treated as a security control: the official Profiles guide explicitly says SOUL can guide the model but does not enforce a workspace boundary. Changes apply cleanly only to a new session. [Profiles versus sandboxing](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/profiles.md#L131-L152).

Bot Mode also injects a teammate-messaging protocol into **only** the canonical Bot Chat, without modifying SOUL or regular sessions. It is enabled by default:

```yaml
agent:
  bot_mode_protocol: true
```

[Official Bot protocol behavior](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L91-L108).

For an external messaging adapter, a channel can additionally override model/provider and use an ephemeral `system_prompt`. Exact channel/thread id wins, then parent channel; session `/model` still overrides the channel model. The channel prompt replaces the global gateway prompt for that channel and is injected per turn, not persisted. This is a channel routing feature, not the Bot's enduring identity. [Per-channel overrides](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/messaging/index.md#L294-L317).

## 5. Tools, skills, MCPs, and working directory

Tools are grouped into toolsets and can be enabled or disabled per platform/profile. Common sets cover web, terminal, files, browser, vision, image generation, skills, TTS, todo, memory, session search, cron, code execution, delegation, clarification, Home Assistant, messaging, and MCP servers. `hermes tools` is the supported interactive configurator. [Tools and Toolsets](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/tools.md#L7-L55).

External messaging presets currently expose full tools, including terminal, for nearly every platform. The API Server drops only `clarify` and TTS, and Raft is wake-only. This makes per-Bot capability reduction important. [Platform toolset table](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/messaging/index.md#L625-L655).

To enforce a toolset off everywhere in a profile, use the post-filter:

```yaml
agent:
  disabled_toolsets:
    - terminal
    - file
    - delegation
```

`agent.disabled_toolsets` applies after per-platform tool configuration, so a listed toolset cannot be re-enabled by a saved platform preset. [Global toolset disable](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/configuration.md#L749-L767).

Skills are profile-local on-demand procedure documents under `~/.hermes/skills/` or the named profile equivalent. New profiles normally receive bundled skills; `--no-skills`/Create empty starts minimal. Skills are invokable by slash command from CLI or messaging, and Hermes progressively loads the full skill only when needed. [Skills system](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/skills.md#L7-L48), [progressive disclosure](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/skills.md#L149-L160).

Set an absolute project directory explicitly for a project Bot:

```yaml
terminal:
  cwd: "/home/user/projects/project"
```

The Bot/profile directory is not automatically the terminal workspace. On the local backend, `cwd: "."` means the directory from which Hermes was launched. [Official workspace boundary explanation](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/profiles.md#L131-L152).

## 6. Routines, schedules, and pipelines

Bot Routines are ordinary profile-local Hermes cron jobs named `[bot:<name>] <routine>`. They appear in `hermes cron list`, and their runs land in the Bot's chat history. The Routines UI has a structured picker plus a raw Hermes schedule field. [Bot Routines](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L70-L75).

Cron supports one-shot and recurring schedules, pause/resume/edit/run/remove, attached skills, platform or local delivery, and no-agent script mode. Each normal run starts a fresh agent session. Model selection is per-job pin → `cron.model` → global default; the model-drift guard can fail an unpinned recurring job closed after the global model changes. [Cron capabilities and model resolution](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/cron.md#L7-L36).

```bash
hermes -p researcher cron create "every 1d at 08:00" \
  "Check the designated evidence sources and report only actionable changes" \
  --workdir "/absolute/project/path" \
  --name "morning evidence scan"

hermes -p researcher cron list
hermes -p researcher cron run "morning evidence scan"
hermes -p researcher cron pause "morning evidence scan"
```

A cron `workdir` loads the project's instruction file and scopes terminal/file/code tools to that directory. Workdir jobs are deliberately serialized because their cwd implementation is process-global. [Cron workdir behavior](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/cron.md#L156-L186).

Recurring jobs normally have no memory of their previous run's result. Turn on `continuity` to prepend the previous output, which is useful for deduplicating scouts and monitors. Upstream `context_from` can form multi-stage pipelines. The Bot Mode routine dialog exposes this continuity toggle. [Cron pipelines and continuity](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/cron.md#L610-L670).

By default cron-launched agents cannot create or edit cron jobs recursively. `cron.allow_agent_scheduling: true` opts into one flat user-owned table, but the official guide recommends listing/updating existing jobs rather than creating a new one each run. [Agent-managed scheduling](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/cron.md#L261-L288).

## 7. Sessions and memory

Each Bot has a pinned canonical Bot Chat. Within it, `/new` and `/reset` are remapped to `/compact`, preserving the same relationship and session id. Ordinary profile sessions retain normal `/new`. [Forever-chat semantics](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L16-L28).

Group rooms create a persistent `Group: <name>` session for each participating Bot. Group context therefore survives, but it is not one shared model context: each member retains its own session. [Group session behavior](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L76-L90).

Hermes' default profile memory has two bounded files injected as frozen snapshots at session start:

| Store | Purpose | Default cap |
|---|---|---:|
| `MEMORY.md` | Agent's environment/project/workflow notes | 2,200 characters |
| `USER.md` | User preferences and profile | 1,375 characters |

Writes persist immediately but do not alter the current session's frozen prompt snapshot. Session history is separately searchable from SQLite/FTS5 through `session_search`. [Persistent Memory](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/memory.md#L7-L67), [session search](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/memory.md#L159-L188).

Never run two agent processes against the same profile home. Hermes warns that both will automatically write and reload each other's memory, compounding state. Use one profile per Bot; use an external memory provider when deliberate sharing is required. [Official one-agent-per-home warning](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/profiles.md#L9-L15).

## 8. Permissions and security boundaries

### The profile boundary is not a sandbox

On the default local terminal backend, a Bot has the filesystem access of the operating-system user. A profile isolates Hermes state, not host files. Host profiles also keep the real OS `HOME` by default, so tools such as Git, SSH, GitHub CLI, cloud CLIs, npm, Codex, and Claude can find the same credentials. Use `terminal.home_mode: profile` for distinct per-profile CLI homes, and use a container/remote backend for actual execution isolation. [Profiles, HOME, and sandboxing](https://hermes-agent.nousresearch.com/docs/user-guide/profiles/#profiles-vs-workspaces-vs-sandboxing).

Hermes supports local, Docker, SSH, Singularity, Modal, Daytona, and Vercel Sandbox terminal backends. Docker/container hardening includes dropped capabilities, no privilege escalation, PID limits, namespaces, and volume-backed persistence, but forwarded environment variables are visible inside the session. [Terminal backends and container security](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/tools.md#L63-L85), [container security](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/tools.md#L187-L214).

### Dangerous-command approvals

The default `approvals.mode: smart` uses an auxiliary model to assess flagged commands; `manual` always asks, while `off` disables checks. User-defined `approvals.deny` globs still block even under YOLO/off. Hermes explicitly warns to disable checks only in trusted sandboxes. [Smart approvals and deny rules](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/configuration.md#L2329-L2370).

### Skill and memory mutation

Agent-created skill scanning is off by default, and both skill-write approval and memory-write approval default to false. For long-lived project Bots, enable explicit review:

```yaml
skills:
  guard_agent_created: true
  write_approval: true

memory:
  write_approval: true
```

Messaging writes are staged for `/skills pending` or `/memory pending` review when these gates are enabled. [Skill and memory write gates](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/configuration.md#L651-L684).

### External messaging authorization

The gateway denies users who are neither allowlisted nor DM-paired by default. Pairing codes expire after one hour and are rate-limited. However, the admin/user split currently gates **slash commands only**; regular users can still chat normally, and the documentation says future capability surfaces may be added later. Therefore, do not treat `allow_admin_from` as a tool-access boundary. Reduce toolsets or use a separate constrained Bot/profile instead. [Gateway security and current admin limitation](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/messaging/index.md#L318-L394).

### Cross-machine Bot security

Headless peer messaging requires the remote `api_server` gateway platform, network reachability, and a strong `API_SERVER_KEY`. Peer URLs are stored under `bot_peers` in `config.yaml`; peer keys live in `.env` as `HERMES_PEER_<NAME>_KEY`. [Official peer requirements](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L110-L126).

## 9. Deployment and day-two operations

Bot Mode itself is a bundled Desktop plugin and adds no separate Bot daemon. Profiles, sessions, memory, and cron remain core Hermes data even if the plugin is turned off. [Turning Bot Mode off](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L135-L150).

Routines and external messaging need the gateway daemon. On Linux, Hermes can install a per-profile user systemd service or a boot-time system service; on macOS it uses launchd. The official Docker image supervises per-profile gateways with s6-overlay. [Profile gateway services](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/profiles.md#L163-L190), [gateway service management](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/messaging/index.md#L551-L624).

Each profile running an external messaging gateway must have its own bot token. Token locks prevent simultaneous reuse for Telegram, Discord, Slack, WhatsApp, and Signal. [Token-lock behavior](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/profiles.md#L163-L190).

Useful operational commands:

```bash
hermes -p <bot> doctor
hermes -p <bot> gateway status
hermes -p <bot> cron status
hermes -p <bot> cron runs --limit 20
hermes sessions list
hermes profile list
```

Cron maintains a profile-local immutable attempt ledger with `claimed`, `running`, and terminal states, and it marks abandoned attempts `unknown` rather than automatically rerunning them. [Cron execution history](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/cron.md#L290-L329).

## 10. Current limitations and non-obvious constraints

1. **Desktop feature, profile core.** The roster/group-room experience is Desktop-only. CLI has profile and peer parity, but not the same visual roster. [Bot Mode overview and CLI parity](https://hermes-agent.nousresearch.com/docs/user-guide/bot-mode).
2. **No standalone Bot schema.** A Bot is a bundle of profile files and state, so configuration-as-code must manage a profile/distribution, not a hypothetical `bots.yaml`. [Profile distributions](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/profile-distributions.md).
3. **Group rooms are deliberately bounded and serial.** A room has 2–6 Bots, up to three serial rounds, and ten messages per user send. Bots may pass; not every member responds. This is deliberation, not parallel task execution. [Group chat limits](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L76-L90).
4. **No live mid-turn Bot interrupt.** Direct bot-to-bot delivery is per invocation; the recipient handles the message when it next runs. Live interruption is explicitly future work. [Bot-to-bot limitation](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L91-L108).
5. **Canonical Bot Chat never forks.** `/new` and `/reset` compact rather than create a new canonical conversation. Use ordinary profile sessions when a genuinely separate scratch context is required. [Forever-chat behavior](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L16-L28).
6. **Cron is fresh-session by default.** A routine does not automatically remember the preceding routine result; enable continuity or chain outputs explicitly. [Cron continuity](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/features/cron.md#L647-L670).
7. **Profiles do not enforce least privilege.** Local Bots share the OS user's file access, and usually the host CLI credential home. Toolsets, terminal backend, HOME mode, allowlists, and secrets must be configured deliberately. [Profile security boundary](https://hermes-agent.nousresearch.com/docs/user-guide/profiles/#profiles-vs-workspaces-vs-sandboxing).
8. **Messaging admin is not tool authorization.** Today the admin/user tier gates slash commands, not plain conversation or per-user agent tools. [Authorization scope](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/messaging/index.md#L360-L394).
9. **One writer per profile.** Two concurrent agents sharing one profile can corrupt the intended memory/persona through automatic writes. [One-agent-per-home warning](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/profiles.md#L9-L15).
10. **Cross-machine work keeps distributed state.** Chats, sessions, memory, and routines stay on the machine that owns each Bot; peers need reachable API servers and strong keys. [Cross-machine ownership](https://github.com/NousResearch/hermes-agent/blob/d07be6e1650abaf68408e671946c445df9defcb8/website/docs/user-guide/bot-mode.md#L127-L133).

## 11. Evidence-backed design guidance for an ISDM Bot roster

These are architectural inferences from the verified mechanics above; the final roster should be chosen after mapping the actual ISDM work streams.

- Make a Bot only for a **stable responsibility with durable context**, not every individual ticket. Profiles carry long-lived memory, skills, credentials, sessions, and routines, so excessive Bots create more state and permissions to govern.
- Give every Bot one concise role and one explicit working directory. Put identity, evidence standards, and escalation rules in SOUL; keep project-wide build/test/release rules in the repository's `AGENTS.md`/context file.
- Start constrained specialists with **Create empty**, then enable only the required skills, toolsets, MCP servers, and secrets. Use a tool-restricted orchestrator rather than giving every coordinator terminal/file access.
- Use a **group room** for short architecture debates, incident synthesis, or go/no-go discussion. Use **Kanban** for implementation, testing, review, and release pipelines that need dependencies, retries, worktrees, human comments, or durable audit.
- Use **routines** for source-of-truth scans, telemetry summaries, dependency/update reviews, stale-document checks, and release-readiness reports. Pin the model/provider and decide explicitly whether continuity is required.
- Separate read-only evidence/report Bots from mutation-capable engineering/release Bots. The strongest boundary is a separate profile plus reduced toolsets and an isolated terminal backend, not prose in SOUL.
- Do not expose a mutation-capable project Bot to a broadly accessible external messaging channel. If external chat is useful, use an allowlisted, tool-restricted front-door Bot and hand off approved work into the internal roster or Kanban board.

## 12. Primary-source index

- [Bot Mode](https://hermes-agent.nousresearch.com/docs/user-guide/bot-mode)
- [Profiles](https://hermes-agent.nousresearch.com/docs/user-guide/profiles/)
- [Messaging Gateway](https://hermes-agent.nousresearch.com/docs/user-guide/messaging/)
- [Tools and Toolsets](https://hermes-agent.nousresearch.com/docs/user-guide/features/tools/)
- [Skills](https://hermes-agent.nousresearch.com/docs/user-guide/features/skills/)
- [Persistent Memory](https://hermes-agent.nousresearch.com/docs/user-guide/features/memory/)
- [Sessions](https://hermes-agent.nousresearch.com/docs/user-guide/sessions/)
- [Cron](https://hermes-agent.nousresearch.com/docs/user-guide/features/cron/)
- [Kanban](https://hermes-agent.nousresearch.com/docs/user-guide/features/kanban/)
- [Security](https://hermes-agent.nousresearch.com/docs/user-guide/security/)
- [Official source tree](https://github.com/NousResearch/hermes-agent/tree/d07be6e1650abaf68408e671946c445df9defcb8)

## 13. Superseded alternative: ISDM Companion workstream map

The recent ISDM Companion task history and the current repository divide into four durable
responsibilities. They should not become four equally powerful Bots.

| Workstream | Repeated work | Existing project machinery | Bot treatment |
|---|---|---|---|
| Reliability and live incidents | Connected-phone diagnosis, attendance/location failures, LMS response-shape changes, parser regressions, background work, authentication retries, crash/ANR versus cached-process telemetry | `isdm-incident-response`, `isdm-lms-runtime-explainer`, `isdm_reliability_engineer`, `isdm_evidence_scout`, Android diagnostic and verification scripts | Dedicated specialist Bot; this is the largest and most evidence-sensitive lane |
| Beta release and tester operations | Cross-layer tests, version/release notes, permanent signing, `adb install -r`, Supabase roster reconciliation, invite handling, APK/WhatsApp preview, delivery status | `isdm-beta-release`, `isdm_release_sentinel`, APK/update scripts, `scripts/send-beta-release.mjs` | Dedicated gated Bot; no scheduled publishing or autonomous sends |
| Product planning and runtime explanation | Feature-idea refinement, onboarding/permissions, readings/profile design, explaining live requests versus refresh/cache/parser failure | `docs/feature-ideas.txt`, `isdm-lms-runtime-explainer` | Fold into the coordinator at first; split out only if this becomes a high-volume lane |
| Repository hygiene | Generated-artifact audit, recoverable cleanup, Git integrity, preservation of active work | `isdm-safe-cleanup` and its audit script | On-demand skill, not a permanent Bot |

The repository already has the valuable procedural layer: four project skills and three Codex
custom agents. Hermes should route into those assets rather than reproduce their instructions in
another set of drifting scripts.

## 14. Superseded three-Bot roster

This was the initial recommendation and should not be implemented now. It is retained as a design
reference only if one Companion relationship later becomes demonstrably overloaded.

### 14.1 `isdm-ops` — ISDM Operations Lead

**Purpose:** the owner's front door. It clarifies requests, distinguishes product questions from
incidents and releases, writes acceptance criteria, maintains Kanban, explains LMS/runtime behavior,
and synthesizes specialist results.

**Configuration**

- Create with **Create empty**; do not clone the broad default profile.
- Pin a strong reasoning model; the currently configured `gpt-5.6-sol` is suitable.
- Set `terminal.cwd` to `/home/vishwasamsung/Linux Repos/isdm companion` even if terminal begins
  disabled, so later explicit enablement starts in the correct repository.
- Enable: file, web, skills, todo, memory, session search, clarification, delegation, and cron.
- Keep disabled initially: terminal, code execution, browser/computer control, external messaging,
  image/audio/video tools, and unrelated productivity skills.
- Add only the planning/runtime procedures it needs. Use Hermes Kanban for durable work. When code or
  live evidence is required, assign the appropriate specialist rather than doing the work itself.
- Add the repository's `.agents/skills` as a profile `skills.external_dirs` source so the Bot reads
  the existing project procedures without copying them into a second, drifting skill tree.
- Do not share keys/accounts with the main profile.

**Standing SOUL**

```text
You are the ISDM Companion Operations Lead.

Companion is an LMS client. The LMS is the source of truth for LMS data.
Start by classifying the request as product/runtime explanation, reliability incident,
release/testing, or repository hygiene. State a testable outcome and route durable work through
Kanban. Use @isdm-reliability for device, LMS, parser, telemetry, and background-service incidents.
Use @isdm-release for build, signing, update, tester, and delivery work.

Do not edit code, deploy, sign, allocate invites, send messages, or change tester/device state.
Keep confirmed facts, inference, and missing evidence separate. Ask @user only for a decision that
materially changes scope, safety, or an outward action.
```

### 14.2 `isdm-reliability` — Reliability Engineer

**Purpose:** owns Android/LMS runtime diagnosis and authorized minimal fixes. It builds one evidence
chain across device state, private diagnostics, source/tests, and beta telemetry.

**Configuration**

- Create empty and pin a coding/reasoning model. `gpt-5.6-terra` at high reasoning is the efficient
  fit if that model is available to Hermes; otherwise use the current Sol model.
- Set the absolute ISDM repository as `terminal.cwd`.
- Enable: terminal/processes, file, code execution, skills, memory, session search, clarification,
  and delegation. Enable web only for official Android/LMS/library documentation.
- Disable: browser/computer control, external messaging, cron management by the agent, media tools,
  email/productivity skills, and mutation-capable MCP servers that are not required.
- Load `isdm-incident-response`, `isdm-lms-runtime-explainer`, `codex`, systematic debugging, and
  test-driven development. For implementation, invoke Codex from the repository and explicitly ask
  it to use the existing `isdm_reliability_engineer` or `isdm_evidence_scout` role.
- Point `skills.external_dirs` at the repository's `.agents/skills`; Bot Chat itself normally lives
  in the home workspace, so `terminal.cwd` alone is not a reliable project-skill loader.
- Keep shared accounts off. If host ADB is required, the local terminal remains powerful; retain
  approval checks and never run unattended phone-changing routines.

**Standing SOUL**

```text
You are the ISDM Companion Reliability Engineer.

First establish time window, app version, package, device, symptom, expected behavior, and source
of truth. Preserve the checkout and device state during the first evidence pass. Do not uninstall,
clear app data, clear logs, change permissions, force-stop, relaunch, or install a build unless the
user authorizes the relevant action. Source and fixture tests do not prove current live LMS behavior.

For attendance, separate the local location gate, LMS markability, Mark request, and LMS
confirmation. For data problems, separate request, refresh, cache, parser, and UI. Diagnose only
when asked to diagnose. When a fix is authorized, reproduce it with the smallest test, implement the
minimum change, and verify the owning contract. Never push or send external messages.
```

### 14.3 `isdm-release` — Beta Release Operator

**Purpose:** owns release preparation from versioning through a verified artifact and exact
distribution preview. It asks the existing independent Codex release sentinel for the go/no-go gate.

**Configuration**

- Create empty; pin the strongest reasoning model used for release work (`gpt-5.6-sol` is suitable).
- Set the absolute repository as `terminal.cwd`.
- Enable: terminal/processes, file, code execution, skills, memory, clarification, and delegation.
- Disable: browser/computer control, general external messaging, cron management by the agent, and
  unrelated skills/MCPs.
- Load `isdm-beta-release`, `codex`, and review/testing procedures. Every release must ask Codex to
  invoke the existing `isdm_release_sentinel` for an independent read-only gate.
- Point `skills.external_dirs` at the repository's `.agents/skills` instead of copying the release
  skill into the profile.
- Keep shared accounts off. Do not store signing passwords, beta invite plaintext, tester identifiers,
  WhatsApp JIDs, or Supabase admin secrets in SOUL or memory.
- Do not give this Bot an outward-send tool initially. Keep the existing approval-gated Codex +
  WhatsMeow path as the delivery operator.

**Standing SOUL**

```text
You are the ISDM Companion Beta Release Operator.

A release is not ready until the intentional diff, version, release notes, Android/backend/dashboard
contracts, tests, permanent signer identity, APK alignment/signatures, and old-to-new adb install -r
data preservation are verified. A WhatsApp upload is not evidence that a tester installed the app.

Preserve active work and tester data. Never push, reset, discard, uninstall a tester app, allocate or
rotate invites, deploy a function, or send a message unless that exact action is authorized. Before
distribution, show @user every recipient, exact attachment, digest, and full caption. Use the
isdm_release_sentinel for an independent go/no-go decision and stop on any blocker or uncertainty.
```

### 14.4 Optional later: `isdm-product`

Create this only if feature design and user-facing explanations become frequent enough to crowd the
Ops Lead's memory. It should be tool-light: file, web, skills, todo, memory, and clarification; no
terminal, Supabase, ADB, signing, or messaging credentials. It can own `docs/feature-ideas.txt`,
acceptance criteria, onboarding language, and Simplified Technical English explanations, but it
should not implement features.

## 15. Superseded coordination design

Create one group room named **ISDM Control Room** with `isdm-ops`, `isdm-reliability`, and
`isdm-release`.

- Use direct Bot chats for focused work and durable context.
- In the room, always @mention the one or two Bots needed. An unscoped message wakes everyone and
  increases duplication and cost.
- Use the room for incident synthesis, architecture trade-offs, and release go/no-go discussion.
- Use Kanban for implementation, test, review, retry, and release stages. Group rooms are capped
  serial discussions, not parallel workers.
- A good release chain is: reliability fix/evidence → release preparation → independent sentinel
  gate → `review-required` human approval → outward delivery in the existing Codex/WhatsMeow flow.
- A good incident chain is: Ops Lead writes the incident contract → Reliability collects evidence
  and fixes only if authorized → Ops Lead translates the result for the user and records remaining
  gaps.

## 16. Superseded routine proposal

Start with no routines for the first week. Validate each Bot interactively, then add only read-only
work.

| Owner | Routine | Suggested schedule | Continuity | Boundary |
|---|---|---|---|---|
| `isdm-reliability` | Beta health digest: group new failures by tester/version, distinguish LMS-confirmed results from telemetry, report only new actionable changes | `30 9,19 * * *` | On | Add only after a genuinely read-only telemetry credential or endpoint exists |
| `isdm-release` | Release-readiness scan: summarize unshipped commits, version/release-note mismatch, stale verification evidence, and blockers | `30 18 * * 1-5` | On | Read-only; no build, sign, deploy, invite allocation, or send |
| `isdm-ops` | Backlog grooming: compare feature ideas, changelog, and open Kanban work; propose priorities | `0 10 * * 1` | On | Suggestions only; no file or board mutation unless separately requested |

The current `beta-admin` secret protects both the dashboard GET and mutation endpoints. Do not put
that broad secret into an unattended monitoring Bot. First create a narrow read-only source (for
example, a restricted endpoint/RPC or a pre-exported owner-readable snapshot), then enable the
health routine.

Never schedule:

- APK signing, release deployment, Git push, invite allocation, or WhatsApp sends;
- ADB install/uninstall, app-data clearing, permission changes, or force-stop/relaunch;
- automatic source fixes based only on telemetry;
- cleanup or deletion, even when recoverable;
- memory/skill self-modification without review.

## 17. Superseded fleet safety configuration

Apply these to all three Bots:

1. **Create empty** and add capabilities explicitly.
2. **Shared keys/accounts off.** Authenticate only what the Bot needs.
3. Set the absolute repository `terminal.cwd`; remember that this is a start directory, not a
   sandbox.
4. Reuse the project procedures from their source of truth:

   ```yaml
   skills:
     external_dirs:
       - "/home/vishwasamsung/Linux Repos/isdm companion/.agents/skills"
   ```

   Keep write approval enabled so a Bot cannot silently edit those repository-owned procedures.
5. Keep dangerous-command approvals on. Add deny rules for push, destructive Git cleanup/reset,
   broad recursive deletion, device uninstall/data clear, and live sends/deploys where practical.
6. Turn on skill and memory write review:

   ```yaml
   skills:
     guard_agent_created: true
     write_approval: true
   memory:
     write_approval: true
   ```

7. Keep sensitive tester data and credentials out of memory and SOUL. Store only stable operating
   rules and non-sensitive project facts.
8. Do not expose these internal Bots through a broad Discord/Telegram/WhatsApp gateway. If a remote
   front door is later useful, create a fourth tool-restricted, allowlisted Bot that can only collect
   a report and open a review-required Kanban card.
9. For stronger execution isolation, use a container/remote terminal backend with only the repo
   mounted. Connected-device diagnosis is the exception: it needs host ADB and must remain an
   interactive, approval-gated workflow.

## 18. Superseded fleet rollout

### Phase 1 — profiles only

Create the three empty Bots, set title/description/SOUL/model/cwd, disable unrelated capabilities,
and leave all routines and external gateways off.

Acceptance checks:

- `hermes profile list` shows exactly the intended profiles.
- `isdm-ops` refuses to code or release and routes a sample incident correctly.
- `isdm-reliability` begins a sample phone incident with a read-only evidence pass.
- `isdm-release` produces a checklist and stops before any signing/deployment/send boundary.

### Phase 2 — handoffs

Create **ISDM Control Room** and test explicit @mentions. Create a harmless Kanban research card,
assign it to one Bot, block it for review, then unblock and complete it. Verify that durable work is
visible on the board rather than trapped in group-chat prose.

### Phase 3 — one routine at a time

Add the release-readiness scan first because it needs no live secret. Run it manually, inspect its
tool calls and output, then enable its schedule with continuity. Add telemetry monitoring only after
the read-only data boundary exists.

### Success criteria after two weeks

- Every incident report names the time window, app version/device, source of truth, confirmed facts,
  inference, and next decisive check.
- Every release has a separate sentinel go/no-go decision and no outward action before an exact
  approval preview.
- Routine reports contain new information rather than repeating the prior run.
- No routine changes source, device state, Supabase state, Git remote state, or external messages.
- Product/runtime questions are answered without waking engineering/release Bots unnecessarily.

## 19. Current local readiness

This machine already has Hermes Agent `0.20.4`, a running user gateway service with systemd linger,
and Bot Mode support in the installed Desktop source. At the time of this review, `hermes profile
list` showed only the `default` profile, so the proposed roster does not conflict with existing Bot
profiles. The repository's current Android/dashboard reliability edits are active work and must be
preserved while the profiles are created; creating profiles should not touch the repository.
