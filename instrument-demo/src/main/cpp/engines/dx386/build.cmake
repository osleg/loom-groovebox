# SPDX-License-Identifier: GPL-3.0-only
set(dx386 "${CMAKE_CURRENT_LIST_DIR}")
file(STRINGS "${dx386}/espeak-source-list.txt" dx386_espeak_list)
list(TRANSFORM dx386_espeak_list PREPEND "${dx386}/")

add_library(dx386_espeak OBJECT ${dx386_espeak_list})
target_compile_options(dx386_espeak PRIVATE
    -w -O2 -ffp-contract=off -fwrapv -include "${dx386}/thirdparty/dx/espeak_port.h")
target_compile_definitions(dx386_espeak PRIVATE DX_HOST=1)
target_include_directories(dx386_espeak PRIVATE
    "${dx386}/thirdparty/espeak/include" "${dx386}/thirdparty/espeak/src" "${dx386}/thirdparty/dx")
set_target_properties(dx386_espeak PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_library(dx386_runtime STATIC
    "${dx386}/dx386_runtime.c"
    "${dx386}/thirdparty/dx/speech.c"
    "${dx386}/thirdparty/dx/singing.c"
    "${dx386}/thirdparty/dx/speech_runtime.c"
    $<TARGET_OBJECTS:dx386_espeak>)
target_compile_definitions(dx386_runtime PRIVATE DX_HOST=1)
target_compile_options(dx386_runtime PRIVATE -w -O2 -ffp-contract=off -fwrapv)
target_include_directories(dx386_runtime PRIVATE
    "${dx386}/thirdparty/espeak/include" "${dx386}/thirdparty/espeak/src")
set_target_properties(dx386_runtime PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(dx386_runtime PRIVATE m)
