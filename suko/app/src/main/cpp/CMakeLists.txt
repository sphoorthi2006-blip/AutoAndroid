cmake_minimum_required(VERSION 3.18.1)
project(upscaler CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The prebuilt ncnn (Vulkan) is downloaded by the build workflow into ./ncnn/ncnn-<date>-android-vulkan
file(GLOB NCNN_ROOTS "${CMAKE_CURRENT_SOURCE_DIR}/ncnn/ncnn-*-android-vulkan")
list(LENGTH NCNN_ROOTS NCNN_COUNT)
if(NCNN_COUNT EQUAL 0)
    message(FATAL_ERROR "ncnn not found in ${CMAKE_CURRENT_SOURCE_DIR}/ncnn. See the 'Fetch ncnn' step in .github/workflows/build.yml")
endif()
list(GET NCNN_ROOTS 0 NCNN_ROOT)

set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

add_library(upscaler SHARED upscaler.cpp)
# -static-openmp: do not depend on a separate libomp.so that the APK would not contain
target_link_libraries(upscaler PRIVATE ncnn android log -fopenmp -static-openmp)
