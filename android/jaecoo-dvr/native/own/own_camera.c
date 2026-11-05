#define _GNU_SOURCE
#include "own_camera.h"
#include "hab_transport.h"
#include <stdatomic.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <time.h>
#define BYTES 2457600u
#define NBUF 6
struct own_camera {
 own_hab_transport_t hab;
 uint32_t command,event,frame;
 uint64_t handle;
 pthread_mutex_t command_lock;
 pthread_mutex_t read_lock;
 pthread_t event_thread;
 atomic_int running;
 atomic_int started,closing;
 int thread_started,command_initialized,read_initialized,global_reserved;
 int fd[NBUF];void *buffer[NBUF];uint32_t export_id[NBUF];
};
static own_camera global;
static pthread_mutex_t global_lock=PTHREAD_MUTEX_INITIALIZER;
static int global_ready;
static unsigned global_users;
static int global_end_pending;
static int global_inputs[16];
static int global_input_count;
static _Thread_local char error_text[256];
const char *own_camera_error(void){return error_text;}
static int failure(const char *step,int code){snprintf(error_text,sizeof(error_text),"%s: %d (%s)",step,code,strerror(code<0?-code:code));fprintf(stderr,"OWN_CAMERA %s\n",error_text);return code<0?code:-EIO;}
static int exchange(own_camera *c,uint32_t channel,void *packet,uint32_t length,uint32_t timeout){
 uint32_t cmd;memcpy(&cmd,packet,4);int r=own_hab_send(&c->hab,channel,packet,length,0);if(r)return failure("send",r);
 uint32_t received=length;r=own_hab_recv(&c->hab,channel,packet,&received,timeout,0);if(r)return failure("recv",r);
 if(received!=length)return failure("response length",-EPROTO);
 uint32_t *words=packet;if(words[0]!=cmd||words[1]!=0){char step[120];snprintf(step,sizeof(step),"response cmd=%u got=%u status=%d",cmd,words[0],(int32_t)words[1]);return failure(step,-EPROTO);}return 0;
}
static int command(own_camera *c,void *packet,uint32_t length,uint32_t timeout){pthread_mutex_lock(&c->command_lock);int r=exchange(c,c->command,packet,length,timeout);pthread_mutex_unlock(&c->command_lock);return r;}
static void *events(void *arg){own_camera *c=arg;while(atomic_load(&c->running)){uint32_t packet[66]={0},size=sizeof(packet);int r=own_hab_recv(&c->hab,c->event,packet,&size,250,0);if(r){if(r==-ENODEV||r==-EBADF)break;continue;}if(size>=4&&packet[0]==2706424046u){uint32_t ping[2]={17,0};command(c,ping,sizeof(ping),500);}}return NULL;}
static int connect_session(own_camera *c,int enumerate){
 memset(c,0,sizeof(*c));for(int k=0;k<NBUF;k++)c->fd[k]=-1;
 atomic_init(&c->running,0);atomic_init(&c->started,0);atomic_init(&c->closing,0);
 int r=pthread_mutex_init(&c->command_lock,NULL);if(r)return failure("command mutex",-r);c->command_initialized=1;
 r=pthread_mutex_init(&c->read_lock,NULL);if(r)return failure("read mutex",-r);c->read_initialized=1;
 r=own_hab_init(&c->hab,NULL,NULL);if(r)return failure("hab init",r);
 uint32_t temporary=0;r=own_hab_open(&c->hab,201,1000,0,&temporary);if(r)return failure("handshake socket",r);
 struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t)!=0)return failure("monotonic clock",-errno);
 uint32_t hello[8]={0,3,(uint32_t)t.tv_nsec,(uint32_t)getpid(),1031,1031,0,(uint32_t)enumerate};
 r=own_hab_send(&c->hab,temporary,hello,sizeof(hello),0);uint32_t size=sizeof(hello);
 if(!r)r=own_hab_recv(&c->hab,temporary,hello,&size,1000,0);
 own_hab_socket_close(&c->hab,temporary,0);if(r||size!=32||hello[0]>65530)return failure("handshake",r?r:-EPROTO);
 uint32_t base=hello[0];c->frame=((base+2)<<16)|201;r=own_hab_open(&c->hab,((base+1)<<16)|201,1000,0,&c->command);
 if(!r)r=own_hab_open(&c->hab,(base<<16)|201,1000,0,&c->event);
 if(r)return failure("session channels",r);
 atomic_store(&c->running,1);r=pthread_create(&c->event_thread,NULL,events,c);if(r)return failure("event thread",-r);c->thread_started=1;
 return 0;
}
static void disconnect_session(own_camera *c){
 atomic_store(&c->running,0);
 if(c->thread_started){pthread_join(c->event_thread,NULL);c->thread_started=0;}
 own_hab_destroy(&c->hab);
 if(c->read_initialized){pthread_mutex_destroy(&c->read_lock);c->read_initialized=0;}
 if(c->command_initialized){pthread_mutex_destroy(&c->command_lock);c->command_initialized=0;}
}
/* Caller holds global_lock; no camera may depend on this global session. */
static void end_global_locked(void){
 if(global_ready){uint32_t end[2]={19,0};command(&global,end,sizeof(end),750);}
 global_ready=0;global_input_count=0;disconnect_session(&global);global_end_pending=0;
}
void own_camera_end(void){
 pthread_mutex_lock(&global_lock);
 if(global_users)global_end_pending=1;else end_global_locked();
 pthread_mutex_unlock(&global_lock);
}
static void release_global_user(void){
 pthread_mutex_lock(&global_lock);
 if(global_users)--global_users;
 if(!global_users&&global_end_pending)end_global_locked();
 pthread_mutex_unlock(&global_lock);
}
int own_camera_begin(void){pthread_mutex_lock(&global_lock);if(global_ready){pthread_mutex_unlock(&global_lock);return 0;}
 int r=connect_session(&global,1);if(!r){uint32_t ping[2]={17,0};r=command(&global,ping,sizeof(ping),1000);}
 if(!r){uint32_t query[1286]={0};r=command(&global,query,sizeof(query),1000);if(!r){fprintf(stderr,"OWN_CAMERA inputs=%u version=%u\n",query[1285],query[4]);uint32_t count=query[1285];if(count>16){r=failure("input count",-EOVERFLOW);}else{memset(query,0,sizeof(query));query[2]=1;query[3]=count;r=command(&global,query,sizeof(query),1000);if(!r){global_input_count=(int)count;for(uint32_t k=0;k<count;k++){global_inputs[k]=(int)query[5+80*k];fprintf(stderr,"OWN_CAMERA input=%d\n",global_inputs[k]);}}}}}
 if(!r)global_ready=1;else disconnect_session(&global);
 pthread_mutex_unlock(&global_lock);return r;
}
int own_camera_inputs(int *out,int capacity){
 if(capacity<0||(!out&&capacity))return -EINVAL;
 int r=own_camera_begin();if(r)return r;
 pthread_mutex_lock(&global_lock);int count=global_ready ? global_input_count : 0;
 int copy=capacity<count ? capacity : count;for(int k=0;k<copy;k++)out[k]=global_inputs[k];
 pthread_mutex_unlock(&global_lock);return count;
}
static void handle_packet(uint32_t *p,uint32_t cmd,uint64_t handle){p[0]=cmd;p[1]=0;memcpy(p+2,&handle,8);}
own_camera *own_camera_open(int input){if(own_camera_begin())return NULL;
 pthread_mutex_lock(&global_lock);if(!global_ready||global_end_pending){pthread_mutex_unlock(&global_lock);failure("session ending",-ESHUTDOWN);return NULL;}
 int supported=0;for(int k=0;k<global_input_count;k++)if(global_inputs[k]==input)supported=1;
 if(!supported){pthread_mutex_unlock(&global_lock);failure("input not enumerated",-EINVAL);return NULL;}++global_users;pthread_mutex_unlock(&global_lock);
 own_camera *c=calloc(1,sizeof(*c));if(!c){release_global_user();failure("allocation",-ENOMEM);return NULL;}
 if(connect_session(c,0))goto fail;
 c->global_reserved=1;
 uint32_t open_packet[6]={1,0,(uint32_t)input,0,0,0};if(command(c,open_packet,sizeof(open_packet),1000))goto fail;
 memcpy(&c->handle,open_packet+4,8);if(!c->handle){failure("empty handle",-EPROTO);goto fail;}
 /* Server assigns command channel at base+1; its MMID is not VCID. Frame base retained separately below. */
 /* Establish frame MMID by a saved session base stored in frame before connection returns. */
 uint32_t frame_mmid=c->frame;c->frame=0;int frame_result=own_hab_open(&c->hab,frame_mmid,1000,0,&c->frame);if(frame_result){failure("frame channel",frame_result);goto fail;}
 int ion=open("/dev/ion",O_RDONLY|O_CLOEXEC);if(ion<0){failure("ion open",-errno);goto fail;}
 for(int k=0;k<NBUF;k++){struct {uint64_t length;uint32_t heap,flags,fd,unused;} allocation={BYTES,0x02000000,0,0,0};
 if(ioctl(ion,0xc0184900u,&allocation)<0){failure("ion allocation",-errno);close(ion);goto fail;}
 c->fd[k]=(int)allocation.fd;c->buffer[k]=mmap(NULL,BYTES,PROT_READ|PROT_WRITE,MAP_SHARED,c->fd[k],0);if(c->buffer[k]==MAP_FAILED){c->buffer[k]=NULL;failure("ion mmap",-errno);close(ion);goto fail;}}
 close(ion);
 uint32_t buffers[250]={0};handle_packet(buffers,5,c->handle);buffers[4]=117965058;buffers[8]=NBUF;
 for(int k=0;k<NBUF;k++){uint32_t *d=buffers+10+20*k;d[0]=1280;d[1]=960;d[2]=2560;d[3]=BYTES;d[4]=(uint32_t)c->fd[k];d[18]=1;}
 pthread_mutex_lock(&c->command_lock);int r=own_hab_send(&c->hab,c->command,buffers,sizeof(buffers),0);uint32_t count=0,size=4;
 if(!r)r=own_hab_recv(&c->hab,c->command,&count,&size,1000,0);
 if(!r&&(size!=4||count!=NBUF))r=-EPROTO;
 for(int k=0;k<NBUF&&!r;k++){r=own_hab_export(&c->hab,c->command,c->buffer[k],BYTES,1,&c->export_id[k]);uint64_t id=c->export_id[k];if(!r)r=own_hab_send(&c->hab,c->command,&id,8,0);}
 size=sizeof(buffers);if(!r)r=own_hab_recv(&c->hab,c->command,buffers,&size,1000,0);pthread_mutex_unlock(&c->command_lock);
 if(r||size!=sizeof(buffers)||buffers[0]!=5||buffers[1]){failure("set buffers",r?r:-EPROTO);goto fail;}
 uint32_t start[4]={0};handle_packet(start,7,c->handle);if(command(c,start,sizeof(start),1000))goto fail;c->started=1;fprintf(stderr,"OWN_CAMERA opened input=%d handle=%llu\n",input,(unsigned long long)c->handle);return c;
 fail:c->global_reserved=1;own_camera_close(c);return NULL;
}
int own_camera_read(own_camera *c,void *destination,int capacity){if(!c||!destination||capacity<(int)BYTES)return failure("frame destination",-EINVAL);
 pthread_mutex_lock(&c->read_lock);
 int r=0;if(atomic_load(&c->closing)){r=-ESHUTDOWN;goto done;}
 uint32_t packet[20]={0};handle_packet(packet,11,c->handle);packet[4]=500000000;r=exchange(c,c->frame,packet,sizeof(packet),750);if(r)goto done;
 uint32_t index=packet[8];if(index>=NBUF){r=failure("buffer index",-EPROTO);goto done;}memcpy(destination,c->buffer[index],BYTES);
 uint32_t release[6]={0};handle_packet(release,12,c->handle);release[4]=index;r=command(c,release,sizeof(release),750);if(!r)r=(int)BYTES;
 done:pthread_mutex_unlock(&c->read_lock);return r;
}
void own_camera_stop(own_camera *c){if(c&&atomic_exchange(&c->started,0)){uint32_t p[4]={0};handle_packet(p,8,c->handle);command(c,p,sizeof(p),750);}}
void own_camera_close(own_camera *c){if(!c)return;char saved_error[sizeof(error_text)];memcpy(saved_error,error_text,sizeof(saved_error));atomic_store(&c->closing,1);if(c->read_initialized)pthread_mutex_lock(&c->read_lock);
 atomic_store(&c->running,0);
 own_camera_stop(c);if(c->handle){uint32_t p[4]={0};handle_packet(p,2,c->handle);command(c,p,sizeof(p),750);c->handle=0;}
 if(c->thread_started){pthread_join(c->event_thread,NULL);c->thread_started=0;}
 if(c->event){int e=own_hab_socket_close(&c->hab,c->event,0);if(e)fprintf(stderr,"OWN_CAMERA event close error=%d\n",e);c->event=0;}
 if(c->frame&&c->handle==0){int e=own_hab_socket_close(&c->hab,c->frame,0);if(e)fprintf(stderr,"OWN_CAMERA frame close error=%d\n",e);c->frame=0;}
 for(int k=0;k<NBUF;k++)if(c->export_id[k]){int e=own_hab_unexport(&c->hab,c->command,c->export_id[k],1);if(e)fprintf(stderr,"OWN_CAMERA unexport id=%u error=%d\n",c->export_id[k],e);}
 own_hab_destroy(&c->hab);for(int k=0;k<NBUF;k++){if(c->buffer[k])munmap(c->buffer[k],BYTES);if(c->fd[k]>=0)close(c->fd[k]);}
 if(c->read_initialized)pthread_mutex_unlock(&c->read_lock);
 int reserved=c->global_reserved;disconnect_session(c);free(c);if(reserved)release_global_user();memcpy(error_text,saved_error,sizeof(error_text));}
