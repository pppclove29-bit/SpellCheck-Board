-- Play subscription entitlements: remember which purchase granted PRO so renewals can be re-verified
-- without real-time developer notifications (Pub/Sub).

alter table public.entitlements
  add column if not exists play_product_id    text,
  add column if not exists play_purchase_token text;

-- One purchase token belongs to exactly one account: blocks sharing a receipt across accounts.
create unique index if not exists entitlements_play_token_idx
  on public.entitlements (play_purchase_token)
  where play_purchase_token is not null;

create or replace function public.grant_play_pro(
  p_user_id        uuid,
  p_expires_at     timestamptz,
  p_product_id     text,
  p_purchase_token text
) returns void
language plpgsql security definer set search_path = public as $$
begin
  -- A token already bound to another account is a replay attempt; leave both accounts untouched.
  if exists (
    select 1 from public.entitlements
    where play_purchase_token = p_purchase_token and user_id <> p_user_id
  ) then
    raise exception 'purchase token already bound to another user' using errcode = '23505';
  end if;

  insert into public.entitlements (user_id, plan, pro_expires_at, store, play_product_id, play_purchase_token, updated_at)
  values (p_user_id, 'pro', p_expires_at, 'play_store', p_product_id, p_purchase_token, now())
  on conflict (user_id) do update set
    plan                = 'pro',
    pro_expires_at      = excluded.pro_expires_at,
    store               = 'play_store',
    play_product_id     = excluded.play_product_id,
    play_purchase_token = excluded.play_purchase_token,
    updated_at          = now();
end $$;

create or replace function public.revoke_pro(p_user_id uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  -- The purchase token is kept: a later renewal re-verifies against it.
  update public.entitlements
     set plan = 'free', pro_expires_at = null, updated_at = now()
   where user_id = p_user_id;
end $$;

create or replace function public.play_purchase(p_user_id uuid) returns jsonb
language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'product_id',     play_product_id,
    'purchase_token', play_purchase_token,
    'pro_expires_at', pro_expires_at
  )
  from public.entitlements
  where user_id = p_user_id
$$;

-- Clients (anon/authenticated) must never call these: only the backend's service-role key may.
revoke all on function public.grant_play_pro(uuid, timestamptz, text, text) from public, anon, authenticated;
revoke all on function public.revoke_pro(uuid)                             from public, anon, authenticated;
revoke all on function public.play_purchase(uuid)                          from public, anon, authenticated;
