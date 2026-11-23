LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := azcap
LOCAL_SRC_FILES := azcap.cpp fdmap.cpp hooks_net.cpp hooks_log.cpp hooks_ssl.cpp \
                   xhook/xhook.c xhook/xh_core.c xhook/xh_elf.c xhook/xh_jni.c \
                   xhook/xh_log.c xhook/xh_util.c xhook/xh_version.c
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/xhook
LOCAL_CPPFLAGS  := -std=c++17 -fno-exceptions -fno-rtti -Wall -Wno-unused-parameter
LOCAL_CONLYFLAGS := -std=c11 -Wall -Wno-unused-parameter
LOCAL_LDLIBS    := -llog -ldl
LOCAL_LDFLAGS   := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
