# GeneralsX @feature fbraz 03/05/2026
# Deterministic cross-platform math library integration (Phase 4)
# GameMath: fdlibm-based deterministic math functions for bit-exact replay validation
#
# Strategy:
# - Enable deterministic math via FetchContent (not on VC6, which uses x87 asm)
# - Define USE_DETERMINISTIC_MATH compile flag when enabled
# - Wrappers in wwmath.h conditionally dispatch to GameMath (deterministic) or CRT (fast fallback)
#
# Reference: Okladnoj et al., PR #2670, TheSuperHackers/GeneralsGameCode
# https://github.com/TheSuperHackers/GeneralsGameCode/pull/2670
#
# IMPORTANT FOR ANDROID CROSS-PLAY:
# The GeneralsOnline Windows client is not an fdlibm/GameMath build. Our Android port
# instead reproduces the PC client's 32-bit MSVC math behaviour in
# GeneralsMD/Code/Main/ReferenceFloatMath.cpp and disables FMA contraction in
# cmake/compilers.cmake. Enabling fdlibm for only the Android peer would create a new
# lockstep contract and can desync from the Windows client. Therefore the Android
# cross-play configuration explicitly keeps GameMath disabled.

# Enable deterministic math only for non-VC6 builds (VC6 uses native x87 asm).
# It remains opt-in for non-cross-play builds until the complete upstream integration
# (including all WWMath call-site routing) is imported.
if(NOT IS_VS6_BUILD)
    option(SAGE_USE_DETERMINISTIC_MATH "Use fdlibm-based deterministic math for cross-platform replay validation" OFF)
else()
    set(SAGE_USE_DETERMINISTIC_MATH OFF)
endif()

# GeneralsX @bugfix Android cross-play 15/10/2026
# Do not mix the experimental fdlibm contract with the production GeneralsOnline PC
# contract. ReferenceFloatMath.cpp is the compatibility layer used by Android instead.
if(ANDROID AND SAGE_ANDROID_CROSSPLAY_COMPAT)
    if(SAGE_USE_DETERMINISTIC_MATH)
        message(WARNING
            "SAGE_USE_DETERMINISTIC_MATH was requested for an Android cross-play build; "
            "forcing it OFF because GeneralsOnline PC uses the MSVC reference math contract")
    endif()
    set(SAGE_USE_DETERMINISTIC_MATH OFF CACHE BOOL
        "Use fdlibm-based deterministic math for cross-platform replay validation" FORCE)
    message(STATUS
        "Android cross-play math: using MSVC-reference compatibility path (ReferenceFloatMath.cpp), not fdlibm")
endif()

if(SAGE_USE_DETERMINISTIC_MATH)
    message(STATUS "Configuring GameMath (fdlibm-based deterministic math)...")

    include(FetchContent)

    # FetchContent declaration for GameMath library.
    # This path is experimental in this fork and is intentionally not used by the
    # Android/GeneralsOnline cross-play build above.
    if(NOT SAGE_GAMEMATH_GIT_REPO)
        set(SAGE_GAMEMATH_GIT_REPO "https://github.com/TheSuperHackers/GeneralsGameCode.git")
    endif()

    if(NOT SAGE_GAMEMATH_GIT_TAG)
        # Pinned SHA: tracking "main" let the deterministic-math source drift silently.
        set(SAGE_GAMEMATH_GIT_TAG "838f9d0fd1c658ec795d3903a4f3f7e265b6e6d5")
    endif()

    FetchContent_Declare(
        gamemath
        GIT_REPOSITORY ${SAGE_GAMEMATH_GIT_REPO}
        GIT_TAG ${SAGE_GAMEMATH_GIT_TAG}
        SOURCE_SUBDIR "Core/GameMath"
    )

    set(GAMEMATH_ENABLE_TESTS OFF CACHE BOOL "Disable GameMath tests" FORCE)
    set(GAMEMATH_ENABLE_EXAMPLES OFF CACHE BOOL "Disable GameMath examples" FORCE)

    FetchContent_MakeAvailable(gamemath)

    add_compile_definitions(USE_DETERMINISTIC_MATH)

    message(STATUS "GameMath deterministic math enabled (fdlibm backend)")
    message(STATUS "  WARNING: this is not the GeneralsOnline PC cross-play math contract")
else()
    if(NOT (ANDROID AND SAGE_ANDROID_CROSSPLAY_COMPAT))
        message(STATUS "Deterministic math disabled (SAGE_USE_DETERMINISTIC_MATH=OFF)")
        message(STATUS "  Math operations will use the platform/reference compatibility path")
    endif()
endif()
