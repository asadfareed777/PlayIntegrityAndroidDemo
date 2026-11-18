cmake_minimum_required(VERSION 3.22.1)
project(secureconfig)

add_library(secureconfig SHARED secure_config.cpp)
find_library(log-lib log)
target_link_libraries(secureconfig ${log-lib})
