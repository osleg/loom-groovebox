/* SPDX-License-Identifier: GPL-3.0-only */
#include "mora.h"
typedef struct { char r[4]; uint8_t v, c; } Mo;
#define M(r,v,c) {r,MV_##v,MC_##c}
static const Mo mo[MORA_N] = {
    M("",A,NONE),
    M("a",A,NONE),M("i",I,NONE),M("u",U,NONE),M("e",E,NONE),M("o",O,NONE),
    M("ka",A,K),M("ki",I,K),M("ku",U,K),M("ke",E,K),M("ko",O,K),
    M("sa",A,S),M("shi",I,SH),M("su",U,S),M("se",E,S),M("so",O,S),
    M("ta",A,T),M("chi",I,CH),M("tsu",U,TS),M("te",E,T),M("to",O,T),
    M("na",A,N),M("ni",I,N),M("nu",U,N),M("ne",E,N),M("no",O,N),
    M("ha",A,H),M("hi",I,H),M("fu",U,F),M("he",E,H),M("ho",O,H),
    M("ma",A,M),M("mi",I,M),M("mu",U,M),M("me",E,M),M("mo",O,M),
    M("ya",A,Y),M("yu",U,Y),M("yo",O,Y),
    M("ra",A,R),M("ri",I,R),M("ru",U,R),M("re",E,R),M("ro",O,R),
    M("wa",A,W),M("wo",O,W),M("n",N,NN),
    M("ga",A,G),M("gi",I,G),M("gu",U,G),M("ge",E,G),M("go",O,G),
    M("za",A,Z),M("ji",I,J),M("zu",U,Z),M("ze",E,Z),M("zo",O,Z),
    M("da",A,D),M("de",E,D),M("do",O,D),
    M("ba",A,B),M("bi",I,B),M("bu",U,B),M("be",E,B),M("bo",O,B),
    M("pa",A,P),M("pi",I,P),M("pu",U,P),M("pe",E,P),M("po",O,P),
    M("sha",A,SH),M("ja",A,J),M("rya",A,R), /* appended IDs: existing 0..69 unchanged; rya uses the R->A approximation */
};
const char *mora_str(uint32_t i){return i<MORA_N?mo[i].r:"";}
uint32_t mora_vowel(uint32_t i){return i<MORA_N?mo[i].v:MV_A;}
uint32_t mora_cons(uint32_t i){return i<MORA_N?mo[i].c:MC_NONE;}
uint32_t mora_find(const char *s){
    uint32_t i,k;
    for(i=1;i<MORA_N;i++){
        for(k=0;mo[i].r[k];k++){
            char c=s[k];
            if(c>='A'&&c<='Z')c=(char)(c+32);
            if(c!=mo[i].r[k])break;
        }
        if(!mo[i].r[k]&&!s[k])return i;
    }
    return 0;
}
