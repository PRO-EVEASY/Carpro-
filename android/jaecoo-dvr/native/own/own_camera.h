#ifndef OWN_CAMERA_H
#define OWN_CAMERA_H
#include <stdint.h>
typedef struct own_camera own_camera;
int own_camera_begin(void);
/* Ends global enumeration/heartbeat session. If cameras remain open, teardown
 * is deferred until the final close. Join readers before closing a handle. */
void own_camera_end(void);
/* Returns the number of enumerated inputs or negative errno; copies at most
 * capacity IDs from the current successful global query. */
int own_camera_inputs(int *out, int capacity);
own_camera *own_camera_open(int input);
int own_camera_read(own_camera *camera, void *destination, int capacity);
void own_camera_stop(own_camera *camera);
void own_camera_close(own_camera *camera);
const char *own_camera_error(void);
#endif
