/* SPDX-License-Identifier: GPL-3.0-only */
/* App-owned sequence windows. Positions are absolute in the header and relative
 * in the visible piano roll. The drum grid has a fixed sixteen-cell stride. */
#ifdef MOTION_PLACE
typedef step_mask_t skiff_lock_steps_t;
#else
typedef uint64_t skiff_lock_steps_t;
#endif

static int skiff_step_locked(skiff_lock_steps_t mask, unsigned step)
{
#ifdef MOTION_PLACE
    return step_mask_has(mask, step);
#else
    return step < 64u && ((mask >> step) & 1u);
#endif
}

static unsigned skiff_arp_grid(uint8_t *b,unsigned at)
{
    static const uint32_t example[3]={60,64,67};
    const track_t *t=TSEL;
    uint32_t list[65],cnt=3,len=0,i,oct=(uint32_t)t->p[P_AOCT],mode=(uint32_t)t->p[P_AMODE];
    if(t->nheld) {
        len=arp_list(t,list,&cnt);
        if(!len){list[0]=60;cnt=len=1;}
    } else {
        for(i=0;i<oct*3;i++)list[len++]=example[i%3]+(i/3)*12;
        if(mode==AM_REPEAT){list[0]=67;len=1;}
    }
    if(mode==AM_UP8 && len<65){list[len]=(uint32_t)clamp(list[0]+12*oct,0,127);len++;}
    unsigned p=skiff_sec(b,at,CAFA_NATIVE_ARP_GRID,8u+len);
    if(!p)return at;
    b[p]=mode;b[p+1]=oct;b[p+2]=t->p[P_AGATE];b[p+3]=t->p[P_ASWING];
    b[p+4]=t->p[P_APROB];b[p+5]=t->nheld?0:1;b[p+6]=cnt;b[p+7]=len;
    for(i=0;i<len;i++)b[p+8+i]=(uint8_t)list[i];
    return p+8+len;
}

static unsigned skiff_sequence_events(uint8_t *b,unsigned at)
{
    uint8_t idx[MOTION_MAX];
    uint32_t n=ev_rows(idx),total=n+2,selected=ui.ev_top?0:ev_row(n)+1;
    uint32_t first=(uint32_t)clamp((int32_t)selected-3,0,total>7?(int32_t)total-7:0);
    uint32_t count=total-first>7?7:total-first;
    unsigned p=skiff_sec(b,at,CAFA_NATIVE_ROWS,1+48*count);
    if(!p)return at;
    skiff_u14(b+24,selected);skiff_u14(b+26,first);skiff_u14(b+28,total);
    b[p]=count;
    for(unsigned k=0;k<count;k++) {
        unsigned row=first+k;
        uint8_t *q=b+p+1+48*k;char name[16],value[20];const char *unit;
        memset(q,' ',40);q[40]=(row==selected);
        if(row==0){
            skiff_text(q+32,8,motion_enabled(TSEL)?"PLAY ON":"PLAY OFF");
            fmt_int(value,n);str_cpy(value+str_len(value),n==1?" RECORD":" RECORDS",12);
            skiff_text(q+16,16,value);
        } else if(row==total-1)skiff_text(q,16,"+ ADD LOCK");
        else {
            const motion_event_t *e = &motion.event[idx[row-1]];
            unsigned id = MOTION_ID(e);
#ifdef MOTION_PLACE
            unsigned step = MOTION_STEP(e->place) + 1u;
#else
            unsigned step = (e->place & 63u) + 1u;
#endif
            char step_label[8];
            fmt_int(step_label, (int32_t)step);
            if (step < 10u) {
                q[0] = '0';
                skiff_text(q + 1u, 7u, step_label);
            } else skiff_text(q, 8u, step_label);
            ev_name(TSEL,id,name);skiff_text(q+8,8,name);
            param_format(track_desc(TSEL,id),e->value,value,&unit);
            if(unit && *unit)str_cpy(value+str_len(value),unit,sizeof value-str_len(value));
            skiff_text(q+16,16,value);skiff_text(q+32,8,e->lock?"LOCK":"AUTO");
            if(e->lock)q[40]|=64;
        }
    }
    return p+1+48*count;
}

