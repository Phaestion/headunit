cmake_minimum_required(VERSION 3.5)
project(hu_jni)

set(PROJECT_NAME_STR hu_jni)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wno-error")

include_directories(includes)

set(SOURCE_FILES
        hu_aap.c
        hu_jni.c
        hu_mai.c
        hu_ssl.c
        hu_tcp.c
        hu_usb.c
        hu_uti.c)

if(ANDROID_ABI STREQUAL "armv7-a")
    link_directories(libs)
elseif(ANDROID_ABI STREQUAL "aarch64")
    link_directories(libsa64)
else(ANDROID_ABI STREQUAL "i686")
    link_directories(libsx86)
endif()

add_library(hu_jni STATIC ${SOURCE_FILES})
target_link_libraries(hu_jni log ssl crypto usbnok)