/* Independently implemented packed-YUV422 to YUV420 CPU compositor.
 * No DSA source, binary loading, private symbol, or third-party pixel code.
 */
#include "own_mosaic.h"

static void set_error(char *out, size_t capacity, const char *message) {
    size_t i = 0;
    if (!out || !capacity) return;
    while (message[i] && i + 1 < capacity) { out[i] = message[i]; i++; }
    out[i] = 0;
}

const char *jm_format_name(jm_pixel_format format) {
    switch (format) {
        case JM_FMT_UYVY: return "UYVY";
        case JM_FMT_YUYV: return "YUYV";
        case JM_FMT_VYUY: return "VYUY";
        case JM_FMT_YVYU: return "YVYU";
        default: return "UNSUPPORTED";
    }
}

int jm_format_from_qcarcam_id(uint32_t id, jm_pixel_format *format) {
    if (id != 117965058u || !format) return 0;
    *format = JM_FMT_UYVY;
    return 1;
}

static int valid_format(jm_pixel_format format) {
    return format == JM_FMT_UYVY || format == JM_FMT_YUYV ||
           format == JM_FMT_VYUY || format == JM_FMT_YVYU;
}

static void pixel(const jm_frame *f, uint32_t x, uint32_t y,
                  unsigned *luma, unsigned *u, unsigned *v) {
    if (x >= f->width) x = f->width - 1;
    if (y >= f->height) y = f->height - 1;
    const uint8_t *p = f->data + (size_t)y * f->stride + (size_t)(x & ~1u) * 2;
    unsigned odd = x & 1u;
    switch (f->format) {
        case JM_FMT_UYVY: *luma = p[1 + odd * 2]; *u = p[0]; *v = p[2]; break;
        case JM_FMT_YUYV: *luma = p[odd * 2]; *u = p[1]; *v = p[3]; break;
        case JM_FMT_VYUY: *luma = p[1 + odd * 2]; *u = p[2]; *v = p[0]; break;
        default: *luma = p[odd * 2]; *u = p[3]; *v = p[1]; break;
    }
}

static uint32_t source_coordinate(uint32_t at, uint32_t source, uint32_t target) {
    uint32_t result = (uint32_t)(((uint64_t)at * 2u + 1u) * source / ((uint64_t)target * 2u));
    return result < source ? result : source - 1;
}

static void uyvy_identity_tile(const jm_frame *f, uint8_t *out, uint32_t width,
                               uint32_t height, uint32_t left, uint32_t top,
                               jm_output_layout layout) {
    size_t pixels = (size_t)width * height;
    for (uint32_t y = 0; y < f->height; y += 2) {
        const uint8_t *r0 = f->data + (size_t)y * f->stride;
        const uint8_t *r1 = r0 + f->stride;
        uint8_t *y0 = out + (size_t)(top+y)*width + left;
        uint8_t *y1 = y0 + width;
        size_t chroma_row = (size_t)((top+y)/2)*(width/2) + left/2;
        for (uint32_t x = 0; x < f->width; x += 2) {
            size_t src = (size_t)x * 2;
            y0[x] = r0[src+1]; y0[x+1] = r0[src+3];
            y1[x] = r1[src+1]; y1[x+1] = r1[src+3];
            uint8_t u = (uint8_t)(((unsigned)r0[src]+r1[src]+1)/2);
            uint8_t v = (uint8_t)(((unsigned)r0[src+2]+r1[src+2]+1)/2);
            size_t c = chroma_row + x/2;
            if (layout == JM_LAYOUT_NV12) { out[pixels+c*2] = u; out[pixels+c*2+1] = v; }
            else { out[pixels+c] = u; out[pixels+pixels/4+c] = v; }
        }
    }
}

int jm_compose_mosaic(const jm_frame frames[4], uint8_t *destination,
                      size_t destination_size, uint32_t output_width,
                      uint32_t output_height, jm_output_layout layout,
                      char *error, size_t error_size) {
    set_error(error, error_size, "");
    if (!frames || !destination) { set_error(error,error_size,"Null frame or output"); return 0; }
    if (!output_width || !output_height || output_width > 8192 || output_height > 8192 ||
        (output_width & 3u) || (output_height & 3u)) {
        set_error(error,error_size,"Output geometry must be a multiple of four, at most 8192"); return 0;
    }
    if (layout != JM_LAYOUT_I420 && layout != JM_LAYOUT_NV12) {
        set_error(error,error_size,"Unsupported output layout"); return 0;
    }
    size_t pixels = (size_t)output_width * output_height;
    size_t required = pixels + pixels / 2;
    if (destination_size < required) { set_error(error,error_size,"Output buffer too small"); return 0; }
    uintptr_t dst = (uintptr_t)destination;
    if (dst > UINTPTR_MAX - required) { set_error(error,error_size,"Output address overflow"); return 0; }
    for (unsigned k = 0; k < 4; k++) {
        const jm_frame *f = frames + k;
        if (!f->data || !f->width || !f->height || f->width > 4096 || f->height > 4096 ||
            (f->width & 1u) || (f->height & 1u) || f->stride < f->width * 2u ||
            !valid_format(f->format)) {
            set_error(error,error_size,"Invalid input geometry, stride or pixel format"); return 0;
        }
        uint64_t span64 = (uint64_t)(f->height - 1) * f->stride + f->width * 2u;
        if (span64 > SIZE_MAX || f->size < (size_t)span64) {
            set_error(error,error_size,"Input buffer too small"); return 0;
        }
        uintptr_t src = (uintptr_t)f->data;
        if (src > UINTPTR_MAX - (size_t)span64) { set_error(error,error_size,"Input address overflow"); return 0; }
        if (src < dst + required && dst < src + (size_t)span64) {
            set_error(error,error_size,"Source and output overlap"); return 0;
        }
    }
    uint32_t tile_w = output_width / 2, tile_h = output_height / 2;
    for (unsigned k = 0; k < 4; k++) {
        const jm_frame *f = frames + k;
        uint32_t left = (k & 1u) * tile_w, top = (k >> 1) * tile_h;
        if (f->format == JM_FMT_UYVY && f->width == tile_w && f->height == tile_h) {
            uyvy_identity_tile(f,destination,output_width,output_height,left,top,layout);
            continue;
        }
        for (uint32_t y = 0; y < tile_h; y += 2) {
            uint32_t sy[2] = {source_coordinate(y,f->height,tile_h),source_coordinate(y+1,f->height,tile_h)};
            for (uint32_t x = 0; x < tile_w; x += 2) {
                uint32_t sx[2] = {source_coordinate(x,f->width,tile_w),source_coordinate(x+1,f->width,tile_w)};
                unsigned sum_u = 0, sum_v = 0;
                for (unsigned row = 0; row < 2; row++) {
                    for (unsigned col = 0; col < 2; col++) {
                        unsigned luma, u, v;
                        pixel(f,sx[col],sy[row],&luma,&u,&v);
                        destination[(size_t)(top+y+row)*output_width+left+x+col] = (uint8_t)luma;
                        sum_u += u; sum_v += v;
                    }
                }
                uint8_t u = (uint8_t)((sum_u+2)/4), v = (uint8_t)((sum_v+2)/4);
                size_t chroma = (size_t)((top+y)/2)*(output_width/2)+(left+x)/2;
                if (layout == JM_LAYOUT_NV12) {
                    destination[pixels+chroma*2] = u;
                    destination[pixels+chroma*2+1] = v;
                } else {
                    destination[pixels+chroma] = u;
                    destination[pixels+pixels/4+chroma] = v;
                }
            }
        }
    }
    return 1;
}