static unsigned skiff_sequence_song(uint8_t *b,unsigned at)
{
    unsigned first_track = skiff_first_visible_track();
    unsigned selected = song.sel % NTRK, entry = sg.entry % NENT;
    unsigned copy_at = NENT, copy_count = 0u, copy_first, copy_last;
    uint8_t copy_pattern[NENT];
    if (song_pick() == SPICK_COPYP) {
        copy_at = ent_free_after(selected, entry);
        if (copy_at < NENT) {
            copy_count = copy_ids(selected, entry, copy_pattern);
            ent_block(selected, entry, &copy_first, &copy_last);
            for (unsigned i = 0; i < copy_count; i++)
                if (copy_pattern[i] > NPAT) {
                    copy_count = 0u;
                    break;
                }
        }
    }
    if (copy_count || NTRK > SKIFF_VIEW_TRACKS) {
        unsigned rows = skiff_sec(b, at, CAFA_NATIVE_ROWS, 1u + 48u * SKIFF_VIEW_TRACKS);
        if (!rows) return at;
        b[rows] = SKIFF_VIEW_TRACKS;
        for (unsigned i = 0; i < SKIFF_VIEW_TRACKS; i++) {
            unsigned k = first_track + i;
            uint8_t *r = b + rows + 1u + 48u * i;
            char name[3] = {'T', (char)('1' + k), 0};
            skiff_text(r, 16u, name);
            skiff_text(r + 16u, 24u, "");
            r[40] = (uint8_t)(k == selected);
            skiff_u14(r + 41u, k);
            if (k == selected && copy_count) {
                skiff_u14(r + 43u, copy_at);
                skiff_u14(r + 45u, copy_count);
            }
        }
        at = rows + 1u + 48u * SKIFF_VIEW_TRACKS;
    }
    unsigned p=skiff_sec(b,at,CAFA_NATIVE_CELLS,2+7*SKIFF_VIEW_TRACKS*NENT);
    if(!p)return at;
    skiff_u14(b+p,SKIFF_VIEW_TRACKS*NENT);skiff_u14(b+24,entry);skiff_u14(b+28,NENT);
    for(unsigned i=0;i<SKIFF_VIEW_TRACKS;i++) {
        unsigned k=first_track+i;
        unsigned first=0,last=NENT-1,next=bank_next(k),current=bank_cur(k);
        while(first<NENT && !ent_on(k,first)) first++;
        while(last>first && !ent_on(k,last)) last--;
        for(unsigned e=0;e<NENT;e++) {
            uint8_t *q=b+p+2+7*(i*NENT+e);
            unsigned chain_first,chain_last;
            int occupied=ent_on(k,e);
            int chain=ent_block(k,e,&chain_first,&chain_last);
            int rest=ent_rest(k,e) || (!occupied && e>first && e<last);
            int current_rest=rest && !trk_pid[k] && lists.cur[k]%NENT==e;
            char label[8]="";
            if (k == selected && e >= copy_at && e < copy_at + copy_count) {
                unsigned part = e - copy_at, id = copy_pattern[part];
                unsigned source = copy_first + part;
                q[2] = (uint8_t)(1u | (lists.snd[k][source][0] ? 8u : 0u) |
                                  (copy_count > 1u ? 32u | (!part ? 64u : 0u) : 0u));
                if (id) pat_num(label, id - 1u);
                else str_cpy(label, "-", sizeof label);
                skiff_text(q + 3u, 4u, label);
                skiff_u14(q, ent_reps(k, source));
                continue;
            }
            if(rest) str_cpy(label,"-",sizeof label);
            else if(occupied) pat_num(label,ent_pat(k,e));
            q[2]=(uint8_t)((occupied?1u:0u)|(k==song.sel && e==sg.entry%NENT?2u:0u)|
                            (e==current || current_rest?4u:0u)|(lists.snd[k][e][0]?8u:0u)|
                            (e==next?16u:0u)|(chain?32u | (e==chain_first?64u:0u):0u));
            skiff_text(q+3,4,label);
            skiff_u14(q,ent_reps(k,e));
        }
    }
    return p+2+7*SKIFF_VIEW_TRACKS*NENT;
}

