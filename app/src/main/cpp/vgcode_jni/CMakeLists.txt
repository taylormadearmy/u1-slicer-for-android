cmake_minimum_required(VERSION 3.22.1)
project(vgcode-jni LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable OpenGL ES path in libvgcode
set(SLIC3R_OPENGL_ES ON CACHE BOOL "Use OpenGL ES" FORCE)

# Add libvgcode via its own CMakeLists.txt
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../libvgcode libvgcode_build)

# JNI shared library
add_library(vgcode-jni SHARED vgcode_bridge.cpp glad_android_stub.c)

target_include_directories(vgcode-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../libvgcode/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../libvgcode/src
)

target_link_libraries(vgcode-jni
    libvgcode
    GLESv3
    EGL
    log
    android
)
