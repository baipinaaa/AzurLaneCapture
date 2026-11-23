APP_ABI := arm64-v8a
APP_PLATFORM := android-22
APP_STL := c++_static
APP_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti -O2 -fvisibility=hidden
APP_LDFLAGS := -Wl,-z,max-page-size=16384
