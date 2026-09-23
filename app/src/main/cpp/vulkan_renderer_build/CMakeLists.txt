cmake_minimum_required(VERSION 3.22.1)
project(GameNativeVulkanRenderer LANGUAGES C CXX)

set(NATIVE_ROOT "${CMAKE_CURRENT_LIST_DIR}/..")
set(GEN_INSTALL_TARGET OFF CACHE BOOL "" FORCE)
add_subdirectory(
    "${NATIVE_ROOT}/extras/adrenotools"
    "${CMAKE_BINARY_DIR}/adrenotools"
    EXCLUDE_FROM_ALL
)

find_program(GLSLANG_VALIDATOR glslangValidator REQUIRED)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
    COMMAND ${GLSLANG_VALIDATOR} -V --vn window_vert_code -x
        -o ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
        ${NATIVE_ROOT}/winlator/window.vert
    DEPENDS ${NATIVE_ROOT}/winlator/window.vert
)
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
    COMMAND ${GLSLANG_VALIDATOR} -V --vn window_frag_code -x
        -o ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
        ${NATIVE_ROOT}/winlator/window.frag
    DEPENDS ${NATIVE_ROOT}/winlator/window.frag
)

add_library(vulkan_renderer SHARED
    ${NATIVE_ROOT}/winlator/vulkan_jni.cpp
    ${NATIVE_ROOT}/winlator/VulkanRendererContext.cpp
    ${NATIVE_ROOT}/winlator/VulkanRendererScanout.cpp
    ${NATIVE_ROOT}/apex/apex_frame_target_ring.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
    ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
)

target_include_directories(vulkan_renderer PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}
    ${NATIVE_ROOT}
)
target_compile_options(vulkan_renderer PRIVATE
    -Wall -Wextra -fvisibility=hidden -std=c++17
)
target_link_libraries(vulkan_renderer
    log
    android
    vulkan
    adrenotools
    dl
    atomic
)
target_link_options(vulkan_renderer PRIVATE -Wl,-z,max-page-size=16384)
