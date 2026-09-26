# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

project("budsplitter")

# Find the Oboe audio package
find_package(oboe REQUIRED CONFIG)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

# Link Oboe along with the standard Android system libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
        oboe::oboe
        android
        log)