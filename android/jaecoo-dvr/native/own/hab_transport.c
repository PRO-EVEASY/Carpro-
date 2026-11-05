#define _POSIX_C_SOURCE 200809L
#define BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD
#include "hab_transport.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

/* Independent ABI implementation from platform-driver request layouts and
 * successful request/response captures. No vendor implementation is linked. */
typedef struct { int32_t vcid; uint32_t mmid; int32_t timeout; uint32_t flags; } open_arg;
typedef struct { uint64_t data; uint32_t vcid, size, flags, reserved; } send_arg;
typedef struct { uint64_t data; uint32_t vcid, size, timeout, flags; } recv_arg;
typedef struct { uint32_t vcid, flags; } close_arg;
typedef struct { uint64_t data; uint32_t vcid, size, export_id, flags; } export_arg;
typedef struct { uint32_t vcid, export_id, flags; } unexport_arg;
#define ASSERT_SIZE(t,n) _Static_assert(sizeof(t)==(n), #t " ABI size")
#define ASSERT_OFF(t,f,n) _Static_assert(offsetof(t,f)==(n), #t "." #f " ABI offset")
ASSERT_SIZE(open_arg,16); ASSERT_OFF(open_arg,vcid,0); ASSERT_OFF(open_arg,mmid,4);
ASSERT_OFF(open_arg,timeout,8); ASSERT_OFF(open_arg,flags,12);
ASSERT_SIZE(send_arg,24); ASSERT_OFF(send_arg,data,0); ASSERT_OFF(send_arg,vcid,8);
ASSERT_OFF(send_arg,size,12); ASSERT_OFF(send_arg,flags,16); ASSERT_OFF(send_arg,reserved,20);
ASSERT_SIZE(recv_arg,24); ASSERT_OFF(recv_arg,data,0); ASSERT_OFF(recv_arg,vcid,8);
ASSERT_OFF(recv_arg,size,12); ASSERT_OFF(recv_arg,timeout,16); ASSERT_OFF(recv_arg,flags,20);
ASSERT_SIZE(close_arg,8); ASSERT_OFF(close_arg,vcid,0); ASSERT_OFF(close_arg,flags,4);
ASSERT_SIZE(export_arg,24); ASSERT_OFF(export_arg,data,0); ASSERT_OFF(export_arg,vcid,8);
ASSERT_OFF(export_arg,size,12); ASSERT_OFF(export_arg,export_id,16); ASSERT_OFF(export_arg,flags,20);
ASSERT_SIZE(unexport_arg,12); ASSERT_OFF(unexport_arg,vcid,0);
ASSERT_OFF(unexport_arg,export_id,4); ASSERT_OFF(unexport_arg,flags,8);
_Static_assert(sizeof(uintptr_t)==8,"HAB transport requires 64-bit userspace");
#define HAB_OPEN UINT32_C(0xc0100a04)
#define HAB_SEND UINT32_C(0x40180a02)
#define HAB_RECV UINT32_C(0xc0180a03)
#define HAB_CLOSE UINT32_C(0x40080a05)
#define HAB_EXPORT UINT32_C(0xc0180a06)
#define HAB_UNEXPORT UINT32_C(0x400c0a08)
_Static_assert(_IOWR(10,4,open_arg)==HAB_OPEN,"open ioctl ABI");
_Static_assert(_IOW(10,2,send_arg)==HAB_SEND,"send ioctl ABI");
_Static_assert(_IOWR(10,3,recv_arg)==HAB_RECV,"recv ioctl ABI");
_Static_assert(_IOW(10,5,close_arg)==HAB_CLOSE,"close ioctl ABI");
_Static_assert(_IOWR(10,6,export_arg)==HAB_EXPORT,"export ioctl ABI");
_Static_assert(_IOW(10,8,unexport_arg)==HAB_UNEXPORT,"unexport ioctl ABI");

