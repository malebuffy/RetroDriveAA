#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <atomic>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>

#include "SDL.h"

// Include DOSBox headers - dosbox.h must come first as it defines Bitu and other types
#include "../../../include/dosbox.h"
#include "../../../include/joystick.h"
#include "../../../include/mapper.h"
#include "../../../include/mem.h"
#include "../../../include/paging.h"
#include "../../../include/render.h"
#include "../../../include/regs.h"
#include "../../../include/video.h"
#include "../../../include/vga.h"

#define LOG_TAG "DOSBoxJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" void Android_ResetLifecycleState(void);
extern "C" void Android_ResetWindowState(void);
extern "C" void ANDROIDAUDIO_ResetDevices(void);
extern void DOSBox_ResetSDLWindowModeState(void);
extern void DOSBox_DestroySDLVideoObjects(void);

std::atomic<bool> g_embedded_session_mode(false);

namespace {
static std::mutex g_state_mutex;
static std::condition_variable g_state_cv;
static std::string g_state_game_id = "__browse__";
static std::string g_state_path;
static bool g_save_requested = false;
static bool g_load_requested = false;
static bool g_save_completed = false;
static bool g_save_result = false;

static const char kStateMagic[8] = {'R', 'D', 'S', 'V', 'S', 'T', 'A', '1'};
static const Bit32u kStateVersion = 3;

struct StateHeader {
    char magic[8];
    Bit32u version;
    Bit64u gameIdHash;
    Bit64u memBytes;
    Bit64u vgaLinearBytes;
    Bit64u vgaFastmemBytes;
    Bit64u vgaFontBytes;
    Bit64u vgaDacBytes;
    Bit64u vgaAttrBytes;
    Bit32u vgaLatch;
    Bit32u vgaReserved;
    CPU_Regs regs;
    Segments segs;
    Bit8u a20Enabled;
    Bit8u pagingEnabled;
    Bit16u reserved;
    Bit32u cr3;
    Bit32u cr2;
};

static Bit64u fnv1a64(const std::string& text) {
    Bit64u hash = 1469598103934665603ULL;
    for (size_t i = 0; i < text.size(); ++i) {
        hash ^= static_cast<Bit8u>(text[i]);
        hash *= 1099511628211ULL;
    }
    return hash;
}

static bool ensureParentDirectory(const std::string& filePath) {
    size_t slash = filePath.find_last_of('/');
    if (slash == std::string::npos) {
        return true;
    }

    std::string dirPath = filePath.substr(0, slash);
    if (dirPath.empty()) {
        return true;
    }

    std::string current;
    for (size_t i = 0; i < dirPath.size(); ++i) {
        current.push_back(dirPath[i]);
        if (dirPath[i] == '/') {
            if (!current.empty()) {
                mkdir(current.c_str(), 0700);
            }
        }
    }
    if (mkdir(dirPath.c_str(), 0700) != 0 && errno != EEXIST) {
        LOGE("Failed to create directory: %s", dirPath.c_str());
        return false;
    }
    return true;
}

static Bit64u currentVgaLinearBytes() {
    Bit64u bytes = static_cast<Bit64u>(vga.vmemsize);
    if (bytes < 512ULL * 1024ULL) {
        bytes = 512ULL * 1024ULL;
    }
    bytes += 2048ULL;
    return bytes;
}

static Bit64u currentVgaFastmemBytes() {
    return (static_cast<Bit64u>(vga.vmemsize) << 1ULL) + 4096ULL;
}

static const Bit64u kVgaFontBytes = 64ULL * 1024ULL;

static void applyLoadedVgaPalette() {
    for (Bitu i = 0; i < 256; ++i) {
        const Bitu sourceIndex = i & vga.dac.pel_mask;
        const Bit8u red = vga.dac.rgb[sourceIndex].red;
        const Bit8u green = vga.dac.rgb[sourceIndex].green;
        const Bit8u blue = vga.dac.rgb[sourceIndex].blue;
        vga.dac.xlat16[i] =
            ((blue >> 1) & 0x1f) |
            ((green & 0x3f) << 5) |
            (((red >> 1) & 0x1f) << 11);
        RENDER_SetPal(i,
                      (red << 2) | (red >> 4),
                      (green << 2) | (green >> 4),
                      (blue << 2) | (blue >> 4));
    }

    for (Bitu i = 0; i < 16; ++i) {
        const Bitu sourceIndex = vga.dac.combine[i] & 0xff;
        const Bit8u red = vga.dac.rgb[sourceIndex].red;
        const Bit8u green = vga.dac.rgb[sourceIndex].green;
        const Bit8u blue = vga.dac.rgb[sourceIndex].blue;
        vga.dac.xlat16[i] =
            ((blue >> 1) & 0x1f) |
            ((green & 0x3f) << 5) |
            (((red >> 1) & 0x1f) << 11);
        RENDER_SetPal(i,
                      (red << 2) | (red >> 4),
                      (green << 2) | (green >> 4),
                      (blue << 2) | (blue >> 4));
    }
}

static bool saveStateToFile(const std::string& gameId, const std::string& statePath) {
    if (statePath.empty()) {
        LOGE("saveStateToFile: empty state path");
        return false;
    }

    if (!ensureParentDirectory(statePath)) {
        return false;
    }

    const Bit64u memBytes = static_cast<Bit64u>(MEM_TotalPages()) * MEM_PAGESIZE;
    if (MemBase == nullptr || memBytes == 0) {
        LOGE("saveStateToFile: invalid memory base/size");
        return false;
    }

    if (vga.mem.linear == nullptr || vga.fastmem == nullptr) {
        LOGE("saveStateToFile: VGA buffers not available");
        return false;
    }

    const Bit64u vgaLinearBytes = currentVgaLinearBytes();
    const Bit64u vgaFastmemBytes = currentVgaFastmemBytes();

    StateHeader header;
    std::memset(&header, 0, sizeof(header));
    std::memcpy(header.magic, kStateMagic, sizeof(kStateMagic));
    header.version = kStateVersion;
    header.gameIdHash = fnv1a64(gameId);
    header.memBytes = memBytes;
    header.vgaLinearBytes = vgaLinearBytes;
    header.vgaFastmemBytes = vgaFastmemBytes;
    header.vgaFontBytes = kVgaFontBytes;
    header.vgaDacBytes = sizeof(VGA_Dac);
    header.vgaAttrBytes = sizeof(VGA_Attr);
    header.vgaLatch = vga.latch.d;
    header.regs = cpu_regs;
    header.segs = Segs;
    header.a20Enabled = MEM_A20_Enabled() ? 1 : 0;
    header.pagingEnabled = paging.enabled ? 1 : 0;
    header.cr3 = static_cast<Bit32u>(paging.cr3);
    header.cr2 = static_cast<Bit32u>(paging.cr2);

    std::string tmpPath = statePath + ".tmp";
    {
        std::ofstream out(tmpPath.c_str(), std::ios::binary | std::ios::trunc);
        if (!out) {
            LOGE("saveStateToFile: failed to open temp file: %s", tmpPath.c_str());
            return false;
        }

        out.write(reinterpret_cast<const char*>(&header), sizeof(header));
        out.write(reinterpret_cast<const char*>(MemBase), static_cast<std::streamsize>(memBytes));
        out.write(reinterpret_cast<const char*>(vga.mem.linear), static_cast<std::streamsize>(vgaLinearBytes));
        out.write(reinterpret_cast<const char*>(vga.fastmem), static_cast<std::streamsize>(vgaFastmemBytes));
        out.write(reinterpret_cast<const char*>(vga.draw.font), static_cast<std::streamsize>(kVgaFontBytes));
        out.write(reinterpret_cast<const char*>(&vga.dac), static_cast<std::streamsize>(sizeof(VGA_Dac)));
        out.write(reinterpret_cast<const char*>(&vga.attr), static_cast<std::streamsize>(sizeof(VGA_Attr)));
        out.flush();
        if (!out.good()) {
            LOGE("saveStateToFile: write failed: %s", tmpPath.c_str());
            out.close();
            std::remove(tmpPath.c_str());
            return false;
        }
    }

    if (std::rename(tmpPath.c_str(), statePath.c_str()) != 0) {
        std::remove(statePath.c_str());
        if (std::rename(tmpPath.c_str(), statePath.c_str()) != 0) {
            LOGE("saveStateToFile: rename failed (%s -> %s)", tmpPath.c_str(), statePath.c_str());
            std::remove(tmpPath.c_str());
            return false;
        }
    }

    LOGI("State saved: %s", statePath.c_str());
    return true;
}

static bool loadStateFromFile(const std::string& gameId, const std::string& statePath) {
    if (statePath.empty()) {
        LOGE("loadStateFromFile: empty state path");
        return false;
    }

    std::ifstream in(statePath.c_str(), std::ios::binary);
    if (!in) {
        LOGE("loadStateFromFile: state not found: %s", statePath.c_str());
        return false;
    }

    StateHeader header;
    std::memset(&header, 0, sizeof(header));
    in.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (!in.good()) {
        LOGE("loadStateFromFile: failed to read header");
        return false;
    }

    if (std::memcmp(header.magic, kStateMagic, sizeof(kStateMagic)) != 0) {
        LOGE("loadStateFromFile: bad magic");
        return false;
    }
    if (header.version != 1 && header.version != 2 && header.version != kStateVersion) {
        LOGE("loadStateFromFile: unsupported version %u", header.version);
        return false;
    }
    if (header.gameIdHash != fnv1a64(gameId)) {
        LOGE("loadStateFromFile: game id mismatch");
        return false;
    }

    const Bit64u expectedMemBytes = static_cast<Bit64u>(MEM_TotalPages()) * MEM_PAGESIZE;
    if (header.memBytes != expectedMemBytes || MemBase == nullptr) {
        LOGE("loadStateFromFile: memory size mismatch (%llu != %llu)",
             static_cast<unsigned long long>(header.memBytes),
             static_cast<unsigned long long>(expectedMemBytes));
        return false;
    }

    in.read(reinterpret_cast<char*>(MemBase), static_cast<std::streamsize>(header.memBytes));
    if (!in.good()) {
        LOGE("loadStateFromFile: failed to read memory payload");
        return false;
    }

    if (header.version >= 2) {
        if (vga.mem.linear == nullptr || vga.fastmem == nullptr) {
            LOGE("loadStateFromFile: VGA buffers not available");
            return false;
        }

        const Bit64u expectedVgaLinearBytes = currentVgaLinearBytes();
        const Bit64u expectedVgaFastmemBytes = currentVgaFastmemBytes();
        if (header.vgaLinearBytes != expectedVgaLinearBytes ||
            header.vgaFastmemBytes != expectedVgaFastmemBytes ||
            header.vgaFontBytes != kVgaFontBytes) {
            LOGE("loadStateFromFile: VGA payload size mismatch");
            return false;
        }

        in.read(reinterpret_cast<char*>(vga.mem.linear), static_cast<std::streamsize>(header.vgaLinearBytes));
        if (!in.good()) {
            LOGE("loadStateFromFile: failed to read VGA linear payload");
            return false;
        }

        in.read(reinterpret_cast<char*>(vga.fastmem), static_cast<std::streamsize>(header.vgaFastmemBytes));
        if (!in.good()) {
            LOGE("loadStateFromFile: failed to read VGA fastmem payload");
            return false;
        }

        in.read(reinterpret_cast<char*>(vga.draw.font), static_cast<std::streamsize>(header.vgaFontBytes));
        if (!in.good()) {
            LOGE("loadStateFromFile: failed to read VGA font payload");
            return false;
        }

        vga.latch.d = header.vgaLatch;
    }

    if (header.version >= 3) {
        if (header.vgaDacBytes != sizeof(VGA_Dac) ||
            header.vgaAttrBytes != sizeof(VGA_Attr)) {
            LOGE("loadStateFromFile: VGA DAC/ATTR size mismatch");
            return false;
        }

        in.read(reinterpret_cast<char*>(&vga.dac), static_cast<std::streamsize>(header.vgaDacBytes));
        if (!in.good()) {
            LOGE("loadStateFromFile: failed to read VGA DAC payload");
            return false;
        }

        in.read(reinterpret_cast<char*>(&vga.attr), static_cast<std::streamsize>(header.vgaAttrBytes));
        if (!in.good()) {
            LOGE("loadStateFromFile: failed to read VGA ATTR payload");
            return false;
        }

        applyLoadedVgaPalette();
    }

    cpu_regs = header.regs;
    Segs = header.segs;
    MEM_A20_Enable(header.a20Enabled != 0);
    paging.cr2 = header.cr2;
    PAGING_SetDirBase(header.cr3);
    PAGING_Enable(header.pagingEnabled != 0);
    PAGING_ClearTLB();

    LOGI("State loaded: %s", statePath.c_str());
    return true;
}

}

