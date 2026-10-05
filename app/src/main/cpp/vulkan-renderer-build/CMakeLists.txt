cmake_minimum_required(VERSION 3.22.1)

project(GameNativeVulkanRenderer LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(WINLATOR_DIR "${CMAKE_CURRENT_LIST_DIR}/../winlator")
set(LSFG_DIR "${CMAKE_CURRENT_LIST_DIR}/../lsfg")
set(LSFG_VK_ANDROID_DIR "${CMAKE_CURRENT_LIST_DIR}/../lsfg-vk-android")
set(ADRENOTOOLS_DIR "${CMAKE_CURRENT_LIST_DIR}/../extras/adrenotools")

add_subdirectory(
    "${ADRENOTOOLS_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/adrenotools"
)

# Reuse the same DXBC translator target that the pinned lsfg-vk-android
# build uses. The LSFG native renderer compiles lsfg_dxbc.cpp directly, so
# this dependency must be part of the standalone renderer target as well.
add_subdirectory(
    "${LSFG_VK_ANDROID_DIR}/thirdparty/dxbc"
    "${CMAKE_CURRENT_BINARY_DIR}/dxbc"
    EXCLUDE_FROM_ALL
)

find_program(GLSLANG_VALIDATOR glslangValidator REQUIRED)

add_custom_command(
    OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/window_vert.h"
    COMMAND "${GLSLANG_VALIDATOR}" -V --vn window_vert_code -x
            -o "${CMAKE_CURRENT_BINARY_DIR}/window_vert.h"
            "${WINLATOR_DIR}/window.vert"
    DEPENDS "${WINLATOR_DIR}/window.vert"
    VERBATIM
)

add_custom_command(
    OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/window_frag.h"
    COMMAND "${GLSLANG_VALIDATOR}" -V --vn window_frag_code -x
            -o "${CMAKE_CURRENT_BINARY_DIR}/window_frag.h"
            "${WINLATOR_DIR}/window.frag"
    DEPENDS "${WINLATOR_DIR}/window.frag"
    VERBATIM
)

add_library(
    vulkan_renderer
    SHARED
    "${WINLATOR_DIR}/vulkan_jni.cpp"
    "${WINLATOR_DIR}/VulkanRendererContext.cpp"
    "${WINLATOR_DIR}/VulkanRendererLsfg.cpp"
    "${WINLATOR_DIR}/VulkanRendererScanout.cpp"
    "${LSFG_DIR}/vk_dispatch.c"
    "${LSFG_DIR}/vkr_lsfg.cpp"
    "${LSFG_DIR}/lsfg_alpha.cpp"
    "${LSFG_DIR}/lsfg_beta.cpp"
    "${LSFG_DIR}/lsfg_chain.cpp"
    "${LSFG_DIR}/lsfg_common.cpp"
    "${LSFG_DIR}/lsfg_delta.cpp"
    "${LSFG_DIR}/lsfg_dxbc.cpp"
    "${LSFG_DIR}/lsfg_gamma.cpp"
    "${LSFG_DIR}/lsfg_generate.cpp"
    "${LSFG_DIR}/lsfg_mipmaps.cpp"
    "${LSFG_DIR}/lsfg_pacer.cpp"
    "${LSFG_DIR}/lsfg_shaders.cpp"
    "${CMAKE_CURRENT_BINARY_DIR}/window_vert.h"
    "${CMAKE_CURRENT_BINARY_DIR}/window_frag.h"
)

target_include_directories(
    vulkan_renderer
    PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${LSFG_DIR}"
    "${LSFG_VK_ANDROID_DIR}/thirdparty/dxbc/src/dxbc"
)

target_compile_options(
    vulkan_renderer
    PRIVATE
    -Wall
    -Wextra
    -fvisibility=hidden
)

target_link_libraries(
    vulkan_renderer
    log
    android
    vulkan
    adrenotools
    dxbc
    dl
    atomic
)

target_link_options(
    vulkan_renderer
    PRIVATE
    -Wl,-z,max-page-size=16384
)
