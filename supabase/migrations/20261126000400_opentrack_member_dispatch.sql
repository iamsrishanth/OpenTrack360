alter table private.api_records add column client_event_id uuid;
alter table private.api_records add column request_payload jsonb;
create unique index api_records_idempotency_idx
  on private.api_records(owner_id, resource_type, client_event_id) where client_event_id is not null;
create index location_samples_captured_at_idx on private.location_samples(captured_at);
alter table private.trips add column share_lock_screen_progress boolean not null default false;
alter table private.trips add column drive_classification text not null default 'driver' check (drive_classification in ('driver','passenger'));
alter table private.circles add column updated_at timestamptz not null default now();

create or replace function private.opentrack_purge_old_location_samples()
returns integer language plpgsql security definer set search_path = '' as $$
declare v_deleted integer;
begin
  with old_rows as (
    select ctid from private.location_samples
    where captured_at < now() - interval '30 days'
    order by captured_at asc limit 5000
  ), removed as (
    delete from private.location_samples s using old_rows o where s.ctid=o.ctid returning 1
  ) select count(*) into v_deleted from removed;
  return coalesce(v_deleted,0);
end;
$$;
revoke execute on function private.opentrack_purge_old_location_samples() from public, anon, authenticated;
grant execute on function private.opentrack_purge_old_location_samples() to service_role;

