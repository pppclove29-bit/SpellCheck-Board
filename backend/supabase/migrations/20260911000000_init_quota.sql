-- TypeRight: PRO entitlements, daily AI 훈수 quota (KST), AdMob reward ledger.
-- Accessed only by the backend with the service-role key. RLS is enabled with no policies,
-- and the functions are revoked from anon/authenticated, so clients (anon key) can't touch quota.

create table public.entitlements (
  user_id        uuid primary key references auth.users (id) on delete cascade,
  plan           text not null default 'free' check (plan in ('free', 'pro')),
  pro_expires_at timestamptz,
  store          text check (store in ('app_store', 'play_store')),
  updated_at     timestamptz not null default now()
);

create table public.ai_usage (
  user_id    uuid not null references auth.users (id) on delete cascade,
  usage_date date not null,
  used       int  not null default 0 check (used >= 0),
  bonus      int  not null default 0 check (bonus >= 0),
  primary key (user_id, usage_date)
);

create table public.ad_rewards (
  transaction_id text primary key,
  user_id        uuid not null references auth.users (id) on delete cascade,
  reward_date    date not null,
  amount         int  not null,
  created_at     timestamptz not null default now()
);
create index ad_rewards_user_date_idx on public.ad_rewards (user_id, reward_date);

alter table public.entitlements enable row level security;
alter table public.ai_usage     enable row level security;
alter table public.ad_rewards   enable row level security;

create or replace function public.kst_today() returns date
language sql stable as $$ select (now() at time zone 'Asia/Seoul')::date $$;

create or replace function public.is_pro(p_user_id uuid) returns boolean
language sql stable as $$
  select exists (
    select 1 from public.entitlements
    where user_id = p_user_id and plan = 'pro' and (pro_expires_at is null or pro_expires_at > now())
  )
$$;

create or replace function public.quota_json(p_is_pro boolean, p_limit int, p_used int, p_bonus int) returns jsonb
language sql immutable as $$
  select jsonb_build_object(
    'is_pro', p_is_pro, 'limit', p_limit, 'used', p_used, 'bonus', p_bonus,
    'remaining', greatest(0, p_limit + p_bonus - p_used)
  )
$$;

create or replace function public.ai_quota_status(p_user_id uuid, p_free_limit int, p_pro_limit int) returns jsonb
language plpgsql stable as $$
declare
  v_is_pro boolean := public.is_pro(p_user_id);
  v_used   int := 0;
  v_bonus  int := 0;
begin
  select used, bonus into v_used, v_bonus
  from public.ai_usage where user_id = p_user_id and usage_date = public.kst_today();
  return public.quota_json(v_is_pro, case when v_is_pro then p_pro_limit else p_free_limit end,
                           coalesce(v_used, 0), coalesce(v_bonus, 0));
end $$;

-- Atomic: the conditional UPDATE never lets `used` exceed limit + bonus under concurrency.
create or replace function public.consume_ai_quota(p_user_id uuid, p_free_limit int, p_pro_limit int) returns jsonb
language plpgsql as $$
declare
  v_today  date := public.kst_today();
  v_is_pro boolean := public.is_pro(p_user_id);
  v_limit  int := case when v_is_pro then p_pro_limit else p_free_limit end;
  v_used   int;
  v_bonus  int;
begin
  insert into public.ai_usage (user_id, usage_date) values (p_user_id, v_today) on conflict do nothing;
  update public.ai_usage set used = used + 1
  where user_id = p_user_id and usage_date = v_today and used < v_limit + bonus;
  select used, bonus into v_used, v_bonus
  from public.ai_usage where user_id = p_user_id and usage_date = v_today;
  return public.quota_json(v_is_pro, v_limit, v_used, v_bonus);
end $$;

-- Idempotent per AdMob transaction_id; at most p_max_per_day rewards per KST day.
create or replace function public.credit_ad_reward(p_user_id uuid, p_transaction_id text, p_amount int, p_max_per_day int) returns text
language plpgsql as $$
declare
  v_today date := public.kst_today();
begin
  perform pg_advisory_xact_lock(hashtext(p_user_id::text));
  if exists (select 1 from public.ad_rewards where transaction_id = p_transaction_id) then
    return 'duplicate';
  end if;
  if (select count(*) from public.ad_rewards where user_id = p_user_id and reward_date = v_today) >= p_max_per_day then
    return 'limit_reached';
  end if;
  insert into public.ad_rewards (transaction_id, user_id, reward_date, amount)
  values (p_transaction_id, p_user_id, v_today, p_amount);
  insert into public.ai_usage (user_id, usage_date, bonus) values (p_user_id, v_today, p_amount)
  on conflict (user_id, usage_date) do update set bonus = public.ai_usage.bonus + excluded.bonus;
  return 'credited';
end $$;

revoke all on function public.ai_quota_status(uuid, int, int)            from public, anon, authenticated;
revoke all on function public.consume_ai_quota(uuid, int, int)           from public, anon, authenticated;
revoke all on function public.credit_ad_reward(uuid, text, int, int)     from public, anon, authenticated;
revoke all on function public.is_pro(uuid)                               from public, anon, authenticated;
grant execute on function public.ai_quota_status(uuid, int, int)        to service_role;
grant execute on function public.consume_ai_quota(uuid, int, int)       to service_role;
grant execute on function public.credit_ad_reward(uuid, text, int, int) to service_role;
grant execute on function public.is_pro(uuid)                           to service_role;
