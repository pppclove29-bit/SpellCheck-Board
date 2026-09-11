-- 월 AI 예산 가드: 월별(UTC, OpenAI 청구 주기) 추정 사용액 누적 + 경고/상한 알림 1회 발송 기록.
-- Backend-only (service role). RLS on, no policies; functions revoked from client roles.

create table public.ai_spend_monthly (
  month      text primary key check (month ~ '^[0-9]{4}-[0-9]{2}$'),
  cost_usd   numeric(12, 6) not null default 0 check (cost_usd >= 0),
  warned_at  timestamptz,
  capped_at  timestamptz,
  updated_at timestamptz not null default now()
);

alter table public.ai_spend_monthly enable row level security;

create or replace function public.ai_spend_total(p_month text) returns numeric
language sql stable as $$
  select coalesce((select cost_usd from public.ai_spend_monthly where month = p_month), 0)
$$;

-- Atomic increment; returns the new monthly total.
create or replace function public.add_ai_spend(p_month text, p_cost numeric) returns numeric
language sql as $$
  insert into public.ai_spend_monthly (month, cost_usd) values (p_month, p_cost)
  on conflict (month) do update
    set cost_usd = public.ai_spend_monthly.cost_usd + excluded.cost_usd, updated_at = now()
  returning cost_usd
$$;

-- True only for the first caller per (month, level), so each alert is sent exactly once.
create or replace function public.claim_budget_alert(p_month text, p_level text) returns boolean
language plpgsql as $$
begin
  if p_level = 'warn' then
    update public.ai_spend_monthly set warned_at = now() where month = p_month and warned_at is null;
  elsif p_level = 'cap' then
    update public.ai_spend_monthly set capped_at = now() where month = p_month and capped_at is null;
  else
    raise exception 'unknown alert level %', p_level;
  end if;
  return found;
end $$;

revoke all on function public.ai_spend_total(text)              from public, anon, authenticated;
revoke all on function public.add_ai_spend(text, numeric)       from public, anon, authenticated;
revoke all on function public.claim_budget_alert(text, text)    from public, anon, authenticated;
grant execute on function public.ai_spend_total(text)           to service_role;
grant execute on function public.add_ai_spend(text, numeric)    to service_role;
grant execute on function public.claim_budget_alert(text, text) to service_role;
