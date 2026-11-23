LOCAL_PATH := $(call my-dir)
XHOOK_DIR := $(LOCAL_PATH)/../third_party/xhook/libxhook/jni

include $(CLEAR_VARS)
LOCAL_MODULE    := azcap
LOCAL_SRC_FILES := azcap.cpp fdmap.cpp hooks_net.cpp hooks_log.cpp hooks_ssl.cpp \
                   $(XHOOK_DIR)/xhook.c $(XHOOK_DIR)/xh_core.c $(XHOOK_DIR)/xh_elf.c \
                   $(XHOOK_DIR)/xh_jni.c $(XHOOK_DIR)/xh_log.c $(XHOOK_DIR)/xh_util.c \
                   $(XHOOK_DIR)/xh_version.c
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(XHOOK_DIR)
LOCAL_CPPFLAGS  := -std=c++17 -fno-exceptions -fno-rtti -Wall -Wno-unused-parameter
LOCAL_CONLYFLAGS := -std=c11 -Wall -Wno-unused-parameter
LOCAL_LDLIBS    := -llog -ldl
LOCAL_LDFLAGS   := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
