#include "own_lens.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

typedef struct {float x,y;} coordinate;
typedef struct {int iw,ih,ow,oh;float profiles[32];coordinate *points;} map_cache;
static map_cache cache[2];
static unsigned replacement;
static pthread_mutex_t cache_lock=PTHREAD_MUTEX_INITIALIZER;
static int valid(const float *p){
 for(int k=0;k<8;k++)if(!isfinite(p[k]))return 0;
 return p[0]>=0&&p[0]<=1&&p[1]>=0&&p[1]<=1&&p[2]>=.05f&&p[2]<=2&&p[3]>=.05f&&p[3]<=2&&p[4]>=30&&p[4]<=150&&fabsf(p[5])<=1&&fabsf(p[6])<=1&&p[7]>=.5f&&p[7]<=3;
}
static coordinate project(float u,float v,const float *p,int iw,int ih){
 float tangent=tanf(p[4]*.008726646259971648f);
 float x=(2*u-1)*tangent,y=(2*v-1)*tangent/((float)iw/ih);
 float radius=sqrtf(x*x+y*y),theta=atanf(radius),theta2=theta*theta;
 float scale=radius>1e-8f?theta*(1+p[5]*theta2+p[6]*theta2*theta2)/radius:1;
 coordinate result={(p[0]+p[2]*x*scale/p[7])*iw-.5f,(p[1]+p[3]*y*scale/p[7])*ih-.5f};return result;
}
static map_cache *mapping(int iw,int ih,int ow,int oh,const float p[32]){
 for(int i=0;i<2;i++)if(cache[i].points&&cache[i].iw==iw&&cache[i].ih==ih&&cache[i].ow==ow&&cache[i].oh==oh&&memcmp(cache[i].profiles,p,32*sizeof(float))==0)return &cache[i];
 int slot=cache[0].points?(cache[1].points?(int)(replacement++%2):1):0;
 coordinate *points=malloc((size_t)ow*(size_t)oh*sizeof(*points));if(!points)return NULL;
 int tw=ow/2,th=oh/2;
 for(int y=0;y<oh;y++)for(int x=0;x<ow;x++){
  int camera=(y/th)*2+x/tw;
  points[(size_t)y*ow+x]=project(((float)(x%tw)+.5f)/tw,((float)(y%th)+.5f)/th,p+camera*8,iw,ih);
 }
 free(cache[slot].points);cache[slot]=(map_cache){iw,ih,ow,oh,{0},points};memcpy(cache[slot].profiles,p,32*sizeof(float));return &cache[slot];
}
static float component(const uint8_t *data,int iw,int ih,coordinate point,int channel){
 /* Source chroma is centered between each adjacent UYVY pixel pair. */
 float x=channel?(point.x-.5f)*.5f:point.x,y=point.y;
 int width=channel?iw/2:iw;
 if(point.x<0||point.x>iw-1||point.y<0||point.y>ih-1)return channel?128.f:16.f;
 if(x<0)x=0;if(x>width-1)x=(float)(width-1);
 int x0=(int)x,y0=(int)y,x1=x0+1<width?x0+1:x0,y1=y0+1<ih?y0+1:y0;
 float fx=x-x0,fy=y-y0;
 size_t row0=(size_t)y0*iw*2,row1=(size_t)y1*iw*2;
 size_t a,b;
 if(channel){a=(size_t)x0*4+(channel==1?0u:2u);b=(size_t)x1*4+(channel==1?0u:2u);}else{a=(size_t)x0*2+1;b=(size_t)x1*2+1;}
 float top=data[row0+a]*(1-fx)+data[row0+b]*fx,bottom=data[row1+a]*(1-fx)+data[row1+b]*fx;
 return top*(1-fy)+bottom*fy;
}
static uint8_t byte(float value){int n=(int)(value+.5f);return (uint8_t)(n<0?0:n>255?255:n);}
int own_lens_compose(const uint8_t *frames[4],size_t frame_capacity,uint8_t *output,size_t output_capacity,int semi,int iw,int ih,int ow,int oh,const float profiles[32]){
 if(!frames||!output||!profiles||iw<2||ih<2||iw>4096||ih>4096||(iw&1)||(ih&1)||ow<4||oh<4||ow>4096||oh>4096||(ow%4)||(oh%4)||frame_capacity<(size_t)iw*ih*2||output_capacity<(size_t)ow*oh*3/2)return 0;
 for(int i=0;i<4;i++)if(!frames[i]||!valid(profiles+i*8))return 0;
 pthread_mutex_lock(&cache_lock);map_cache *map=mapping(iw,ih,ow,oh,profiles);if(!map){pthread_mutex_unlock(&cache_lock);return 0;}
 size_t pixels=(size_t)ow*oh;int tw=ow/2,th=oh/2;
 for(int y=0;y<oh;y+=2)for(int x=0;x<ow;x+=2){
  int camera=(y/th)*2+x/tw;const uint8_t *source=frames[camera];float u=0,v=0;
  for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){
   size_t index=(size_t)(y+dy)*ow+x+dx;coordinate p=map->points[index];output[index]=byte(component(source,iw,ih,p,0));u+=component(source,iw,ih,p,1);v+=component(source,iw,ih,p,2);
  }
  size_t uv=(size_t)(y/2)*(ow/2)+x/2;
  if(semi){output[pixels+uv*2]=byte(u*.25f);output[pixels+uv*2+1]=byte(v*.25f);}else{output[pixels+uv]=byte(u*.25f);output[pixels+pixels/4+uv]=byte(v*.25f);}
 }
 pthread_mutex_unlock(&cache_lock);return 1;
}
void own_lens_clear_cache(void){pthread_mutex_lock(&cache_lock);for(int i=0;i<2;i++){free(cache[i].points);memset(&cache[i],0,sizeof(cache[i]));}replacement=0;pthread_mutex_unlock(&cache_lock);}
