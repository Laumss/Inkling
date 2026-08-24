cmake_minimum_required(VERSION 3.18.1)
project(inkfilter_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(inkfilter_jni SHARED
    inkfilter.cpp
)

target_compile_options(inkfilter_jni PRIVATE -O3 -fvisibility=hidden)

target_link_libraries(inkfilter_jni
    PRIVATE jnigraphics
    PRIVATE log
)
