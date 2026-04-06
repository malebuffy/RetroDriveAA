
# Uncomment this if you're using STL in your project
# See CPLUSPLUS-SUPPORT.html in the NDK documentation for more information
# APP_STL := stlport_static (DEPRECATED - use c++_shared or c++_static)
APP_STL := c++_static
APP_ABI := all
APP_PLATFORM := android-29
APP_LDFLAGS += -Wl,-z,max-page-size=16384
APP_LDFLAGS += -Wl,-z,common-page-size=4096
ANDROID_ABI := "armeabi-v7a with NEON"
#ANDROID_ABI := arm64-v8a
#TARGET_ARCH_ABI := arm64-v8a
ANDROID_ARM_NEON := true
LOCAL_ARM_NEON := true
ANDROID_ARM_VFP := hard
LOCAL_CXXFLAGS  := -O2 -mhard-float -mfloat-abi=hard -mfpu=neon
LOCAL_CFLAGS    := -O2 -mhard-float -mfloat-abi=hard -mfpu=neon
LOCAL_LDFLAGS   := -lm_hard