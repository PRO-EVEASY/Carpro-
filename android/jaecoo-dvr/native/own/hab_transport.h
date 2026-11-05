#ifndef OWN_HAB_TRANSPORT_H
#define OWN_HAB_TRANSPORT_H

#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OWN_HAB_MAX_SOCKETS 64u
#define OWN_HAB_DEFAULT_RECV_MS 250u
#define OWN_HAB_MAX_RECV_MS 60000u
#define OWN_HAB_MAX_TRANSFER (64u * 1024u * 1024u)

/* Not copyable. Initialize once, join users before destroy; concurrent I/O is safe. */
typedef struct own_hab_transport {
    pthread_mutex_t lock;
    pthread_mutex_t log_lock;
    pthread_cond_t idle;
    int fd;
    int initialized;
    int closing;
    unsigned active;
    uint32_t sockets[OWN_HAB_MAX_SOCKETS];
    size_t socket_count;
    FILE *log;
    size_t log_bytes;
    uint64_t log_sequence;
} own_hab_transport_t;

/* All operations return 0 or negative errno. NULL device means /dev/hab.
 * NULL log_path disables logging. An explicitly requested log must open. */
int own_hab_init(own_hab_transport_t *ctx, const char *device, const char *log_path);
void own_hab_destroy(own_hab_transport_t *ctx);
int own_hab_open(own_hab_transport_t *ctx, uint32_t mmid, int32_t timeout_ms,
                 uint32_t flags, uint32_t *vcid);
int own_hab_socket_close(own_hab_transport_t *ctx, uint32_t vcid, uint32_t flags);
int own_hab_send(own_hab_transport_t *ctx, uint32_t vcid, const void *data,
                 uint32_t size, uint32_t flags);
/* size is capacity on entry, received bytes on success, zero on error.
 * timeout=0 is one immediate driver attempt; positive values use a monotonic
 * deadline across EINTR. 250ms is recommended for stoppable worker loops. */
int own_hab_recv(own_hab_transport_t *ctx, uint32_t vcid, void *data,
                 uint32_t *size, uint32_t timeout_ms, uint32_t flags);
/* The caller owns exported memory and must retain it until successful unexport
 * (or until transport destruction has closed every driver channel). */
int own_hab_export(own_hab_transport_t *ctx, uint32_t vcid, void *data,
                   uint32_t size, uint32_t flags, uint32_t *export_id);
int own_hab_unexport(own_hab_transport_t *ctx, uint32_t vcid,
                     uint32_t export_id, uint32_t flags);
/* fd is borrowed for diagnostics, never close it. Snapshot returns total count;
 * at most capacity IDs are copied. */
int own_hab_fd(own_hab_transport_t *ctx);
size_t own_hab_socket_snapshot(own_hab_transport_t *ctx, uint32_t *ids, size_t capacity);

#ifdef __cplusplus
}
#endif
#endif
