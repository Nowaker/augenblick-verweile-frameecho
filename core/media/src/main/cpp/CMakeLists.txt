cmake_minimum_required(VERSION 3.22.1)
project(frameecho_file_times C)

add_library(frameecho_file_times SHARED file_times.c)
target_compile_options(frameecho_file_times PRIVATE -Wall -Wextra -Werror)
