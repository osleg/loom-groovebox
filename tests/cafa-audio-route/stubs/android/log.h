#pragma once
#define ANDROID_LOG_DEBUG 0
#define ANDROID_LOG_ERROR 1
inline void __android_log_print(int,const char*,const char*,...) {}
