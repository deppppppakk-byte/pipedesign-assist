package in.tamishra.wirelesskey;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class ConnectionManager {
    public interface Listener {
        void onState(String state, String detail);
        void onDiscovered(String name, String host, int port);
        void onPairCodeRequired();
        void onReady(String name, String host);
        void onError(String message);
    }

    public static final int PROTOCOL=5, UDP_PORT=8764, TCP_PORT=8765;
    private final Context context;
    private final Listener listener;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Object sendLock=new Object();
    private final SharedPreferences prefs;
    private ScheduledExecutorService heartbeat;
    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;
    private volatile String foundHost;
    private volatile String foundName="Windows PC";
    private volatile int foundPort=TCP_PORT;
    private volatile String pendingPairCode="";
    private volatile boolean ready;

    public ConnectionManager(Context context,Listener listener){
        this.context=context.getApplicationContext();
        this.listener=listener;
        this.prefs=this.context.getSharedPreferences("wirelesskey_v5",Context.MODE_PRIVATE);
    }

    public String lastHost(){return prefs.getString("last_host","");}
    public boolean isReady(){return ready;}

    public void discover(){
        listener.onState("discovering","Searching for WirelessKey Receiver…");
        io.execute(()->{
            WifiManager.MulticastLock lock=null;
            try(DatagramSocket ds=new DatagramSocket()){
                WifiManager wm=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if(wm!=null){
                    lock=wm.createMulticastLock("WirelessKeyDiscovery");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
                ds.setBroadcast(true);
                ds.setSoTimeout(3000);
                byte[] request="WK5_DISCOVER".getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet=new DatagramPacket(request,request.length,InetAddress.getByName("255.255.255.255"),UDP_PORT);
                ds.send(packet);
                byte[] buf=new byte[2048];
                DatagramPacket response=new DatagramPacket(buf,buf.length);
                ds.receive(response);
                JSONObject o=new JSONObject(new String(response.getData(),0,response.getLength(),StandardCharsets.UTF_8));
                if(o.optInt("proto")!=PROTOCOL)throw new IllegalStateException("Receiver protocol mismatch. Use WirelessKey Receiver v5.");
                foundHost=response.getAddress().getHostAddress();
                foundName=o.optString("name","Windows PC");
                foundPort=o.optInt("tcp",TCP_PORT);
                prefs.edit().putString("last_host",foundHost).apply();
                listener.onDiscovered(foundName,foundHost,foundPort);
            }catch(Exception e){
                String last=prefs.getString("last_host","");
                if(!last.isEmpty()){
                    foundHost=last;foundPort=TCP_PORT;
                    listener.onDiscovered("Last PC",foundHost,foundPort);
                }else listener.onError("Receiver not found. Keep phone and PC on the same Wi-Fi, allow the receiver through Windows Firewall, or enter the PC IP manually.");
            }finally{
                if(lock!=null && lock.isHeld()) lock.release();
            }
        });
    }

    public void connectManual(String host,String pairCode){
        if(host!=null && !host.trim().isEmpty()){
            foundHost=host.trim(); foundPort=TCP_PORT;
            prefs.edit().putString("last_host",foundHost).apply();
        }
        pendingPairCode=pairCode==null?"":pairCode.trim();
        connect();
    }

    public void connect(){
        final String host=foundHost;
        if(host==null || host.isEmpty()){listener.onError("Discover a PC first or enter its IP address.");return;}
        listener.onState("connecting","Connecting to "+host+"…");
        io.execute(()->connectBlocking(host,foundPort));
    }

    private void connectBlocking(String host,int port){
        closeSocketOnly();
        try{
            Socket s=new Socket();
            s.connect(new InetSocketAddress(host,port),4000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(12000);
            BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8),32768);
            BufferedWriter w=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8),32768);
            socket=s;reader=r;writer=w;

            String id=prefs.getString("device_id","");
            JSONObject hello=new JSONObject();
            hello.put("t","hello");
            hello.put("proto",PROTOCOL);
            hello.put("device",Build.MANUFACTURER+" "+Build.MODEL);
            hello.put("id",id);
            writeNow(hello);

            JSONObject first=readJson(r);
            String type=first.optString("t");
            if("challenge".equals(type)){
                String secret=prefs.getString("device_secret","");
                if(secret.isEmpty()){
                    forgetTrust();
                    throw new IllegalStateException("Trusted key missing. Pair again.");
                }
                JSONObject auth=new JSONObject();
                auth.put("t","auth");
                auth.put("proof",hmac(secret,first.getString("nonce")));
                writeNow(auth);
                JSONObject result=readJson(r);
                if("error".equals(result.optString("t"))){
                    forgetTrust();
                    throw new IllegalStateException("Saved pairing expired. Pair once again.");
                }
                if(!"ready".equals(result.optString("t")))throw new IllegalStateException("Unexpected receiver response");
            }else if("pair_required".equals(type)){
                if(pendingPairCode.length()!=6){
                    closeSocketOnly();
                    listener.onPairCodeRequired();
                    return;
                }
                JSONObject pair=new JSONObject();
                pair.put("t","pair");
                pair.put("code",pendingPairCode);
                writeNow(pair);
                JSONObject paired=readJson(r);
                if("error".equals(paired.optString("t")))throw new IllegalStateException("Pairing code rejected. Check the 6-digit code shown on the PC.");
                if(!"paired".equals(paired.optString("t")))throw new IllegalStateException("Pairing failed");
                prefs.edit().putString("device_id",paired.getString("id")).putString("device_secret",paired.getString("secret")).apply();
                JSONObject readyMsg=readJson(r);
                if(!"ready".equals(readyMsg.optString("t")))throw new IllegalStateException("Receiver did not enter ready state");
            }else if("error".equals(type)){
                throw new IllegalStateException("protocol_mismatch".equals(first.optString("code"))?"Receiver version mismatch. Install WirelessKey Receiver v5.":first.optString("code","Receiver error"));
            }else throw new IllegalStateException("Unknown receiver response");

            s.setSoTimeout(0);
            ready=true;
            listener.onReady(foundName,host);
            startHeartbeat();
            readLoop(r);
        }catch(Exception e){
            ready=false;
            closeSocketOnly();
            listener.onError(e.getMessage()==null?"Connection failed":e.getMessage());
        }
    }

    private JSONObject readJson(BufferedReader r)throws Exception{
        String line=r.readLine();
        if(line==null)throw new IllegalStateException("Receiver closed the connection");
        return new JSONObject(line);
    }

    private void readLoop(BufferedReader r){
        try{
            while(socket!=null && !socket.isClosed()){
                String line=r.readLine();
                if(line==null)break;
            }
        }catch(Exception ignored){}
        if(ready){
            ready=false;
            listener.onState("disconnected","Connection lost. Tap Connect to reconnect.");
        }
        closeSocketOnly();
    }

    private String hmac(String secretB64,String nonce)throws Exception{
        byte[] secret=Base64.decode(secretB64,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret,"HmacSHA256"));
        byte[] out=mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(out,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
    }

    private void startHeartbeat(){
        if(heartbeat!=null)heartbeat.shutdownNow();
        heartbeat=Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(()->{if(ready)send(simple("ping"));},3,5,TimeUnit.SECONDS);
    }

    private JSONObject simple(String t){
        JSONObject o=new JSONObject();
        try{o.put("t",t);}catch(Exception ignored){}
        return o;
    }

    private void writeNow(JSONObject o)throws Exception{
        synchronized(sendLock){
            if(writer==null)throw new IllegalStateException("Not connected");
            writer.write(o.toString());
            writer.write('\n');
            writer.flush();
        }
    }

    public void send(JSONObject o){
        if(!ready)return;
        try{writeNow(o);}
        catch(Exception e){
            ready=false;closeSocketOnly();
            listener.onState("disconnected","Connection interrupted.");
        }
    }

    public void sendMove(int dx,int dy){try{JSONObject o=new JSONObject();o.put("t","move");o.put("dx",dx);o.put("dy",dy);send(o);}catch(Exception ignored){}}
    public void click(String button){try{JSONObject o=new JSONObject();o.put("t","click");o.put("button",button);send(o);}catch(Exception ignored){}}
    public void scroll(int dy){try{JSONObject o=new JSONObject();o.put("t","scroll");o.put("dy",dy);send(o);}catch(Exception ignored){}}
    public void key(int vk,boolean down){try{JSONObject o=new JSONObject();o.put("t","key");o.put("vk",vk);o.put("down",down);send(o);}catch(Exception ignored){}}
    public void text(String text){try{JSONObject o=new JSONObject();o.put("t","text");o.put("text",text);send(o);}catch(Exception ignored){}}

    public void forgetTrust(){prefs.edit().remove("device_id").remove("device_secret").apply();}

    private void closeSocketOnly(){
        try{if(socket!=null)socket.close();}catch(Exception ignored){}
        socket=null;reader=null;writer=null;
    }

    public void close(){
        ready=false;
        if(heartbeat!=null)heartbeat.shutdownNow();
        io.shutdownNow();
        closeSocketOnly();
    }
}