void DOSBOX_ProcessPendingSaveLoadRequests(void) {
    bool doSave = false;
    bool doLoad = false;
    std::string gameId;
    std::string statePath;

    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        if (g_save_requested) {
            doSave = true;
            g_save_requested = false;
            gameId = g_state_game_id;
            statePath = g_state_path;
        }
        if (g_load_requested) {
            doLoad = true;
            g_load_requested = false;
            gameId = g_state_game_id;
            statePath = g_state_path;
        }
    }

    if (doSave) {
        bool result = saveStateToFile(gameId, statePath);
        {
            std::lock_guard<std::mutex> lock(g_state_mutex);
            g_save_result = result;
            g_save_completed = true;
        }
        g_state_cv.notify_all();
    }

    if (doLoad) {
        const bool loaded = loadStateFromFile(gameId, statePath);
        if (loaded) {
            render.scale.clearCache = true;
            render.fullFrame = true;
            GFX_ResetScreen();
        }
    }
}

extern "C" {

/**
 * JNI function to send joystick axis movement directly to DOSBox
 * Called from Java: DOSBoxJNI.nativeJoystickAxis(x, y)
 * 
 * @param x X-axis position (-1.0 to 1.0)
 * @param y Y-axis position (-1.0 to 1.0)
 */
JNIEXPORT void JNICALL Java_com_dosbox_emu_DOSBoxJNI_nativeJoystickAxis(
    JNIEnv* env, jclass clazz, jfloat x, jfloat y)
{
    LOGD("nativeJoystickAxis: x=%.3f, y=%.3f", x, y);

    // Keep mapper virtual joystick state in sync so periodic mapper updates
    // do not overwrite WiFi joystick values.
    MAPPER_SetVirtualJoystickAxis(x, y);

    JOYSTICK_Enable(0, true);
    JOYSTICK_Enable(1, true);

    JOYSTICK_Move_X(0, x);
    JOYSTICK_Move_Y(0, y);
    JOYSTICK_Move_X(1, x);
    JOYSTICK_Move_Y(1, y);
}

JNIEXPORT void JNICALL Java_com_dosbox_emu_DOSBoxJNI_nativeSetSaveStateContext(
    JNIEnv* env, jclass clazz, jstring gameId, jstring statePath)
{
    const char* gameIdChars = gameId ? env->GetStringUTFChars(gameId, nullptr) : nullptr;
    const char* statePathChars = statePath ? env->GetStringUTFChars(statePath, nullptr) : nullptr;

    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        g_state_game_id = gameIdChars ? gameIdChars : "__browse__";
        g_state_path = statePathChars ? statePathChars : "";
    }

    if (gameIdChars) {
        env->ReleaseStringUTFChars(gameId, gameIdChars);
    }
    if (statePathChars) {
        env->ReleaseStringUTFChars(statePath, statePathChars);
    }
}

