-- Expose the exact linked drive and its session/epoch binding to the sender so
-- Android can safely bound offline evidence. These fields are never returned
-- by the guest read RPC; the raw authenticated session identifier stays private.
do $$
declare
  v_definition text;
  v_old text;
  v_new text;
begin
  v_definition := pg_get_functiondef('public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text,boolean)'::regprocedure);
  -- SQL-editor-installed functions may retain Windows line endings.
  v_definition := replace(v_definition, E'\r\n', E'\n');
  v_old := $old$'expiresAt',v_share.expires_at,'endCondition',v_end_condition)$old$;
  v_new := $new$'expiresAt',v_share.expires_at,'endCondition',v_end_condition,
    'tripId',v_share.trip_id,
    'tripStartedAt',(select t.started_at from private.trips t where t.id=v_share.trip_id and t.user_id=p_user_id),
    'tripSessionMatchesCurrent',case when v_share.mode='drive_end' then exists(
      select 1 from private.trips t where t.id=v_share.trip_id and t.user_id=p_user_id
        and t.status='active' and t.confirmed_drive and t.drive_classification='driver'
        and t.started_session_id=p_session_id and t.sharing_epoch=v_share.sharing_epoch
        and v_share.sharing_epoch=p_sharing_epoch) else null end,
    'sharingEpoch',v_share.sharing_epoch)$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected share-create response projection was not found';
  end if;
  execute v_definition;
end;
$$;

do $$
declare
  v_definition text;
  v_old text;
  v_new text;
begin
  v_definition := pg_get_functiondef('public.opentrack_share_list(uuid,uuid)'::regprocedure);
  -- SQL-editor-installed functions may retain Windows line endings.
  v_definition := replace(v_definition, E'\r\n', E'\n');
  if position('tripStartedAt' in v_definition)=0 then
    if position($marker$case when s.mode='destination' then jsonb_build_object($marker$ in v_definition)>0 then
      v_old := $old$'endCondition',$old$;
      v_new := $new$'tripId',case when s.mode='drive_end' then s.trip_id else null end,
        'tripStartedAt',case when s.mode='drive_end' then (select t.started_at from private.trips t where t.id=s.trip_id and t.user_id=p_user_id) else null end,
        'tripSessionMatchesCurrent',case when s.mode='drive_end' then exists(
          select 1 from private.trips t where t.id=s.trip_id and t.user_id=p_user_id
            and t.status='active' and t.confirmed_drive and t.drive_classification='driver'
            and t.started_session_id=p_session_id and t.sharing_epoch=s.sharing_epoch and s.sharing_epoch=a.sharing_epoch) else null end,
        'sharingEpoch',s.sharing_epoch,
        'endCondition',$new$;
    else
      v_old := $old$'endedAt',s.ended_at,'endCondition',$old$;
      v_new := $new$'endedAt',s.ended_at,
        'destination',case when s.mode='destination' then jsonb_build_object('latitude',s.destination_latitude,'longitude',s.destination_longitude,'radiusMeters',s.destination_radius_meters) else null end,
        'tripId',case when s.mode='drive_end' then s.trip_id else null end,
        'tripStartedAt',case when s.mode='drive_end' then (select t.started_at from private.trips t where t.id=s.trip_id and t.user_id=p_user_id) else null end,
        'tripSessionMatchesCurrent',case when s.mode='drive_end' then exists(
          select 1 from private.trips t where t.id=s.trip_id and t.user_id=p_user_id
            and t.status='active' and t.confirmed_drive and t.drive_classification='driver'
            and t.started_session_id=p_session_id and t.sharing_epoch=s.sharing_epoch and s.sharing_epoch=a.sharing_epoch) else null end,
        'sharingEpoch',s.sharing_epoch,
        'endCondition',$new$;
    end if;
    if position(v_old in v_definition)>0 then
      v_definition := replace(v_definition,v_old,v_new);
    else
      raise exception 'Expected sender share-list projection was not found';
    end if;
  end if;
  v_definition := replace(v_definition,'After confirmed drive end','When a confirmed drive has five continuous minutes of credible stationary evidence');
  execute v_definition;
end;
$$;
