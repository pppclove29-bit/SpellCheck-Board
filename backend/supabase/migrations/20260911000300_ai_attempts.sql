-- AI 호출 시도 상한 (훈수 차감과 별개). 훈수 쿼터는 오류가 있을 때만 차감되므로, 이 상한이 없으면
-- 맞는 문장만 계속 보내 AI를 무제한 무료로 호출할 수 있다.

alter table public.ai_usage add column attempts int not null default 0 check (attempts >= 0);

-- Atomic: returns false once today's (KST) attempt cap is reached.
create or replace function public.try_ai_attempt(p_user_id uuid, p_free_limit int, p_pro_limit int) returns boolean
language plpgsql as $$
declare
  v_today date := public.kst_today();
  v_limit int := case when public.is_pro(p_user_id) then p_pro_limit else p_free_limit end;
begin
  insert into public.ai_usage (user_id, usage_date) values (p_user_id, v_today) on conflict do nothing;
  update public.ai_usage set attempts = attempts + 1
  where user_id = p_user_id and usage_date = v_today and attempts < v_limit;
  return found;
end $$;

revoke all on function public.try_ai_attempt(uuid, int, int) from public, anon, authenticated;
grant execute on function public.try_ai_attempt(uuid, int, int) to service_role;
