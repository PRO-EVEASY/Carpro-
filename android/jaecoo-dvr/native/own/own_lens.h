#ifndef OWN_LENS_H
#define OWN_LENS_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
int own_lens_compose(const uint8_t *frames[4], size_t frame_capacity, uint8_t *output, size_t output_capacity, int semi_planar, int input_width, int input_height, int output_width, int output_height, const float profiles[32]);
void own_lens_clear_cache(void);
#ifdef __cplusplus
}
#endif
#endif
