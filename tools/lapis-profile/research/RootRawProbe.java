import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Size;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in headless hardware diagnostic. Run with app_process under the existing temporary root. */
public class RootRawProbe {
  static final Map<String,String> opt=new HashMap<>();
  static final HandlerThread thread=new HandlerThread("root-raw-probe");
  static Handler handler;
  static Context context;
  static final java.util.List<Long> frameTimes=java.util.Collections.synchronizedList(new java.util.ArrayList<Long>());
  static final java.util.Map<Long,TotalCaptureResult> frameResults=new java.util.concurrent.ConcurrentHashMap<>();
  static volatile long selectedTimestamp;
  static CameraCaptureSession liveSession;
  static CaptureRequest.Builder liveRequest;
  static CameraCaptureSession.CaptureCallback liveCallback;
  static void log(String s){System.out.println(s);System.out.flush();}
  static int integer(String k,int fallback){return opt.containsKey(k)?Integer.decode(opt.get(k)):fallback;}
  static <T> T waitFor(CompletableFuture<T> f)throws Exception{return f.get(15,TimeUnit.SECONDS);}
  static void settings(CaptureRequest.Builder b){
    b.set(CaptureRequest.CONTROL_AE_MODE,opt.containsKey("auto")?CaptureRequest.CONTROL_AE_MODE_ON:CaptureRequest.CONTROL_AE_MODE_OFF);
    if(opt.containsKey("repeat")) b.set(CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
    b.set(CaptureRequest.SENSOR_SENSITIVITY,integer("iso",400));
    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,Long.parseLong(opt.getOrDefault("exposure","20000000")));
    b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
    // Advertised vendor key is int[] (verified through the native metadata key).
    // Opt-in diagnostic only; array shape/processing effect still needs a controlled A/B.
    if(opt.containsKey("processRaw")) {
      int value=integer("processRaw",0);
      if(value!=0 && value!=1)throw new IllegalArgumentException("processRaw must be 0 or 1");
      b.set(new CaptureRequest.Key<int[]>("com.mediatek.control.capture.processRaw.enable",int[].class),new int[]{value});
    }
    if(opt.containsKey("calibration"))b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
    if(opt.containsKey("focus"))b.set(CaptureRequest.LENS_FOCUS_DISTANCE,Float.parseFloat(opt.get("focus")));
    if(opt.containsKey("nr"))b.set(CaptureRequest.NOISE_REDUCTION_MODE,integer("nr",0));
    if(opt.containsKey("edge"))b.set(CaptureRequest.EDGE_MODE,integer("edge",0));
    int fps=integer("fps",10);
    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new android.util.Range<>(fps,fps));
    b.set(CaptureRequest.SENSOR_FRAME_DURATION,1_000_000_000L/fps);
    if(opt.containsKey("force"))b.set(new CaptureRequest.Key<int[]>("com.mediatek.seamlessfeature.forceSensorMode",int[].class),new int[]{0,integer("force",0)});
    if(opt.containsKey("scenario"))b.set(new CaptureRequest.Key<int[]>("com.mediatek.seamlessfeature.sensorScenario",int[].class),new int[]{0,integer("scenario",0)});
    if(opt.containsKey("configScenario"))b.set(new CaptureRequest.Key<int[]>("com.mediatek.seamlessfeature.configSensorScenarios",int[].class),new int[]{0,1,integer("configScenario",0)});
    if(opt.containsKey("cellFull"))b.set(new CaptureRequest.Key<int[]>("com.mediatek.seamlessfeature.configCellFullSensorIds",int[].class),new int[]{0});
    if(opt.containsKey("cellCrop"))b.set(new CaptureRequest.Key<int[]>("com.mediatek.seamlessfeature.configCellCropSensorIds",int[].class),new int[]{0});
    if(opt.containsKey("hdr"))b.set(new CaptureRequest.Key<Integer>("com.mediatek.hdrfeature.hdrMode",Integer.class),integer("hdr",0));
    if(opt.containsKey("sessionHdr"))b.set(new CaptureRequest.Key<Integer>("com.mediatek.hdrfeature.SessionParamhdrMode",Integer.class),integer("sessionHdr",0));
    if(opt.containsKey("operation"))b.set(new CaptureRequest.Key<Integer>("com.xiaomi.sessionparams.operation",Integer.class),integer("operation",0));
    if(opt.containsKey("client"))b.set(new CaptureRequest.Key<byte[]>("com.xiaomi.sessionparams.clientName",byte[].class),(opt.get("client")+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(opt.containsKey("zoom"))b.set(CaptureRequest.CONTROL_ZOOM_RATIO,Float.valueOf(opt.get("zoom")));
  }
  public static void main(String[] args)throws Exception{
    for(String s:args){String[] pair=s.split("=",2);opt.put(pair[0],pair.length==2?pair[1]:"true");}
    Looper.prepareMainLooper();
    Class<?> at=Class.forName("android.app.ActivityThread");
    Object activityThread=at.getMethod("systemMain").invoke(null);
    context=(Context)at.getMethod("getSystemContext").invoke(activityThread);
    new Thread(()->{try{runProbe();}catch(Throwable e){e.printStackTrace();System.exit(1);}},"root-probe-main").start();
    Looper.loop();
  }
  static void runProbe()throws Exception{
    Class<?> toggle=Class.forName("android.window.DesktopModeFlags$ToggleOverride");
    log("DESKTOP_OVERRIDE_VALUES "+Arrays.toString(toggle.getEnumConstants()));
    for(Object value:toggle.getEnumConstants())if(value.toString().equals("OVERRIDE_UNSET")){
      java.lang.reflect.Field cached=Class.forName("android.window.DesktopModeFlags").getDeclaredField("sCachedToggleOverride");cached.setAccessible(true);cached.set(null,value);
    }
    if(opt.containsKey("package"))context=context.createPackageContext(opt.get("package"),Context.CONTEXT_IGNORE_SECURITY);
    log("CONTEXT uid="+android.os.Process.myUid()+" package="+context.getPackageName()+" opPackage="+context.getOpPackageName());
    CameraManager manager=context.getSystemService(CameraManager.class);
    log("IDS "+Arrays.toString(manager.getCameraIdList()));
    String id=opt.getOrDefault("id","0");
    CameraCharacteristics c;
    if(opt.containsKey("methods")){for(java.lang.reflect.Method m:CameraManager.class.getDeclaredMethods())log("METHOD "+m);for(java.lang.reflect.Field f:Class.forName("android.window.DesktopModeFlags").getDeclaredFields())log("FIELD "+f);System.exit(0);return;}
    java.lang.reflect.Method chars=CameraManager.class.getDeclaredMethod("getCameraCharacteristics",String.class,int.class);chars.setAccessible(true);
    c=(CameraCharacteristics)chars.invoke(manager,id,0);
    Size[] sizes=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.RAW_SENSOR);
    log("HIGH_RES_RAW "+Arrays.toString(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)));
    log("STATIC id="+id+" raw="+Arrays.toString(sizes)+" white="+c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)+" active="+c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
    try{int[] full=c.get(new CameraCharacteristics.Key<int[]>("com.xiaomi.scaler.availableSuperResolutionStreamConfigurations",int[].class));log("SUPER_RES "+Arrays.toString(full));}catch(Exception e){log("SUPER_RES_ERROR "+e);}
    if(opt.containsKey("keys"))for(CaptureRequest.Key<?> key:c.getAvailableCaptureRequestKeys())if(key.getName().toLowerCase().matches(".*(hdr|exposure|gain|raw|remosaic|cms|ae\\.).*")){ log("REQUEST_KEY "+key.getName());
      if(opt.containsKey("keyTypes") && key.getName().matches(".*(processRaw|remosaicenable|recalculateRawType).*"))try {
        java.lang.reflect.Method nativeKey=key.getClass().getDeclaredMethod("getNativeKey");nativeKey.setAccessible(true);Object nk=nativeKey.invoke(key);
        for(java.lang.reflect.Method method:nk.getClass().getDeclaredMethods())if(method.getParameterCount()==0 && (method.getName().equals("getType")||method.getName().equals("getTypeReference")||method.getName().equals("getTag"))) {
          method.setAccessible(true);log("KEY_DETAIL "+key.getName()+" "+method.getName()+"="+method.invoke(nk));
        }
      }catch(Exception e){log("KEY_DETAIL_ERROR "+e);}
    }
    log("CALIB_STATIC distortion="+Arrays.toString(c.get(CameraCharacteristics.LENS_DISTORTION))+" intrinsic="+Arrays.toString(c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION))+" shadingApplied="+c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED)+" mapSize="+c.get(new CameraCharacteristics.Key<android.util.Size>("android.lens.info.shadingMapSize",android.util.Size.class))+" pre="+c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE));
    if(opt.containsKey("calibration"))for(CameraCharacteristics.Key<?> key:c.getKeys())if(key.getName().toLowerCase().matches(".*(distort|intrinsic|radial|lensshad|lsc|calibr).*")) {
      try { Object value=c.get(key);log("CALIB_KEY "+key.getName()+"="+(value!=null&&value.getClass().isArray()?Arrays.deepToString(new Object[]{value}):String.valueOf(value))); }catch(Exception e){log("CALIB_KEY_ERROR "+key.getName()+" "+e);}
    }
    if(!opt.containsKey("capture")){System.exit(0);return;}
    thread.start();handler=new Handler(thread.getLooper());
    CompletableFuture<CameraDevice> opened=new CompletableFuture<>();
    CameraDevice.StateCallback callback=new CameraDevice.StateCallback(){public void onOpened(CameraDevice d){opened.complete(d);}public void onDisconnected(CameraDevice d){log("DISCONNECTED");opened.completeExceptionally(new Exception("disconnected"));}public void onError(CameraDevice d,int e){log("CAMERA_ERROR "+e);opened.completeExceptionally(new Exception("camera error "+e));}};
    CameraManager.class.getMethod("openCameraImpl",String.class,CameraDevice.StateCallback.class,java.util.concurrent.Executor.class,int.class,int.class,boolean.class).invoke(manager,id,callback,(java.util.concurrent.Executor)(r->handler.post(r)),0,0,false);
    CameraDevice device=waitFor(opened);
    int width=integer("width",4080),height=integer("height",3060);
    ImageReader reader=ImageReader.newInstance(width,height,ImageFormat.RAW_SENSOR,1);
    CompletableFuture<String> imageReady=new CompletableFuture<>();
    reader.setOnImageAvailableListener(r->{try(Image image=r.acquireNextImage()){
      if(image==null || imageReady.isDone())return;
      frameTimes.add(image.getTimestamp());
      if(frameTimes.size()==integer("switchAfter",-1)) {
        if(opt.containsKey("lockAfter")) liveRequest.set(CaptureRequest.CONTROL_AE_LOCK,true);
        else {
          liveRequest.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
          if(opt.containsKey("lockWithManual"))liveRequest.set(CaptureRequest.CONTROL_AE_LOCK,true);
          liveRequest.set(CaptureRequest.SENSOR_SENSITIVITY,integer("iso",400));
          liveRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME,Long.parseLong(opt.getOrDefault("exposure","20000000")));
        }
        liveSession.setRepeatingRequest(liveRequest.build(),liveCallback,handler);
        log("SWITCH_REQUEST frame="+frameTimes.size()+" ae="+liveRequest.get(CaptureRequest.CONTROL_AE_MODE)+" lock="+liveRequest.get(CaptureRequest.CONTROL_AE_LOCK));
      }
      if(frameTimes.size()<integer("frames",1))return;
      selectedTimestamp=image.getTimestamp();
      Image.Plane plane=image.getPlanes()[0];ByteBuffer data=plane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
      File output=new File(opt.getOrDefault("output","/data/local/tmp/root-raw-probe.raw16"));
      if(!opt.containsKey("noRaw"))try(java.nio.channels.FileChannel channel=new FileOutputStream(output).getChannel()){ByteBuffer copy=data.duplicate();while(copy.hasRemaining())channel.write(copy);}
      if(opt.containsKey("metrics")) {
        double energy=0,laplace=0,total=0;long count=0,saturated=0;
        for(int y=height/8;y<height*7/8-2;y+=8)for(int x=width/8;x<width*7/8-2;x+=8) {
          int v=data.getShort(y*plane.getRowStride()+x*2)&65535;
          int right=data.getShort(y*plane.getRowStride()+(x+2)*2)&65535;
          int down=data.getShort((y+2)*plane.getRowStride()+x*2)&65535;
          energy+=(right-v)*(double)(right-v)+(down-v)*(double)(down-v);
          laplace+=(right+down-2*v)*(double)(right+down-2*v);total+=v;count++;if(v>=1018)saturated++;
        }
        log("DETAIL energy="+(energy/count)+" laplace="+(laplace/count)+" mean="+(total/count)+" clipped="+(saturated/(double)count));
        int pw=width/8,ph=height/8;
        try(OutputStream preview=new BufferedOutputStream(new FileOutputStream(output.getPath()+".pgm"))) {
          preview.write(("P5\n"+pw+" "+ph+"\n255\n").getBytes("US-ASCII"));
          for(int y=0;y<ph;y++)for(int x=0;x<pw;x++) {
            int v=data.getShort(y*8*plane.getRowStride()+x*16)&65535;
            preview.write((int)(255*Math.sqrt(Math.max(0,Math.min(1,(v-64)/959.0)))));
          }
        }
      }
      int min=65535,max=0,bitOr=0;long sum=0,n=0;
      for(int y=height/4;y<height*3/4;y+=16)for(int x=width/4;x<width*3/4;x+=16){int v=data.getShort(y*plane.getRowStride()+x*plane.getPixelStride())&65535;min=Math.min(min,v);max=Math.max(max,v);bitOr|=v;sum+=v;n++;}
      imageReady.complete("IMAGE "+image.getWidth()+"x"+image.getHeight()+" stride="+plane.getRowStride()+" pixelStride="+plane.getPixelStride()+" bytes="+data.remaining()+" min="+min+" max="+max+" bitOr=0x"+Integer.toHexString(bitOr)+" mean="+(sum/(double)n)+" file="+output);
    }catch(Exception e){imageReady.completeExceptionally(e);}},handler);
    CompletableFuture<CameraCaptureSession> configured=new CompletableFuture<>();
    List<OutputConfiguration> outputs=new ArrayList<>();outputs.add(new OutputConfiguration(reader.getSurface()));
    ImageReader jpeg=opt.containsKey("jpeg")?ImageReader.newInstance(width,height,ImageFormat.JPEG,1):null;
    if(jpeg!=null)outputs.add(new OutputConfiguration(jpeg.getSurface()));
    SessionConfiguration config=new SessionConfiguration(integer("sessionMode",0),outputs,r->handler.post(r),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession s){configured.complete(s);}public void onConfigureFailed(CameraCaptureSession s){configured.completeExceptionally(new Exception("configure failed"));}});
    CaptureRequest.Builder params=device.createCaptureRequest(integer("template",CameraDevice.TEMPLATE_STILL_CAPTURE));settings(params);config.setSessionParameters(params.build());
    device.createCaptureSession(config);CameraCaptureSession session=waitFor(configured);log("CONFIGURED");
    CaptureRequest.Builder request=device.createCaptureRequest(integer("template",CameraDevice.TEMPLATE_STILL_CAPTURE));settings(request);request.addTarget(reader.getSurface());
    CompletableFuture<TotalCaptureResult> result=new CompletableFuture<>();
    java.lang.reflect.Method submit=CameraCaptureSession.class.getMethod(opt.containsKey("repeat")?"setRepeatingRequest":"capture",CaptureRequest.class,CameraCaptureSession.CaptureCallback.class,Handler.class);
    liveSession=session;liveRequest=request;
    liveCallback=new CameraCaptureSession.CaptureCallback(){public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest q,TotalCaptureResult r){Long ts=r.get(CaptureResult.SENSOR_TIMESTAMP);if(ts!=null)frameResults.put(ts,r);result.complete(r);}public void onCaptureFailed(CameraCaptureSession s,CaptureRequest q,CaptureFailure f){result.completeExceptionally(new Exception("capture failed "+f.getReason()));}};
    submit.invoke(session,request.build(),liveCallback,handler);
    log(waitFor(imageReady));
    long deadline=SystemClock.elapsedRealtime()+5000;
    while(!frameResults.containsKey(selectedTimestamp) && SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
    TotalCaptureResult metadata=frameResults.get(selectedTimestamp);
    if(metadata==null)throw new Exception("No timestamp-matched result for selected image");
    if(frameTimes.size()>1) {
      long first=frameTimes.get(0),last=frameTimes.get(frameTimes.size()-1);
      log("CADENCE frames="+frameTimes.size()+" first="+first+" last="+last+" fps="+((frameTimes.size()-1)*1e9/(last-first)));
    }
    log("RESULT aeMode="+metadata.get(CaptureResult.CONTROL_AE_MODE)+" aeLock="+metadata.get(CaptureResult.CONTROL_AE_LOCK)+" timestamp="+metadata.get(CaptureResult.SENSOR_TIMESTAMP)+" iso="+metadata.get(CaptureResult.SENSOR_SENSITIVITY)+" exposure="+metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME)+" white="+metadata.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)+" black="+Arrays.toString(metadata.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)));
    if(opt.containsKey("keys"))for(CaptureResult.Key<?> key:metadata.getKeys())if(key.getName().toLowerCase().matches(".*(stagger|exposure|gainlist|isolist).*")) {
      Object value=metadata.get(key);
      String rendered=value==null?"null":value.getClass().isArray()?Arrays.deepToString(new Object[]{value}):value.toString();
      log("RESULT_KEY "+key.getName()+" type="+(value==null?"null":value.getClass().getName())+" value="+rendered);
    }
    for(String name:new String[]{"com.mediatek.seamlessfeature.sensorScenario","xiaomi.streaming.sensormode","xiaomi.insensorzoom.status"}){try{log("MODE "+name+"="+metadata.get(new CaptureResult.Key<Integer>(name,Integer.class)));}catch(Exception e){log("MODE_ERROR "+name+" "+e);}}
    try{
      java.lang.Process sensor=new ProcessBuilder("/data/local/tmp/rawlens-sensor-enum","/dev/v4l-subdev24").redirectErrorStream(true).start();
      try(BufferedReader lines=new BufferedReader(new InputStreamReader(sensor.getInputStream()))){String line;while((line=lines.readLine())!=null)if(line.startsWith("ACTIVE"))log("DRIVER "+line);}
      sensor.waitFor();
    }catch(Exception e){log("DRIVER_PROBE_ERROR "+e);}
    if(opt.containsKey("calibration")) {
      LensShadingMap lsc=metadata.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
      log("CALIB_RESULT distortion="+Arrays.toString(metadata.get(CaptureResult.LENS_DISTORTION))+" intrinsic="+Arrays.toString(metadata.get(CaptureResult.LENS_INTRINSIC_CALIBRATION))+" shadingMode="+metadata.get(CaptureResult.SHADING_MODE)+" map="+(lsc==null?"null":lsc.toString()));
    }
    log("PROCESSING nr="+metadata.get(CaptureResult.NOISE_REDUCTION_MODE)+" edge="+metadata.get(CaptureResult.EDGE_MODE)+" focusDiopters="+metadata.get(CaptureResult.LENS_FOCUS_DISTANCE)+" lensState="+metadata.get(CaptureResult.LENS_STATE)+" afState="+metadata.get(CaptureResult.CONTROL_AF_STATE));
    if(opt.containsKey("maps")) {
      java.lang.Process maps=new ProcessBuilder("sh","-c","pid=$(getprop init.svc_debug_pid.camerahalserver); cat /proc/$pid/maps").redirectErrorStream(true).start();
      try(BufferedReader lines=new BufferedReader(new InputStreamReader(maps.getInputStream()))){String line;while((line=lines.readLine())!=null)if(line.contains("remosaic")||line.contains("tuning")||line.contains("featurepipe.capture"))log("HAL_MAP "+line);}
      maps.waitFor();
    }
    session.close();reader.close();if(jpeg!=null)jpeg.close();device.close();thread.quitSafely();System.exit(0);
  }
}
