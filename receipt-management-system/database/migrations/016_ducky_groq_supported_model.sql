-- Replace the original shared default only when it is still configured.
-- Explicit Creator model choices are preserved.
update public.ducky_ai_config
set model = 'openai/gpt-oss-120b',
    updated_at = now()
where id = 1
  and model = 'llama-3.3-70b-versatile';
