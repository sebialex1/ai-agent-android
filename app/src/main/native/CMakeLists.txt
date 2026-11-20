project(sdncnn)
cmake_minimum_required(VERSION 3.18)

set(ncnn_DIR ${ncnn_DIR})
find_package(ncnn REQUIRED)

add_library(sdncnn SHARED sdncnn_jni.cpp)

target_link_libraries(sdncnn ncnn)

target_compile_options(sdncnn PRIVATE -O2 -fexceptions -frtti -fvisibility=hidden)
