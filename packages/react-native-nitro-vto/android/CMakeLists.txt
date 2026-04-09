project(NitroVto)
cmake_minimum_required(VERSION 3.9.0)

set(PACKAGE_NAME NitroVto)
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD 20)

# Enable Raw Props parsing in react-native (for Nitro Views)
add_compile_options(-DRN_SERIALIZABLE_STATE=1)

# Define C++ library and add all sources
add_library(${PACKAGE_NAME} SHARED
        src/main/cpp/cpp-adapter.cpp
        src/main/cpp/VtoCoreJniBridge.cpp
        ../cpp/core/VtoRendererCore.cpp
        ../cpp/core/model/ModelState.cpp
        ../cpp/core/lighting/LightingState.cpp
        ../cpp/core/tracking/FaceTrackingState.cpp
        ../cpp/core/render/FilamentRenderer.cpp
        ../cpp/core/platform/android/AndroidSurfaceBridge.cpp
)

# Add Nitrogen specs :)
include(${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/NitroVto+autolinking.cmake)

# Set up local includes
include_directories(
        "src/main/cpp"
        "../cpp"
        "../third_party/filament/sdk/android/include"
)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(EGL_LIB EGL)
find_library(GLESV3_LIB GLESv3)

file(GLOB FILAMENT_STATIC_LIBS
        "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/filament/sdk/android/lib/${ANDROID_ABI}/*.a")

# Link all libraries together
target_link_libraries(
        ${PACKAGE_NAME}
        ${LOG_LIB}
        ${ANDROID_LIB}
        ${EGL_LIB}
        ${GLESV3_LIB}
        ${FILAMENT_STATIC_LIBS}
)
