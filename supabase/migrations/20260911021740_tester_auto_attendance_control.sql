-- Owner automatic-attendance control is independent from the phone preference and app access.
alter table public.beta_installations
    add column auto_attendance_blocked boolean not null default false,
    add column auto_attendance_changed_at timestamptz,
    add column auto_attendance_reason text check (auto_attendance_reason is null or length(auto_attendance_reason) <= 1000);

alter table public.beta_control_audit drop constraint beta_control_audit_action_check;
alter table public.beta_control_audit add constraint beta_control_audit_action_check
    check (action in ('stop_auto_attendance', 'allow_auto_attendance', 'suspend_access', 'restore_access',
        'stop_tester_auto_attendance', 'allow_tester_auto_attendance'));

create function public.set_beta_tester_auto_attendance(
    p_tester_code text,
    p_blocked boolean,
    p_reason text,
    p_expected_changed_at timestamptz
) returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
    target public.beta_installations%rowtype;
    changed_at timestamptz := clock_timestamp();
begin
    if p_tester_code is null or p_tester_code !~ '^T-[0-9]{2}$' or p_blocked is null
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