create or replace function public.opentrack_api_dispatch(
  p_user_id uuid, p_session_id uuid, p_operation text, p_args jsonb, p_sharing_epoch bigint
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  a private.member_accounts%rowtype;
  v_args jsonb := coalesce(p_args,'{}'::jsonb);
  v_path jsonb := coalesce(p_args->'path','{}'::jsonb);
  v_query jsonb := coalesce(p_args->'query','{}'::jsonb);
  v_body jsonb := coalesce(p_args->'body','{}'::jsonb);
  v_circle_id uuid;
  v_member_id uuid;
  v_place_id uuid;
  v_resource_id uuid;
  v_request_id uuid;
  v_client_event_id uuid;
  v_role text;
  v_type text;
  v_parent_id uuid;
  v_cursor uuid;
  v_limit integer := 100;
  v_has_more boolean := false;
  v_next uuid;
  v_data jsonb;
  v_payload jsonb;
  v_result jsonb;
  v_created timestamptz;
  v_lat double precision;
  v_lon double precision;
  v_accuracy double precision;
  v_radius integer;
  v_expires timestamptz;
  v_token text;
  v_token_hash text;
  v_invite private.circle_invites%rowtype;
  v_recovery_token private.pet_recovery_tokens%rowtype;
  v_consent private.guardian_consents%rowtype;
  v_circle private.circles%rowtype;
  v_cm private.circle_members%rowtype;
  v_bubble private.member_bubbles%rowtype;
  v_record private.api_records%rowtype;
  v_pet private.pets%rowtype;
  v_trip private.trips%rowtype;
  v_actor_username text;
  v_photo_path text;
  v_recovery_fields text[];
  v_target_account_id uuid;
  v_ids uuid[];
  v_status integer := 200;
begin
  if p_operation not in (
    'listCircles','createCircle','getCircle','updateCircle','leaveCircle','listCircleInvites','createCircleInvite','acceptCircleInvite',
    'listCircleMembers','updateCircleMember','removeCircleMember','getCircleMap','getCircleHistory','getActivitySharing','setCircleSharing',
    'listBubbles','createBubble','endBubble','listPlaces','createPlace','updatePlace','deletePlace','listPlaceRules','createPlaceRule','updatePlaceRule','deletePlaceRule',
    'listCheckins','createCheckin','listQuickNotes','createQuickNote','listEvents','createEvent','updateEventRsvp','setActivitySharing','getCircleActivity',
    'listMorningCheckIns','createMorningCheckIn','listTrips','startTrip','stopTrip','getDrivingReports','correctDriveClassification',
    'listNotifications','acknowledgeNotifications','listEmergencyContacts','inviteEmergencyContact','acceptEmergencyContactInvite',
    'createSosAlert','getSosAlert','cancelSosAlert','acknowledgeSosAlert',
    'listPets','getPet','createPet','updatePet','deletePet','listPetRoutines','createPetRoutine','listPetActivity','completePetRoutine',
    'reportLostPet','revokeLostPetReport','createPetRecoveryLink','revokePetRecoveryLink','createPetPhotoUpload',
    'requestGuardianConsent','approveGuardianConsent','requestDataExport','requestAccountDeletion'
  ) then
    return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found')));
  end if;

  select * into a from private.member_accounts where auth_user_id=p_user_id and status='active' for update;
  if a.account_id is null or a.reset_pending or not exists(
    select 1 from private.member_sessions s where s.session_id=p_session_id and s.user_id=p_user_id
      and s.session_epoch=a.session_epoch and s.revoked_at is null
  ) then
    return jsonb_build_object('httpStatus',401,'body',jsonb_build_object('error',jsonb_build_object('code','unauthorized','message','Unauthorized')));
  end if;
  if a.must_rotate_password then
    return jsonb_build_object('httpStatus',428,'body',jsonb_build_object('error',jsonb_build_object('code','password_rotation_required','message','Password rotation required')));
  end if;
  if p_sharing_epoch is distinct from a.sharing_epoch then
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','SHARING_EPOCH_MISMATCH','message','Refresh the member profile and discard queued samples from an older sharing epoch.')));
  end if;
  begin
    if coalesce(v_query->>'limit','') <> '' then v_limit := (v_query->>'limit')::integer; end if;
    if coalesce(v_query->>'cursor','') <> '' then v_cursor := (v_query->>'cursor')::uuid; end if;
    if coalesce(v_path->>'circleId','') <> '' then v_circle_id := (v_path->>'circleId')::uuid; end if;
    if coalesce(v_path->>'memberId','') <> '' then v_member_id := (v_path->>'memberId')::uuid; end if;
    if coalesce(v_path->>'placeId','') <> '' then v_place_id := (v_path->>'placeId')::uuid; end if;
    if coalesce(v_path->>'petId','') <> '' then v_resource_id := (v_path->>'petId')::uuid; end if;
  exception when others then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid request parameters')));
  end;
  if v_limit < 1 or v_limit > 1000 then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid page limit')));
  end if;

  if p_operation in ('listCircles','createCircle','acceptCircleInvite') then
    if p_operation='listCircles' then
      with page as (
        select c.id,c.name,c.kind,cm.role,c.created_at,c.expires_at,row_number() over(order by c.id desc) rn
        from private.circles c join private.circle_members cm on cm.circle_id=c.id
        where cm.user_id=p_user_id and cm.membership_state='active' and c.archived_at is null and (c.expires_at is null or c.expires_at>now())
          and (v_cursor is null or c.id<v_cursor)
        order by c.id desc limit v_limit+1
      )
      select coalesce(jsonb_agg(jsonb_build_object('id',p.id,'name',p.name,'kind',p.kind,'role',p.role,'createdAt',p.created_at,'expiresAt',p.expires_at)
        order by p.id desc) filter(where p.rn<=v_limit),'[]'::jsonb),coalesce(bool_or(p.rn>v_limit),false),
        (array_agg(p.id order by p.id desc) filter(where p.rn<=v_limit))[v_limit]
      into v_data,v_has_more,v_next from page p;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',case when v_has_more then v_next::text else null end));
    elsif p_operation='createCircle' then
      begin
        if length(trim(coalesce(v_body->>'name',''))) not between 1 and 80 then raise exception 'invalid'; end if;
        if coalesce(v_body->>'kind','standard') not in ('standard','temporary','lightweight') then raise exception 'invalid'; end if;
        v_expires := nullif(v_body->>'expiresAt','')::timestamptz;
      exception when others then
        return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid Circle details')));
      end;
      if coalesce(v_body->>'kind','standard')='temporary' and (v_expires is null or v_expires<=now() or v_expires>now()+interval '30 days') then
        return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Temporary Circle expiry must be within 30 days')));
      end if;
      insert into private.circles(name,kind,created_by,expires_at)
      values(trim(v_body->>'name'),coalesce(v_body->>'kind','standard'),p_user_id,v_expires) returning * into v_circle;
      insert into private.circle_members(circle_id,user_id,role) values(v_circle.id,p_user_id,'admin');
      return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('id',v_circle.id,'name',v_circle.name,'kind',v_circle.kind,'role','admin','createdAt',v_circle.created_at,'expiresAt',v_circle.expires_at));
    else
      v_token_hash := lower(coalesce(v_body->>'inviteTokenHash',''));
      if length(v_token_hash)<>64 or v_token_hash !~ '^[0-9a-f]{64}$' then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
      select * into v_invite from private.circle_invites where token_hash=v_token_hash for update;
      if not found or v_invite.revoked_at is not null or v_invite.expires_at<=now() or v_invite.uses>=v_invite.max_uses then
        return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found')));
      end if;
      select * into v_circle from private.circles where id=v_invite.circle_id and archived_at is null and (expires_at is null or expires_at>now());
      if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
      insert into private.circle_members(circle_id,user_id,role,membership_state) values(v_circle.id,p_user_id,v_invite.role,'active')
        on conflict(circle_id,user_id) do update set role=excluded.role,membership_state='active',joined_at=now();
      update private.circle_invites set uses=uses+1 where id=v_invite.id;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('joined',true,'circleId',v_circle.id,'role',v_invite.role));
    end if;
  end if;

  if p_operation in ('getCircle','updateCircle','leaveCircle','listCircleInvites','createCircleInvite','listCircleMembers','updateCircleMember','removeCircleMember',
    'getCircleMap','getCircleHistory','getActivitySharing','setCircleSharing','listBubbles','createBubble','endBubble','listPlaces','createPlace','updatePlace','deletePlace',
    'listPlaceRules','createPlaceRule','updatePlaceRule','deletePlaceRule','listCheckins','createCheckin','listQuickNotes','createQuickNote',
    'listEvents','createEvent','updateEventRsvp','setActivitySharing','getCircleActivity','listMorningCheckIns','createMorningCheckIn',
    'listTrips','startTrip','stopTrip','getDrivingReports','correctDriveClassification','listEmergencyContacts','inviteEmergencyContact','createSosAlert','getSosAlert','cancelSosAlert','acknowledgeSosAlert') then
    if v_circle_id is null then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Circle id is required')));
    end if;
    select cm.role into v_role from private.circle_members cm join private.circles c on c.id=cm.circle_id
      where cm.circle_id=v_circle_id and cm.user_id=p_user_id and cm.membership_state='active' and c.archived_at is null
        and (c.expires_at is null or c.expires_at>now());
    if not found then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
  end if;

  if p_operation='getCircle' then
    select * into v_circle from private.circles where id=v_circle_id and archived_at is null;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('id',v_circle.id,'name',v_circle.name,'kind',v_circle.kind,'role',v_role,'createdAt',v_circle.created_at,'expiresAt',v_circle.expires_at));
  elsif p_operation='updateCircle' then
    if v_role<>'admin' then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
    update private.circles set name=coalesce(nullif(trim(v_body->>'name'),''),name),updated_at=now()
      where id=v_circle_id returning * into v_circle;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('id',v_circle.id,'name',v_circle.name,'kind',v_circle.kind,'role',v_role,'createdAt',v_circle.created_at,'expiresAt',v_circle.expires_at));
  elsif p_operation='leaveCircle' then
    if v_role='admin' and (select count(*) from private.circle_members where circle_id=v_circle_id and role='admin' and membership_state='active')=1 then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','last_admin','message','Promote another Circle administrator before leaving')));
    end if;
    update private.circle_members set membership_state='left',sharing_enabled=false,activity_sharing_enabled=false where circle_id=v_circle_id and user_id=p_user_id;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('ok',true,'requestId',null));
  elsif p_operation='listCircleInvites' then
    if v_role<>'admin' then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
    select coalesce(jsonb_agg(jsonb_build_object('id',i.id,'role',i.role,'expiresAt',i.expires_at,'maxUses',i.max_uses,'uses',i.uses,'createdAt',i.created_at)
      order by i.created_at desc),'[]'::jsonb) into v_data from private.circle_invites i where i.circle_id=v_circle_id and i.revoked_at is null and i.expires_at>now();
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
  elsif p_operation='createCircleInvite' then
    if v_role<>'admin' then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
    begin v_request_id := (v_body->>'clientRequestId')::uuid; exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','A request id is required'))); end;
    if exists(select 1 from private.circle_invites where circle_id=v_circle_id and created_by=p_user_id and client_request_id=v_request_id) then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','request_replayed','message','This invite request was already processed; create a new request to rotate the token')));
    end if;
    v_token := rtrim(translate(encode(extensions.gen_random_bytes(32),'base64'),'+/','-_'),'=');
    if coalesce(v_body->>'role','member') not in ('admin','member') then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid invite role'))); end if;
    begin
      v_expires := coalesce(nullif(v_body->>'expiresAt','')::timestamptz,now()+interval '72 hours');
      v_limit := greatest(1,least(coalesce((v_body->>'maxUses')::integer,1),100));
    exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid invite details')));
    end;
    if v_expires<=now() or v_expires>now()+interval '72 hours' then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invite expiry must be within 72 hours'))); end if;
    insert into private.circle_invites(circle_id,token_hash,created_by,role,client_request_id,expires_at,max_uses)
      values(v_circle_id,encode(extensions.digest(convert_to(v_token,'UTF8'),'sha256'),'hex'),p_user_id,coalesce(v_body->>'role','member'),v_request_id,v_expires,v_limit)
      on conflict(circle_id,created_by,client_request_id) where client_request_id is not null do nothing returning * into v_invite;
    if not found then return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','request_replayed','message','This invite request was already processed'))); end if;
    return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('id',v_invite.id,'circleId',v_circle_id,'inviteToken',v_token,'role',v_invite.role,'expiresAt',v_invite.expires_at,'maxUses',v_invite.max_uses,'createdAt',v_invite.created_at));
  elsif p_operation='listCircleMembers' then
    select coalesce(jsonb_agg(jsonb_build_object('memberId',m.user_id,'username',a.username_normalized,'displayName',a.display_name,'role',m.role,
      'sharingEnabled',m.sharing_enabled,'activitySharingEnabled',m.activity_sharing_enabled,'precision',m.precision,'joinedAt',m.joined_at) order by m.joined_at),'[]'::jsonb)
      into v_data from private.circle_members m join private.member_accounts a on a.auth_user_id=m.user_id
      where m.circle_id=v_circle_id and m.membership_state='active';
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
  elsif p_operation='updateCircleMember' then
    v_member_id := (v_path->>'memberId')::uuid;
    if (v_body ? 'sharingEnabled' or v_body ? 'activitySharingEnabled') then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Use the member consent routes to change sharing')));
    end if;
    if not (v_body ? 'role' or v_body ? 'precision') then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','No member setting supplied'))); end if;
    if v_body ? 'role' and (v_role<>'admin' or v_member_id=p_user_id) then
      return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Only Circle admins may change another member role')));
    end if;
    if v_body ? 'precision' and v_member_id<>p_user_id then
      return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Members can change only their own location precision')));
    end if;
    if v_body ? 'role' and (v_body->>'role' not in ('admin','member') or exists(select 1 from private.circle_members where circle_id=v_circle_id and user_id=v_member_id and role='admin')
      and v_body->>'role'='member' and (select count(*) from private.circle_members where circle_id=v_circle_id and role='admin' and membership_state='active')=1) then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','last_admin','message','A Circle must retain an administrator')));
    end if;
    if v_body ? 'precision' and v_body->>'precision' not in ('precise','approximate','hidden') then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid location precision')));
    end if;
    update private.circle_members set role=coalesce(v_body->>'role',role),precision=coalesce(v_body->>'precision',precision),updated_at=now()
      where circle_id=v_circle_id and user_id=v_member_id and membership_state='active' returning * into v_cm;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('circleId',v_circle_id,'memberId',v_member_id,'role',v_cm.role,'precision',v_cm.precision,'updatedAt',v_cm.updated_at));
  elsif p_operation='removeCircleMember' then
    if v_role<>'admin' then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
    v_member_id := (v_path->>'memberId')::uuid;
    if v_member_id=p_user_id and v_role='admin' and (select count(*) from private.circle_members where circle_id=v_circle_id and role='admin' and membership_state='active')=1 then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','last_admin','message','Promote another Circle administrator first')));
    end if;
    if exists(select 1 from private.circle_members where circle_id=v_circle_id and user_id=v_member_id and role='admin' and membership_state='active')
      and (select count(*) from private.circle_members where circle_id=v_circle_id and role='admin' and membership_state='active')=1 then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','last_admin','message','A Circle must retain an administrator')));
    end if;
    update private.circle_members set membership_state='removed',sharing_enabled=false,activity_sharing_enabled=false,updated_at=now() where circle_id=v_circle_id and user_id=v_member_id;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('ok',true,'requestId',null));
  elsif p_operation='setCircleSharing' then
    update private.circle_members set sharing_enabled=coalesce((v_body->>'sharingEnabled')::boolean,false),updated_at=now() where circle_id=v_circle_id and user_id=p_user_id and membership_state='active' returning * into v_cm;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('circleId',v_circle_id,'sharingEnabled',v_cm.sharing_enabled,'updatedAt',v_cm.updated_at));
  elsif p_operation='setActivitySharing' then
    update private.circle_members set activity_sharing_enabled=coalesce((v_body->>'activitySharingEnabled')::boolean,false),updated_at=now() where circle_id=v_circle_id and user_id=p_user_id and membership_state='active' returning * into v_cm;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('circleId',v_circle_id,'activitySharingEnabled',v_cm.activity_sharing_enabled,'updatedAt',v_cm.updated_at));
  elsif p_operation='getActivitySharing' then
    select * into v_cm from private.circle_members where circle_id=v_circle_id and user_id=p_user_id and membership_state='active';
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('circleId',v_circle_id,'activitySharingEnabled',v_cm.activity_sharing_enabled,'updatedAt',v_cm.updated_at));
  elsif p_operation='getCircleMap' then
    select coalesce(jsonb_agg(jsonb_build_object(
      'memberId',m.user_id,'displayName',ma.display_name,
      'sharingState',case when not m.sharing_enabled then 'paused'
        when m.precision='hidden' then 'hidden'
        when ll.user_id is null or ll.captured_at<now()-interval '90 seconds' then 'signal_lost' else 'sharing' end,
      'location',case when not m.sharing_enabled or m.precision='hidden'
          or ll.user_id is null or ll.captured_at<now()-interval '90 seconds' then null
        else jsonb_build_object(
          'latitude',case when b.id is not null or m.precision='approximate' then round(coalesce(b.latitude,ll.latitude)::numeric,2)::double precision else ll.latitude end,
          'longitude',case when b.id is not null or m.precision='approximate' then round(coalesce(b.longitude,ll.longitude)::numeric,2)::double precision else ll.longitude end,
          'accuracyMeters',case when b.id is not null or m.precision='approximate' then greatest(ll.accuracy_meters,coalesce(b.radius_meters,1000)) else ll.accuracy_meters end,
          'capturedAt',ll.captured_at,'precision',case when b.id is not null or m.precision='approximate' then 'bubble_approximate' else 'precise' end)
        end,
      'batteryPercent',null,
      'activity',case when m.activity_sharing_enabled and ma.activity_sharing_enabled and ll.user_id is not null and ll.captured_at>=now()-interval '90 seconds'
        then case when ll.speed_mps>=4.5 then 'driving' when ll.speed_mps>=0.8 then 'walking' when ll.speed_mps is not null then 'stationary' else 'unknown' end else null end
    ) order by ma.display_name),'[]'::jsonb) into v_data
    from private.circle_members m
    join private.member_accounts ma on ma.auth_user_id=m.user_id and ma.status='active'
    left join private.latest_locations ll on ll.user_id=m.user_id and ll.sharing_epoch=ma.sharing_epoch
    left join lateral (select mb.* from private.member_bubbles mb where mb.circle_id=v_circle_id and mb.user_id=m.user_id
      and mb.ended_at is null and mb.expires_at>now() order by mb.created_at desc limit 1) b on true
    where m.circle_id=v_circle_id and m.membership_state='active';
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('serverTime',now(),'data',v_data));
  elsif p_operation='getCircleHistory' then
    begin
      v_member_id := coalesce(nullif(v_query->>'memberId','')::uuid,p_user_id);
      v_expires := (v_query->>'from')::timestamptz;
      v_created := (v_query->>'to')::timestamptz;
    exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid history range')));
    end;
    if v_created<=v_expires or v_created-v_expires>interval '90 days' then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid history range')));
    end if;
    select cm.precision into v_role from private.circle_members cm where cm.circle_id=v_circle_id and cm.user_id=v_member_id and cm.membership_state='active';
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    if v_role='hidden' or (select not sharing_enabled from private.circle_members where circle_id=v_circle_id and user_id=v_member_id) then
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data','[]'::jsonb,'nextCursor',null));
    end if;
    with page as (
      select s.sample_id,s.latitude,s.longitude,s.accuracy_meters,s.speed_mps,s.captured_at,
        (v_role='approximate' or exists(select 1 from private.member_bubbles b where b.circle_id=v_circle_id and b.user_id=v_member_id
          and b.created_at<=s.captured_at and s.captured_at<coalesce(b.ended_at,b.expires_at))) as approximate,
        row_number() over(order by s.sample_id desc) rn
      from private.location_samples s join private.member_accounts ma on ma.auth_user_id=s.user_id and ma.sharing_epoch=s.sharing_epoch and ma.status='active'
      where s.user_id=v_member_id and s.captured_at>=greatest(v_expires,now()-interval '30 days') and s.captured_at<=v_created
        and (v_cursor is null or s.sample_id<v_cursor)
      order by s.sample_id desc limit v_limit+1
    )
    select coalesce(jsonb_agg(jsonb_build_object('sampleId',p.sample_id,
      'latitude',case when p.approximate then round(p.latitude::numeric,2)::double precision else p.latitude end,
      'longitude',case when p.approximate then round(p.longitude::numeric,2)::double precision else p.longitude end,
      'accuracyMeters',case when p.approximate then greatest(p.accuracy_meters,1000) else p.accuracy_meters end,
      'capturedAt',p.captured_at,'speedMps',case when p.approximate then null else p.speed_mps end,
      'precision',case when p.approximate then 'bubble_approximate' else 'precise' end) order by p.sample_id desc) filter(where p.rn<=v_limit),'[]'::jsonb),
      coalesce(bool_or(p.rn>v_limit),false),(array_agg(p.sample_id order by p.sample_id desc) filter(where p.rn<=v_limit))[v_limit]
      into v_data,v_has_more,v_next from page p;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',case when v_has_more then v_next::text else null end));
  elsif p_operation='listBubbles' then
    select coalesce(jsonb_agg(jsonb_build_object('id',b.id,'circleId',b.circle_id,'creatorMemberId',b.user_id,
      'latitude',round(b.latitude::numeric,2)::double precision,'longitude',round(b.longitude::numeric,2)::double precision,
      'radiusMeters',b.radius_meters,'createdAt',b.created_at,'expiresAt',b.expires_at,'endedAt',b.ended_at) order by b.id desc),'[]'::jsonb)
      into v_data from (select * from private.member_bubbles where circle_id=v_circle_id and ended_at is null and expires_at>now() order by id desc limit v_limit) b;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
  elsif p_operation='createBubble' then
    begin
      v_client_event_id := (v_body->>'clientEventId')::uuid;
      v_lat := (v_body->>'latitude')::double precision; v_lon := (v_body->>'longitude')::double precision;
      v_radius := (v_body->>'radiusMeters')::integer; v_limit := (v_body->>'durationMinutes')::integer;
    exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid Bubble details')));
    end;
    if v_lat not between -90 and 90 or v_lon not between -180 and 180 or v_radius not between 50 and 10000 or v_limit not between 1 and 360 then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid Bubble details')));
    end if;
    select response into v_result from private.client_commands where user_id=p_user_id and client_request_id=v_client_event_id and command_type='create_bubble';
    if found then return jsonb_build_object('httpStatus',201,'body',v_result); end if;
    insert into private.member_bubbles(circle_id,user_id,latitude,longitude,radius_meters,expires_at)
      values(v_circle_id,p_user_id,round(v_lat::numeric,2)::double precision,round(v_lon::numeric,2)::double precision,v_radius,now()+make_interval(mins=>v_limit)) returning * into v_bubble;
    v_result := jsonb_build_object('id',v_bubble.id,'circleId',v_circle_id,'creatorMemberId',p_user_id,'latitude',v_bubble.latitude,
      'longitude',v_bubble.longitude,'radiusMeters',v_bubble.radius_meters,'createdAt',v_bubble.created_at,'expiresAt',v_bubble.expires_at,'endedAt',null);
    insert into private.client_commands(user_id,client_request_id,command_type,response) values(p_user_id,v_client_event_id,'create_bubble',v_result);
    return jsonb_build_object('httpStatus',201,'body',v_result);
  elsif p_operation='endBubble' then
    v_resource_id := (v_path->>'bubbleId')::uuid;
    update private.member_bubbles set ended_at=coalesce(ended_at,now()) where id=v_resource_id and circle_id=v_circle_id and (user_id=p_user_id or v_role='admin') returning * into v_bubble;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('ended',true,'endedAt',v_bubble.ended_at));
  elsif p_operation in ('getCircleActivity','listMorningCheckIns') then
    if p_operation='getCircleActivity' then
      select coalesce(jsonb_agg(jsonb_build_object('memberId',m.user_id,
        'activity',case when ll.speed_mps>=4.5 then 'driving' when ll.speed_mps>=0.8 then 'walking' when ll.speed_mps is not null then 'stationary' else 'unknown' end,
        'observedAt',ll.captured_at) order by ll.captured_at desc),'[]'::jsonb) into v_data
      from private.circle_members m join private.member_accounts ma on ma.auth_user_id=m.user_id
      join private.latest_locations ll on ll.user_id=m.user_id and ll.sharing_epoch=ma.sharing_epoch
      where m.circle_id=v_circle_id and m.membership_state='active' and m.activity_sharing_enabled and ma.activity_sharing_enabled
        and ll.captured_at>=now()-interval '90 seconds';
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'serverTime',now()));
    end if;
    select coalesce(jsonb_agg(jsonb_build_object('memberId',m.user_id,'displayName',ma.display_name,
      'activityState',case when r.id is not null then 'active' else 'unknown' end,'lastActivityAt',r.created_at) order by ma.display_name),'[]'::jsonb)
      into v_data from private.circle_members m join private.member_accounts ma on ma.auth_user_id=m.user_id
      left join lateral (select ar.id,ar.created_at from private.api_records ar where ar.owner_id=m.user_id and ar.circle_id=v_circle_id
        and ar.resource_type='morning_check_in' and ar.created_at>=date_trunc('day',now()) order by ar.created_at desc limit 1) r on true
      where m.circle_id=v_circle_id and m.membership_state='active' and m.activity_sharing_enabled;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'serverTime',now()));
  elsif p_operation='updateEventRsvp' then
    v_resource_id := (v_path->>'eventId')::uuid;
    if v_body->>'response' not in ('yes','no','maybe') then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid RSVP response'))); end if;
    select * into v_record from private.api_records e where e.id=v_resource_id and e.circle_id=v_circle_id and e.resource_type='event' and e.deleted_at is null
      and (e.owner_id=p_user_id or e.payload->'inviteeMemberIds' ? p_user_id::text);
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    update private.api_records set payload=jsonb_build_object('response',v_body->>'response','eventId',v_resource_id,'memberId',p_user_id,'updatedAt',now()),updated_at=now()
      where resource_type='event_rsvp' and owner_id=p_user_id and circle_id=v_circle_id and parent_id=v_resource_id and deleted_at is null returning * into v_record;
    if not found then
      insert into private.api_records(resource_type,owner_id,circle_id,parent_id,payload)
      values('event_rsvp',p_user_id,v_circle_id,v_resource_id,jsonb_build_object('response',v_body->>'response','eventId',v_resource_id,'memberId',p_user_id,'updatedAt',now())) returning * into v_record;
    end if;
    return jsonb_build_object('httpStatus',200,'body',v_record.payload);
  elsif p_operation in ('getDrivingReports','correctDriveClassification') then
    if p_operation='getDrivingReports' then
      select coalesce(jsonb_agg(jsonb_build_object('tripId',t.id,'memberId',t.user_id,'startedAt',t.started_at,'endedAt',t.ended_at,
        'classification',t.drive_classification,'confirmedDrive',t.confirmed_drive) order by t.started_at desc),'[]'::jsonb) into v_data
      from private.trips t where t.circle_id=v_circle_id and t.status='ended' and (t.user_id=p_user_id or p_user_id=any(t.recipients));
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
    end if;
    v_resource_id := (v_path->>'tripId')::uuid;
    if v_body->>'classification' not in ('driver','passenger') then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid classification'))); end if;
    update private.trips set drive_classification=v_body->>'classification' where id=v_resource_id and circle_id=v_circle_id and user_id=p_user_id returning * into v_trip;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('tripId',v_trip.id,'classification',v_trip.drive_classification,'updatedAt',now()));
  elsif p_operation='listTrips' then
    select coalesce(jsonb_agg(jsonb_build_object('id',t.id,'status',t.status,'startedAt',t.started_at,'endedAt',t.ended_at,'endReason',t.end_reason,
      'estimatedArrivalAt',null,'distanceRemainingMeters',null,'destinationLabel',t.destination_label) order by t.started_at desc),'[]'::jsonb) into v_data
    from (select * from private.trips t where t.circle_id=v_circle_id and (t.user_id=p_user_id or p_user_id=any(t.recipients))
      order by t.started_at desc limit v_limit) t;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
  elsif p_operation='startTrip' then
    if jsonb_typeof(v_body->'recipientMemberIds') is distinct from 'array' or jsonb_array_length(v_body->'recipientMemberIds')<1 then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','At least one Circle recipient is required')));
    end if;
    if not exists(select 1 from private.circle_members where circle_id=v_circle_id and user_id=p_user_id and membership_state='active' and sharing_enabled) then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','sharing_disabled','message','Enable location sharing in this Circle before starting a shared trip')));
    end if;
    if exists(select 1 from jsonb_array_elements_text(v_body->'recipientMemberIds') x where not exists(
      select 1 from private.circle_members cm where cm.circle_id=v_circle_id and cm.user_id=x.value::uuid and cm.membership_state='active')) then
      return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Recipients must be active Circle members')));
    end if;
    select array_agg(distinct x.value::uuid) into v_ids from jsonb_array_elements_text(v_body->'recipientMemberIds') x;
    insert into private.trips(user_id,circle_id,recipients,destination_label,destination_latitude,destination_longitude,share_lock_screen_progress,confirmed_drive)
    values(p_user_id,v_circle_id,v_ids,nullif(v_body->>'destinationLabel',''),nullif(v_body->>'destinationLatitude','')::double precision,
      nullif(v_body->>'destinationLongitude','')::double precision,coalesce((v_body->>'shareLockScreenProgress')::boolean,false),
      coalesce((v_body->>'confirmedDrive')::boolean,false)) returning * into v_trip;
    return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('id',v_trip.id,'status',v_trip.status,'startedAt',v_trip.started_at,
      'endedAt',v_trip.ended_at,'endReason',v_trip.end_reason,'estimatedArrivalAt',null,'distanceRemainingMeters',null,'destinationLabel',v_trip.destination_label));
  elsif p_operation='stopTrip' then
    v_resource_id := (v_path->>'tripId')::uuid;
    v_request_id := (v_body->>'clientRequestId')::uuid;
    if v_body->>'endReason' not in ('arrived','cancelled','other') then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid trip end reason'))); end if;
    select response into v_result from private.client_commands where user_id=p_user_id and client_request_id=v_request_id and command_type='stop_trip:'||v_resource_id::text;
    if found then return jsonb_build_object('httpStatus',200,'body',v_result); end if;
    update private.trips set status='ended',ended_at=coalesce(ended_at,now()),end_reason=case when status='active' then v_body->>'endReason' else end_reason end
      where id=v_resource_id and circle_id=v_circle_id and user_id=p_user_id returning * into v_trip;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    v_result := jsonb_build_object('id',v_trip.id,'status','ended','startedAt',v_trip.started_at,'endedAt',v_trip.ended_at,'endReason',coalesce(v_trip.end_reason,'other'));
    insert into private.client_commands(user_id,client_request_id,command_type,response) values(p_user_id,v_request_id,'stop_trip:'||v_resource_id::text,v_result);
    return jsonb_build_object('httpStatus',200,'body',v_result);
  elsif p_operation='listEmergencyContacts' then
    select coalesce(jsonb_agg(jsonb_build_object('memberId',(r.payload->>'recipientMemberId')::uuid,'username',r.payload->>'recipientUsername','acceptedAt',r.payload->>'acceptedAt') order by r.created_at desc),'[]'::jsonb)
      into v_data from private.api_records r where r.circle_id=v_circle_id and r.resource_type='emergency_contact_invite' and r.deleted_at is null and r.payload->>'status'='accepted';
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
  elsif p_operation='inviteEmergencyContact' then
    begin v_request_id := (v_body->>'clientRequestId')::uuid; exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','A request id is required'))); end;
    select auth_user_id,username_normalized into v_member_id,v_actor_username from private.member_accounts where username_normalized=lower(trim(v_body->>'recipientUsername')) and status='active';
    if v_member_id is null or v_member_id=p_user_id then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Recipient account not found'))); end if;
    select * into v_record from private.api_records where owner_id=p_user_id and resource_type='emergency_contact_invite' and client_event_id=v_request_id;
    if found then
      if v_record.request_payload is distinct from v_body-'clientRequestId' then return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','idempotency_conflict','message','Request id was already used with different content'))); end if;
      return jsonb_build_object('httpStatus',201,'body',v_record.payload);
    end if;
    v_payload := jsonb_build_object('circleId',v_circle_id,'recipientMemberId',v_member_id,'recipientUsername',v_actor_username,'status','pending','createdAt',now());
    insert into private.api_records(resource_type,owner_id,circle_id,client_event_id,request_payload,payload)
      values('emergency_contact_invite',p_user_id,v_circle_id,v_request_id,v_body-'clientRequestId',v_payload) returning * into v_record;
    update private.api_records set payload=payload||jsonb_build_object('id',id) where id=v_record.id returning * into v_record;
    return jsonb_build_object('httpStatus',201,'body',v_record.payload);
  elsif p_operation='acceptEmergencyContactInvite' then
    v_resource_id := coalesce(nullif(v_path->>'inviteId','')::uuid,nullif(v_path->>'inviteToken','')::uuid);
    select * into v_record from private.api_records where id=v_resource_id and resource_type='emergency_contact_invite' and deleted_at is null for update;
    if not found or v_record.payload->>'recipientMemberId'<>p_user_id::text or v_record.payload->>'status'<>'pending' then
      return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found')));
    end if;
    update private.api_records set payload=payload||jsonb_build_object('status','accepted','acceptedAt',now()),updated_at=now() where id=v_record.id returning * into v_record;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('accepted',true,'circleId',v_record.circle_id,'memberId',p_user_id,'acceptedAt',v_record.payload->>'acceptedAt'));
  elsif p_operation='createSosAlert' then
    select coalesce(array_agg(user_id),array[]::uuid[]) into v_ids from private.circle_members where circle_id=v_circle_id and membership_state='active' and user_id<>p_user_id;
    if cardinality(v_ids)=0 then return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','no_recipients','message','No other Circle members are available to receive this alert'))); end if;
    begin v_client_event_id := (v_body->>'clientEventId')::uuid; exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','A client event id is required'))); end;
    begin
      v_lat := case when v_body->'latitude' is null or v_body->'latitude'='null'::jsonb then null else (v_body->>'latitude')::double precision end;
      v_lon := case when v_body->'longitude' is null or v_body->'longitude'='null'::jsonb then null else (v_body->>'longitude')::double precision end;
      v_accuracy := case when v_body->'accuracyMeters' is null or v_body->'accuracyMeters'='null'::jsonb then null else (v_body->>'accuracyMeters')::double precision end;
    exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid SOS location'))); end;
    if (v_lat is null or v_lon is null or v_accuracy is null) and not (v_lat is null and v_lon is null and v_accuracy is null) then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Provide a complete SOS location fix or no location')));
    end if;
    if v_lat is not null and (v_lat not between -90 and 90 or v_lon not between -180 and 180 or v_accuracy not between 0 and 10000) then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid SOS location')));
    end if;
    select * into v_record from private.api_records where owner_id=p_user_id and resource_type='sos_alert' and client_event_id=v_client_event_id;
    if found then
      if v_record.request_payload is distinct from (v_body-'clientEventId') then return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','idempotency_conflict','message','Request id was already used with different content'))); end if;
      return jsonb_build_object('httpStatus',202,'body',v_record.payload-'cancelCountdownSeconds'-'requestPayload');
    end if;
    v_payload := jsonb_build_object('status','queued','latitude',v_lat,'longitude',v_lon,'accuracyMeters',v_accuracy,
      'recipients',(select jsonb_agg(jsonb_build_object('recipientId',u.recipient_id,'state','queued')) from unnest(v_ids) as u(recipient_id)),
      'createdAt',now(),'providerMessageId',null,'cancelCountdownSeconds',greatest(0,least(coalesce((v_body->>'cancelCountdownSeconds')::integer,5),30)),
      'requestPayload',v_body);
    insert into private.api_records(resource_type,owner_id,circle_id,client_event_id,request_payload,payload)
      values('sos_alert',p_user_id,v_circle_id,v_client_event_id,v_body-'clientEventId',v_payload) returning * into v_record;
    update private.api_records set payload=payload||jsonb_build_object('id',id) where id=v_record.id returning * into v_record;
    insert into private.notifications(recipient_user_id,circle_id,kind,title,body,delivery_state)
      select u.recipient_id,v_circle_id,'sos','SOS alert',null,'queued' from unnest(v_ids) as u(recipient_id);
    return jsonb_build_object('httpStatus',202,'body',v_record.payload-'cancelCountdownSeconds'-'requestPayload');
  elsif p_operation='getSosAlert' then
    v_resource_id := (v_path->>'sosId')::uuid;
    select * into v_record from private.api_records where id=v_resource_id and circle_id=v_circle_id and resource_type='sos_alert' and deleted_at is null
      and (owner_id=p_user_id or payload->'recipients' @> jsonb_build_array(jsonb_build_object('recipientId',p_user_id)));
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',v_record.payload-'cancelCountdownSeconds'-'requestPayload');
  elsif p_operation in ('cancelSosAlert','acknowledgeSosAlert') then
    v_resource_id := (v_path->>'sosId')::uuid;
    select * into v_record from private.api_records where id=v_resource_id and circle_id=v_circle_id and resource_type='sos_alert' and deleted_at is null for update;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    if p_operation='cancelSosAlert' then
      if v_record.owner_id<>p_user_id or now()>v_record.created_at+make_interval(secs=>coalesce((v_record.payload->>'cancelCountdownSeconds')::integer,5)) or v_record.payload->>'status'<>'queued' then
        return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','sos_not_cancellable','message','The alert can no longer be cancelled')));
      end if;
      update private.api_records set payload=payload||jsonb_build_object('status','failed','cancelledAt',now(),
        'recipients',(select coalesce(jsonb_agg(x||jsonb_build_object('state','failed')),'[]'::jsonb) from jsonb_array_elements(payload->'recipients') x)),updated_at=now() where id=v_record.id;
      delete from private.notifications where recipient_user_id in (select (x->>'recipientId')::uuid from jsonb_array_elements(v_record.payload->'recipients') x)
        and circle_id=v_circle_id and kind='sos' and delivery_state='queued' and created_at=v_record.created_at;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('ok',true,'requestId',null));
    end if;
    if not exists(select 1 from jsonb_array_elements(v_record.payload->'recipients') x where x->>'recipientId'=p_user_id::text) then
      return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found')));
    end if;
    update private.api_records set payload=payload||jsonb_build_object('status','acknowledged','recipients',(
      select jsonb_agg(case when x->>'recipientId'=p_user_id::text then x||jsonb_build_object('state','acknowledged') else x end)
      from jsonb_array_elements(payload->'recipients') x)),updated_at=now() where id=v_record.id;
    update private.notifications set delivery_state='acknowledged',acknowledged_at=now() where recipient_user_id=p_user_id and circle_id=v_circle_id and kind='sos' and created_at=v_record.created_at;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('ok',true,'requestId',null));
  elsif p_operation='listNotifications' then
    with page as (
      select n.*,row_number() over(order by n.id desc) rn from private.notifications n where n.recipient_user_id=p_user_id
        and (v_cursor is null or n.id<v_cursor) order by n.id desc limit least(v_limit,100)+1
    )
    select coalesce(jsonb_agg(jsonb_build_object('id',p.id,'kind',p.kind,'title',p.title,'body',p.body,'deliveryState',p.delivery_state,
      'createdAt',p.created_at,'acknowledgedAt',p.acknowledged_at) order by p.id desc) filter(where p.rn<=least(v_limit,100)),'[]'::jsonb),
      coalesce(bool_or(p.rn>least(v_limit,100)),false),(array_agg(p.id order by p.id desc) filter(where p.rn<=least(v_limit,100)))[least(v_limit,100)]
      into v_data,v_has_more,v_next from page p;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',case when v_has_more then v_next::text else null end));
  elsif p_operation='acknowledgeNotifications' then
    if jsonb_typeof(v_body->'notificationIds') is distinct from 'array' then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','notificationIds must be an array'))); end if;
    if jsonb_array_length(v_body->'notificationIds') not between 1 and 100 then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Provide between 1 and 100 notification ids'))); end if;
    begin select array_agg(value::uuid) into v_ids from jsonb_array_elements_text(v_body->'notificationIds');
    exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid notification id'))); end;
    update private.notifications set delivery_state='acknowledged',acknowledged_at=coalesce(acknowledged_at,now())
      where recipient_user_id=p_user_id and id=any(coalesce(v_ids,array[]::uuid[]));
    get diagnostics v_limit=row_count;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('acknowledgedCount',v_limit,'acknowledgedAt',now()));
  elsif p_operation='listPets' then
    select coalesce(jsonb_agg(jsonb_build_object('id',x.id,'name',x.name,'species',x.species,'photoObjectPath',x.photo_object_path,'circleIds',x.circle_ids,
      'recoveryEnabled',x.recovery_enabled,'createdAt',x.created_at) order by x.id desc),'[]'::jsonb) into v_data from (
      select pet.id,pet.name,pet.species,case when 'photo'=any(pet.recovery_fields) then pet.photo_object_path else null end photo_object_path,pet.recovery_enabled,pet.created_at,
        coalesce((select jsonb_agg(pc.circle_id order by pc.circle_id) from private.pet_circles pc where pc.pet_id=pet.id),'[]'::jsonb) circle_ids
      from private.pets pet where pet.owner_id=p_user_id or exists(select 1 from private.pet_circles pc join private.circle_members cm on cm.circle_id=pc.circle_id
        where pc.pet_id=pet.id and cm.user_id=p_user_id and cm.membership_state='active')
      order by pet.id desc limit v_limit
    ) x;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
  elsif p_operation='createPet' then
    begin
      if length(trim(coalesce(v_body->>'name',''))) not between 1 and 80 or jsonb_typeof(v_body->'circleIds') is distinct from 'array' or jsonb_array_length(v_body->'circleIds')<1 then raise exception 'invalid'; end if;
      select array_agg(distinct value::uuid) into v_ids from jsonb_array_elements_text(v_body->'circleIds');
      if v_body ? 'recoveryFields' and jsonb_typeof(v_body->'recoveryFields') is distinct from 'array' then raise exception 'invalid recovery fields'; end if;
      select coalesce(array_agg(distinct f.value),array[]::text[]) into v_recovery_fields
        from jsonb_array_elements_text(coalesce(v_body->'recoveryFields','[]'::jsonb)) as f(value);
      if cardinality(v_recovery_fields)>5 or exists(select 1 from unnest(v_recovery_fields) f(value)
        where f.value not in ('petName','species','photo','ownerMessage','contactRoute'))
        or coalesce(length(v_body->>'species'),0)>40 or coalesce(length(v_body->>'ownerMessage'),0)>500 or coalesce(length(v_body->>'contactRoute'),0)>200 then raise exception 'invalid recovery fields'; end if;
    exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid pet details'))); end;
    if exists(select 1 from unnest(v_ids) u(circle_id) where not exists(select 1 from private.circle_members cm where cm.circle_id=u.circle_id and cm.user_id=p_user_id and cm.membership_state='active')) then
      return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Pets can be shared only to your Circles')));
    end if;
    insert into private.pets(owner_id,name,species,photo_object_path,recovery_enabled,recovery_fields,owner_message,contact_route)
      values(p_user_id,trim(v_body->>'name'),nullif(v_body->>'species',''),null,coalesce((v_body->>'recoveryEnabled')::boolean,false),
        v_recovery_fields,coalesce(v_body->>'ownerMessage',''),nullif(v_body->>'contactRoute','')) returning * into v_pet;
    insert into private.pet_circles(pet_id,circle_id) select v_pet.id,u.circle_id from unnest(v_ids) u(circle_id);
    return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('id',v_pet.id,'name',v_pet.name,'species',v_pet.species,'photoObjectPath',null,'circleIds',to_jsonb(v_ids),'recoveryEnabled',v_pet.recovery_enabled,'recoveryFields',to_jsonb(v_pet.recovery_fields),'ownerMessage',v_pet.owner_message,'contactRoute',v_pet.contact_route,'createdAt',v_pet.created_at));
  elsif p_operation='getPet' then
    select * into v_pet from private.pets pet where pet.id=v_resource_id and pet.owner_id=p_user_id;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    select coalesce(jsonb_agg(pc.circle_id order by pc.circle_id),'[]'::jsonb) into v_data from private.pet_circles pc where pc.pet_id=v_pet.id;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('id',v_pet.id,'name',v_pet.name,'species',v_pet.species,
      'circleIds',v_data,'recoveryEnabled',v_pet.recovery_enabled,'recoveryFields',to_jsonb(v_pet.recovery_fields),
      'ownerMessage',v_pet.owner_message,'contactRoute',v_pet.contact_route,'photoObjectPath',v_pet.photo_object_path,'createdAt',v_pet.created_at));
  elsif p_operation in ('updatePet','deletePet','createPetPhotoUpload','createPetRecoveryLink','revokePetRecoveryLink','listPetRoutines','createPetRoutine','listPetActivity','completePetRoutine') then
    v_resource_id := (v_path->>'petId')::uuid;
    select * into v_pet from private.pets pet where pet.id=v_resource_id and (pet.owner_id=p_user_id or exists(
      select 1 from private.pet_circles pc join private.circle_members cm on cm.circle_id=pc.circle_id where pc.pet_id=pet.id and cm.user_id=p_user_id and cm.membership_state='active'));
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    if p_operation='updatePet' then
      if v_pet.owner_id<>p_user_id then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
      if v_body ? 'recoveryFields' then
        begin
          if jsonb_typeof(v_body->'recoveryFields') is distinct from 'array' then raise exception 'invalid recovery fields'; end if;
          select coalesce(array_agg(distinct f.value),array[]::text[]) into v_recovery_fields
            from jsonb_array_elements_text(v_body->'recoveryFields') as f(value);
          if cardinality(v_recovery_fields)>5 or exists(select 1 from unnest(v_recovery_fields) f(value)
            where f.value not in ('petName','species','photo','ownerMessage','contactRoute'))
            or (select count(*) from jsonb_array_elements_text(v_body->'recoveryFields'))<>jsonb_array_length(v_body->'recoveryFields') then raise exception 'invalid recovery fields'; end if;
        exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid recovery fields'))); end;
      end if;
      if coalesce(length(v_body->>'species'),0)>40 or coalesce(length(v_body->>'ownerMessage'),0)>500 or coalesce(length(v_body->>'contactRoute'),0)>200 then
        return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Pet field exceeds its limit')));
      end if;
      if v_body ? 'photoObjectPath' then
        v_photo_path := nullif(v_body->>'photoObjectPath','');
        if v_photo_path is not null and (length(v_photo_path)>240 or v_photo_path not like v_pet.id::text||'/%'
          or position('..' in v_photo_path)>0 or not exists(select 1 from storage.objects o where o.bucket_id='pet-photos' and o.name=v_photo_path)) then
          return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Photo must be an uploaded object for this pet')));
        end if;
      end if;
      if v_body ? 'circleIds' then
        begin select array_agg(distinct value::uuid) into v_ids from jsonb_array_elements_text(v_body->'circleIds'); exception when others then v_ids:=null; end;
        if coalesce(cardinality(v_ids),0)<1 or exists(select 1 from unnest(v_ids) u(circle_id) where not exists(select 1 from private.circle_members cm where cm.circle_id=u.circle_id and cm.user_id=p_user_id and cm.membership_state='active')) then
          return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Pets can be shared only to your Circles')));
        end if;
        delete from private.pet_circles where pet_id=v_pet.id;
        insert into private.pet_circles(pet_id,circle_id) select v_pet.id,u.circle_id from unnest(v_ids) u(circle_id);
      end if;
      update private.pets set name=coalesce(nullif(trim(v_body->>'name'),''),name),species=case when v_body ? 'species' then nullif(v_body->>'species','') else species end,
        photo_object_path=case when v_body ? 'photoObjectPath' then v_photo_path else photo_object_path end,
        recovery_enabled=coalesce((v_body->>'recoveryEnabled')::boolean,recovery_enabled),
        recovery_fields=case when v_body ? 'recoveryFields' then v_recovery_fields else recovery_fields end,
        owner_message=case when v_body ? 'ownerMessage' then coalesce(v_body->>'ownerMessage','') else owner_message end,
        contact_route=case when v_body ? 'contactRoute' then nullif(v_body->>'contactRoute','') else contact_route end,updated_at=now()
        where id=v_pet.id returning * into v_pet;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('id',v_pet.id,'name',v_pet.name,'species',v_pet.species,
        'photoObjectPath',v_pet.photo_object_path,
        'circleIds',coalesce((select jsonb_agg(circle_id) from private.pet_circles where pet_id=v_pet.id),'[]'::jsonb),
        'recoveryEnabled',v_pet.recovery_enabled,'recoveryFields',to_jsonb(v_pet.recovery_fields),'ownerMessage',v_pet.owner_message,'contactRoute',v_pet.contact_route,'createdAt',v_pet.created_at));
    elsif p_operation='deletePet' then
      if v_pet.owner_id<>p_user_id then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
      delete from private.pets where id=v_pet.id;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('ok',true,'requestId',null));
    elsif p_operation='createPetPhotoUpload' then
      if v_pet.owner_id<>p_user_id then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
      if v_body->>'contentType' not in ('image/jpeg','image/png','image/webp') or coalesce((v_body->>'sizeBytes')::integer,0) not between 1 and 5242880 then
        return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid pet photo upload')));
      end if;
      return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('authorized',true));
    elsif p_operation='createPetRecoveryLink' then
      if v_pet.owner_id<>p_user_id then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
      if not v_pet.recovery_enabled then return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','recovery_disabled','message','Enable the recovery card first'))); end if;
      if coalesce(v_body->>'secretHash','') !~ '^[0-9a-f]{64}$' then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid recovery token'))); end if;
      update private.pet_recovery_tokens set revoked_at=coalesce(revoked_at,now()) where pet_id=v_pet.id and revoked_at is null;
      insert into private.pet_recovery_tokens(pet_id,token_hash) values(v_pet.id,v_body->>'secretHash') returning * into v_recovery_token;
      return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('id',v_recovery_token.id,'createdAt',v_recovery_token.created_at,'expiresAt',v_recovery_token.expires_at));
    elsif p_operation='revokePetRecoveryLink' then
      if v_pet.owner_id<>p_user_id then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden'))); end if;
      update private.pet_recovery_tokens set revoked_at=coalesce(revoked_at,now()) where pet_id=v_pet.id and revoked_at is null;
      get diagnostics v_limit=row_count;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('revoked',true,'revokedCount',v_limit,'revokedAt',now()));
    elsif p_operation='listPetRoutines' then
      with page as (select r.id,r.payload,row_number() over(order by r.id desc) rn from private.api_records r
        where r.resource_type='pet_routine' and r.parent_id=v_pet.id and r.deleted_at is null and (v_cursor is null or r.id<v_cursor)
        order by r.id desc limit v_limit+1)
      select coalesce(jsonb_agg(p.payload order by p.id desc) filter(where p.rn<=v_limit),'[]'::jsonb),coalesce(bool_or(p.rn>v_limit),false),
        (array_agg(p.id order by p.id desc) filter(where p.rn<=v_limit))[v_limit] into v_data,v_has_more,v_next from page p;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',case when v_has_more then v_next::text else null end));
    elsif p_operation='createPetRoutine' then
      v_client_event_id := (v_body->>'clientRequestId')::uuid;
      v_payload := v_body-'clientRequestId';
      select * into v_record from private.api_records where owner_id=p_user_id and resource_type='pet_routine' and client_event_id=v_client_event_id;
      if found then
        if v_record.request_payload is distinct from v_payload then return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','idempotency_conflict','message','Request id was already used with different content'))); end if;
        return jsonb_build_object('httpStatus',201,'body',v_record.payload);
      end if;
      v_payload := v_payload || jsonb_build_object('petId',v_pet.id,'createdByMemberId',p_user_id,'createdAt',now(),'active',true);
      insert into private.api_records(resource_type,owner_id,parent_id,client_event_id,request_payload,payload)
        values('pet_routine',p_user_id,v_pet.id,v_client_event_id,v_body-'clientRequestId',v_payload) returning * into v_record;
      update private.api_records set payload=payload||jsonb_build_object('id',id) where id=v_record.id returning * into v_record;
      return jsonb_build_object('httpStatus',201,'body',v_record.payload);
    elsif p_operation='completePetRoutine' then
      v_resource_id := (v_path->>'routineId')::uuid;
      v_client_event_id := (v_body->>'clientEventId')::uuid;
      select * into v_record from private.api_records where id=v_resource_id and parent_id=v_pet.id and resource_type='pet_routine' and deleted_at is null;
      if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
      select * into v_record from private.api_records where owner_id=p_user_id and resource_type='pet_routine_completion' and client_event_id=v_client_event_id;
      if found then
        if v_record.request_payload is distinct from jsonb_build_object('routineId',v_resource_id) then return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','idempotency_conflict','message','Request id was already used with different content'))); end if;
        return jsonb_build_object('httpStatus',201,'body',v_record.payload);
      end if;
      v_payload := jsonb_build_object('routineId',v_resource_id,'petId',v_pet.id,'completedByMemberId',p_user_id,'clientEventId',v_client_event_id,'completedAt',now());
      insert into private.api_records(resource_type,owner_id,parent_id,client_event_id,request_payload,payload)
        values('pet_routine_completion',p_user_id,v_resource_id,v_client_event_id,jsonb_build_object('routineId',v_resource_id),v_payload) returning * into v_record;
      update private.api_records set payload=payload||jsonb_build_object('id',id) where id=v_record.id returning * into v_record;
      return jsonb_build_object('httpStatus',201,'body',v_record.payload);
    elsif p_operation='listPetActivity' then
      select coalesce(jsonb_agg(r.payload order by r.created_at desc),'[]'::jsonb) into v_data from private.api_records r
        where r.resource_type='pet_routine_completion' and r.deleted_at is null and r.parent_id in
          (select routine.id from private.api_records routine where routine.resource_type='pet_routine' and routine.parent_id=v_pet.id and routine.deleted_at is null)
        and r.created_at>now()-interval '30 days';
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
    end if;
  end if;

  -- The remaining Circle entities use a private owner+Circle record store. Each
  -- operation maps to one explicit resource type; no caller-supplied type is used.
  v_type := case p_operation
    when 'listPlaces' then 'place' when 'createPlace' then 'place' when 'updatePlace' then 'place' when 'deletePlace' then 'place'
    when 'listPlaceRules' then 'place_rule' when 'createPlaceRule' then 'place_rule' when 'updatePlaceRule' then 'place_rule' when 'deletePlaceRule' then 'place_rule'
    when 'listCheckins' then 'checkin' when 'createCheckin' then 'checkin'
    when 'listQuickNotes' then 'quick_note' when 'createQuickNote' then 'quick_note'
    when 'listEvents' then 'event' when 'createEvent' then 'event'
    when 'createMorningCheckIn' then 'morning_check_in'
    else null end;
  if v_type is not null then
    if p_operation in ('listPlaceRules','createPlaceRule','updatePlaceRule','deletePlaceRule') then
      v_resource_id := coalesce(nullif(v_path->>'ruleId','')::uuid,null);
      select * into v_record from private.api_records where id=v_place_id and circle_id=v_circle_id and resource_type='place' and deleted_at is null;
      if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
      v_parent_id := v_place_id;
    end if;
    if p_operation in ('createPlace','updatePlace','deletePlace','createPlaceRule','updatePlaceRule','deletePlaceRule') and v_role<>'admin' then
      return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden')));
    end if;
    if p_operation in ('listPlaces','listPlaceRules','listCheckins','listQuickNotes','listEvents') then
      with page as (
        select r.id,r.payload,row_number() over(order by r.id desc) rn from private.api_records r
        where r.resource_type=v_type and r.circle_id=v_circle_id and r.deleted_at is null
          and (v_parent_id is null or r.parent_id=v_parent_id) and (v_cursor is null or r.id<v_cursor)
          and (p_operation<>'listEvents' or r.owner_id=p_user_id or r.payload->'inviteeMemberIds' ? p_user_id::text)
        order by r.id desc limit v_limit+1
      )
      select coalesce(jsonb_agg(case
          when p_operation='listPlaceRules' then p.payload || jsonb_build_object('placeId',v_place_id)
          when v_type='event' then p.payload || jsonb_build_object('myRsvp',
            (select rr.payload->>'response' from private.api_records rr where rr.resource_type='event_rsvp' and rr.owner_id=p_user_id and rr.parent_id=p.id and rr.deleted_at is null limit 1))
          else p.payload end order by p.id desc) filter(where p.rn<=v_limit),'[]'::jsonb),coalesce(bool_or(p.rn>v_limit),false),
        (array_agg(p.id order by p.id desc) filter(where p.rn<=v_limit))[v_limit]
      into v_data,v_has_more,v_next from page p;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',case when v_has_more then v_next::text else null end));
    elsif p_operation in ('createPlace','createPlaceRule','createCheckin','createQuickNote','createEvent','createMorningCheckIn') then
      if p_operation='createPlaceRule' then
        v_parent_id := v_place_id;
      end if;
      if p_operation in ('createCheckin','createQuickNote','createEvent','createMorningCheckIn') then
        v_client_event_id := coalesce(nullif(v_body->>'clientEventId','')::uuid,nullif(v_body->>'clientRequestId','')::uuid);
        if v_client_event_id is null then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','An idempotency identifier is required'))); end if;
      end if;
      if p_operation='createEvent' then
        if jsonb_typeof(v_body->'inviteeMemberIds') is distinct from 'array' or jsonb_array_length(v_body->'inviteeMemberIds')<1
          or exists(select 1 from jsonb_array_elements_text(v_body->'inviteeMemberIds') x where not exists(
            select 1 from private.circle_members cm where cm.circle_id=v_circle_id and cm.user_id=x.value::uuid and cm.membership_state='active')) then
          return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Invitees must be active Circle members')));
        end if;
      end if;
      v_payload := v_body - 'clientEventId' - 'clientRequestId';
      if p_operation='createPlaceRule' then v_payload := v_payload || jsonb_build_object('placeId',v_place_id); end if;
      if p_operation='createCheckin' then v_payload := v_payload || jsonb_build_object('circleId',v_circle_id,'memberId',p_user_id,'createdAt',now()); end if;
      if p_operation='createQuickNote' then v_payload := v_payload || jsonb_build_object('circleId',v_circle_id,'senderMemberId',p_user_id,'deliveryState','queued','createdAt',now()); end if;
      if p_operation='createEvent' then v_payload := v_payload || jsonb_build_object('circleId',v_circle_id,'creatorMemberId',p_user_id,'myRsvp',null,'createdAt',now()); end if;
      if p_operation='createMorningCheckIn' then v_payload := v_payload || jsonb_build_object('circleId',v_circle_id,'memberId',p_user_id,'createdAt',now()); end if;
      if v_client_event_id is not null then
        select * into v_record from private.api_records where owner_id=p_user_id and resource_type=v_type and client_event_id=v_client_event_id;
        if found then
          if v_record.request_payload is distinct from (v_body - 'clientEventId' - 'clientRequestId') then
            return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','idempotency_conflict','message','Request id was already used with different content')));
          end if;
          return jsonb_build_object('httpStatus',201,'body',v_record.payload);
        end if;
      end if;
      insert into private.api_records(resource_type,owner_id,circle_id,parent_id,client_event_id,request_payload,payload)
        values(v_type,p_user_id,v_circle_id,v_parent_id,v_client_event_id,(v_body - 'clientEventId' - 'clientRequestId'),v_payload)
        returning * into v_record;
      update private.api_records set payload=payload || jsonb_build_object('id',id,'circleId',circle_id,'createdAt',created_at)
        where id=v_record.id returning * into v_record;
      return jsonb_build_object('httpStatus',201,'body',v_record.payload);
    elsif p_operation in ('updatePlace','updatePlaceRule') then
      v_resource_id := case when p_operation='updatePlace' then (v_path->>'placeId')::uuid else (v_path->>'ruleId')::uuid end;
      select * into v_record from private.api_records where id=v_resource_id and resource_type=v_type and circle_id=v_circle_id and deleted_at is null
        and (p_operation<>'updatePlaceRule' or parent_id=v_place_id) for update;
      if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
      update private.api_records set payload=(payload || v_body) || jsonb_build_object('id',id,'circleId',circle_id,'createdAt',created_at)
          || case when p_operation='updatePlaceRule' then jsonb_build_object('placeId',v_place_id) else '{}'::jsonb end,updated_at=now()
        where id=v_resource_id returning * into v_record;
      return jsonb_build_object('httpStatus',200,'body',v_record.payload);
    elsif p_operation in ('deletePlace','deletePlaceRule') then
      v_resource_id := case when p_operation='deletePlace' then (v_path->>'placeId')::uuid else (v_path->>'ruleId')::uuid end;
      update private.api_records set deleted_at=coalesce(deleted_at,now()),updated_at=now() where id=v_resource_id and resource_type=v_type and circle_id=v_circle_id
        and deleted_at is null and (p_operation<>'deletePlaceRule' or parent_id=v_place_id);
      if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('ok',true,'requestId',null));
    end if;
  end if;
  if p_operation='reportLostPet' then
    begin
      v_resource_id := (v_body->>'petId')::uuid;
      v_expires := (v_body->>'expiresAt')::timestamptz;
      select array_agg(distinct value::uuid) into v_ids from jsonb_array_elements_text(v_body->'recipientCircleIds');
    exception when others then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid lost pet report'))); end;
    select * into v_pet from private.pets where id=v_resource_id and owner_id=p_user_id;
    if not found or coalesce(cardinality(v_ids),0)<1 or v_expires<=now() or v_expires>now()+interval '72 hours'
      or exists(select 1 from unnest(v_ids) u(circle_id) where not exists(select 1 from private.pet_circles pc join private.circle_members cm on cm.circle_id=pc.circle_id
        where pc.pet_id=v_pet.id and pc.circle_id=u.circle_id and cm.user_id=p_user_id and cm.membership_state='active')) then
      return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Select active Circles that share this pet')));
    end if;
    v_payload := jsonb_build_object('petId',v_pet.id,'ownerMemberId',p_user_id,'recipientCircleIds',to_jsonb(v_ids),'message',nullif(v_body->>'message',''),
      'status','active','createdAt',now(),'expiresAt',v_expires);
    insert into private.api_records(resource_type,owner_id,payload) values('lost_pet_report',p_user_id,v_payload) returning * into v_record;
    update private.api_records set payload=payload||jsonb_build_object('id',id) where id=v_record.id returning * into v_record;
    insert into private.notifications(recipient_user_id,circle_id,kind,title,body,delivery_state)
      select cm.user_id,cm.circle_id,'lost_pet','Lost pet report',null,'queued'
      from private.circle_members cm where cm.circle_id=any(v_ids) and cm.membership_state='active' and cm.user_id<>p_user_id;
    return jsonb_build_object('httpStatus',201,'body',v_record.payload);
  elsif p_operation='revokeLostPetReport' then
    v_resource_id := (v_path->>'reportId')::uuid;
    update private.api_records set payload=payload||jsonb_build_object('status','revoked','endedAt',coalesce(payload->>'endedAt',now()::text)),updated_at=now()
      where id=v_resource_id and owner_id=p_user_id and resource_type='lost_pet_report' and deleted_at is null returning * into v_record;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('revoked',true,'reportId',v_record.id,'endedAt',v_record.payload->>'endedAt'));
  elsif p_operation='requestGuardianConsent' then
    if v_body->>'childMemberId' is distinct from p_user_id::text then return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','A member can request consent only for their own account'))); end if;
    v_actor_username := lower(trim(coalesce(v_body->>'guardianUsername','')));
    if v_actor_username='' or coalesce(v_body->>'relationship','parent') not in ('parent','guardian') then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid guardian details'))); end if;
    if not exists(select 1 from private.member_accounts where username_normalized=v_actor_username and status='active' and auth_user_id<>p_user_id) then
      return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Guardian account not found')));
    end if;
    insert into private.guardian_consents(child_user_id,guardian_username_normalized,relationship,token_hash)
      values(p_user_id,v_actor_username,coalesce(v_body->>'relationship','parent'),encode(extensions.gen_random_bytes(32),'hex')) returning * into v_consent;
    insert into private.notifications(recipient_user_id,kind,title,body,delivery_state)
      select auth_user_id,'guardian_consent','Guardian consent requested',null,'queued' from private.member_accounts where username_normalized=v_actor_username;
    return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('id',v_consent.id,'status','pending','guardianUsername',v_actor_username,'createdAt',v_consent.created_at));
  elsif p_operation='approveGuardianConsent' then
    v_resource_id := (v_path->>'consentId')::uuid;
    update private.guardian_consents set status='approved',decided_at=now() where id=v_resource_id and guardian_username_normalized=a.username_normalized and status='pending'
      and child_user_id<>p_user_id returning * into v_consent;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('ok',true,'requestId',v_consent.id));
  elsif p_operation='requestDataExport' then
    insert into private.api_records(resource_type,owner_id,payload) values('privacy_export_request',p_user_id,jsonb_build_object('status','queued','createdAt',now())) returning * into v_record;
    update private.api_records set payload=payload||jsonb_build_object('requestId',id) where id=v_record.id returning * into v_record;
    return jsonb_build_object('httpStatus',202,'body',jsonb_build_object('ok',true,'requestId',v_record.id,'status','queued'));
  elsif p_operation='requestAccountDeletion' then
    if v_body->>'confirmationPhrase'<>'DELETE MY ACCOUNT' then return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','confirmation_required','message','Confirm account deletion to continue'))); end if;
    update private.member_accounts set status='deleting',session_epoch=session_epoch+1,sharing_epoch=sharing_epoch+1,sharing_enabled=false,activity_sharing_enabled=false,updated_at=now()
      where account_id=a.account_id and status='active' returning * into a;
    update private.member_sessions set revoked_at=coalesce(revoked_at,now()) where user_id=p_user_id;
    update private.circle_members set sharing_enabled=false,activity_sharing_enabled=false,membership_state='removed',updated_at=now() where user_id=p_user_id;
    update private.guest_shares set status='ended',ended_at=coalesce(ended_at,now()),end_reason='owner_deletion' where owner_id=p_user_id and status='active';
    update private.trips set status='ended',ended_at=coalesce(ended_at,now()),end_reason='owner_deletion' where user_id=p_user_id and status='active';
    delete from private.latest_locations where user_id=p_user_id;
    insert into private.api_records(resource_type,owner_id,payload) values('privacy_deletion_request',p_user_id,jsonb_build_object('status','queued','reason',v_body->>'reason','details',v_body->>'details','createdAt',now())) returning * into v_record;
    insert into private.admin_audit_events(actor_user_id,actor_username,target_account_id,event_type,metadata)
      values(p_user_id,a.username_normalized,a.account_id,'account.deletion_requested',jsonb_build_object('requestId',v_record.id));
    return jsonb_build_object('httpStatus',202,'body',jsonb_build_object('accepted',true,'requestId',v_record.id,'status','queued'));
  end if;

  return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found')));
