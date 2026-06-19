cmake_minimum_required(VERSION 3.22.1)
project(lowlatencyvisualizer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Google Oboe — fetched, compiled and linked automatically.
# FetchContent clones a pinned, reproducible tag and builds it as part of our
# native build. No manual download / AAR / prefab wiring required.
# ---------------------------------------------------------------------------
include(FetchContent)

# Don't build Oboe's samples/tests — we only need the static library.
set(OBOE_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(OBOE_BUILD_TESTS    OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        oboe
        GIT_REPOSITORY https://github.com/google/oboe.git
        GIT_TAG        1.9.0          # pinned for reproducible builds
        GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(oboe)

# ---------------------------------------------------------------------------
# KissFFT — lightweight FFT, fetched + compiled in-tree.
# KISSFFT_STATIC=ON is required: KissFFT ignores BUILD_SHARED_LIBS and even
# forces it ON otherwise, producing a standalone libkissfft-float.so that is
# NOT 16 KB-aligned. Building it static links it directly into libnative-lib.so
# (which carries the max-page-size flag), so no separate .so ships in the APK.
# ---------------------------------------------------------------------------
set(KISSFFT_STATIC    ON  CACHE BOOL   "" FORCE)
set(KISSFFT_TEST      OFF CACHE BOOL   "" FORCE)
set(KISSFFT_TOOLS     OFF CACHE BOOL   "" FORCE)
set(KISSFFT_PKGCONFIG OFF CACHE BOOL   "" FORCE)
set(KISSFFT_DATATYPE  float CACHE STRING "" FORCE)

FetchContent_Declare(
        kissfft
        GIT_REPOSITORY https://github.com/mborgerding/kissfft.git
        GIT_TAG        131.1.0        # pinned for reproducible builds
        GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(kissfft)

# ---------------------------------------------------------------------------
# Ableton Link — wireless tempo/beat sync (Traktor, Ableton Live, etc.).
# Link is header-only but pulls asio-standalone as a git submodule (fetched
# automatically). We do NOT add_subdirectory it: its `Ableton::Link` target is an
# IMPORTED INTERFACE that would be scoped to Link's own directory. Instead we just
# download the sources and include AbletonLinkConfig.cmake here, which defines
# `Ableton::Link` in THIS scope (the documented integration path).
# ---------------------------------------------------------------------------
FetchContent_Declare(
        abletonlink
        GIT_REPOSITORY https://github.com/Ableton/link.git
        GIT_TAG        Link-3.1.2     # pinned for reproducible builds
        GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(abletonlink)
if(NOT abletonlink_POPULATED)
        FetchContent_Populate(abletonlink)
endif()
include(${abletonlink_SOURCE_DIR}/AbletonLinkConfig.cmake)

# The wrapper: compiled WITH exceptions/RTTI, which override the global
# -fno-exceptions (target options come after CMAKE_CXX_FLAGS on the command
# line). It links the Ableton::Link interface (headers + asio) and exposes a
# plain interface that the no-exceptions engine can call safely.
# LINK_PLATFORM_LINUX=1 forces Link's posix backend: its config only sets this
# when CMAKE_SYSTEM_NAME=="Linux", but on Android it's "Android", so we set it
# explicitly (Android uses the Linux/posix clock + networking implementation).
add_library(linkbridge STATIC LinkController.cpp)
target_link_libraries(linkbridge PUBLIC Ableton::Link)
target_compile_definitions(linkbridge PRIVATE LINK_PLATFORM_LINUX=1)
target_compile_options(linkbridge PRIVATE -O3 -fexceptions -frtti)

# ---------------------------------------------------------------------------
# Our native library
# ---------------------------------------------------------------------------
add_library(native-lib SHARED
        native-lib.cpp
        AudioEngine.cpp
        FftProcessor.cpp)

target_include_directories(native-lib PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${kissfft_SOURCE_DIR})      # for kiss_fft.h / kiss_fftr.h

# Latency matters: optimize hard and drop exception/RTTI overhead.
target_compile_options(native-lib PRIVATE
        -O3
        -ffast-math
        -fno-exceptions
        -fno-rtti)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(native-lib
        oboe            # Oboe transitively brings in AAudio / OpenSL ES
        kissfft         # static FFT, linked into native-lib.so
        linkbridge      # Ableton Link wrapper (exceptions isolated to this lib)
        ${log-lib}
        ${android-lib})

# Align LOAD segments to 16 KB so the APK passes Play Store validation for
# Android 15+ devices (mandatory from November 2025). This flag is a no-op on
# devices with 4 KB pages — it only matters when the kernel uses 16 KB pages.
target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=16384")
