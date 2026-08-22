cmake_minimum_required(VERSION 3.22)
project(stark_signal_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Build only the inference library. Stark Signal has no native CLI, server, test,
# or example binaries and does not link FFmpeg.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/whisper.cpp)

add_library(stark_whisper SHARED stark_whisper_jni.cpp)

target_include_directories(stark_whisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp/include)

find_library(log-lib log)

target_link_libraries(stark_whisper PRIVATE whisper ${log-lib})

# Android 15+ can use 16 KB memory pages. Keep every native load segment
# compatible while retaining support for older devices.
target_link_options(stark_whisper PRIVATE "-Wl,-z,max-page-size=16384")
