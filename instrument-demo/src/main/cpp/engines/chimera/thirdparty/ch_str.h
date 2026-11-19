/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Shan-Sherif
 * fm-1-chimera-vocal: the few string functions the front end uses (ch_speech.c, ch_live.c). The FM-1's DSP units are
 * built without the C library's headers, so on the device they are these; elsewhere the library's. */
#ifndef CH_STR_H
#define CH_STR_H
#include <stddef.h>
#if defined(CH_FM1) && !defined(PLAT_HOST)
static inline size_t ch_strlen(const char *s)
{
    const char *p = s;
    while (*p)
        p++;
    return (size_t)(p - s);
}
static inline int ch_strcmp(const char *a, const char *b)
{
    while (*a && *a == *b)
        a++, b++;
    return (unsigned char)*a - (unsigned char)*b;
}
static inline int ch_memcmp(const void *a, const void *b, size_t n)
{
    const unsigned char *x = (const unsigned char *)a, *y = (const unsigned char *)b;
    for (; n; n--, x++, y++)
        if (*x != *y)
            return *x - *y;
    return 0;
}
static inline void *ch_memcpy(void *d, const void *s, size_t n)
{
    unsigned char *x = (unsigned char *)d;
    const unsigned char *y = (const unsigned char *)s;
    while (n--)
        *x++ = *y++;
    return d;
}
static inline void *ch_memmove(void *d, const void *s, size_t n)
{
    unsigned char *x = (unsigned char *)d;
    const unsigned char *y = (const unsigned char *)s;
    if (x < y)
        while (n--)
            *x++ = *y++;
    else
        while (n--)
            x[n] = y[n];
    return d;
}
static inline void *ch_memset(void *d, int c, size_t n)
{
    unsigned char *x = (unsigned char *)d;
    while (n--)
        *x++ = (unsigned char)c;
    return d;
}
static inline char *ch_strchr(const char *s, int c)
{
    for (;; s++) {
        if (*s == (char)c)
            return (char *)s;
        if (!*s)
            return 0;
    }
}
static inline char *ch_strstr(const char *h, const char *n)
{
    size_t k = ch_strlen(n);
    for (; *h; h++)
        if (!ch_memcmp(h, n, k))
            return (char *)h;
    return k ? 0 : (char *)h;
}
static inline char *ch_strcpy(char *d, const char *s)
{
    char *r = d;
    while ((*d++ = *s++))
        ;
    return r;
}
static inline char *ch_strncpy(char *d, const char *s, size_t n)
{
    size_t i = 0;
    for (; i < n && s[i]; i++)
        d[i] = s[i];
    for (; i < n; i++)
        d[i] = 0;
    return d;
}
#define strlen ch_strlen
#define strcmp ch_strcmp
#define memcmp ch_memcmp
#define memcpy ch_memcpy
#define memmove ch_memmove
#define memset ch_memset
#define strchr ch_strchr
#define strstr ch_strstr
#define strcpy ch_strcpy
#define strncpy ch_strncpy
#else
#include <string.h>
#endif
#endif
