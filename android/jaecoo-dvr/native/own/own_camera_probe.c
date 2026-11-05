#include "own_camera.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
int main(int argc,char **argv){int input=argc>1?atoi(argv[1]):5;own_camera *c=own_camera_open(input);if(!c){fprintf(stderr,"FAILED %s\n",own_camera_error());return 1;}unsigned char *b=malloc(2457600);int ok=0;for(int n=0;n<10;n++){int r=own_camera_read(c,b,2457600);uint32_t hash=2166136261u;if(r>0){for(int k=0;k<r;k++)hash=(hash^b[k])*16777619u;ok++;}printf("OWN_FRAME input=%d frame=%d bytes=%d hash=%08x\n",input,n,r,hash);if(r<0)break;}own_camera_close(c);free(b);return ok==10?0:2;}
