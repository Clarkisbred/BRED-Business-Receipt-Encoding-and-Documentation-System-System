-- Cross-device Ducky memory stores only a bounded non-sensitive summary.
-- Reads and writes are performed by the authenticated ducky-ai Edge Function.
create table if not exists public.ducky_ai_memory (
  user_id uuid not null references auth.users(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  summary text not null default '',
  updated_at timestamptz not null default now(),
  primary key (user_id, company_id),
  constraint ducky_ai_memory_summary_length check (char_length(summary) <= 4000)
);

alter table public.ducky_ai_memory enable row level security;
revoke all on table public.ducky_ai_memory from anon, authenticated;
grant all on table public.ducky_ai_memory to service_role;
