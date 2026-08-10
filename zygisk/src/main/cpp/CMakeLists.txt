cmake_minimum_required(VERSION 3.10)

project(zygisk)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(${VECTOR_ROOT}/native native)

add_library(${PROJECT_NAME} SHARED module.cpp ipc_bridge.cpp)
target_include_directories(${PROJECT_NAME} PUBLIC include)

# The Zygisk loader only resolves these two ABI entry points by name. Everything else in this
# shared object is an implementation detail: JNI methods are registered explicitly with
# RegisterNatives, while LSPlant/Dobby and the native core are linked statically. Keeping their
# default-visibility symbols in .dynsym exposes a process-wide framework fingerprint to injected
# applications and is unnecessary for runtime linkage.
set(ZYGISK_EXPORT_MAP "${CMAKE_CURRENT_BINARY_DIR}/zygisk.exports.map")
file(WRITE "${ZYGISK_EXPORT_MAP}" "{
    global:
        zygisk_module_entry;
    local:
        *;
};
")
set_property(TARGET ${PROJECT_NAME} APPEND_STRING PROPERTY LINK_FLAGS
    " -Wl,--version-script=${ZYGISK_EXPORT_MAP}")

if (NOT DEFINED VECTOR_INJECTED_PACKAGE_NAME)
    set(VECTOR_INJECTED_PACKAGE_NAME "com.android.shell")
endif()

if (NOT DEFINED VECTOR_INJECTED_PACKAGE_UID)
    set(VECTOR_INJECTED_PACKAGE_UID 2000)
endif()

if (NOT DEFINED VECTOR_MANAGER_PACKAGE_NAME)
    set(VECTOR_MANAGER_PACKAGE_NAME "org.lsposed.manager")
endif()

target_compile_definitions(${PROJECT_NAME} PRIVATE
    INJECTED_PACKAGE_NAME="${VECTOR_INJECTED_PACKAGE_NAME}"
    INJECTED_PACKAGE_UID=${VECTOR_INJECTED_PACKAGE_UID}
    MANAGER_PACKAGE_NAME="${VECTOR_MANAGER_PACKAGE_NAME}"
)

target_link_libraries(${PROJECT_NAME} native log)

if (DEFINED DEBUG_SYMBOLS_PATH)
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>
            COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            $<TARGET_FILE:${PROJECT_NAME}>)
endif()
