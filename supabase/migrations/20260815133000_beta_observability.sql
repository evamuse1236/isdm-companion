create table public.beta_installations (
    tester_code text primary key check (tester_code ~ '^T-[0-9]{2}$'),
    invite_code_hash text unique not null check (length(invite_code_hash) = 64),
    installation_id uuid unique,
    install_token_hash text check (install_token_hash is null or length(install_token_hash) = 64),
    consent_version text,
    consented_at timestamptz,
    self_section text,
    self_plc text,
    manufacturer text,
    model text,
    android_version text,
    app_version text,
    app_version_code integer,
    detected_sections text[] not null default '{}',
    detected_groups text[] not null default '{}',
    schedule_status text not null default 'not_asked'
        check (schedule_status in ('not_asked', 'confirmed', 'mismatch')),
    auto_attendance_enabled boolean,
    last_sync_status text,
    last_sync_at timestamptz,
    first_seen_at timestamptz,
    last_seen_at timestamptz,
    claimed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.beta_events (
    id bigint generated always as identity primary key,
    tester_code text not null references public.beta_installations(tester_code) on delete cascade,
    installation_id uuid not null,
    event_id uuid not null unique,
    event_type text not null check (length(event_type) between 1 and 80),
    occurred_at timestamptz not null,
    payload jsonb not null default '{}'::jsonb check (jsonb_typeof(payload) = 'object'),
    received_at timestamptz not null default now()
);

create index beta_events_tester_occurred_idx
    on public.beta_events (tester_code, occurred_at desc);
create index beta_events_type_occurred_idx
    on public.beta_events (event_type, occurred_at desc);

create table public.beta_schedule_confirmations (
    id bigint generated always as identity primary key,
    tester_code text not null references public.beta_installations(tester_code) on delete cascade,
    installation_id uuid not null,
    confirmed_at timestamptz not null,
    status text not null check (status in ('confirmed', 'mismatch')),
    selected_date date not null,
    app_session_count integer not null check (app_session_count >= 0),
    note text,
    snapshot jsonb not null default '{}'::jsonb check (jsonb_typeof(snapshot) = 'object'),
    received_at timestamptz not null default now()
);

create index beta_schedule_confirmations_tester_idx
    on public.beta_schedule_confirmations (tester_code, confirmed_at desc);

create table public.beta_attendance_decisions (
    id uuid primary key default gen_random_uuid(),
    event_id uuid not null unique,
    tester_code text not null references public.beta_installations(tester_code) on delete cascade,
    installation_id uuid not null,
    occurred_at timestamptz not null,
    method text not null check (method in ('auto', 'manual')),
    session_fingerprint text not null,
    session_label text,
    latitude double precision,
    longitude double precision,
    accuracy_m double precision,
    location_age_ms bigint,
    distance_m double precision,
    gate_allowed boolean,
    gate_reason text,
    lms_markable boolean,
    outcome text not null check (outcome in ('blocked', 'attempted', 'present', 'failed', 'unknown')),
    suspected_incorrect boolean not null default false,
    details jsonb not null default '{}'::jsonb check (jsonb_typeof(details) = 'object'),
    received_at timestamptz not null default now()
);

create index beta_attendance_tester_occurred_idx
    on public.beta_attendance_decisions (tester_code, occurred_at desc);
create index beta_attendance_suspected_idx
    on public.beta_attendance_decisions (suspected_incorrect, occurred_at desc);

create table public.beta_issue_reports (
    id uuid primary key default gen_random_uuid(),
    tester_code text not null references public.beta_installations(tester_code) on delete cascade,
    installation_id uuid not null,
    category text not null check (category in ('issue', 'suggestion', 'general')),
    title text not null check (length(title) between 1 and 160),
    description text not null check (length(description) between 1 and 8000),
    status text not null default 'new'
        check (status in ('new', 'seen', 'investigating', 'waiting_on_tester', 'resolved', 'wont_fix')),
    owner_note text,
    linked_attendance_id uuid references public.beta_attendance_decisions(id) on delete set null,
    app_context jsonb not null default '{}'::jsonb check (jsonb_typeof(app_context) = 'object'),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index beta_issue_reports_status_created_idx
    on public.beta_issue_reports (status, created_at desc);

create table public.beta_report_attachments (
    id uuid primary key default gen_random_uuid(),
    report_id uuid not null references public.beta_issue_reports(id) on delete cascade,
    storage_path text not null unique,
    content_type text not null,
    size_bytes integer not null check (size_bytes between 1 and 5242880),
    created_at timestamptz not null default now()
);

create table public.beta_lms_diagnostics (
    id uuid primary key default gen_random_uuid(),
    tester_code text not null references public.beta_installations(tester_code) on delete cascade,
    installation_id uuid not null,
    endpoint_label text not null,
    http_status integer,
    diagnostic jsonb not null check (jsonb_typeof(diagnostic) = 'object'),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '7 days')
);

create index beta_lms_diagnostics_expiry_idx
    on public.beta_lms_diagnostics (expires_at);

create table public.beta_config (
    singleton boolean primary key default true check (singleton),
    auto_attendance_blocked boolean not null default false,
    minimum_version_code integer not null default 1,
    admin_secret_hash text not null default '' check (admin_secret_hash = '' or length(admin_secret_hash) = 64),
    beta_starts_at timestamptz not null default '2026-08-17 00:00:00+05:30',
    beta_ends_at timestamptz not null default '2026-08-24 00:00:00+05:30',
    updated_at timestamptz not null default now()
);

insert into public.beta_config (singleton) values (true)
on conflict (singleton) do nothing;

create table public.beta_control_audit (
    id bigint generated always as identity primary key,
    action text not null check (action in ('stop_auto_attendance', 'allow_auto_attendance')),
    reason text,
    actor_label text not null default 'site_owner',
    created_at timestamptz not null default now()
);

alter table public.beta_installations enable row level security;
alter table public.beta_events enable row level security;
alter table public.beta_schedule_confirmations enable row level security;
alter table public.beta_attendance_decisions enable row level security;
alter table public.beta_issue_reports enable row level security;
alter table public.beta_report_attachments enable row level security;
alter table public.beta_lms_diagnostics enable row level security;
alter table public.beta_config enable row level security;
alter table public.beta_control_audit enable row level security;

revoke all on all tables in schema public from anon, authenticated, public;
revoke all on all sequences in schema public from anon, authenticated, public;
alter default privileges in schema public revoke all on tables from anon, authenticated, public;
alter default privileges in schema public revoke all on sequences from anon, authenticated, public;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'beta-evidence',
    'beta-evidence',
    false,
    5242880,
    array['image/jpeg', 'image/png', 'text/plain', 'application/json']
)
on conflict (id) do update set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;
