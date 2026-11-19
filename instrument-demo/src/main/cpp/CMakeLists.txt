cmake_minimum_required(VERSION 3.22.1)
project(skiff_companion C CXX)

if(NOT SKIFF_SOURCE_DIR OR NOT EXISTS "${SKIFF_SOURCE_DIR}/web/emu/felucca_web.c")
    message(FATAL_ERROR "SKIFF_SOURCE_DIR must contain stock Skiff web/emu/felucca_web.c")
endif()
if(NOT SKIFF_GENERATED_DIR OR NOT EXISTS "${SKIFF_GENERATED_DIR}/felucca_tables.h" OR
        NOT EXISTS "${SKIFF_GENERATED_DIR}/felucca_samples.h")
    message(FATAL_ERROR "SKIFF_GENERATED_DIR must contain stock generated tables and samples")
endif()
if(NOT SKIFF_PROTOCOL_DIR OR NOT EXISTS "${SKIFF_PROTOCOL_DIR}/cafa_native_view.h")
    message(FATAL_ERROR "SKIFF_PROTOCOL_DIR must contain the companion native view wire contract")
endif()

set(plaits "${CMAKE_CURRENT_SOURCE_DIR}/engines/plaits")
file(GLOB_RECURSE plaits_models CONFIGURE_DEPENDS "${plaits}/thirdparty/eurorack/*.cc")
set(vocal_manifest "${CMAKE_CURRENT_SOURCE_DIR}/vocal-sources.txt")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${vocal_manifest}")
file(STRINGS "${vocal_manifest}" vocal_sources)
include(engines/dx386/build.cmake)

add_library(skiff-companion SHARED
    skiff_runtime.c
    engines/atek303/thirdparty/atek_voice.c
    engines/cz/thirdparty/cz_voice.c
    engines/dock_analog/thirdparty/arp_engine.c
    engines/dock_analog/thirdparty/em_engine.c
    engines/dock_modeled/thirdparty/kalimba.c
    engines/dock_modeled/thirdparty/monk.c
    engines/esq/thirdparty/esq_voice.c
    engines/obervave/thirdparty/obv_voice.c
    engines/opn/thirdparty/opn2.c
    engines/opn/thirdparty/psg.c
    engines/plaits/plaits_bridge.cc
    engines/sid/thirdparty/sid.c
    engines/synthex/thirdparty/sc_voice.c
    engines/x0x/thirdparty/drum808.c
    engines/x0x/thirdparty/drum909.c
    ${vocal_sources}
    ${plaits_models})
target_compile_features(skiff-companion PRIVATE c_std_99 cxx_std_11)
target_compile_options(skiff-companion PRIVATE -O2 -ffp-contract=off -w)
target_compile_definitions(skiff-companion PRIVATE $<$<COMPILE_LANGUAGE:CXX>:TEST>)
target_include_directories(skiff-companion PRIVATE
    "${SKIFF_SOURCE_DIR}/web/emu"
    "${SKIFF_SOURCE_DIR}/firmware/src"
    "${SKIFF_GENERATED_DIR}"
    "${plaits}/thirdparty/eurorack"
    "${SKIFF_PROTOCOL_DIR}")
target_link_libraries(skiff-companion PRIVATE dx386_runtime m)