static unsigned skiff_sequence_detail(uint8_t *b,unsigned at,uint8_t screen)
{
    const track_t *t=TSEL;
    if(screen==CAFA_SCREEN_ARP || screen==CAFA_SCREEN_ARP2)return skiff_arp_grid(b,at);
    if(screen==CAFA_SCREEN_SONG)return skiff_sequence_song(b,at);
    if(screen==CAFA_SCREEN_AUTOMATION)return skiff_sequence_events(b,at);
    unsigned len=t->p[P_SLEN],first=ui.bank*16u,n=first<len?len-first:0;
    if(n>16)n=16;
    skiff_u14(b+17,first);b[19]=n;skiff_u14(b+20,ui.cursor);skiff_u14(b+22,song.playing?t->seq_idx:16383);
    if(screen==CAFA_SCREEN_NOTES && !drum_track(t)){
        at=skiff_steps(b,at,screen);
        first=cafa_native_wire_u14(b+17);n=b[19];
        unsigned p=skiff_sec(b,at,CAFA_NATIVE_KEYBOARD,20);
        if(!p)return at;
        uint32_t mask=scale_mask(t);
        for(unsigned note=0;note<128;note++)if(mask==0xfffu ? !KEY_BLACK[note%12u] :
                (mask & (1u<<((note+120-t->p[P_ROOT])%12))))
            b[p+note/7]|=1u<<(note%7);
        b[p+19]=t->p[P_ROOT];
        at=p+20;
        p=skiff_sec(b,at,CAFA_NATIVE_ROWS,49);
        if(!p)return at;
        char scale[16];
        str_cpy(scale,N_NOTE[t->p[P_ROOT]%12u],sizeof scale);
        str_cpy(scale+str_len(scale)," ",sizeof scale-str_len(scale));
        str_cpy(scale+str_len(scale),N_SCALE[t->p[P_SCALE]%NELEM(N_SCALE)],sizeof scale-str_len(scale));
        b[p]=1;
        skiff_text(b+p+1,16,"ROLL VIEW V1");
        skiff_text(b+p+17,16,scale);
        skiff_text(b+p+33,8,"");
        skiff_u14(b+p+42,sel_lo());
        skiff_u14(b+p+44,sel_hi());
        at=p+49;
        p=skiff_sec(b,at,CAFA_NATIVE_CELLS,2+7*n);
        if(!p)return at;
        skiff_u14(b+p,n);
        skiff_lock_steps_t locks=motion_lock_steps(trk_index(t));
        for(unsigned i=0;i<n;i++){
            uint8_t *cell=b+p+2+7*i;
            if(skiff_step_locked(locks,first+i)) cell[2]=8;
            skiff_text(cell+3,4,"");
        }
        return p+2+7*n;
    }
    int grid=drum_track(t) && screen!=CAFA_SCREEN_PATTERN;
    if(grid){
        const char *labels[NLANE];for(unsigned l=0;l<NLANE;l++)labels[l]=drum_lane_name(t,l);
        unsigned row_at=at;at=skiff_rows(b,at,NLANE,labels,0,ui.lane);
        for(unsigned l=0;l<NLANE;l++)if((drum_mute[trk_index(t)]>>l)&1u)b[row_at+4+48*l+40]|=4;
        skiff_u14(b+24,ui.lane);skiff_u14(b+28,NLANE);
    } else if(screen==CAFA_SCREEN_PATTERN){
        const char *labels[]={"DIR"},*values[]={N_DIR[t->p[P_DIR]%NELEM(N_DIR)]};
        unsigned r=at;at=skiff_rows(b,at,1,labels,values,16);
        skiff_u14(b+r+4+41,t->p[P_DIR]);skiff_u14(b+r+4+43,NELEM(N_DIR));
        unsigned cursor = ui.cursor < len ? ui.cursor : len ? len - 1u : 0u;
        first = cursor / 64u * 64u;
        n = len - first;
        if (n > 64u) n = 64u;
        skiff_u14(b + 17u, first);
        b[19] = (uint8_t)(n > 16u ? 16u : n);
    }
    unsigned count=grid?NLANE*16:n,p=skiff_sec(b,at,CAFA_NATIVE_CELLS,2+7*count);
    if(!p)return at;
    skiff_u14(b+p,count);
    skiff_lock_steps_t locks=motion_lock_steps(trk_index(t));
    for(unsigned i=0;i<count;i++){
        unsigned l=grid?i/16:0,si=first+(grid?i%16:i);uint8_t *q=b+p+2+7*i;
        skiff_text(q+3,4,"");if(si>=len)continue;
        const step_t *st=&t->step[si];
        int on=grid?(step_lanes(st)>>l)&1u:step_on(st);
        int accent=grid?(step_accents(st)>>l)&1u:(st->flags&SF_ACCENT)!=0;
        q[2]=(on?1:0)|(si==ui.cursor?2:0)|(song.playing&&si==t->seq_idx?4:0)|
             (step_tie(st)?8:0)|(accent?32:0);
        if(grid){
            skiff_u14(q,8192+clamp(st->nudge,-32,31)*256);
            q[3]='0'+step_ratchet(st); /* opt-in numeric ratchet count for native drum grid */
        } else if(screen==CAFA_SCREEN_FEEL){
            char v[12];feel_text(v,st,nsel.view);skiff_text(q+3,4,v);
            q[2]=(q[2]&~32u)|((step_chance(st)<100u || st->cond)?32:0)|(skiff_step_locked(locks,si)?16:0);
        }
    }
    return p+2+7*count;
}
