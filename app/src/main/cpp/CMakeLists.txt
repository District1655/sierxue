cmake_minimum_required(VERSION 3.22.1)
project(sierxue_llama LANGUAGES C CXX)

if(NOT DEFINED LLAMA_CPP_ROOT)
    message(FATAL_ERROR "请通过 LLAMA_CPP_ROOT 指定 llama.cpp 源码目录")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_ROOT} ${CMAKE_BINARY_DIR}/llama.cpp)
add_library(sierxue_llama SHARED llama_jni.cpp)
target_include_directories(sierxue_llama PRIVATE
    ${LLAMA_CPP_ROOT}/include
    ${LLAMA_CPP_ROOT}/ggml/include
    ${LLAMA_CPP_ROOT}/common
)
target_compile_features(sierxue_llama PRIVATE cxx_std_17)
target_compile_options(sierxue_llama PRIVATE -O3 -fexceptions -frtti)
target_link_libraries(sierxue_llama PRIVATE llama llama-common ggml android log)
