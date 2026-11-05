#ifndef JAECOO_OWN_MOSAIC_H
#define JAECOO_OWN_MOSAIC_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define JM_API __declspec(dllexport)
#else
#define JM_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* These are explicit FourCC values, not QCarCam numeric format IDs. */
typedef enum {
    JM_FMT_UYVY = 0x59565955u,
    JM_FMT_YUYV = 0x56595559u,
    JM_FMT_VYUY = 0x59555956u,
    JM_FMT_YVYU = 0x55595659u
} jm_pixel_format;

typedef enum {
    JM_LAYOUT_I420 = 0,
    JM_LAYOUT_NV12 = 1
} jm_output_layout;

typedef struct {
    const uint8_t *data;
    size_t size;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    jm_pixel_format format;
} jm_frame;

/*
 * Compose frames 0/1 above frames 2/3. Each source may have independent
 * geometry/stride. Output width and height must be multiples of four.
 * Center-aligned nearest sampling; 2x2 sampled chroma is averaged for 4:2:0.
 * Returns 1 on success, 0 on validation failure. The output is left unchanged
 * on validation failure. Source and destination buffers must not overlap.
 * This routine does not allocate memory, load libraries, or touch hardware.
 */
JM_API int jm_compose_mosaic(const jm_frame frames[4], uint8_t *destination,
                            size_t destination_size, uint32_t output_width,
                            uint32_t output_height, jm_output_layout layout,
                            char *error, size_t error_size);

JM_API const char *jm_format_name(jm_pixel_format format);
/* Only IDs established against real captured buffers are accepted. */
JM_API int jm_format_from_qcarcam_id(uint32_t id, jm_pixel_format *format);

#ifdef __cplusplus
}
#endif
#endif
