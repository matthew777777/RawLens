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
  static void log(String s){System.out.println(s);System.out.flush();}
  static int integer(String k,int fallback){return opt.containsKey(k)?Integer.decode(opt.get(k)):fallback;}
  static <T> T waitFor(CompletableFuture<T> f)throws Exception{return f.get(15,TimeUnit.SECONDS);}
  static void settings(CaptureRequest.Builder b){
    b.set(CaptureRequest.CONTROL_AE_MODE,opt.containsKey("auto")?CaptureRequest.CONTROL_AE_MODE_ON:CaptureRequest.CONTROL_AE_MODE_OFF);
    if(opt.containsKey("repeat")) b.set(CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
    b.set(CaptureRequest.SENSOR_SENSITIVITY,400);
    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,20_000_000L);
    b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
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
      if(frameTimes.size()<integer("frames",1))return;
      selectedTimestamp=image.getTimestamp();
      Image.Plane plane=image.getPlanes()[0];ByteBuffer data=plane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
      File output=new File(opt.getOrDefault("output","/data/local/tmp/root-raw-probe.raw16"));
      try(java.nio.channels.FileChannel channel=new FileOutputStream(output).getChannel()){ByteBuffer copy=data.duplicate();while(copy.hasRemaining())channel.write(copy);}
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
    submit.invoke(session,request.build(),new CameraCaptureSession.CaptureCallback(){public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest q,TotalCaptureResult r){Long ts=r.get(CaptureResult.SENSOR_TIMESTAMP);if(ts!=null)frameResults.put(ts,r);result.complete(r);}public void onCaptureFailed(CameraCaptureSession s,CaptureRequest q,CaptureFailure f){result.completeExceptionally(new Exception("capture failed "+f.getReason()));}},handler);
    log(waitFor(imageReady));
    long deadline=SystemClock.elapsedRealtime()+5000;
    while(!frameResults.containsKey(selectedTimestamp) && SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
    TotalCaptureResult metadata=frameResults.get(selectedTimestamp);
    if(metadata==null)throw new Exception("No timestamp-matched result for selected image");
    if(frameTimes.size()>1) {
      long first=frameTimes.get(0),last=frameTimes.get(frameTimes.size()-1);
      log("CADENCE frames="+frameTimes.size()+" first="+first+" last="+last+" fps="+((frameTimes.size()-1)*1e9/(last-first)));
    }
    log("RESULT timestamp="+metadata.get(CaptureResult.SENSOR_TIMESTAMP)+" iso="+metadata.get(CaptureResult.SENSOR_SENSITIVITY)+" exposure="+metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME)+" white="+metadata.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)+" black="+Arrays.toString(metadata.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)));
    for(String name:new String[]{"com.mediatek.seamlessfeature.sensorScenario","xiaomi.streaming.sensormode","xiaomi.insensorzoom.status"}){try{log("MODE "+name+"="+metadata.get(new CaptureResult.Key<Integer>(name,Integer.class)));}catch(Exception e){log("MODE_ERROR "+name+" "+e);}}
    try{
      java.lang.Process sensor=new ProcessBuilder("/data/local/tmp/rawlens-sensor-enum","/dev/v4l-subdev24").redirectErrorStream(true).start();
      try(BufferedReader lines=new BufferedReader(new InputStreamReader(sensor.getInputStream()))){String line;while((line=lines.readLine())!=null)if(line.startsWith("ACTIVE"))log("DRIVER "+line);}
      sensor.waitFor();
    }catch(Exception e){log("DRIVER_PROBE_ERROR "+e);}
    session.close();reader.close();if(jpeg!=null)jpeg.close();device.close();thread.quitSafely();System.exit(0);
  }
}
