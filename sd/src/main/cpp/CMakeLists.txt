cmake_minimum_required(VERSION 3.31.6)

project("sd-turbo" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

find_package(Threads REQUIRED)

# --------------------------------------------------------------------------
# stable-diffusion.cpp — isolated from llama.cpp's ggml (its own patched ggml)
# --------------------------------------------------------------------------

set(SD_BUILD_EXAMPLES        OFF CACHE BOOL "" FORCE)
set(SD_WEBP                  OFF CACHE BOOL "" FORCE)
set(SD_WEBM                  OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_GGML       OFF CACHE BOOL "" FORCE)
set(SD_USE_UPSTREAM_GGML     OFF CACHE BOOL "" FORCE)
if(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
else()
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
endif()

set(SD_CPP_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/sd.cpp)
add_subdirectory(${SD_CPP_SRC} build-sd)

# JNI bridge
add_library(sd_jni SHARED sd_image.cpp)
target_include_directories(sd_jni PRIVATE ${SD_CPP_SRC}/include)
target_link_libraries(sd_jni stable-diffusion log)
