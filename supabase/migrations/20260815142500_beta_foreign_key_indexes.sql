create index beta_issue_reports_tester_idx
    on public.beta_issue_reports (tester_code);
create index beta_issue_reports_attendance_idx
    on public.beta_issue_reports (linked_attendance_id)
    where linked_attendance_id is not null;
create index beta_lms_diagnostics_tester_idx
    on public.beta_lms_diagnostics (tester_code);
create index beta_report_attachments_report_idx
    on public.beta_report_attachments (report_id);
