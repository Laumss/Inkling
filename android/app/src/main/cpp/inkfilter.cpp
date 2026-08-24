#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <vector>

namespace {

constexpr const char* kTag = "InkFilter";

constexpr int FILTER_NONE = 0;
constexpr int FILTER_ENHANCE = 1;
constexpr int FILTER_TEXT_BW = 2;

constexpr int BG_SHIFT = 3;
constexpr int BG_SCALE = 1 << BG_SHIFT;
constexpr int DILATE_RADIUS = 3;
constexpr int BLUR_RADIUS = 4;
constexpr int BG_FLOOR = 32;
constexpr int BW_THRESHOLD = 200;
constexpr int CURVE_BLACK = 45;
constexpr int CURVE_WHITE = 235;

inline uint8_t clamp255(int v) {
    return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

void buildLut(uint8_t lut[256], bool enhance, int density) {
    const float alpha = static_cast<float>(density) / 100.f;
    for (int i = 0; i < 256; i++) {
        float t = static_cast<float>(i);
        if (enhance) {
            t = (t - CURVE_BLACK) * 255.f / (CURVE_WHITE - CURVE_BLACK);
            t = std::min(255.f, std::max(0.f, t));
        }
        const float out = 255.f - alpha * (255.f - t);
        lut[i] = clamp255(static_cast<int>(out + 0.5f));
    }
}

void dilateSmall(std::vector<uint8_t>& img, int w, int h, int radius) {
    std::vector<uint8_t> tmp(img.size());
    for (int y = 0; y < h; y++) {
        const uint8_t* row = img.data() + y * w;
        uint8_t* out = tmp.data() + y * w;
        for (int x = 0; x < w; x++) {
            const int x0 = std::max(0, x - radius);
            const int x1 = std::min(w - 1, x + radius);
            uint8_t m = 0;
            for (int i = x0; i <= x1; i++) m = std::max(m, row[i]);
            out[x] = m;
        }
    }
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            const int y0 = std::max(0, y - radius);
            const int y1 = std::min(h - 1, y + radius);
            uint8_t m = 0;
            for (int i = y0; i <= y1; i++) m = std::max(m, tmp[i * w + x]);
            img[y * w + x] = m;
        }
    }
}

void boxBlurSmall(std::vector<uint8_t>& img, int w, int h, int radius) {
    std::vector<uint8_t> tmp(img.size());
    for (int y = 0; y < h; y++) {
        const uint8_t* row = img.data() + y * w;
        uint8_t* out = tmp.data() + y * w;
        int sum = 0;
        int count = 0;
        for (int i = 0; i <= std::min(w - 1, radius); i++) { sum += row[i]; count++; }
        for (int x = 0; x < w; x++) {
            out[x] = static_cast<uint8_t>(sum / count);
            const int add = x + radius + 1;
            const int sub = x - radius;
            if (add < w) { sum += row[add]; count++; }
            if (sub >= 0) { sum -= row[sub]; count--; }
        }
    }
    for (int x = 0; x < w; x++) {
        int sum = 0;
        int count = 0;
        for (int i = 0; i <= std::min(h - 1, radius); i++) { sum += tmp[i * w + x]; count++; }
        for (int y = 0; y < h; y++) {
            img[y * w + x] = static_cast<uint8_t>(sum / count);
            const int add = y + radius + 1;
            const int sub = y - radius;
            if (add < h) { sum += tmp[add * w + x]; count++; }
            if (sub >= 0) { sum -= tmp[sub * w + x]; count--; }
        }
    }
}

struct BilinearAxis {
    std::vector<int> i0, i1, frac;
    void build(int fullLen, int smallLen) {
        i0.resize(fullLen);
        i1.resize(fullLen);
        frac.resize(fullLen);
        for (int x = 0; x < fullLen; x++) {
            int f = (x << 5) - 112;
            if (f < 0) f = 0;
            int idx = f >> 8;
            int fr = f & 0xFF;
            if (idx >= smallLen - 1) { idx = smallLen - 1; fr = 0; }
            i0[x] = idx;
            i1[x] = std::min(smallLen - 1, idx + 1);
            frac[x] = fr;
        }
    }
};

