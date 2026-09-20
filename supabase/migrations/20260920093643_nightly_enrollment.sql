-- Keep legacy beta credentials and audit history; nightly registration creates new rows.
alter table public.beta_installations
    add column enrollment_channel text not null default 'beta'
        check (enrollment_channel in ('beta', 'nightly')),
    alter column invite_code_hash drop not null;
alter table public.beta_installations drop constraint beta_installations_tester_code_check;
alter table public.beta_installations add constraint beta_installations_tester_code_check
    check (tester_code ~ '^(T-[0-9]{2}|N-[0-9]{6,})$');
create sequence public.nightly_tester_number;
revoke all on sequence public.nightly_tester_number from public, anon, authenticated;
grant usage, select on sequence public.nightly_tester_number to service_role;
create index beta_nightly_created_idx on public.beta_installations(created_at)
    where enrollment_channel = 'nightly';

-- Service-only, atomic idempotency and a bounded daily enrollment quota.
create function public.register_nightly_installation(
    p_installation_id uuid, p_token_hash text, p_name text, p_section text,
    p_plc text, p_model text, p_version text, p_version_code integer
) returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
    existing public.beta_installations%rowtype;
    code text;
    number bigint;
begin
    if p_installation_id is null or p_token_hash is null or p_token_hash !~ '^[a-f0-9]{64}$'
        or p_name is null or length(trim(p_name)) not between 1 and 160
        or p_section is null or length(trim(p_section)) not between 1 and 40
        or (p_plc is not null and length(p_plc) > 120)
        or (p_model is not null and length(p_model) > 160)
        or (p_version is not null and length(p_version) > 80)
        or p_version_code is null or p_version_code < 16 then
        raise exception 'invalid_nightly_input' using errcode = '22023';
    end if;
    perform pg_advisory_xact_lock(719204113);
    select * into existing from public.beta_installations where installation_id = p_installation_id;
    if found then
        if existing.enrollment_channel <> 'nightly' or existing.install_token_hash is distinct from p_token_hash then
            return jsonb_build_object('error', 'registration_conflict');
        end if;
        if existing.access_suspended then return jsonb_build_object('error', 'access_suspended'); end if;
        return jsonb_build_object('tester_code', existing.tester_code, 'installation_id', existing.installation_id);
    end if;
    if (select count(*) from public.beta_installations where enrollment_channel = 'nightly'
        and created_at >= date_trunc('day', now())) >= 1000 then
        return jsonb_build_object('error', 'enrollment_limit');
    end if;
    number := nextval('public.nightly_tester_number');
    code := 'N-' || lpad(number::text, greatest(6,length(number::text)), '0');
    insert into public.beta_installations (tester_code, installation_id, install_token_hash,
        enrollment_channel, support_name, self_section, self_plc, model, app_version, app_version_code,
        consent_version, consented_at, profile_confirmed_at, claimed_at, first_seen_at, last_seen_at)
    values (code, p_installation_id, p_token_hash, 'nightly', trim(p_name), trim(p_section), p_plc,
        p_model, p_version, p_version_code, 'nightly-2026-09-20', now(), now(), now(), now(), now());
    return jsonb_build_object('tester_code', code, 'installation_id', p_installation_id);
end;
$$;
revoke all on function public.register_nightly_installation(uuid,text,text,text,text,text,text,integer)
    from public, anon, authenticated;
grant execute on function public.register_nightly_installation(uuid,text,text,text,text,text,text,integer)
    to service_role;

create or replace function public.set_beta_tester_access(
    p_tester_code text,
    p_suspended boolean,
    p_reason text,
    p_expected_changed_at timestamptz
) returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
    target public.beta_installations%rowtype;
    changed_at timestamptz := clock_timestamp();
