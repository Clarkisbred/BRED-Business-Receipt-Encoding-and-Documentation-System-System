-- BRED shared Ducky AI configuration.
-- The Groq API key is intentionally never stored here.
create table if not exists public.ducky_ai_config (
  id smallint primary key default 1 check (id = 1),
  model text not null default 'openai/gpt-oss-120b',
  updated_by uuid references auth.users(id),
  updated_at timestamptz not null default now()
);

alter table public.ducky_ai_config enable row level security;

-- No client role has direct access. The du-- Edge Function uses the service-role
-- key only after validating the caller's authenticated profile and role.
revoke all on table public.ducky_ai_config from anon, authenticated;

insert into public.ducky_ai_config (id, model)
values (1, 'openai/gpt-oss-120b')
on conflict (id) do nothing;
