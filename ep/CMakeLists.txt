cmake_minimum_required(VERSION 3.20)
project(FlyAwayEndpoint
    VERSION 1.0.0
    DESCRIPTION "FlyAway Edge Scanner Kiosk Client"
    LANGUAGES CXX
)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Enable compiler diagnostics
if(MSVC)
    add_compile_options(/W4 /permissive-)
else()
    add_compile_options(-Wall -Wextra -Wpedantic)
endif()

include(FetchContent)
FetchContent_Declare(cpr
    GIT_REPOSITORY https://github.com/libcpr/cpr.git
    GIT_TAG bb01c8db702fb41e5497aee9c0559ddf4bf13749
)
FetchContent_MakeAvailable(cpr)

add_executable(ep src/main.cpp)
target_link_libraries(ep PRIVATE cpr::cpr)

install(TARGETS ep RUNTIME DESTINATION bin)