begin
    if p_tester_code is null or p_tester_code !~ '^(T-[0-9]{2}|N-[0-9]{6,})$' or p_suspended is null
        or p_reason is null or length(trim(p_reason)) not between 3 and 1000 then
        raise exception 'invalid_access_input' using errcode = '22023';
    end if;
    select * into target from public.beta_installations
        where tester_code = p_tester_code for update;
    if not found then return jsonb_build_object('error', 'tester_not_found'); end if;
    if target.access_changed_at is distinct from p_expected_changed_at then
        return jsonb_build_object('error', 'access_changed');
    end if;
    if target.access_suspended = p_suspended then
        return jsonb_build_object('tester_code', target.tester_code,
            'access_suspended', target.access_suspended, 'access_changed_at', target.access_changed_at,
            'access_reason', target.access_reason);
    end if;
    update public.beta_installations set
        access_suspended = p_suspended, access_changed_at = changed_at,
        access_reason = trim(p_reason), updated_at = changed_at
        where tester_code = p_tester_code;
    insert into public.beta_control_audit (action, tester_code, reason, actor_label)
        values (case when p_suspended then 'suspend_access' else 'restore_access' end,
            p_tester_code, trim(p_reason), 'chatgpt_site_owner');
    return jsonb_build_object('tester_code', p_tester_code,
        'access_suspended', p_suspended, 'access_changed_at', changed_at,
        'access_reason', trim(p_reason));
end;
$$;
revoke all on function public.set_beta_tester_access(text, boolean, text, timestamptz)
    from public, anon, authenticated;
grant execute on function public.set_beta_tester_access(text, boolean, text, timestamptz)
    to service_role;

create or replace function public.set_beta_tester_auto_attendance(
    p_tester_code text,
    p_blocked boolean,
    p_reason text,
    p_expected_changed_at timestamptz
) returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
    target public.beta_installations%rowtype;
    changed_at timestamptz := clock_timestamp();
begin
    if p_tester_code is null or p_tester_code !~ '^(T-[0-9]{2}|N-[0-9]{6,})$' or p_blocked is null
        or p_reason is null or length(trim(p_reason)) not between 3 and 1000 then
        raise exception 'invalid_auto_attendance_input' using errcode = '22023';
    end if;
    select * into target from public.beta_installations
        where tester_code = p_tester_code for update;
    if not found then return jsonb_build_object('error', 'tester_not_found'); end if;
    if target.auto_attendance_changed_at is distinct from p_expected_changed_at then
        return jsonb_build_object('error', 'auto_attendance_changed');
    end if;
    if target.auto_attendance_blocked = p_blocked then
        return jsonb_build_object('tester_code', target.tester_code,
            'auto_attendance_blocked', target.auto_attendance_blocked, 'auto_attendance_changed_at', target.auto_attendance_changed_at,
            'auto_attendance_reason', target.auto_attendance_reason);
    end if;
    update public.beta_installations set
        auto_attendance_blocked = p_blocked, auto_attendance_changed_at = changed_at,
        auto_attendance_reason = trim(p_reason), updated_at = changed_at
        where tester_code = p_tester_code;
    insert into public.beta_control_audit (action, tester_code, reason, actor_label)
        values (case when p_blocked then 'stop_tester_auto_attendance' else 'allow_tester_auto_attendance' end,
            p_tester_code, trim(p_reason), 'chatgpt_site_owner');
    return jsonb_build_object('tester_code', p_tester_code,
        'auto_attendance_blocked', p_blocked, 'auto_attendance_changed_at', changed_at,
        'auto_attendance_reason', trim(p_reason));
end;
$$;
revoke all on function public.set_beta_tester_auto_attendance(text, boolean, text, timestamptz)
    from public, anon, authenticated;
grant execute on function public.set_beta_tester_auto_attendance(text, boolean, text, timestamptz)
    to service_role;

select cron.schedule(
    'beta-delete-expired-support-names',
    '17 0 * * *',
    $job$
        with retention as (
            select beta_ends_at + interval '30 days' as delete_after
            from public.beta_config
            where singleton = true
        )
        update public.beta_installations as installation
        set support_name = null,
            support_name_deleted_at = coalesce(installation.support_name_deleted_at, now()),
            updated_at = now()
        from retention
        where installation.enrollment_channel = 'beta'
          and installation.support_name is not null
          and now() >= retention.delete_after;
    $job$
);

select cron.schedule('nightly-delete-inactive-support-names', '23 0 * * *', $job$
    update public.beta_installations set support_name = null,
        support_name_deleted_at = now(), updated_at = now()
    where enrollment_channel = 'nightly' and support_name is not null
        and last_seen_at < now() - interval '180 days';
$job$);
