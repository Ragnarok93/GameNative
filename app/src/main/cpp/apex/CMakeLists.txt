cmake_minimum_required(VERSION 3.22.1)
project(GameNativeApex LANGUAGES CXX)

add_library(gamenative_apex SHARED
    apex_pipeline.cpp
    apex_pacing.cpp
    apex_gpu_profile.cpp
    apex_frame_target_ring.cpp
    apex_jni_bridge.cpp
)

target_compile_features(gamenative_apex PRIVATE cxx_std_17)
target_compile_options(gamenative_apex PRIVATE -Wall -Wextra -fvisibility=hidden)
target_include_directories(gamenative_apex PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(gamenative_apex log android EGL GLESv3)
target_link_options(gamenative_apex PRIVATE -Wl,-z,max-page-size=16384)
