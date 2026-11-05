cmake_minimum_required(VERSION 3.22.1)
project(jaecoo_camera_adapter C)
set(CMAKE_C_STANDARD 11)
add_library(cameraadapter SHARED
    native/own/android_yuv.c
    native/bridge.c
    native/own/hab_transport.c
    native/own/own_camera.c
    native/own/own_camera_jni.c
    native/own/own_mosaic.c
    native/own/own_mosaic_jni.c
    native/own/own_lens.c
    native/own/own_lens_jni.c)
target_compile_definitions(cameraadapter PRIVATE _POSIX_C_SOURCE=200809L)
target_compile_options(cameraadapter PRIVATE -O2 -Wall -Wextra -pthread)
target_link_options(cameraadapter PRIVATE -Wl,--no-undefined)
target_link_libraries(cameraadapter log android m)
