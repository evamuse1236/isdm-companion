-- A suspension belongs to the tester, so rotating an installation token cannot erase it.
alter table public.beta_installations
    add column access_suspended boolean not null default false,
    add column access_changed_at timestamptz,
    add column access_reason text check (access_reason is null or length(access_reason) <= 1000);

alter table public.beta_control_audit
    add column tester_code text references public.beta_installations(tester_code) on delete set null;
create index beta_control_audit_tester_created_idx
    on public.beta_control_audit (tester_code, created_at desc);
alter table public.beta_control_audit drop constraint beta_control_audit_action_check;
alter table public.beta_control_audit add constraint beta_control_audit_action_check
    check (action in ('stop_auto_attendance', 'allow_auto_attendance', 'suspend_access', 'restore_access'));

-- Service-role only, SECURITY INVOKER: authorization remains in the owner Edge Function.
-- The row lock, expected revision, state update and audit insert are one transaction.
create function public.set_beta_tester_access(
    p_tester_code text,
    p_suspended boolean,
    p_reason text,
    p_expected_changed_at timestamptz
) returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
    target public.beta_installations%rowtype;
    changed_at timestamptz := clock_timestamp();
begin
    if p_tester_code is null or p_tester_code !~ '^T-[0-9]{2}$' or p_suspended is null
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
