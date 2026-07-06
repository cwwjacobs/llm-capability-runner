cmake_minimum_required(VERSION 3.31.6)
project("ai-chat" LANGUAGES C CXX)

include(FetchContent)
set(FETCHCONTENT_QUIET OFF)
FetchContent_Declare(
  llama_cpp
  GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
  GIT_TAG cb295bf59663cd3577389315636772f4060bd1f5
  GIT_SHALLOW FALSE
  GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(
  ai-chat
  SHARED
  gguf_bridge.cpp
)

target_include_directories(
  ai-chat
  PRIVATE
  ${llama_cpp_SOURCE_DIR}
  ${llama_cpp_SOURCE_DIR}/common
  ${llama_cpp_SOURCE_DIR}/include
  ${llama_cpp_SOURCE_DIR}/ggml/include
  ${llama_cpp_SOURCE_DIR}/ggml/src
  ${llama_cpp_SOURCE_DIR}/examples/llama.android/lib/src/main/cpp
  ${llama_cpp_SOURCE_DIR}/tools/mtmd
)

target_link_libraries(ai-chat llama llama-common mtmd android log)
