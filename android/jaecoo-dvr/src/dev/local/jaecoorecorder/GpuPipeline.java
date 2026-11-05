package dev.local.jaecoorecorder;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;
import android.graphics.Bitmap;
import android.opengl.GLUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.Arrays;

/** GPU UYVY decode, geometric correction, mosaic and Surface encoding.
 * All methods must execute on one render thread. Supplied Android Surfaces
 * remain owned by their creators. Optional packed-YUV readback uses the verified byte-buffer encoder path. */
final class GpuPipeline implements AutoCloseable {
 private static final int EGL_RECORDABLE_ANDROID=0x3142;
 private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
 private EGLContext context=EGL14.EGL_NO_CONTEXT;
 private EGLConfig config;
 private EGLSurface pbuffer=EGL14.EGL_NO_SURFACE;
 private EGLSurface preview=EGL14.EGL_NO_SURFACE;
 private EGLSurface encoder=EGL14.EGL_NO_SURFACE;
 private Surface previewNative;
 private int previewWidth,previewHeight,encoderWidth,encoderHeight;
 private int program,position,textureCoordinate,textureUniform,sizeUniform;
 private int activeCount=4;
 private int overlayProgram,overlayTexture,overlayWidth,overlayHeight;
 private long previewNextNs,previewFirstNs,previewLastNs,previewFrames;private long previewAtNs;private int previewRate=25;private float overlayRatio=.045f;
 private final FloatBuffer overlayQuad=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{-1,-1,0,1,1,-1,1,1,-1,1,0,0,1,1,1,0});
 private int rgbFbo,rgbTexture,packedFbo,packedTexture,packedProgram,readWidth,readHeight;
 private final FloatBuffer packedQuad=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{-1,-1,1,-1,-1,1,1,1});
 long readbackFrames,readbackTotalNs,lastReadbackNs;
 private static final String PACK_FRAGMENT=
 "precision highp float;uniform sampler2D rgb;uniform float w;uniform float h;uniform float pw;uniform float semi;"+
 "vec3 pixel(float x,float y){return texture2D(rgb,vec2((x+.5)/w,1.-(y+.5)/h)).rgb;}"+
 "float value(float i){float pixels=w*h;if(i<pixels){float y=floor(i/w);float x=i-y*w;return clamp(dot(pixel(x,y),vec3(.256788,.504129,.097906))+16./255.,0.,1.);}"+
 "float q=i-pixels;float v=0.;float c=q;if(semi>.5){v=mod(q,2.);c=floor(q*.5);}else{if(q>=pixels*.25){v=1.;c=q-pixels*.25;}}"+
 "float row=floor(c/(w*.5));float x=(c-row*(w*.5))*2.;float y=row*2.;vec3 color=pixel(x+.5,y+.5);"+
 "return clamp(dot(color,v>.5?vec3(.439216,-.367788,-.071427):vec3(-.148223,-.290993,.439216))+128./255.,0.,1.);}"+
 "void main(){float i=((gl_FragCoord.y-.5)*pw+gl_FragCoord.x-.5)*4.;gl_FragColor=vec4(value(i),value(i+1.),value(i+2.),value(i+3.));}";
 private final int[] textures=new int[4];
 private final ByteBuffer[] upload=new ByteBuffer[4];
 private final boolean[] textureReady=new boolean[4];
 private int inputWidth,inputHeight;
 private final int[] meshBuffers=new int[4],meshVertexCounts=new int[4],meshWidths=new int[4],meshHeights=new int[4];
 private final float[][] meshProfiles=new float[4][];
 private final boolean[] meshCorrected=new boolean[4];
 private long ownerThread,encoderEpochNs=-1,lastEncoderPts=-1;
 long frames,totalCpuNs,lastCpuNs;
 long lastUploadNs,totalUploadNs,maxUploadNs,lastPreviewNs,totalPreviewNs,maxPreviewNs,lastEncodeNs,totalEncodeNs,maxEncodeNs;
 long lastCopyNs,totalCopyNs,maxCopyNs,lastTextureNs,totalTextureNs,maxTextureNs;
 String glInfo="GPU not initialized";
 private static final String VERTEX="attribute vec2 aPosition;attribute vec2 aUv;varying vec2 vUv;void main(){gl_Position=vec4(aPosition,0.,1.);vUv=aUv;}";
 private static final String FRAGMENT=
  "precision highp float;varying vec2 vUv;uniform sampler2D uTex;uniform vec2 uSize;"+
  "vec3 atPixel(vec2 p){p=clamp(p,vec2(0.),uSize-vec2(1.));float pair=floor(p.x*.5);vec4 raw=texture2D(uTex,vec2((pair+.5)/(uSize.x*.5),(p.y+.5)/uSize.y));"+
  "float yy=mix(raw.g,raw.a,mod(p.x,2.));float y=max(0.,(yy*255.-16.)/255.);float u=raw.r-128./255.;float v=raw.b-128./255.;"+
  "return clamp(vec3(1.16438356*y+1.59602678*v,1.16438356*y-.39176229*u-.81296765*v,1.16438356*y+2.01723214*u),0.,1.);}"+
  "void main(){vec2 uv=vUv;"+
  "if(any(lessThan(uv,vec2(0.)))||any(greaterThan(uv,vec2(1.)))){gl_FragColor=vec4(0.,0.,0.,1.);return;}"+
  "vec2 p=uv*uSize-vec2(.5);vec2 base=floor(p);vec2 f=fract(p);vec3 a=mix(atPixel(base),atPixel(base+vec2(1.,0.)),f.x);vec3 b=mix(atPixel(base+vec2(0.,1.)),atPixel(base+vec2(1.,1.)),f.x);gl_FragColor=vec4(mix(a,b,f.y),1.);}";
 private void thread(){long id=Thread.currentThread().getId();if(ownerThread==0)ownerThread=id;else if(ownerThread!=id)throw new IllegalStateException("GPU must remain on its render thread");}
 void initialize(){
  thread();if(context!=EGL14.EGL_NO_CONTEXT)return;
  try{
   display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);if(display==EGL14.EGL_NO_DISPLAY)throw egl("eglGetDisplay");
   int[] version=new int[2];if(!EGL14.eglInitialize(display,version,0,version,1))throw egl("eglInitialize");
   int[] attributes={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|EGL14.EGL_PBUFFER_BIT,EGL_RECORDABLE_ANDROID,1,EGL14.EGL_NONE};
   EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];if(!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0)||count[0]<1)throw egl("eglChooseConfig recordable");config=configs[0];
   context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);if(context==EGL14.EGL_NO_CONTEXT)throw egl("eglCreateContext");
   pbuffer=EGL14.eglCreatePbufferSurface(display,config,new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);if(pbuffer==EGL14.EGL_NO_SURFACE)throw egl("eglCreatePbufferSurface");current(pbuffer);
   program=link(VERTEX,FRAGMENT);position=GLES20.glGetAttribLocation(program,"aPosition");textureCoordinate=GLES20.glGetAttribLocation(program,"aUv");textureUniform=GLES20.glGetUniformLocation(program,"uTex");sizeUniform=GLES20.glGetUniformLocation(program,"uSize");
   GLES20.glGenBuffers(4,meshBuffers,0);GLES20.glGenTextures(4,textures,0);for(int texture:textures){GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);}
   glInfo=GLES20.glGetString(GLES20.GL_VENDOR)+" / "+GLES20.glGetString(GLES20.GL_RENDERER)+" / "+GLES20.glGetString(GLES20.GL_VERSION);check("initialize");
  }catch(RuntimeException e){close();throw e;}
 }
 void setPreviewSurface(Surface surface,int width,int height){
  thread();initialize();if(surface==previewNative&&width==previewWidth&&height==previewHeight&&preview!=EGL14.EGL_NO_SURFACE)return;
  previewNextNs=previewFirstNs=previewLastNs=previewFrames=0;current(pbuffer);destroy(preview);preview=EGL14.EGL_NO_SURFACE;previewNative=null;previewWidth=previewHeight=0;
  if(surface!=null&&surface.isValid()&&width>0&&height>0){preview=window(surface);previewNative=surface;previewWidth=width;previewHeight=height;}
 }
 void attachEncoder(Surface surface,int width,int height){thread();initialize();if(surface==null||!surface.isValid()||width<=0||height<=0)throw new IllegalArgumentException("Encoder Surface invalid");detachEncoder();encoder=window(surface);encoderWidth=width;encoderHeight=height;encoderEpochNs=-1;lastEncoderPts=-1;}
 void detachEncoder(){thread();if(display!=EGL14.EGL_NO_DISPLAY&&context!=EGL14.EGL_NO_CONTEXT){current(pbuffer);destroy(encoder);}encoder=EGL14.EGL_NO_SURFACE;encoderWidth=encoderHeight=0;encoderEpochNs=-1;lastEncoderPts=-1;}
 void setActiveCount(int count){activeCount=Math.max(1,Math.min(4,count));}
 void setPreviewRate(int fps){previewRate=Math.max(1,Math.min(25,fps));}
 void setOverlay(Bitmap bitmap){thread();initialize();current(pbuffer);if(bitmap==null){if(overlayTexture!=0)GLES20.glDeleteTextures(1,new int[]{overlayTexture},0);overlayTexture=0;overlayWidth=overlayHeight=0;return;}if(overlayTexture==0){int[] id=new int[1];GLES20.glGenTextures(1,id,0);overlayTexture=id[0];}GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,overlayTexture);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);if(overlayWidth!=bitmap.getWidth()||overlayHeight!=bitmap.getHeight()){GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);overlayWidth=bitmap.getWidth();overlayHeight=bitmap.getHeight();}else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,bitmap);check("overlay upload");}
 void draw(byte[][] frames,int width,int height,int selected,float[][] profiles,boolean corrected,long timeNs){ByteBuffer[] direct=new ByteBuffer[4];for(int k=0;k<4;k++){if(frames[k]==null)continue;if(upload[k]==null||upload[k].capacity()!=frames[k].length)upload[k]=ByteBuffer.allocateDirect(frames[k].length);upload[k].clear();upload[k].put(frames[k]).flip();direct[k]=upload[k];}drawDirect(direct,width,height,selected,profiles,corrected,timeNs);}
 void drawDirect(ByteBuffer[] source,int width,int height,int selected,float[][] profiles,boolean corrected,long timeNs){thread();initialize();if(source==null||source.length!=4||width<2||height<2||(width&1)!=0||width>4096||height>4096)throw new IllegalArgumentException("Invalid direct GPU input");long begin=System.nanoTime(),uploadStart=begin;current(pbuffer);boolean changed=width!=inputWidth||height!=inputHeight;int required=width*height*2;for(int k=0;k<4;k++){if(source[k]==null){textureReady[k]=false;continue;}if(!source[k].isDirect()||source[k].capacity()<required)throw new IllegalArgumentException("Direct frame required");ByteBuffer bytes=source[k].duplicate();bytes.position(0);bytes.limit(required);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[k]);GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,4);if(changed||!textureReady[k])GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width/2,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes);else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,width/2,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes);textureReady[k]=true;}lastUploadNs=System.nanoTime()-uploadStart;totalUploadNs+=lastUploadNs;maxUploadNs=Math.max(maxUploadNs,lastUploadNs);lastCopyNs=0;lastTextureNs=lastUploadNs;totalTextureNs+=lastTextureNs;inputWidth=width;inputHeight=height;long previewStart=System.nanoTime();if(preview!=EGL14.EGL_NO_SURFACE&&(previewRate==25||previewNextNs==0||timeNs>=previewNextNs)){if(previewNative!=null&&previewNative.isValid()){render(preview,previewWidth,previewHeight,selected,profiles,corrected);swap(preview);previewAtNs=timeNs;previewFrames++;if(previewFirstNs==0)previewFirstNs=timeNs;previewLastNs=timeNs;long interval=1000000000L/previewRate;if(previewNextNs==0)previewNextNs=timeNs;previewNextNs+=interval;if(previewNextNs<timeNs-interval)previewNextNs=timeNs+interval;}else setPreviewSurface(null,0,0);}lastPreviewNs=System.nanoTime()-previewStart;totalPreviewNs+=lastPreviewNs;maxPreviewNs=Math.max(maxPreviewNs,lastPreviewNs);check("draw direct");lastCpuNs=System.nanoTime()-begin;totalCpuNs+=lastCpuNs;frames++;}
 private void render(EGLSurface surface,int width,int height,int selected,float[][] profiles,boolean corrected){
  current(surface);renderScene(width,height,selected,profiles,corrected);
 }
 private void renderScene(int width,int height,int selected,float[][] profiles,boolean corrected){if(activeCount==1&&selected<0)selected=0;
  GLES20.glViewport(0,0,width,height);GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glUniform1i(textureUniform,0);GLES20.glUniform2f(sizeUniform,inputWidth,inputHeight);GLES20.glEnableVertexAttribArray(position);GLES20.glEnableVertexAttribArray(textureCoordinate);
  float fitAspect=(float)inputWidth/inputHeight;int fitW=Math.min(width,Math.round(height*fitAspect)),fitH=Math.min(height,Math.round(width/fitAspect));int fitLeft=(width-fitW)/2,fitBottom=(height-fitH)/2;
  for(int k=0;k<4;k++){
   if(!textureReady[k]||(selected>=0&&selected!=k))continue;
   int cellW=selected>=0?fitW:fitW/2,cellH=selected>=0?fitH:fitH/2;int left=fitLeft+(selected>=0?0:(k%2)*cellW);int bottom=fitBottom+(selected>=0?0:(1-k/2)*cellH);
   float aspect=(float)inputWidth/inputHeight;int vw=Math.min(cellW,Math.round(cellH*aspect)),vh=Math.min(cellH,Math.round(cellW/aspect));GLES20.glViewport(left+(cellW-vw)/2,bottom+(cellH-vh)/2,vw,vh);
   float[] p=profiles!=null&&profiles.length>k?profiles[k]:null;if(p==null||p.length<8)p=new float[]{.5f,.5f,.318309886f,.424413181f,100,0,0,1};
   ensureMesh(k,p,corrected);GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,meshBuffers[k]);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,0);GLES20.glVertexAttribPointer(textureCoordinate,2,GLES20.GL_FLOAT,false,16,8);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[k]);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,meshVertexCounts[k]);
  }
  GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);GLES20.glDisableVertexAttribArray(position);GLES20.glDisableVertexAttribArray(textureCoordinate);
  drawOverlay(width,height);
 }
 private void drawOverlay(int width,int height){if(overlayTexture==0)return;if(overlayProgram==0)overlayProgram=link(VERTEX,"precision mediump float;varying vec2 vUv;uniform sampler2D uTex;void main(){gl_FragColor=texture2D(uTex,vUv);}");GLES20.glUseProgram(overlayProgram);GLES20.glViewport(0,0,width,Math.max(20,Math.round(height*overlayRatio)));GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,overlayTexture);GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram,"uTex"),0);int a=GLES20.glGetAttribLocation(overlayProgram,"aPosition"),u=GLES20.glGetAttribLocation(overlayProgram,"aUv");GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);overlayQuad.position(0);GLES20.glEnableVertexAttribArray(a);GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,16,overlayQuad);overlayQuad.position(2);GLES20.glEnableVertexAttribArray(u);GLES20.glVertexAttribPointer(u,2,GLES20.GL_FLOAT,false,16,overlayQuad);GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisable(GLES20.GL_BLEND);GLES20.glDisableVertexAttribArray(a);GLES20.glDisableVertexAttribArray(u);}
 private int target(int texture,int width,int height){GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);int[] id=new int[1];GLES20.glGenFramebuffers(1,id,0);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,id[0]);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture,0);if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("GPU readback framebuffer incomplete");return id[0];}
 private void releaseReadback(){if(rgbFbo!=0)GLES20.glDeleteFramebuffers(1,new int[]{rgbFbo},0);if(packedFbo!=0)GLES20.glDeleteFramebuffers(1,new int[]{packedFbo},0);if(rgbTexture!=0)GLES20.glDeleteTextures(1,new int[]{rgbTexture},0);if(packedTexture!=0)GLES20.glDeleteTextures(1,new int[]{packedTexture},0);rgbFbo=packedFbo=rgbTexture=packedTexture=readWidth=readHeight=0;}
 void readYuv(ByteBuffer output,boolean semi,int width,int height,float[][] profiles,boolean corrected){thread();initialize();if(width<4||height<4||width>4096||height>4096||width%4!=0||height%4!=0||output==null||!output.isDirect()||output.capacity()<width*height*3/2)throw new IllegalArgumentException("Invalid packed YUV output");long start=System.nanoTime();current(pbuffer);try{if(readWidth!=width||readHeight!=height){releaseReadback();int[] names=new int[2];GLES20.glGenTextures(2,names,0);rgbTexture=names[0];packedTexture=names[1];rgbFbo=target(rgbTexture,width,height);packedFbo=target(packedTexture,width/4,height*3/2);readWidth=width;readHeight=height;}if(packedProgram==0)packedProgram=link(VERTEX,PACK_FRAGMENT);
 GLES20.glDisable(GLES20.GL_DITHER);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,rgbFbo);renderScene(width,height,-1,profiles,corrected);
 GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,packedFbo);GLES20.glViewport(0,0,width/4,height*3/2);GLES20.glUseProgram(packedProgram);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,rgbTexture);GLES20.glUniform1i(GLES20.glGetUniformLocation(packedProgram,"rgb"),0);GLES20.glUniform1f(GLES20.glGetUniformLocation(packedProgram,"w"),width);GLES20.glUniform1f(GLES20.glGetUniformLocation(packedProgram,"h"),height);GLES20.glUniform1f(GLES20.glGetUniformLocation(packedProgram,"pw"),width/4);GLES20.glUniform1f(GLES20.glGetUniformLocation(packedProgram,"semi"),semi?1:0);int location=GLES20.glGetAttribLocation(packedProgram,"aPosition");GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);packedQuad.position(0);GLES20.glEnableVertexAttribArray(location);GLES20.glVertexAttribPointer(location,2,GLES20.GL_FLOAT,false,0,packedQuad);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(location);output.clear();GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT,4);GLES20.glReadPixels(0,0,width/4,height*3/2,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,output);output.position(0);output.limit(width*height*3/2);check("packed YUV readback");readbackFrames++;lastReadbackNs=System.nanoTime()-start;readbackTotalNs+=lastReadbackNs;
 }finally{GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);}}
 private void ensureMesh(int camera,float[] profile,boolean corrected){
  boolean changed=meshVertexCounts[camera]==0||meshWidths[camera]!=inputWidth||meshHeights[camera]!=inputHeight||meshCorrected[camera]!=corrected||(corrected&&!Arrays.equals(meshProfiles[camera],profile));
  if(!changed)return;FloatBuffer mesh=LensMesh.build(corrected?64:1,corrected?48:1,profile,inputWidth,inputHeight,corrected);GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,meshBuffers[camera]);GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,mesh.remaining()*4,mesh,GLES20.GL_STATIC_DRAW);meshVertexCounts[camera]=mesh.remaining()/4;meshWidths[camera]=inputWidth;meshHeights[camera]=inputHeight;meshCorrected[camera]=corrected;meshProfiles[camera]=profile.clone();
 }
 void resetStats(){thread();frames=totalCpuNs=lastCpuNs=0;lastUploadNs=totalUploadNs=maxUploadNs=lastPreviewNs=totalPreviewNs=maxPreviewNs=lastEncodeNs=totalEncodeNs=maxEncodeNs=0;lastCopyNs=totalCopyNs=maxCopyNs=lastTextureNs=totalTextureNs=maxTextureNs=0;}
 double actualPreviewFps(){return previewFrames<2?0:(previewFrames-1)*1e9/Math.max(1,previewLastNs-previewFirstNs);}
 String stats(){double count=Math.max(1,frames);return "YUV readback="+readbackFrames+" avg="+(readbackFrames==0?0:readbackTotalNs/1e6/readbackFrames)+"ms; "+String.format(Locale.US,"GPU renders=%d CPU-submit=%.2fms avg=%.2fms; upload last/avg/max=%.2f/%.2f/%.2fms (copy=%.2f/%.2f/%.2f tex=%.2f/%.2f/%.2f); preview=%.2f/%.2f/%.2fms; encode-submit=%.2f/%.2f/%.2fms",frames,lastCpuNs/1e6,totalCpuNs/1e6/count,lastUploadNs/1e6,totalUploadNs/1e6/count,maxUploadNs/1e6,lastCopyNs/1e6,totalCopyNs/1e6/count,maxCopyNs/1e6,lastTextureNs/1e6,totalTextureNs/1e6/count,maxTextureNs/1e6,lastPreviewNs/1e6,totalPreviewNs/1e6/count,maxPreviewNs/1e6,lastEncodeNs/1e6,totalEncodeNs/1e6/count,maxEncodeNs/1e6);}
 private EGLSurface window(Surface s){EGLSurface result=EGL14.eglCreateWindowSurface(display,config,s,new int[]{EGL14.EGL_NONE},0);if(result==EGL14.EGL_NO_SURFACE)throw egl("eglCreateWindowSurface");return result;}
 private void current(EGLSurface surface){if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw egl("eglMakeCurrent");}
 private void swap(EGLSurface surface){if(!EGL14.eglSwapBuffers(display,surface))throw egl("eglSwapBuffers");}
 private void destroy(EGLSurface surface){if(surface!=null&&surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);}
 private static IllegalStateException egl(String operation){return new IllegalStateException(operation+" EGL=0x"+Integer.toHexString(EGL14.eglGetError()));}
 private static void check(String operation){int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR)throw new IllegalStateException(operation+" GL=0x"+Integer.toHexString(error));}
 private static int shader(int type,String source){int shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);int[] ok=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){String error=GLES20.glGetShaderInfoLog(shader);GLES20.glDeleteShader(shader);throw new IllegalStateException("GPU shader: "+error);}return shader;}
 private static int link(String vertex,String fragment){int v=shader(GLES20.GL_VERTEX_SHADER,vertex),f=0,p=0;try{f=shader(GLES20.GL_FRAGMENT_SHADER,fragment);p=GLES20.glCreateProgram();GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException("GPU link: "+GLES20.glGetProgramInfoLog(p));return p;}catch(RuntimeException e){if(p!=0)GLES20.glDeleteProgram(p);throw e;}finally{GLES20.glDeleteShader(v);if(f!=0)GLES20.glDeleteShader(f);}}
 @Override public void close(){
  thread();if(display!=EGL14.EGL_NO_DISPLAY){if(context!=EGL14.EGL_NO_CONTEXT&&pbuffer!=EGL14.EGL_NO_SURFACE&&EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context)){if(overlayProgram!=0){GLES20.glDeleteProgram(overlayProgram);overlayProgram=0;}if(overlayTexture!=0){GLES20.glDeleteTextures(1,new int[]{overlayTexture},0);overlayTexture=0;}releaseReadback();if(packedProgram!=0){GLES20.glDeleteProgram(packedProgram);packedProgram=0;}if(program!=0)GLES20.glDeleteProgram(program);GLES20.glDeleteTextures(4,textures,0);GLES20.glDeleteBuffers(4,meshBuffers,0);}EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);destroy(preview);destroy(encoder);destroy(pbuffer);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);EGL14.eglReleaseThread();}
  display=EGL14.EGL_NO_DISPLAY;context=EGL14.EGL_NO_CONTEXT;preview=encoder=pbuffer=EGL14.EGL_NO_SURFACE;previewNative=null;program=0;inputWidth=inputHeight=0;encoderEpochNs=-1;for(int k=0;k<4;k++){textures[k]=0;meshBuffers[k]=meshVertexCounts[k]=meshWidths[k]=meshHeights[k]=0;meshProfiles[k]=null;meshCorrected[k]=false;textureReady[k]=false;upload[k]=null;}
 }
}
