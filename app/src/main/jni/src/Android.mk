LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := main

SDL_PATH := ../SDL2
SDL_NET_PATH := ../SDL_net-2.0.1

LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(SDL_PATH)/include \
	$(LOCAL_PATH)/$(SDL_NET_PATH) \
	$(LOCAL_PATH)/../../../../include \
	$(LOCAL_PATH)

# NOTE: The following patch is required in order to take advantage of the
# ARMv7 recompiling core: http://vogons.zetafleet.com/viewtopic.php?t=31787
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
	LOCAL_CPPFLAGS += -DC_TARGETCPU=ARMV7LE
endif
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
	LOCAL_CPPFLAGS += -DC_TARGETCPU=ARMV8LE
endif
ifeq ($(TARGET_ARCH_ABI),armeabi)
	LOCAL_CPPFLAGS += -DC_TARGETCPU=ARMV4LE
endif
ifeq ($(TARGET_ARCH_ABI),x86)
	LOCAL_CPPFLAGS += -DC_TARGETCPU=X86 -DC_FPU_X86=1
endif
ifeq ($(TARGET_ARCH_ABI),x86_64)
	LOCAL_CPPFLAGS += -DC_TARGETCPU=X86_64 -DC_FPU_X86=1
endif
ifeq ($(TARGET_ARCH_ABI),mips)
	LOCAL_CPPFLAGS += -DC_TARGETCPU=MIPSEL
endif

# Type sizes - architecture dependent for pointer size
# 64-bit architectures (arm64-v8a, x86_64) have 8-byte pointers
ifneq (,$(filter $(TARGET_ARCH_ABI), arm64-v8a x86_64))
	LOCAL_CPPFLAGS += -DSIZEOF_INT_P=8
else
	LOCAL_CPPFLAGS += -DSIZEOF_INT_P=4
endif

# Other type sizes are architecture independent
LOCAL_CPPFLAGS += -DSIZEOF_UNSIGNED_CHAR=1 \
	-DSIZEOF_UNSIGNED_INT=4 \
	-DSIZEOF_UNSIGNED_LONG=4 \
	-DSIZEOF_UNSIGNED_LONG_LONG=8 \
	-DSIZEOF_UNSIGNED_SHORT=2

# Compatibility flags for modern NDK/Clang with old C++ code
LOCAL_CPPFLAGS += -std=gnu++11 -Wno-error=dynamic-exception-spec -frtti -Wno-c++11-narrowing -Wno-pointer-to-int-cast

LOCAL_SRC_PATH := $(LOCAL_PATH)/../../../../src

LOCAL_CPP_FEATURES += exceptions

# Note: We don't want any cdrom cpp file differing from cdrom_image.cpp.
# Furthermore, we compile zmbv.cpp only... and even that can't be done
# without zlib. So, let's give up for now.
#
# NOTE: Disabled dynamic recompiler cores (core_dynrec, core_dyn_x86) due to 
# compatibility issues with modern NDK/Clang. Using simpler cores instead.
LOCAL_SRC_FILES := \
	$(LOCAL_SRC_PATH)/dosbox.cpp \
	./ftime.c \
	$(filter-out %core_dynrec.cpp %core_dyn_x86.cpp, $(wildcard $(LOCAL_SRC_PATH)/cpu/*.cpp)) \
	$(wildcard $(LOCAL_SRC_PATH)/cpu/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/core_full/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/core_full/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/core_normal/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/core_normal/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/core_simple/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/core_simple/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/debug/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/debug/*.cpp) \
	\
	$(wildcard $(LOCAL_SRC_PATH)/dos/cdrom_image.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/dos/cdrom.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/dos/d*.cpp) \
	\
	$(wildcard $(LOCAL_SRC_PATH)/fpu/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/fpu/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/gui/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/gui/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/hardware/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/hardware/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/hardware/serialport/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/hardware/serialport/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/ints/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/ints/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/libs/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/libs/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/misc/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/misc/*.cpp) \
	$(wildcard $(LOCAL_SRC_PATH)/shell/*.c) \
	$(wildcard $(LOCAL_SRC_PATH)/shell/*.cpp) \
	$(SDL_PATH)/src/main/android/SDL_android_main.c \
	$(LOCAL_PATH)/../dosbox_jni.cpp
	
LOCAL_SHARED_LIBRARIES := SDL2 SDL2_net
LOCAL_BUILD_MODULE := $(PRIVATE_SYSROOT_LINK)/libc.a

LOCAL_LDLIBS := -lc -lGLESv1_CM -llog 
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=4096

include $(BUILD_SHARED_LIBRARY)
