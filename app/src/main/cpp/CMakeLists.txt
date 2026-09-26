cmake_minimum_required(VERSION 3.22.1)

Project(Pluvia)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -s")

#add_subdirectory(proot)
add_subdirectory(virglrenderer)
add_subdirectory(patchelf)
add_subdirectory(extras/adrenotools)

add_library(winlator SHARED
            winlator/drawable.c
            winlator/sysvshared_memory.c
            winlator/xconnector_epoll.c
            winlator/alsa_client.c
            winlator/patchelf_wrapper.cpp)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      aaudio
                      EGL
                      GLESv2
                      GLESv3)

target_link_options(winlator PRIVATE -Wl,-z,max-page-size=16384)


add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
    COMMAND glslangValidator -V --vn window_vert_code -x
            -o ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
            ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.vert
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.vert
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
    COMMAND glslangValidator -V --vn window_frag_code -x
            -o ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
            ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.frag
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/winlator/window.frag
)

set(APEX_VK_SHADER_NAMES
    luma_grad
    inverse_search
    propagate
    densify
    vr_setup
    vr_sor
    interpolate
    rcas
)
set(APEX_VK_SHADER_HEADERS)
foreach(APEX_VK_SHADER IN LISTS APEX_VK_SHADER_NAMES)
    set(APEX_VK_SHADER_SOURCE
        "${CMAKE_CURRENT_SOURCE_DIR}/apex/vulkan/shaders/${APEX_VK_SHADER}.comp")
    set(APEX_VK_SHADER_HEADER
        "${CMAKE_CURRENT_BINARY_DIR}/apex_vk_${APEX_VK_SHADER}.h")
    add_custom_command(
        OUTPUT ${APEX_VK_SHADER_HEADER}
        COMMAND glslangValidator -V --target-env vulkan1.1
            --vn apex_vk_${APEX_VK_SHADER}_code -x
            -o ${APEX_VK_SHADER_HEADER}
            ${APEX_VK_SHADER_SOURCE}
        DEPENDS ${APEX_VK_SHADER_SOURCE}
    )
    list(APPEND APEX_VK_SHADER_HEADERS ${APEX_VK_SHADER_HEADER})
endforeach()

add_library(vulkan_renderer SHARED
            winlator/vulkan_jni.cpp
            winlator/VulkanRendererContext.cpp
            winlator/VulkanRendererScanout.cpp
            apex/apex_frame_target_ring.cpp
            apex/vulkan/apex_vk_backend.cpp
            ${APEX_VK_SHADER_HEADERS}
            ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
            ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h)

target_include_directories(vulkan_renderer PRIVATE ${CMAKE_CURRENT_BINARY_DIR})

target_compile_options(vulkan_renderer PRIVATE
                       -Wall -Wextra -fvisibility=hidden -std=c++17)

target_link_libraries(vulkan_renderer
                      log
                      android
                      vulkan
                      adrenotools
                      dl
                      atomic)

target_link_options(vulkan_renderer PRIVATE -Wl,-z,max-page-size=16384)