JNIEXPORT void JNICALL Java_com_dosbox_emu_DOSBoxJNI_nativeSetEmbeddedSessionMode(
    JNIEnv* env, jclass clazz, jboolean embedded)
{
    g_embedded_session_mode.store(embedded == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_dosbox_emu_DOSBoxJNI_nativeResetSdlAndroidSessionState(
    JNIEnv* env, jclass clazz)
{
    if (g_embedded_session_mode.load()) {
        LOGD("Skipping SDL Android session reset while embedded session is active");
        return;
    }

    const Uint32 initialized = SDL_WasInit(0);
    if (initialized != 0) {
        LOGD("Resetting lingering SDL subsystems before embedded restart: 0x%x", initialized);
    }

    SDL_AudioQuit();
    ANDROIDAUDIO_ResetDevices();
    SDL_Quit();
    DOSBox_DestroySDLVideoObjects();
    Android_ResetLifecycleState();
    Android_ResetWindowState();
    DOSBox_ResetSDLWindowModeState();
    LOGD("Reset SDL Android embedded session state");
}

JNIEXPORT void JNICALL Java_com_dosbox_emu_DOSBoxJNI_nativeRequestLoadState(
    JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_load_requested = true;
}

JNIEXPORT jboolean JNICALL Java_com_dosbox_emu_DOSBoxJNI_nativeSaveStateAndWait(
    JNIEnv* env, jclass clazz, jint timeoutMs)
{
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        g_save_completed = false;
        g_save_result = false;
        g_save_requested = true;
    }

    std::unique_lock<std::mutex> lock(g_state_mutex);
    bool done = g_state_cv.wait_for(
        lock,
        std::chrono::milliseconds(timeoutMs > 0 ? timeoutMs : 1),
        []() { return g_save_completed; }
    );

    if (!done) {
        LOGE("nativeSaveStateAndWait timed out");
        return JNI_FALSE;
    }

    return g_save_result ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
