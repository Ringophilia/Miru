// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.releaseprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** No application classes, reflection, debug API or test keep-rules are used. */
public final class ReleaseRuntimeProbe extends Instrumentation {
    private final JSONObject report = new JSONObject();
    private final AtomicReference<Activity> activity = new AtomicReference<>();
    private int passed;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        try {
            ApplicationInfo target = getTargetContext().getApplicationInfo();
            check((target.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0, "Target must be a non-debuggable release APK");
            report.put("targetPackage", target.packageName).put("debuggable", false).put("sourceDir", target.sourceDir);
            byte[] movie;
            try (InputStream input = getContext().getAssets().open("colors.mp4")) { movie = readAll(input); }
            String hash = hex(MessageDigest.getInstance("SHA-256").digest(movie));
            check(hash.equals("8259466bc2ec96cfd7d3866f9ae30d325192eea48e4663beed6b9b839f559357"), "Owned movie digest differs");
            report.put("movieSha256", hash);
            try (Origin origin = new Origin(movie)) {
                // Use ordinary exported PlayerActivity and a normal external VIEW request.
                launchPlayer(origin.url);
                seekOwnedRedThroughPublicControls();
                JSONArray first = awaitRedFrame();
                check(origin.success.get() > 0, "The first playback must consume the actual HTTP source");
                finishActivity();
                int before = origin.total.get();
                origin.available.set(false);
                launchPlayer(origin.url);
                seekOwnedRedThroughPublicControls();
                JSONArray second = awaitRedFrame();
                check(origin.total.get() == before, "Cache-only re-open made an upstream request");
                report.put("firstRgb", first).put("cachedRgb", second).put("upstreamRequests", before);
                finishActivity();
                pass("releaseVideoDecodesAndColdReopenUsesTheDiskCache");
            // Exercise a real Gecko-rendered document through the ordinary browser entry.
            origin.available.set(true);
            Intent browser = new Intent(Intent.ACTION_VIEW).setData(Uri.parse(origin.url + "/page")).setComponent(new ComponentName("app.miru.browser", "app.miru.browser.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.set(startActivitySync(browser));
            long deadline = SystemClock.elapsedRealtime() + 20000;
            boolean visible = false;
            while(SystemClock.elapsedRealtime() < deadline && !visible) {
                AtomicBoolean drawn = new AtomicBoolean();
                runOnMainSync(() -> { Activity a = activity.get(); View v = a.getWindow().getDecorView(); drawn.set(v.isShown() && v.hasWindowFocus() && v.getWidth() > 0 && v.getHeight() > 0); });
                visible = drawn.get() && containsLabel(getUiAutomation().getRootInActiveWindow(), "Miru R8 runtime content", 0);
                if(!visible) SystemClock.sleep(50);
            }
            check(visible && origin.pages.get() > 0, "Browser entry did not expose the actual served document heading");
            report.put("renderedHeading", "Miru R8 runtime content").put("pageRequests", origin.pages.get());
            finishActivity();
            pass("releaseBrowserEntryRendersTheServedDocument");
            }
            report.put("status", "PASSED").put("passed", passed).put("scope", "x86_64 release black-box entry/actual decoder/cache re-open only; not full R8, ARM64 or physical performance acceptance");
            writeReport();
            Bundle result = new Bundle(); result.putString("report",report.toString());result.putString(REPORT_KEY_STREAMRESULT, "\nOK (" + passed + " tests)\n"); finish(Activity.RESULT_OK, result);
        } catch(Throwable failure) {
            try { report.put("status", "FAILED").put("failure", failure.toString()); writeReport(); } catch(Exception ignored) { }
            Bundle status = new Bundle(); status.putString("stack", android.util.Log.getStackTraceString(failure)); sendStatus(-2, status);
            Bundle result = new Bundle(); result.putString("report",report.toString());result.putString(REPORT_KEY_STREAMRESULT, "\nFAILURES!!!\n" + failure); finish(Activity.RESULT_CANCELED, result);
        } finally { finishActivity(); }
    }

    private void launchPlayer(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/mp4")
                .setComponent(new ComponentName("app.miru.browser", "app.miru.player.PlayerActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.set(startActivitySync(intent));
    }

    private JSONArray awaitRedFrame() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 25000;
        JSONArray last = new JSONArray();
        while(SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<SurfaceView> output = new AtomicReference<>();
            runOnMainSync(() -> output.set(surface(activity.get().getWindow().getDecorView())));
            SurfaceView view = output.get();
            if(view == null || !view.getHolder().getSurface().isValid()) { SystemClock.sleep(50); continue; }
            Bitmap bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
            try {
                CountDownLatch done = new CountDownLatch(1); AtomicInteger status = new AtomicInteger(-1);
                runOnMainSync(() -> PixelCopy.request(view, bitmap, code -> { status.set(code); done.countDown(); }, new Handler(Looper.getMainLooper())));
                check(done.await(5, TimeUnit.SECONDS), "PixelCopy callback timed out");
                if(status.get() == PixelCopy.SUCCESS) {
                    long r=0,g=0,b=0;
                    for(int y=15;y<=35;y++) for(int x=55;x<=95;x++) { int color=bitmap.getPixel(x,y);r+=Color.red(color);g+=Color.green(color);b+=Color.blue(color); }
                    double count=21*41;last=new JSONArray().put(r/count).put(g/count).put(b/count);
                    if(Math.abs(r/count-255)<=45 && g/count<=45 && b/count<=45) {
                        ByteArrayOutputStream png=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,png);
                        JSONArray frames=report.optJSONArray("frames");if(frames==null){frames=new JSONArray();report.put("frames",frames);}
                        frames.put(new JSONObject().put("name",frames.length()==0?"first-red.png":"cached-red.png")
                                .put("sha256",hex(MessageDigest.getInstance("SHA-256").digest(png.toByteArray())))
                                .put("pngBase64",android.util.Base64.encodeToString(png.toByteArray(),android.util.Base64.NO_WRAP)));
                        return last;
                    }
                }
            } finally { bitmap.recycle(); }
            SystemClock.sleep(40);
        }
        throw new AssertionError("Release decoder did not present owned red frame; last RGB=" + last);
    }

    private void seekOwnedRedThroughPublicControls() throws Exception {
        long deadline=SystemClock.elapsedRealtime()+15000,lastTap=0;
        while(SystemClock.elapsedRealtime()<deadline) {
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo pause=control(root,"Pause","暂停",0);
            if(pause!=null) { pause.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(100);root=getUiAutomation().getRootInActiveWindow(); }
            AccessibilityNodeInfo slider=seekControl(root,0);
            if(slider!=null) {
                Bundle arguments=new Bundle();arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,1000f);
                check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),arguments),"Public progress action was rejected");
                report.put("requestedSeekMs",1000).put("seekRoute","public accessibility progress action");
                return;
            }
            if(SystemClock.elapsedRealtime()-lastTap>=1000) {
                AtomicReference<float[]> point=new AtomicReference<>();
                runOnMainSync(() -> { SurfaceView view=surface(activity.get().getWindow().getDecorView());if(view!=null){int[] location=new int[2];view.getLocationOnScreen(location);point.set(new float[]{location[0]+view.getWidth()/2f,location[1]+view.getHeight()/2f});} });
                if(point.get()!=null) {
                    long time=SystemClock.uptimeMillis();
                    for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
                        MotionEvent event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,point.get()[0],point.get()[1],0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                        try { check(getUiAutomation().injectInputEvent(event,true),"Public control tap was rejected"); } finally { event.recycle(); }
                    }
                    lastTap=SystemClock.elapsedRealtime();
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("The public twelve-second playback progress control did not become available");
    }
    private static AccessibilityNodeInfo seekControl(AccessibilityNodeInfo node,int depth) {
        if(node==null||depth>50)return null;
        AccessibilityNodeInfo.RangeInfo range=node.getRangeInfo();
        if(node.isVisibleToUser()&&range!=null&&range.getMin()==0f&&range.getMax()==12000f)return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=seekControl(node.getChild(i),depth+1);if(found!=null)return found;}
        return null;
    }
    private static AccessibilityNodeInfo control(AccessibilityNodeInfo node,String english,String chinese,int depth) {
        if(node==null||depth>50)return null;
        CharSequence description=node.getContentDescription();
        if(node.isVisibleToUser()&&description!=null&&(english.contentEquals(description)||chinese.contentEquals(description)))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=control(node.getChild(i),english,chinese,depth+1);if(found!=null)return found;}
        return null;
    }

    private static SurfaceView surface(View view) {
        if(view instanceof SurfaceView) return (SurfaceView)view;
        if(view instanceof ViewGroup) { ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++) { SurfaceView found=surface(parent.getChildAt(i));if(found!=null)return found; } }
        return null;
    }
    private void finishActivity() {
        Activity old=activity.getAndSet(null);
        if(old!=null) {
            runOnMainSync(old::finish);
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(!old.isDestroyed() && SystemClock.elapsedRealtime()<deadline) { waitForIdleSync();SystemClock.sleep(20); }
            check(old.isDestroyed(), "Previous activity did not finish destruction before cold re-open");
        }
    }
    private static boolean containsLabel(AccessibilityNodeInfo node,String label,int depth) {
        if(node==null||depth>50)return false;
        if(label.contentEquals(node.getText()==null?"":node.getText()))return true;
        for(int i=0;i<node.getChildCount();i++)if(containsLabel(node.getChild(i),label,depth+1))return true;
        return false;
    }
    private void pass(String name) { passed++;Bundle state=new Bundle();state.putString("class",getClass().getName());state.putString("test",name);state.putInt("current",passed);state.putInt("numtests",2);sendStatus(0,state); }
    private void writeReport() throws Exception { try(OutputStream output=new java.io.FileOutputStream(new File(getTargetContext().getFilesDir(),"release-runtime-probe.json"))) { output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); } }
    private static byte[] readAll(InputStream input) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];for(int n;(n=input.read(block))!=-1;)out.write(block,0,n);return out.toByteArray(); }
    private static String hex(byte[] data) { StringBuilder out=new StringBuilder();for(byte b:data)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString(); }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }

    private static final class Origin implements AutoCloseable {
        final AtomicBoolean available=new AtomicBoolean(true),running=new AtomicBoolean(true);
        final AtomicInteger total=new AtomicInteger(),success=new AtomicInteger(),pages=new AtomicInteger();
        final ServerSocket server;final String url;final byte[] body;
        Origin(byte[] body) throws Exception {
            this.body=body;server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));url="http://127.0.0.1:"+server.getLocalPort()+"/owned.mp4";
            Thread listener=new Thread(() -> { while(running.get()) { try { Socket socket=server.accept();Thread worker=new Thread(() -> serve(socket));worker.setDaemon(true);worker.start(); } catch(Exception error) { if(running.get())throw new RuntimeException(error); } } });listener.setDaemon(true);listener.start();
        }
        void serve(Socket socket) { try(Socket request=socket) {
            request.setSoTimeout(10000);java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(request.getInputStream(),StandardCharsets.US_ASCII));
            String first=input.readLine();if(first==null)return;long start=0,end=body.length-1;
            for(String line;(line=input.readLine())!=null&&!line.isEmpty();) if(line.toLowerCase(java.util.Locale.ROOT).startsWith("range:")) {
                String[] range=line.substring(line.indexOf('=')+1).split("-",-1);start=Long.parseLong(range[0]);if(!range[1].isEmpty())end=Math.min(end,Long.parseLong(range[1]));
            }
            OutputStream out=request.getOutputStream();
            if(first.split(" ")[1].startsWith("/owned.mp4/page")) {
                byte[] html="<!doctype html><html><head><title>Runtime probe</title></head><body><h1>Miru R8 runtime content</h1></body></html>".getBytes(StandardCharsets.UTF_8);
                pages.incrementAndGet();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+html.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(html);out.flush();return;
            }
            total.incrementAndGet();
            if(!available.get()) { out.write("HTTP/1.1 503 Stopped\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();return; }
            check(start>=0&&start<=end&&end<body.length,"Unexpected range");success.incrementAndGet();
            String header="HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: "+(end-start+1)+"\r\nContent-Range: bytes "+start+"-"+end+"/"+body.length+"\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));if(!first.startsWith("HEAD "))out.write(body,(int)start,(int)(end-start+1));out.flush();
        } catch(Exception ignored) { } }
        @Override public void close() throws Exception { running.set(false);server.close(); }
    }
}
