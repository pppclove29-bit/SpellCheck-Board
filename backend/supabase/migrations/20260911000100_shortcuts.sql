-- 사용자 단축어 동기화 (PRO 전용 저장). Clients talk to PostgREST directly with their own access token;
-- RLS enforces ownership, and writes additionally require an active PRO entitlement.

create table public.shortcuts (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null default auth.uid() references auth.users (id) on delete cascade,
  shortcut_key text not null check (char_length(shortcut_key) between 1 and 20),
  expansion    text not null check (char_length(expansion) between 1 and 500),
  updated_at   timestamptz not null default now(),
  unique (user_id, shortcut_key)
);

alter table public.shortcuts enable row level security;

-- Only answers for the caller (no user id parameter), so it is safe to expose to clients.
create or replace function public.current_user_is_pro() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.entitlements
    where user_id = auth.uid() and plan = 'pro' and (pro_expires_at is null or pro_expires_at > now())
  )
$$;
revoke all on function public.current_user_is_pro() from public, anon;
grant execute on function public.current_user_is_pro() to authenticated;

create policy "shortcuts: owner reads" on public.shortcuts
  for select to authenticated using (user_id = auth.uid());

create policy "shortcuts: pro owner inserts" on public.shortcuts
  for insert to authenticated with check (user_id = auth.uid() and public.current_user_is_pro());

create policy "shortcuts: pro owner updates" on public.shortcuts
  for update to authenticated using (user_id = auth.uid())
  with check (user_id = auth.uid() and public.current_user_is_pro());

-- Deleting stays allowed after PRO lapses so users can always clean up their data.
create policy "shortcuts: owner deletes" on public.shortcuts
  for delete to authenticated using (user_id = auth.uid());

create or replace function public.touch_updated_at() returns trigger
language plpgsql as $$ begin new.updated_at := now(); return new; end $$;

create trigger shortcuts_touch_updated_at before update on public.shortcuts
  for each row execute function public.touch_updated_at();
