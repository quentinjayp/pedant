# espeak-ng for the expected sounds: the phonemes Piper is given, word by word (see Phonemizer.kt).
# The same espeak-ng as sherpa-onnx 1.13.8 builds for Piper (its cmake/espeak-ng-for-piper.cmake),
# with the same options, so it reads the espeak-ng-data the voices come with the same way.
cmake_minimum_required(VERSION 3.22.1)
project(pedant_espeak C CXX)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_ESPEAK_NG_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_ESPEAK_NG_EXE OFF CACHE BOOL "" FORCE)
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
set(USE_KLATT OFF CACHE BOOL "" FORCE)
set(USE_SPEECHPLAYER OFF CACHE BOOL "" FORCE)
set(EXTRA_cmn ON CACHE BOOL "" FORCE)
set(EXTRA_ru ON CACHE BOOL "" FORCE)

FetchContent_Declare(espeak_ng
  URL https://github.com/csukuangfj/espeak-ng/archive/ed530aa113046142eb5115cf2fc9157854d0ffe1.zip
  URL_HASH SHA256=e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd
)
FetchContent_MakeAvailable(espeak_ng)

# Kept inside this library: sherpa-onnx has its own espeak-ng, and the two mustn't share symbols.
set_target_properties(espeak-ng ucd PROPERTIES
  POSITION_INDEPENDENT_CODE ON
  C_VISIBILITY_PRESET hidden
  CXX_VISIBILITY_PRESET hidden
)
target_compile_options(espeak-ng PRIVATE -Wno-unused-result -Wno-format -Wno-uninitialized)
target_include_directories(espeak-ng INTERFACE
  ${espeak_ng_SOURCE_DIR}/src/include
  ${espeak_ng_SOURCE_DIR}/src/ucd-tools/src/include
)

if(ANDROID)
  add_library(pedant_espeak SHARED jni.c phonemize.c)
  target_link_libraries(pedant_espeak PRIVATE espeak-ng log)
  target_link_options(pedant_espeak PRIVATE "-Wl,--exclude-libs,ALL")
else()
  # For the check in CI (.github/workflows/android.yml, check-espeak).
  add_executable(pedant_espeak_test test_main.c phonemize.c)
  target_link_libraries(pedant_espeak_test PRIVATE espeak-ng)
endif()
