// SPDX-License-Identifier: GPL-3.0-or-later
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, headless, single CameraDevice/Session mode-switch experiment. No direct sensor writes. */
public class RootSeamlessProbe {
    static final Map<String,String> args=new HashMap<>();
    static int number(String key,int fallback){return Integer.parseInt(args.getOrDefault(key,""+fallback));}
    static int[] list(String key,String fallback){return Arrays.stream(args.getOrDefault(key,fallback).split(",")).mapToInt(Integer::parseInt).toArray();}
    static void log(String value){System.out.println(value);System.out.flush();}
    static String command(String... cmd)throws Exception {
        java.lang.Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String result=new String(p.getInputStream().readAllBytes());
        if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Command timeout");}
        if(p.exitValue()!=0)throw new IOException(result);
        return result.trim();
    }
    static <T> T waitFor(CompletableFuture<T> future)throws Exception{return future.get(15,TimeUnit.SECONDS);}
    static void settings(CaptureRequest.Builder b,int driver,int scenario,int[] configs,boolean session) {
        b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new android.util.Range<>(30,30));
        b.set(CaptureRequest.SENSOR_FRAME_DURATION,33_333_333L);
        b.set(CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
        b.set(new CaptureRequest.Key<int[]>("com.mediatek.seamlessfeature.forceSensorMode",int[].class),new int[]{0,driver});
        b.set(new CaptureRequest.Key<int[]>("com.mediatek.seamlessfeature.sensorScenario",int[].class),new int[]{0,scenario});
        b.set(new CaptureRequest.Key<Integer>("com.mediatek.hdrfeature.hdrMode",Integer.class),number("hdr",0));
        b.set(new CaptureRequest.Key<Integer>("com.mediatek.hdrfeature.SessionParamhdrMode",Integer.class),number("hdr",0));
        if(session) {
            int[] value=new int[configs.length+2];value[0]=0;value[1]=configs.length;
            System.arraycopy(configs,0,value,2,configs.length);
            b.set(new CaptureRequest.Key<int[]>("com.mediatek.seamlessfeature.configSensorScenarios",int[].class),value);
        }
    }
    public static void main(String[] values)throws Exception {
        for(String value:values){int at=value.indexOf('=');if(at>0)args.put(value.substring(0,at),value.substring(at+1));}
        Looper.prepareMainLooper();
        Class<?> activity=Class.forName("android.app.ActivityThread");
        Object thread=activity.getMethod("systemMain").invoke(null);
        Context context=(Context)activity.getMethod("getSystemContext").invoke(thread);
        Class<?> toggle=Class.forName("android.window.DesktopModeFlags$ToggleOverride");
        for(Object value:toggle.getEnumConstants())if(value.toString().equals("OVERRIDE_UNSET")) {
            java.lang.reflect.Field field=Class.forName("android.window.DesktopModeFlags").getDeclaredField("sCachedToggleOverride");
            field.setAccessible(true);field.set(null,value);
        }
        new Thread(()->{try{run(context);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}},"seamless-control").start();
        Looper.loop();
    }
    static void run(Context context)throws Exception {
        int[] modes=list("modes","6,7,6"),scenarios=list("scenarios","0,7,0"),configs=list("configs","0,7");
        if(modes.length!=scenarios.length||modes.length<2||modes.length>5)throw new IllegalArgumentException("Invalid sequence");
        int width=4080,height=number("height",3060),frames=number("frames",45);
        File output=new File(args.getOrDefault("output","/data/local/tmp/seamless-probe"));output.mkdirs();
        String hal=command("getprop","init.svc_debug_pid.camerahalserver"),server=command("pidof","cameraserver");
        log("INITIAL_SERVICES hal="+hal+" cameraserver="+server);
        HandlerThread cameraThread=new HandlerThread("seamless-camera",android.os.Process.THREAD_PRIORITY_DISPLAY);cameraThread.start();
        Handler handler=new Handler(cameraThread.getLooper());
        CameraManager manager=context.getSystemService(CameraManager.class);
        CompletableFuture<CameraDevice> opened=new CompletableFuture<>();
        CameraDevice.StateCallback state=new CameraDevice.StateCallback(){
            public void onOpened(CameraDevice d){opened.complete(d);}
            public void onDisconnected(CameraDevice d){d.close();opened.completeExceptionally(new IOException("Disconnected"));}
            public void onError(CameraDevice d,int e){d.close();opened.completeExceptionally(new IOException("Camera error "+e));}
        };
        CameraDevice device=null;CameraCaptureSession session=null;ImageReader raw=null,preview=null;
        final TreeMap<Long,Image> images=new TreeMap<>();
        final Map<Long,TotalCaptureResult> results=new HashMap<>();
        final List<Long> times=new ArrayList<>();
        final int[] phase={-1},count={0};
        final CompletableFuture<?>[] completed={null};
        final long[] last={0},boundaryGap={0};
        final class Pairer {
            void pair(long stamp)throws Exception {
                Image image=images.get(stamp);TotalCaptureResult result=results.get(stamp);
                if(image==null||result==null)return;
                images.remove(stamp);results.remove(stamp);
                try {
                    Object tag=result.getRequest().getTag();
                    if(!(tag instanceof Integer)||((Integer)tag)!=phase[0]||completed[0]==null||completed[0].isDone())return;
                    if(count[0]==0 && last[0]!=0)boundaryGap[0]=stamp-last[0];
                    times.add(stamp);last[0]=stamp;count[0]++;
                    if(count[0]<frames)return;
                    Image.Plane plane=image.getPlanes()[0];
                    if(image.getWidth()!=width||image.getHeight()!=height||plane.getPixelStride()!=2)throw new IOException("Unexpected RAW layout");
                    ByteBuffer data=plane.getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    int min=65535,max=0;long sum=0,n=0;
                    for(int y=16;y<height-16;y+=64)for(int x=16;x<width-16;x+=64) {
                        int v=data.getShort(y*plane.getRowStride()+x*2)&65535;min=Math.min(min,v);max=Math.max(max,v);sum+=v;n++;
                    }
                    if(max==0)throw new IOException("Empty RAW");
                    try(java.nio.channels.FileChannel channel=new FileOutputStream(new File(output,"phase-"+phase[0]+".raw16")).getChannel()) {
                        data.rewind();while(data.hasRemaining())channel.write(data);
                    }
                    long span=times.get(times.size()-1)-times.get(0);
                    log("PHASE "+phase[0]+" requestedDriver="+modes[phase[0]]+" logical="+scenarios[phase[0]]+" frames="+count[0]+" fps="+((times.size()-1)*1e9/span)+" boundaryGapNs="+boundaryGap[0]+" min="+min+" max="+max+" mean="+(sum/(double)n)+" white="+result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)+" iso="+result.get(CaptureResult.SENSOR_SENSITIVITY));
                    ((CompletableFuture)completed[0]).complete(Boolean.TRUE);
                } finally { image.close(); }
            }
            void failure(Throwable failure){if(completed[0]!=null)completed[0].completeExceptionally(failure);else failure.printStackTrace();}
        }
        Pairer pairer=new Pairer();
        try {
            CameraManager.class.getMethod("openCameraImpl",String.class,CameraDevice.StateCallback.class,java.util.concurrent.Executor.class,int.class,int.class,boolean.class)
                .invoke(manager,"0",state,(java.util.concurrent.Executor)(r->handler.post(r)),0,0,false);
            device=waitFor(opened);
            raw=ImageReader.newInstance(width,height,ImageFormat.RAW_SENSOR,8);
            raw.setOnImageAvailableListener(r->{try {
                Image image=r.acquireNextImage();if(image==null)return;images.put(image.getTimestamp(),image);pairer.pair(image.getTimestamp());
                while(images.size()>4)images.pollFirstEntry().getValue().close();
            }catch(Throwable failure){pairer.failure(failure);}},handler);
            preview=ImageReader.newInstance(640,480,ImageFormat.YUV_420_888,2);
            preview.setOnImageAvailableListener(r->{try(Image image=r.acquireNextImage()){}},handler);
            CompletableFuture<CameraCaptureSession> configured=new CompletableFuture<>();
            SessionConfiguration config=new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,Arrays.asList(new OutputConfiguration(raw.getSurface()),new OutputConfiguration(preview.getSurface())),r->handler.post(r),new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession s){configured.complete(s);}
                public void onConfigureFailed(CameraCaptureSession s){configured.completeExceptionally(new IOException("Configure failed"));}
            });
            CaptureRequest.Builder params=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);settings(params,modes[0],scenarios[0],configs,true);config.setSessionParameters(params.build());
            device.createCaptureSession(config);session=waitFor(configured);log("CONFIGURED_SINGLE_SESSION");
            final CameraCaptureSession.CaptureCallback callback=new CameraCaptureSession.CaptureCallback(){
                public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest request,TotalCaptureResult result){try {
                    Long stamp=result.get(CaptureResult.SENSOR_TIMESTAMP);if(stamp==null)return;results.put(stamp,result);pairer.pair(stamp);
                    while(results.size()>64)results.remove(Collections.min(results.keySet()));
                }catch(Throwable failure){pairer.failure(failure);}}
                public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f){log("CAPTURE_FAILED phase="+r.getTag()+" reason="+f.getReason());}
            };
            for(int index=0;index<modes.length;index++) {
                final int selected=index;CompletableFuture<Boolean> done=new CompletableFuture<>();
                CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);settings(request,modes[index],scenarios[index],configs,false);
                request.addTarget(raw.getSurface());request.addTarget(preview.getSurface());request.setTag(index);
                CompletableFuture<Void> submitted=new CompletableFuture<>();final CameraCaptureSession active=session;
                handler.post(()->{try {phase[0]=selected;count[0]=0;times.clear();boundaryGap[0]=0;completed[0]=done;active.setRepeatingRequest(request.build(),callback,handler);submitted.complete(null);}catch(Throwable failure){submitted.completeExceptionally(failure);}});
                waitFor(submitted);waitFor(done);
                String actual=command("/data/local/tmp/rawlens-sensor-enum","/dev/v4l-subdev24");
                String expected=String.format("ACTIVE code=0x%08x size=%dx%d",0x1000301a|(modes[index]<<16),width,height);
                for(String line:actual.split("\n"))if(line.startsWith("ACTIVE"))log("DRIVER_PHASE "+index+" "+line);
                if(!actual.contains(expected))throw new IOException("Requested mode not active; expected "+expected);
                if(!command("getprop","init.svc_debug_pid.camerahalserver").equals(hal)||!command("pidof","cameraserver").equals(server))throw new IOException("Camera service restarted");
                if(index==0 && args.containsKey("clearForceAfterConfig")){command("setprop","vendor.debug.cameng.force_sensormode","");log("CLEARED_STARTUP_FORCE_WITHOUT_RESTART");}
            }
            log("SEQUENCE_COMPLETE sameCameraDevice=true sameSession=true sameServicePids=true");
        } finally {
            if(session!=null)session.close();if(device!=null)device.close();
            CompletableFuture<Void> drained=new CompletableFuture<>();handler.post(()->{for(Image image:images.values())image.close();images.clear();drained.complete(null);});
            waitFor(drained);if(raw!=null)raw.close();if(preview!=null)preview.close();cameraThread.quitSafely();
        }
    }
}