int processBitmap(uint32_t* pixels, int w, int h, int strideBytes, int filter, int density) {
    const int stridePx = strideBytes / 4;
    const bool densityOnly = (filter == FILTER_NONE);

    uint8_t lut[256];
    buildLut(lut, filter == FILTER_ENHANCE, density);

    if (densityOnly) {
        for (int y = 0; y < h; y++) {
            uint32_t* row = pixels + y * stridePx;
            for (int x = 0; x < w; x++) {
                const uint32_t p = row[x];
                const int a = (p >> 24) & 0xFF;
                const int white = 255 - a;
                const int r = lut[std::min(255, static_cast<int>(p & 0xFF) + white)];
                const int g = lut[std::min(255, static_cast<int>((p >> 8) & 0xFF) + white)];
                const int b = lut[std::min(255, static_cast<int>((p >> 16) & 0xFF) + white)];
                row[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        }
        return 0;
    }

    std::vector<uint8_t> gray(static_cast<size_t>(w) * h);
    for (int y = 0; y < h; y++) {
        const uint32_t* row = pixels + y * stridePx;
        uint8_t* grow = gray.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; x++) {
            const uint32_t p = row[x];
            const int a = (p >> 24) & 0xFF;
            const int r = p & 0xFF;
            const int g = (p >> 8) & 0xFF;
            const int b = (p >> 16) & 0xFF;
            const int luma = (77 * r + 150 * g + 29 * b) >> 8;
            grow[x] = clamp255(luma + (255 - a));
        }
    }

    const int sw = std::max(1, (w + BG_SCALE - 1) / BG_SCALE);
    const int sh = std::max(1, (h + BG_SCALE - 1) / BG_SCALE);
    std::vector<uint8_t> bg(static_cast<size_t>(sw) * sh);
    {
        std::vector<uint32_t> sum(static_cast<size_t>(sw) * sh, 0);
        std::vector<uint16_t> cnt(static_cast<size_t>(sw) * sh, 0);
        for (int y = 0; y < h; y++) {
            const int sy = y >> BG_SHIFT;
            const uint8_t* grow = gray.data() + static_cast<size_t>(y) * w;
            uint32_t* srow = sum.data() + static_cast<size_t>(sy) * sw;
            uint16_t* crow = cnt.data() + static_cast<size_t>(sy) * sw;
            for (int x = 0; x < w; x++) {
                const int sx = x >> BG_SHIFT;
                srow[sx] += grow[x];
                crow[sx]++;
            }
        }
        for (size_t i = 0; i < bg.size(); i++) {
            bg[i] = static_cast<uint8_t>(cnt[i] ? sum[i] / cnt[i] : 255);
        }
    }
    dilateSmall(bg, sw, sh, DILATE_RADIUS);
    boxBlurSmall(bg, sw, sh, BLUR_RADIUS);
    boxBlurSmall(bg, sw, sh, BLUR_RADIUS);
    for (auto& v : bg) v = std::max<uint8_t>(v, BG_FLOOR);

    BilinearAxis ax, ay;
    ax.build(w, sw);
    ay.build(h, sh);
    const bool binarize = (filter == FILTER_TEXT_BW);
    const uint8_t inkLevel = lut[0];

    for (int y = 0; y < h; y++) {
        uint32_t* row = pixels + y * stridePx;
        const uint8_t* grow = gray.data() + static_cast<size_t>(y) * w;
        const uint8_t* b0 = bg.data() + static_cast<size_t>(ay.i0[y]) * sw;
        const uint8_t* b1 = bg.data() + static_cast<size_t>(ay.i1[y]) * sw;
        const int wy = ay.frac[y];
        for (int x = 0; x < w; x++) {
            const int x0 = ax.i0[x];
            const int x1 = ax.i1[x];
            const int wx = ax.frac[x];
            const int top = b0[x0] * (256 - wx) + b0[x1] * wx;
            const int bot = b1[x0] * (256 - wx) + b1[x1] * wx;
            const int bgv = (top * (256 - wy) + bot * wy) >> 16;
            const int n = std::min(255, (grow[x] * 255 + bgv / 2) / (bgv ? bgv : 1));
            uint8_t v;
            if (binarize) {
                v = (n < BW_THRESHOLD) ? inkLevel : 255;
            } else {
                v = lut[n];
            }
            row[x] = 0xFF000000u | (static_cast<uint32_t>(v) << 16)
                     | (static_cast<uint32_t>(v) << 8) | v;
        }
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_supernote_1quicktoolbar_ImageFilter_nativeProcess(
        JNIEnv* env, jclass /*clazz*/, jobject jbitmap, jint filter, jint density) {
    if (jbitmap == nullptr) return -1;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, jbitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "getInfo failed");
        return -2;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "unsupported format %d", info.format);
        return -3;
    }

    void* addr = nullptr;
    if (AndroidBitmap_lockPixels(env, jbitmap, &addr) != ANDROID_BITMAP_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "lockPixels failed");
        return -4;
    }

    const int rc = processBitmap(
            static_cast<uint32_t*>(addr),
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            static_cast<int>(info.stride),
            filter, density);

    AndroidBitmap_unlockPixels(env, jbitmap);
    return rc;
}