end;
$$;

revoke execute on function public.opentrack_api_dispatch(uuid,uuid,text,jsonb,bigint) from public, anon, authenticated;
grant execute on function public.opentrack_api_dispatch(uuid,uuid,text,jsonb,bigint) to service_role;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('pet-photos','pet-photos',false,5242880,array['image/jpeg','image/png','image/webp']::text[])
on conflict(id) do update set public=false,file_size_limit=excluded.file_size_limit,allowed_mime_types=excluded.allowed_mime_types;
-- No anon/authenticated Storage policies are created for pet-photos. Upload and
-- short-lived read URLs are minted only by the server using the service key.

do $cron$
declare v_job record;
begin
  if exists(select 1 from pg_available_extensions where name='pg_cron') then
    begin
      execute 'create extension if not exists pg_cron';
    exception when others then
      raise notice 'pg_cron is available but could not be enabled by this migration role: %', sqlerrm;
    end;
  end if;
  if to_regclass('cron.job') is not null then
    for v_job in execute 'select jobid from cron.job where jobname = ''opentrack360-location-retention''' loop
      execute format('select cron.unschedule(%s)',v_job.jobid);
    end loop;
    execute 'select cron.schedule(''opentrack360-location-retention'', ''15 3 * * *'', ''select private.opentrack_purge_old_location_samples()'')';
  else
    raise notice 'pg_cron is not enabled; schedule private.opentrack_purge_old_location_samples() daily at 03:15 UTC after enabling it.';
  end if;
end;
$cron$;
