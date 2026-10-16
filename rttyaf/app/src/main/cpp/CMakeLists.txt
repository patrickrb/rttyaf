cmake_minimum_required(VERSION 3.18.1)

# librttyaf.so: the USB-audio (libusb) bridge only. The RTTY modem is pure
# Kotlin; the FT8 DSP (ft8_lib) and its JNI glue were removed with the FT8
# engine. What remains lets the app drive a USB sound card (CM108-style codecs
# and car-dash kernels) directly when Android's own USB audio path can't.
project(rttyaf_native)

# libusb (static), built from the vendored source tree.
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)
add_library(usb1.0 STATIC
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)
target_include_directories(usb1.0
        PUBLIC
        ${LIBUSB_ROOT}/libusb
        PRIVATE
        ${LIBUSB_ROOT}/android
        ${LIBUSB_ROOT}/libusb/os
)
target_compile_options(usb1.0 PRIVATE
        -fvisibility=hidden
        -pthread
        -Wno-pointer-arith
        -Wno-deprecated-declarations
)

# The JNI bridge (UsbAudioNative): iso capture with FIR decimation / rational
# resampling to the modem rate, and iso playback with live TX gain.
add_library(rttyaf SHARED
        usb_audio.cpp
        usb_audio_capture.cpp
)

find_library(log-lib log)
target_link_options(rttyaf PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(rttyaf
        usb1.0
        ${log-lib}
)