static uint64_t now_ns(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC,&t)!=0) return 0;
    return (uint64_t)t.tv_sec*UINT64_C(1000000000)+(uint64_t)t.tv_nsec;
}
static int request(int fd,uint32_t op,void *arg) {
    int r=ioctl(fd,(int)op,arg);
    if (r==-1) return errno ? -errno : -EIO;
    return r==0 ? 0 : (r<0 ? r : -EIO);
}
static int acquire(own_hab_transport_t *c,int *fd) {
    if (!c || !c->initialized) return -EINVAL;
    pthread_mutex_lock(&c->lock);
    int r=0;
    if (c->closing || c->fd<0) r=-ESHUTDOWN;
    else { ++c->active; *fd=c->fd; }
    pthread_mutex_unlock(&c->lock);
    return r;
}
static void release(own_hab_transport_t *c) {
    pthread_mutex_lock(&c->lock);
    if (--c->active==0) pthread_cond_broadcast(&c->idle);
    pthread_mutex_unlock(&c->lock);
}
static void log_event(own_hab_transport_t *c,const char *op,uint32_t vcid,
                      uint32_t size,int result,const void *data) {
    pthread_mutex_lock(&c->log_lock);
    if (c->log && c->log_bytes<8u*1024u*1024u) {
        char hex[129]; size_t n=data ? (size<64 ? size : 64) : 0;
        const unsigned char *p=data;
        for(size_t i=0;i<n;++i) { static const char digits[]="0123456789abcdef";
            hex[i*2]=digits[p[i]>>4]; hex[i*2+1]=digits[p[i]&15]; }
        hex[n*2]=0;
        int written=fprintf(c->log,"{\"seq\":%" PRIu64 ",\"monotonic_ns\":%" PRIu64
            ",\"op\":\"%s\",\"vcid\":%" PRIu32 ",\"size\":%" PRIu32
            ",\"result\":%d,\"prefix_hex\":\"%s\"}\n",
            ++c->log_sequence,now_ns(),op,vcid,size,result,hex);
        if(written>0) c->log_bytes+=(size_t)written;
        fflush(c->log);
    }
    pthread_mutex_unlock(&c->log_lock);
}
int own_hab_init(own_hab_transport_t *c,const char *device,const char *log_path) {
    if(!c) return -EINVAL;
    memset(c,0,sizeof(*c)); c->fd=-1;
    int e=pthread_mutex_init(&c->lock,NULL); if(e) return -e;
    e=pthread_mutex_init(&c->log_lock,NULL);
    if(e) { pthread_mutex_destroy(&c->lock); return -e; }
    e=pthread_cond_init(&c->idle,NULL);
    if(e) { pthread_mutex_destroy(&c->log_lock); pthread_mutex_destroy(&c->lock); return -e; }
    c->initialized=1;
    if(log_path) { c->log=fopen(log_path,"w"); if(!c->log) { e=errno; own_hab_destroy(c); return -e; } }
    c->fd=open(device ? device : "/dev/hab",O_RDWR|O_CLOEXEC);
    if(c->fd<0) { e=errno; own_hab_destroy(c); return -e; }
    log_event(c,"init",0,0,0,NULL); return 0;
}
void own_hab_destroy(own_hab_transport_t *c) {
    if(!c || !c->initialized) return;
    pthread_mutex_lock(&c->lock); c->closing=1;
    while(c->active) pthread_cond_wait(&c->idle,&c->lock);
    int fd=c->fd; c->fd=-1;
    if(fd>=0) {
        for(size_t i=c->socket_count;i>0;--i) {
            close_arg arg={c->sockets[i-1],0};
            int r=request(fd,HAB_CLOSE,&arg); log_event(c,"destroy_socket",arg.vcid,0,r,NULL);
        }
        close(fd);
    }
    c->socket_count=0; pthread_mutex_unlock(&c->lock);
    if(c->log) { fclose(c->log); c->log=NULL; }
    pthread_cond_destroy(&c->idle); pthread_mutex_destroy(&c->log_lock);
    pthread_mutex_destroy(&c->lock); c->initialized=0;
}
int own_hab_open(own_hab_transport_t *c,uint32_t mmid,int32_t timeout_ms,uint32_t flags,uint32_t *vcid) {
    if(!vcid || timeout_ms < -1) return -EINVAL;
    *vcid=0; int fd; int r=acquire(c,&fd); if(r) return r;
    open_arg arg={0,mmid,timeout_ms,flags}; r=request(fd,HAB_OPEN,&arg);
    if(!r) {
        pthread_mutex_lock(&c->lock);
        if(c->socket_count==OWN_HAB_MAX_SOCKETS) {
            close_arg undo={(uint32_t)arg.vcid,0}; request(fd,HAB_CLOSE,&undo); r=-EMFILE;
        } else { c->sockets[c->socket_count++]=(uint32_t)arg.vcid; *vcid=(uint32_t)arg.vcid; }
        pthread_mutex_unlock(&c->lock);
    }
    log_event(c,"open",(uint32_t)arg.vcid,mmid,r,NULL); release(c); return r;
}
int own_hab_socket_close(own_hab_transport_t *c,uint32_t vcid,uint32_t flags) {
    int fd; int r=acquire(c,&fd); if(r) return r;
    close_arg arg={vcid,flags}; r=request(fd,HAB_CLOSE,&arg);
    if(!r) { pthread_mutex_lock(&c->lock);
        for(size_t i=0;i<c->socket_count;++i) if(c->sockets[i]==vcid) {
            c->sockets[i]=c->sockets[--c->socket_count]; break; }
        pthread_mutex_unlock(&c->lock); }
    log_event(c,"close",vcid,0,r,NULL); release(c); return r;
}
int own_hab_send(own_hab_transport_t *c,uint32_t vcid,const void *data,uint32_t size,uint32_t flags) {
    if(!data || !size || size>OWN_HAB_MAX_TRANSFER) return -EINVAL;
    int fd; int r=acquire(c,&fd); if(r) return r;
    send_arg arg={(uint64_t)(uintptr_t)data,vcid,size,flags,0}; r=request(fd,HAB_SEND,&arg);
    log_event(c,"send",vcid,size,r,data); release(c); return r;
}
int own_hab_recv(own_hab_transport_t *c,uint32_t vcid,void *data,uint32_t *size,uint32_t timeout_ms,uint32_t flags) {
    if(!size) return -EINVAL;
    uint32_t capacity=*size; *size=0;
    if(!data || !capacity || capacity>OWN_HAB_MAX_TRANSFER || timeout_ms>OWN_HAB_MAX_RECV_MS) return -EINVAL;
    int fd; int r=acquire(c,&fd); if(r) return r;
    uint64_t start=now_ns();
    if(!start) { r=-EIO; goto done; }
    uint64_t deadline=start+(uint64_t)timeout_ms*1000000u;
    for(;;) {
        uint64_t current=now_ns(); uint32_t remaining=0;
        if(timeout_ms) {
            if(!current || current>=deadline) { r=-ETIMEDOUT; break; }
            remaining=(uint32_t)((deadline-current+999999u)/1000000u);
        }
        recv_arg arg={(uint64_t)(uintptr_t)data,vcid,capacity,remaining,flags};
        r=request(fd,HAB_RECV,&arg);
        if(!r) { if(arg.size>capacity) r=-EOVERFLOW; else *size=arg.size; break; }
        if(r!=-EINTR) break;
        if(!timeout_ms) break;
        pthread_mutex_lock(&c->lock); int closing=c->closing; pthread_mutex_unlock(&c->lock);
        if(closing) { r=-ESHUTDOWN; break; }
    }
done:
    log_event(c,"recv",vcid,*size,r,r ? NULL : data); release(c); return r;
}
int own_hab_export(own_hab_transport_t *c,uint32_t vcid,void *data,uint32_t size,uint32_t flags,uint32_t *export_id) {
    if(!data || !size || size>OWN_HAB_MAX_TRANSFER || !export_id) return -EINVAL;
    *export_id=0; int fd; int r=acquire(c,&fd); if(r) return r;
    export_arg arg={(uint64_t)(uintptr_t)data,vcid,size,0,flags}; r=request(fd,HAB_EXPORT,&arg);
    if(!r) *export_id=arg.export_id;
    log_event(c,"export",vcid,size,r,NULL); release(c); return r;
}
int own_hab_unexport(own_hab_transport_t *c,uint32_t vcid,uint32_t export_id,uint32_t flags) {
    int fd; int r=acquire(c,&fd); if(r) return r;
    unexport_arg arg={vcid,export_id,flags}; r=request(fd,HAB_UNEXPORT,&arg);
    log_event(c,"unexport",vcid,export_id,r,NULL); release(c); return r;
}
int own_hab_fd(own_hab_transport_t *c) {
    if(!c || !c->initialized) return -1;
    pthread_mutex_lock(&c->lock); int fd=c->closing ? -1 : c->fd;
    pthread_mutex_unlock(&c->lock); return fd;
}
size_t own_hab_socket_snapshot(own_hab_transport_t *c,uint32_t *ids,size_t capacity) {
    if(!c || !c->initialized) return 0;
    pthread_mutex_lock(&c->lock); size_t total=c->socket_count;
    if(ids) { size_t n=capacity<total ? capacity : total; memcpy(ids,c->sockets,n*sizeof(*ids)); }
    pthread_mutex_unlock(&c->lock); return total;
}
