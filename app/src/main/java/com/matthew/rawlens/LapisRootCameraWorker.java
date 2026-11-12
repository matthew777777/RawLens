// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.*;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Fixed-purpose, bounded root capture entry point. Never runs inside the app process. */
public final class LapisRootCameraWorker {
    private static final String LIB = "/vendor/lib64/mt6878/libmtkcam_metastore.so";
    private static final String CORE_LIB = "/vendor/lib64/mt6878/libmtkcam_hal_core_utils.so";
    private static final String HDR_CONFIG = "vendor.debug.vhdr.strategy.config.force";
    private static final String HDR_RUNTIME = "vendor.debug.vhdr.strategy.force";
    private static final String PROP = "vendor.debug.cameng.force_sensormode";
    private static final File STATE = new File("/data/adb/rawlens-lapis");
    private static final File CAMERASERVER_PENDING = new File(STATE,"restart-cameraserver");
    private static Context context;
    private static File job;
    private static int owner;
    private static final JSONObject report = new JSONObject();

    public static void main(String[] args) throws Exception {
        if (args.length != 11 || android.os.Process.myUid() != 0) throw new SecurityException("Root capture requires root");
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        context = (Context)at.getMethod("getSystemContext").invoke(thread);
        new Thread(() -> {
            int status = 1;
            try { run(args); status = 0; }
            catch (Throwable failure) {
                failure.printStackTrace();
                try { report.put("error", failure.toString()); } catch (Exception ignored) { }
            } finally {
                if (job != null) {
                    try {
                        File file = new File(job, "report.json");
                        Files.write(file.toPath(), report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        android.system.Os.chown(file.getPath(), owner, owner);
                    } catch (Exception failure) { failure.printStackTrace(); status = 1; }
                }
            }
            System.exit(status);
        }, "lapis-root-capture").start();
        Looper.loop();
    }

    private static void run(String[] args) throws Exception {
        if (!LapisRootProfile.INSTANCE.supported()) throw new IOException("Untested device or firmware");
        boolean live = args[0].startsWith("session-");
        String mode = live ? args[0].substring(8) : args[0];
        boolean full = mode.equals("50mp");
        boolean dcg=mode.equals("dcg14");
        if(dcg && !live)throw new IOException("DCG14 requires a regular app AE session");
        if (!full && !dcg && !mode.equals("2x")) throw new IOException("Unsupported sensor mode");
        job = new File(args[1]).getCanonicalFile();
        String apk = new File(args[2]).getCanonicalPath();
        String nativeDirectory = args[3];
        owner = Integer.parseInt(args[4]);
        int iso = Integer.parseInt(args[5]);
        long exposure = Long.parseLong(args[6]);
        int orientation = Integer.parseInt(args[7]);
        String[] values = args[8].split(",");
        if (values.length != 4) throw new IOException("Invalid white balance");
        float[] gains = new float[4];
        for (int i=0;i<4;i++) { gains[i]=Float.parseFloat(values[i]); if (!Float.isFinite(gains[i]) || gains[i]<=0) throw new IOException("Invalid white balance"); }
        if (!args[9].equals("v1") || iso < 50 || iso > 6400 || exposure < 100000 || exposure > 1000000000L || orientation < 1 || orientation > 8)
            throw new IOException("Capture settings outside the tested range");
        if (!job.isDirectory() || owner < 10000) throw new IOException("Invalid private capture directory");
        if (!command("getenforce").trim().equals("Enforcing")) throw new IOException("SELinux must be Enforcing");
        if (!command("readlink", "/proc/self/ns/mnt").equals(command("readlink", "/proc/1/ns/mnt")))
            throw new IOException("Root must use the main mount namespace");
        File nativeLibrary=new File(nativeDirectory,"librawLensLapis.so");
        if(!nativeLibrary.isFile()) {
            nativeLibrary=new File(job,"librawLensLapis.so");
            try(ZipFile zip=new ZipFile(apk);InputStream in=zip.getInputStream(zip.getEntry("lib/arm64-v8a/librawLensLapis.so"))) {
                Files.write(nativeLibrary.toPath(),readAll(in));
            }
        }
        System.load(nativeLibrary.getPath());
        STATE.mkdirs(); command("chmod", "700", STATE.getPath());
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(STATE,"session.lock"), "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Another Redmi root capture is active");
            File lease = new File(STATE,"lease"), cleanup = new File(STATE,"restore.sh");
            if (lease.exists()) {
                command("sh", cleanup.getPath());
                if (lease.exists() || !hash(new File(LIB)).equals(LapisRootProfile.VENDOR_SHA256))
                    throw new IOException("Previous camera session could not be restored");
            }
            if (!hash(new File(LIB)).equals(LapisRootProfile.VENDOR_SHA256)) throw new IOException("Vendor camera library does not match the tested firmware");
            String old = command("getprop", PROP).trim();
            if (!old.matches("-?[0-9]*")) throw new IOException("Unknown existing sensor override");
            if(!hash(new File(CORE_LIB)).equals(LapisRootProfile.CORE_SHA256))throw new IOException("Unexpected core camera library");
            String oldConfig=command("getprop",HDR_CONFIG).trim(),oldRuntime=command("getprop",HDR_RUNTIME).trim();
            if(!oldConfig.matches("-?[0-9]*")||!oldRuntime.matches("-?[0-9]*"))throw new IOException("Unknown existing HDR override");
            String token = UUID.randomUUID().toString();
            File patched = new File(job,"vendor-patch.so");
            if (full) patch(apk,patched);
            if(dcg)patchCore(patched);
            String selectedLib=dcg?CORE_LIB:LIB;
            String script = "#!/system/bin/sh\n" +
                "test \"$(cat '"+lease+"' 2>/dev/null)\" = '"+token+"' || exit 0\n" +
                ((full||dcg) ? "umount -l '"+selectedLib+"' 2>/dev/null || true\n" : "") +
                "setprop '"+PROP+"' '"+old+"' || exit 1\n" +
                (dcg?"setprop '"+HDR_CONFIG+"' '"+oldConfig+"' || exit 1\nsetprop '"+HDR_RUNTIME+"' '"+oldRuntime+"' || exit 1\n":"")+
                "test \"$(sha256sum '"+CORE_LIB+"' | cut -d ' ' -f 1)\" = '"+LapisRootProfile.CORE_SHA256+"' || exit 1\n" +
                "test \"$(sha256sum '"+LIB+"' | cut -d ' ' -f 1)\" = '"+LapisRootProfile.VENDOR_SHA256+"' || exit 1\n" +
                ((full||dcg) ? "rm -f "+shellQuote(patched.getPath())+"\n" : "") +
                // "handoff": the next mode's worker restarts the HAL once for both sessions.
                (full ? "touch '"+CAMERASERVER_PENDING+"' || exit 1\n" : "") +
                "if [ \"$1\" != handoff ]; then\n" +
                "setprop ctl.restart camerahalserver || exit 1\n" +
                "if [ -e '"+CAMERASERVER_PENDING+"' ]; then rm -f '"+CAMERASERVER_PENDING+"'; setprop ctl.restart cameraserver || exit 1; fi\n" +
                "fi\n" +
                "rm -f '"+lease+"'\n";
            Files.write(cleanup.toPath(),script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(lease.toPath(),token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ownedJson(new File(job,"recovery.json"),new JSONObject().put("previousMode",old).put("previousConfig",oldConfig).put("previousRuntime",oldRuntime).put("token",token));
            // Independent process: restoration survives an app/worker crash or SIGKILL.
            File watchdogRestore=new File(STATE,"restore-"+token+".sh");
            Files.write(watchdogRestore.toPath(),script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(new File(STATE,"owner.pid").toPath(),Integer.toString(android.os.Process.myPid()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String wait = live ? "last=$(cut -d. -f1 /proc/uptime); while [ \"$(cat '"+lease+"' 2>/dev/null)\" = '"+token+"' ]; do sleep 5; now=$(cut -d. -f1 /proc/uptime); stamp=$(cat "+shellQuote(new File(job,"heartbeat").getPath())+" 2>/dev/null); case \"$stamp\" in ''|*[!0-9]*) ;; *) last=$stamp;; esac; [ $((now-last)) -gt 30 ] && break; done; " : "sleep 90; ";
            String watchdog=wait+"if [ \"$(cat '"+lease+"' 2>/dev/null)\" = '"+token+"' ]; then " +
                "case \"$(cat /proc/"+android.os.Process.myPid()+"/cmdline 2>/dev/null)\" in *com.matthew.rawlens.LapisRootCameraWorker*"+job+"*) kill -KILL "+android.os.Process.myPid()+";; esac; " +
                "sh '"+watchdogRestore+"'; fi; rm -f '"+watchdogRestore+"'";
            new ProcessBuilder("sh","-c",watchdog).redirectOutput(new File(job,"watchdog.log")).redirectErrorStream(true).start();
            try {
                if (full||dcg) command("mount","-o","bind",patched.getPath(),selectedLib);
                if(dcg) { command("setprop",HDR_CONFIG,"2");command("setprop",HDR_RUNTIME,"64"); }
                command("setprop",PROP,full?"12":dcg?"9":"7");
                // A handed-off 50 MP session left stale stream tables in cameraserver.
                boolean cameraserver=full||CAMERASERVER_PENDING.exists();
                if (live) restartAndAwait(cameraserver);
                else {
                    command("setprop","ctl.restart","camerahalserver");
                    if (cameraserver) command("setprop","ctl.restart","cameraserver");
                    Thread.sleep(7000);
                }
                Files.deleteIfExists(CAMERASERVER_PENDING.toPath());
                float focus=Float.parseFloat(args[10]);
                if(!Float.isFinite(focus)||focus<0||focus>20)throw new IOException("Invalid focus distance");
                if (live) liveSession(full,dcg);
                else capture(full,iso,exposure,orientation,gains,focus);
            } finally {
                File release=new File(job,"release");
                boolean handoff=live&&release.isFile()&&new String(Files.readAllBytes(release.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim().equals("handoff");
                if (handoff) command("sh",cleanup.getPath(),"handoff"); else command("sh",cleanup.getPath());
                report.put("handoff",handoff);
                Files.deleteIfExists(patched.toPath());
                boolean restored = !lease.exists() && command("getprop",PROP).trim().equals(old)
                    && hash(new File(LIB)).equals(LapisRootProfile.VENDOR_SHA256)
                    && hash(new File(CORE_LIB)).equals(LapisRootProfile.CORE_SHA256)
                    && (!dcg || (command("getprop",HDR_CONFIG).trim().equals(oldConfig) && command("getprop",HDR_RUNTIME).trim().equals(oldRuntime)))
                    && command("getenforce").trim().equals("Enforcing");
                report.put("restored",restored);
                if (!restored) throw new IOException("Camera restoration failed; watchdog retained");
            }
        }
    }

    /** Restart the HAL and return once init reports a new running process; the app then polls camera 0.
     * Falls back to the original fixed 7 s when init does not expose service PIDs. */
    private static void restartAndAwait(boolean cameraserver)throws Exception {
        String hal=servicePid("camerahalserver"),server=cameraserver?servicePid("cameraserver"):"";
        command("setprop","ctl.restart","camerahalserver");
        if(cameraserver)command("setprop","ctl.restart","cameraserver");
        long started=SystemClock.elapsedRealtime(),deadline=started+7000;
        while(SystemClock.elapsedRealtime()<deadline) {
            if(restarted("camerahalserver",hal)&&(!cameraserver||restarted("cameraserver",server))) {
                // Let cameraserver process the old provider's binder death before the app polls camera 0.
                Thread.sleep(250);
                report.put("halRestartMs",SystemClock.elapsedRealtime()-started);
                return;
            }
            Thread.sleep(50);
        }
        report.put("halRestartMs",-1);
    }
    private static String servicePid(String service)throws Exception{return command("getprop","init.svc_debug_pid."+service).trim();}
    private static boolean restarted(String service,String previous)throws Exception {
        if(previous.isEmpty())return false;
        String now=servicePid(service);
        return !now.isEmpty()&&!now.equals(previous)&&command("getprop","init.svc."+service).trim().equals("running");
    }

    private static void patch(String apk,File out) throws Exception {
        byte[] library=Files.readAllBytes(new File(LIB).toPath());
        if (ByteBuffer.wrap(library).order(ByteOrder.LITTLE_ENDIAN).getInt(0x87240)!=0xd10403ff)
            throw new IOException("Unexpected metadata entry instruction");
        byte[] hook;
        try (ZipFile zip=new ZipFile(apk);InputStream in=zip.getInputStream(zip.getEntry("assets/device-profiles/lapis-50mp-hook.bin"))) { hook=readAll(in); }
        if (hook.length<100 || hook.length>0x2000) throw new IOException("Invalid metadata wrapper");
        System.arraycopy(hook,0,library,0x509a0,hook.length);
        ByteBuffer.wrap(library).order(ByteOrder.LITTLE_ENDIAN).putInt(0x87240,0x14000000|(((0x509a0-0x87240)/4)&0x3ffffff));
        Files.write(out.toPath(),library);
        command("chmod","644",out.getPath());
        command("chcon","u:object_r:vendor_file:s0",out.getPath());
    }

    /** Root owns only the sensor lease. RawLens itself owns all Camera2 frames and requests. */
    private static void patchCore(File out)throws Exception {
        byte[] bytes=Files.readAllBytes(new File(CORE_LIB).toPath());
        ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(data.getInt(0x41410)!=0xd10303ff||data.getInt(0x41414)!=0xa9097bfd)throw new IOException("Unexpected RAW format selector");
        // Choose BAYER14_UNPAK for the app stream; never scale or synthesize pixels.
        data.putInt(0x41410,0x528442e0);data.putInt(0x41414,0xd65f03c0);
        Files.write(out.toPath(),bytes);command("chmod","644",out.getPath());command("chcon","u:object_r:vendor_file:s0",out.getPath());
    }
    private static void liveSession(boolean full,boolean dcg)throws Exception {
        JSONObject ready=new JSONObject().put("mode",full?"50mp":dcg?"dcg14":"2x").put("state","ready");
        ownedJson(new File(job,"ready.json"),ready);
        long lastHeartbeat=SystemClock.elapsedRealtime()/1000;
        int expected=full?0x100c300e:dcg?0x1009301a:0x1007301a,width=full?8160:4080,height=full?6120:3060;
        boolean verified=false;
        while(!new File(job,"release").exists()) {
            try { lastHeartbeat=Long.parseLong(new String(Files.readAllBytes(new File(job,"heartbeat").toPath()),java.nio.charset.StandardCharsets.UTF_8).trim()); }
            catch(Exception ignored) { }
            if(SystemClock.elapsedRealtime()/1000-lastHeartbeat>25)throw new IOException("Camera-session heartbeat expired");
            int[] active=LapisRootCameraNative.activeSensorFormat();
            boolean match=active!=null&&active[0]==width&&active[1]==height&&active[2]==expected;
            if(match!=verified) {
                ownedJson(new File(job,"verified.json"),new JSONObject().put("verified",match).put("mode",full?"50mp":dcg?"dcg14":"2x")
                    .put("width",width).put("height",height).put("driverCode",active==null?0:active[2]));
                verified=match;
            }
            Thread.sleep(200);
        }
        report.put("mode",full?"50mp":dcg?"dcg14":"2x").put("session",true);
    }

    private static void ownedJson(File file,JSONObject value)throws Exception {
        File temp=new File(file.getPath()+".tmp");
        Files.write(temp.toPath(),value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        android.system.Os.chown(temp.getPath(),owner,owner);
        Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void capture(boolean full,int iso,long exposure,int orientation,float[] gains,float focus) throws Exception {
        // Android 16's desktop-mode lookup otherwise asks Settings for an unregistered app_process.
        Class<?> toggle=Class.forName("android.window.DesktopModeFlags$ToggleOverride");
        Field cache=Class.forName("android.window.DesktopModeFlags").getDeclaredField("sCachedToggleOverride");cache.setAccessible(true);
        for(Object value:toggle.getEnumConstants())if(value.toString().equals("OVERRIDE_UNSET"))cache.set(null,value);
        CameraManager manager=context.getSystemService(CameraManager.class);
        Method chars=CameraManager.class.getDeclaredMethod("getCameraCharacteristics",String.class,int.class);chars.setAccessible(true);
        CameraCharacteristics c=(CameraCharacteristics)chars.invoke(manager,"0",0);
        if (!Objects.equals(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG))
            throw new IOException("Unexpected CFA arrangement");
        int width=full?8160:4080,height=full?6120:3060;
        HandlerThread thread=new HandlerThread("lapis-camera");thread.start();Handler handler=new Handler(thread.getLooper());
        CameraDevice device=null;CameraCaptureSession session=null;ImageReader reader=null;Image image=null;
        try {
            CompletableFuture<CameraDevice> opened=new CompletableFuture<>();
            CameraDevice.StateCallback callback=new CameraDevice.StateCallback(){public void onOpened(CameraDevice d){opened.complete(d);}public void onDisconnected(CameraDevice d){d.close();opened.completeExceptionally(new IOException("Camera disconnected"));}public void onError(CameraDevice d,int e){d.close();opened.completeExceptionally(new IOException("Camera error "+e));}};
            CameraManager.class.getMethod("openCameraImpl",String.class,CameraDevice.StateCallback.class,Executor.class,int.class,int.class,boolean.class)
                .invoke(manager,"0",callback,(Executor)(r->handler.post(r)),0,0,false);
            device=await(opened);
            reader=ImageReader.newInstance(width,height,ImageFormat.RAW_SENSOR,2);
            CompletableFuture<Image> arrived=new CompletableFuture<>();
            reader.setOnImageAvailableListener(r->{Image frame=r.acquireNextImage();if(frame!=null&&!arrived.complete(frame))frame.close();},handler);
            CaptureRequest.Builder params=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            settings(params,iso,exposure,gains);
            params.set(CaptureRequest.LENS_FOCUS_DISTANCE,focus);
            CompletableFuture<CameraCaptureSession> configured=new CompletableFuture<>();
            SessionConfiguration config=new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,Collections.singletonList(new OutputConfiguration(reader.getSurface())),r->handler.post(r),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession s){configured.complete(s);}public void onConfigureFailed(CameraCaptureSession s){configured.completeExceptionally(new IOException("Sensor configuration rejected"));}});
            config.setSessionParameters(params.build());device.createCaptureSession(config);session=await(configured);
            CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);settings(request,iso,exposure,gains);request.addTarget(reader.getSurface());
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE,focus);
            CompletableFuture<TotalCaptureResult> completed=new CompletableFuture<>();
            session.capture(request.build(),new CameraCaptureSession.CaptureCallback(){public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest q,TotalCaptureResult r){completed.complete(r);}public void onCaptureFailed(CameraCaptureSession s,CaptureRequest q,CaptureFailure f){completed.completeExceptionally(new IOException("RAW capture failed"));}},handler);
            TotalCaptureResult result=await(completed);image=await(arrived);
            if (image.getTimestamp()!=result.get(CaptureResult.SENSOR_TIMESTAMP))throw new IOException("RAW/metadata timestamp mismatch");
            int[] active=LapisRootCameraNative.activeSensorFormat();int expectedCode=full?0x100c300e:0x1007301a;
            if(active==null||active[0]!=width||active[1]!=height||active[2]!=expectedCode)throw new IOException("Actual driver mode does not match the requested sensor readout");
            validatePixels(image,width,height);
            CameraCharacteristics corrected=geometry(c,width,height,full);
            // Preserve the caller's established WB. No unverified mode-specific noise/LSC calibration.
            Field results=CaptureResult.class.getDeclaredField("mResults");results.setAccessible(true);Object nativeResult=results.get(result);
            Method set=nativeResult.getClass().getMethod("set",CaptureResult.Key.class,Object.class);
            double green=(gains[1]+gains[2])/2.0;
            set.invoke(nativeResult,CaptureResult.SENSOR_NEUTRAL_COLOR_POINT,new Rational[]{rational(green/gains[0]),new Rational(1,1),rational(green/gains[3])});
            set.invoke(nativeResult,CaptureResult.SCALER_CROP_REGION,new Rect(0,0,width,height));
            set.invoke(nativeResult,CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP,null);
            set.invoke(nativeResult,CaptureResult.STATISTICS_HOT_PIXEL_MAP,null);
            set.invoke(nativeResult,CaptureResult.SENSOR_NOISE_PROFILE,null);
            File dng=new File(job,"capture.dng");
            try(FileOutputStream stream=new FileOutputStream(dng);DngCreator creator=new DngCreator(corrected,result)) {
                creator.setOrientation(orientation);
                creator.setDescription("RawLens Redmi Note 15 Pro 5G: "+(full?"50 MP sensor RAW10":"2x sensor crop; RAW14 bus, 10-bit delivery; extra precision unverified"));
                creator.writeImage(stream,image);stream.getFD().sync();
            }
            fullFrameCrop(dng,width,height);
            android.system.Os.chown(dng.getPath(),owner,owner);
            report.put("mode",full?"50mp":"2x").put("width",width).put("height",height).put("driverCode",active[2])
                .put("cfa","GBRG").put("cfaRepeat",2).put("whiteLevel",1023).put("blackLevel",64)
                .put("sourceBusBits",full?10:14).put("deliveredBits",10).put("precision14Verified",false)
                .put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY)).put("exposureNanos",result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                .put("timestampNanos",image.getTimestamp()).put("dngBytes",dng.length());
        } finally {
            if(image!=null)image.close();if(reader!=null)reader.setOnImageAvailableListener(null,null);
            if(session!=null)session.close();if(reader!=null)reader.close();if(device!=null)device.close();thread.quitSafely();
        }
    }

    private static void settings(CaptureRequest.Builder b,int iso,long exposure,float[] gains) {
        b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
        b.set(CaptureRequest.SENSOR_SENSITIVITY,iso);b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposure);
        b.set(CaptureRequest.SENSOR_FRAME_DURATION,Math.max(100000000L,exposure+1000000L));
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new android.util.Range<>(10,10));
        b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_OFF);
        b.set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
        b.set(CaptureRequest.COLOR_CORRECTION_GAINS,new RggbChannelVector(gains[0],gains[1],gains[2],gains[3]));
        b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF);
    }

    private static CameraCharacteristics geometry(CameraCharacteristics c,int width,int height,boolean full) throws Exception {
        Method copy=CameraCharacteristics.class.getDeclaredMethod("getNativeCopy");copy.setAccessible(true);Object nativeMeta=copy.invoke(c);
        Method set=nativeMeta.getClass().getMethod("set",CameraCharacteristics.Key.class,Object.class);
        set.invoke(nativeMeta,CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE,new Size(width,height));
        set.invoke(nativeMeta,CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,new Rect(0,0,width,height));
        set.invoke(nativeMeta,CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,new Rect(0,0,width,height));
        if(android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.S){  // *_MAXIMUM_RESOLUTION keys are API 31
            set.invoke(nativeMeta,CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION,new Size(width,height));
            set.invoke(nativeMeta,CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION,new Rect(0,0,width,height));
            set.invoke(nativeMeta,CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION,new Rect(0,0,width,height));
        }
        SizeF physical=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if(!full&&physical!=null)set.invoke(nativeMeta,CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,new SizeF(physical.getWidth()/2,physical.getHeight()/2));
        float[] intrinsic=c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if(intrinsic!=null) { intrinsic=intrinsic.clone();intrinsic[0]*=2;intrinsic[1]*=2;intrinsic[4]*=2;
            intrinsic[2]=full?intrinsic[2]*2:(intrinsic[2]-2040)*2+2040;
            intrinsic[3]=full?intrinsic[3]*2:(intrinsic[3]-1530)*2+1530;
            set.invoke(nativeMeta,CameraCharacteristics.LENS_INTRINSIC_CALIBRATION,intrinsic); }
        Constructor<CameraCharacteristics> ctor=CameraCharacteristics.class.getDeclaredConstructor(nativeMeta.getClass());ctor.setAccessible(true);return ctor.newInstance(nativeMeta);
    }

    private static void validatePixels(Image image,int width,int height) throws Exception {
        Image.Plane plane=image.getPlanes()[0];ByteBuffer data=plane.getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if(image.getWidth()!=width||image.getHeight()!=height||plane.getPixelStride()!=2||plane.getRowStride()<width*2)throw new IOException("Invalid RAW layout");
        int max=0;
        for(int ty=0;ty<2;ty++)for(int tx=0;tx<2;tx++) {
            int nonzero=0,total=0;
            for(int y=ty*height/2+8;y<(ty+1)*height/2-8;y+=32)for(int x=tx*width/2+8;x<(tx+1)*width/2-8;x+=32) {
                int value=data.getShort(y*plane.getRowStride()+x*2)&65535;max=Math.max(max,value);if(value!=0)nonzero++;total++;
            }
            if(nonzero<total/2)throw new IOException("RAW has an empty quadrant: refusing padded pseudo-resolution");
        }
        if(max>1023)throw new IOException("RAW values exceed this profile's verified 10-bit metadata");
        report.put("sampledMaximum",max).put("quadrantsPopulated",true);
    }
    private static Rational rational(double v){return new Rational((int)Math.round(v*1000000),1000000);}
    private static String shellQuote(String value){return "'"+value.replace("'","'\\''")+"'";}
    /** Android adds a default edge crop. Preserve every verified sample in this profile. */
    private static void fullFrameCrop(File file,int width,int height)throws Exception {
        try(RandomAccessFile io=new RandomAccessFile(file,"rw")) {
            int order=io.readUnsignedShort();boolean little=order==0x4949;
            if(!little&&order!=0x4d4d)throw new IOException("Invalid DNG byte order");
            if(tiffShort(io,little)!=42)throw new IOException("Invalid DNG TIFF");
            long offset=tiffLong(io,little);io.seek(offset);int count=tiffShort(io,little);int patched=0;
            for(int i=0;i<count;i++) {
                io.seek(offset+2+i*12L);int tag=tiffShort(io,little),type=tiffShort(io,little);long n=tiffLong(io,little),at=io.getFilePointer(),data=tiffLong(io,little);
                int[] values=tag==50829?new int[]{0,0,height,width}:tag==50719?new int[]{0,0}:tag==50720?new int[]{width,height}:null;
                if(values==null)continue;
                int size=type==3?2:type==4?4:type==5?8:0;
                if(size==0||n!=values.length)throw new IOException("Unexpected DNG crop representation");
                io.seek(n*size<=4?at:data);
                for(int v:values) {
                    if(size==2)io.writeShort(little?Short.reverseBytes((short)v):(short)v);
                    else { io.writeInt(little?Integer.reverseBytes(v):v);if(type==5)io.writeInt(little?Integer.reverseBytes(1):1); }
                }
                patched++;
            }
            if(patched!=3)throw new IOException("Missing DNG crop tags");io.getFD().sync();
        }
    }
    private static int tiffShort(RandomAccessFile io,boolean little)throws IOException{int n=io.readUnsignedShort();return little?((n&255)<<8)|(n>>>8):n;}
    private static long tiffLong(RandomAccessFile io,boolean little)throws IOException{int n=io.readInt();return (little?Integer.reverseBytes(n):n)&0xffffffffL;}
    private static <T>T await(CompletableFuture<T> value)throws Exception{return value.get(15,TimeUnit.SECONDS);}
    private static String command(String... args)throws Exception {
        java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).start();String out=new String(readAll(p.getInputStream()),java.nio.charset.StandardCharsets.UTF_8);
        if(p.waitFor()!=0)throw new IOException(args[0]+": "+out.trim());return out;
    }
    private static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
    private static String hash(File file)throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:md.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
