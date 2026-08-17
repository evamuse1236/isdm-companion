alter table public.beta_installations
    add column support_name text
        check (support_name is null or length(btrim(support_name)) between 1 and 160),
    add column profile_confirmed_at timestamptz,
    add column support_name_deleted_at timestamptz;

comment on column public.beta_installations.support_name is
    'Tester-confirmed name visible only through the owner-authenticated beta dashboard.';
comment on column public.beta_installations.profile_confirmed_at is
    'Time at which the tester confirmed the LMS-detected beta profile.';
comment on column public.beta_installations.support_name_deleted_at is
    'Early-request or scheduled deletion time for the tester support name.';

create extension if not exists pg_cron with schema pg_catalog;
grant usage on schema cron to postgres;
grant all privileges on all tables in schema cron to postgres;

do $$
declare
    existing_job bigint;
begin
    for existing_job in
        select jobid from cron.job where jobname = 'beta-delete-expired-support-names'
    loop
        perform cron.unschedule(existing_job);
    end loop;
end
$$;

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
        where installation.support_name is not null
          and now() >= retention.delete_after;
    $job$
);
