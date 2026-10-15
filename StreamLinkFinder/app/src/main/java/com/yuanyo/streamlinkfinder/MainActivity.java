package com.yuanyo.streamlinkfinder;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int PICK=101,SAVE=102,MAX=8*1024*1024;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<String> found=new LinkedHashSet<>();
    private final List<JSONObject> rows=new ArrayList<>();
    private static final Pattern STREAM=Pattern.compile("\\.m3u8?(?:$|[?#/&])",Pattern.CASE_INSENSITIVE);
    private LinearLayout root,browserLayout;
    private WebView ui,video;
    private TextView message;
    private boolean scanning=false;
    private String title="Vídeo";
    private byte[] exportBytes;
    private final Runnable scanLoop=new Runnable(){public void run(){if(scanning){inspect();handler.postDelayed(this,2800);}}};

    private static final String SNIFFER = """
        (function(){
          try {
            var names=[], matched={}, parts=[];
            document.querySelectorAll('video,source,a,iframe').forEach(function(n){
              parts.push(n.src||n.href||'');
            });
            try { performance.getEntriesByType('resource').forEach(function(n){parts.push(n.name)}); }catch(e){}
            try {
              document.querySelectorAll('iframe').forEach(function(frame){
                try {
                  var doc=frame.contentDocument;
                  if(doc && doc.documentElement){
                    doc.querySelectorAll('video,source,a').forEach(function(n){parts.push(n.currentSrc||n.src||n.href||'')});
                    parts.push(doc.documentElement.outerHTML.slice(0,200000));
                  }
                }catch(e){}
              });
            }catch(e){}
            var html=document.documentElement.outerHTML.slice(0,1200000);
            parts.push(html.replace(/\\\\\\//g,'/').replace(/&amp;/g,'&'));
            var re=/https?:\\/\\/[^\\s"'<>\\[\\]{}\\\\]+/ig;
            for(var i=0;i<parts.length;i++){
              var txt=parts[i], m;
              while((m=re.exec(txt))!==null && names.length<350){
                var u=m[0].replace(/[),;]+$/,'');
                if(/\\.m3u8?(?=$|[?#\\/&])/i.test(u)&&!matched[u]){matched[u]=1;names.push(u)}
              }
              re.lastIndex=0;
            }
            return JSON.stringify({title:document.title||'Vídeo',urls:names});
          }catch(e){return JSON.stringify({title:'Vídeo',urls:[]})}
        })()
        """;

    @Override public void onCreate(Bundle b){
      super.onCreate(b);
      getWindow().setStatusBarColor(Color.rgb(10,14,18));
      getWindow().setNavigationBarColor(Color.rgb(10,14,18));
      root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
      setContentView(root);
      ui=new WebView(this);
      ui.getSettings().setJavaScriptEnabled(true);
      ui.getSettings().setDomStorageEnabled(true);
      ui.getSettings().setAllowFileAccessFromFileURLs(false);
      ui.getSettings().setAllowUniversalAccessFromFileURLs(false);
      ui.addJavascriptInterface(new Bridge(),"Android");
      ui.setWebViewClient(new WebViewClient(){
        @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r){
          return !"file".equalsIgnoreCase(r.getUrl().getScheme());
        }
      });
      root.addView(ui,new LinearLayout.LayoutParams(-1,-1));
      ui.loadUrl("file:///android_asset/index.html");
      browserLayout=new LinearLayout(this);
      browserLayout.setOrientation(LinearLayout.VERTICAL);
      browserLayout.setBackgroundColor(Color.rgb(10,14,18));
      LinearLayout bar=new LinearLayout(this);
      Button back=new Button(this);back.setText("← ENLACES");back.setAllCaps(false);
      back.setOnClickListener(v->closeBrowser());
      bar.addView(back,new LinearLayout.LayoutParams(0,55*getResources().getDisplayMetrics().density>0?(int)(55*getResources().getDisplayMetrics().density):55,1));
      message=new TextView(this);message.setTextColor(Color.WHITE);
      message.setText("Reproduce el vídeo para analizar");
      message.setTextSize(12);message.setPadding(7,16,4,0);
      bar.addView(message,new LinearLayout.LayoutParams(0,-1,2));
      browserLayout.addView(bar,new LinearLayout.LayoutParams(-1,-2));
      video=new WebView(this);
      WebSettings w=video.getSettings();w.setJavaScriptEnabled(true);w.setDomStorageEnabled(true);
      w.setJavaScriptCanOpenWindowsAutomatically(false);w.setSupportMultipleWindows(false);
      w.setMediaPlaybackRequiresUserGesture(true);
      // Algunos reproductores incrustados en páginas PHP rechazan el sufijo "wv" del navegador.
      try{w.setUserAgentString(w.getUserAgentString().replace("; wv",""));}catch(Exception ignored){}
      // Las listas HLS pueden cargarse desde iframes y service workers.
      if(Build.VERSION.SDK_INT>=24)try{
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient(){
          @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest req){
            String u=req.getUrl().toString();
            if(isStream(u))handler.post(()->record(u,"Reproductor incrustado"));
            return null;
          }
        });
      }catch(Exception ignored){} 
      w.setAllowFileAccess(false);
      w.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
      if(Build.VERSION.SDK_INT>=26)w.setSafeBrowsingEnabled(true);
      video.setWebChromeClient(new WebChromeClient(){
        @Override public void onProgressChanged(WebView v,int p){if(p==100)inspect();}
      });
      video.setWebViewClient(new WebViewClient(){
        @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
          String u=r.getUrl().toString();
          if(isStream(u))handler.post(()->record(u,"Petición de red"));
          return null;
        }
        @Override public void onPageFinished(WebView v,String url){
          if(v.getTitle()!=null&&!v.getTitle().isEmpty())title=v.getTitle();
          inspect();
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
          String s=r.getUrl().getScheme();return !("http".equalsIgnoreCase(s)||"https".equalsIgnoreCase(s));
        }
      });
      browserLayout.addView(video,new LinearLayout.LayoutParams(-1,0,1));
    }
    private boolean isStream(String s){return (s.startsWith("https://")||s.startsWith("http://"))&&STREAM.matcher(s).find();}
    private void send(String js){handler.post(()->ui.evaluateJavascript(js,null));}
    private void startBrowse(String url){
      if(!(url.startsWith("http://")||url.startsWith("https://"))){send("window.onNativeError('URL no válida')");return;}
      found.clear();rows.clear();title="Vídeo";scanning=true;
      root.removeView(ui);
      if(browserLayout.getParent() instanceof ViewGroup)((ViewGroup)browserLayout.getParent()).removeView(browserLayout);
      root.addView(browserLayout,new LinearLayout.LayoutParams(-1,-1));
      message.setText("Reproduce el vídeo · buscando M3U/M3U8");
      video.loadUrl(url);handler.postDelayed(scanLoop,2800);
    }
    private void inspect(){
      if(!scanning)return;
      video.evaluateJavascript(SNIFFER,value->{
        try{
          Object raw=new JSONTokener(value).nextValue();
          JSONObject obj=new JSONObject(String.valueOf(raw));
          title=obj.optString("title","Vídeo");
          JSONArray a=obj.optJSONArray("urls");
          if(a!=null)for(int i=0;i<a.length();i++)record(a.optString(i),"Reproductor / página");
        }catch(Exception ignored){}
      });
    }
    private void record(String url,String source){
      if(!scanning||!isStream(url)||found.size()>=400||!found.add(url))return;
      try{
        JSONObject obj=new JSONObject();
        obj.put("url",url);obj.put("name",title.substring(0,Math.min(170,title.length())));
        obj.put("source",source);rows.add(obj);
        message.setText("M3U encontrados: "+found.size());
      }catch(Exception ignored){}
    }
    private void closeBrowser(){
      scanning=false;handler.removeCallbacks(scanLoop);
      video.stopLoading();video.loadUrl("about:blank");
      root.removeView(browserLayout);
      if(ui.getParent() instanceof ViewGroup)((ViewGroup)ui.getParent()).removeView(ui);
      root.addView(ui,new LinearLayout.LayoutParams(-1,-1));
      JSONArray a=new JSONArray();for(JSONObject j:rows)a.put(j);
      send("window.onNativeCandidates("+JSONObject.quote(a.toString())+")");
      send("window.onNativeMessage("+JSONObject.quote("Análisis completado: "+found.size()+" enlace(s).")+")");
    }
    @Override public void onBackPressed(){if(scanning){if(video.canGoBack())video.goBack();else closeBrowser();}else super.onBackPressed();}
    @Override protected void onDestroy(){scanning=false;handler.removeCallbacks(scanLoop);video.destroy();ui.destroy();super.onDestroy();}
    private byte[] limited(InputStream in)throws IOException{
      ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;
      while((n=in.read(b))>=0){total+=n;if(total>MAX)throw new IOException("Máximo 8 MB");out.write(b,0,n);}
      return out.toByteArray();
    }
    private void importUrl(String url){
      if(!(url.startsWith("http://")||url.startsWith("https://"))){send("window.onNativeError('URL no válida')");return;}
      new Thread(()->{
        HttpURLConnection c=null;
        try{
          c=(HttpURLConnection)new URL(url).openConnection();
          c.setConnectTimeout(12000);c.setReadTimeout(15000);
          c.setRequestProperty("User-Agent","Mozilla/5.0 StreamLinkFinder/1.0");
          if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
          String data=new String(limited(c.getInputStream()),StandardCharsets.UTF_8);
          send("window.onRemoteText("+JSONObject.quote(data)+",'Lista URL')");
        }catch(Exception e){send("window.onNativeError("+JSONObject.quote(e.getMessage())+")");}
        finally{if(c!=null)c.disconnect();}
      }).start();
    }
    @Override protected void onActivityResult(int rq,int rc,Intent data){
      super.onActivityResult(rq,rc,data);
      if(rc!=RESULT_OK||data==null||data.getData()==null)return;
      Uri uri=data.getData();
      try{
        if(rq==PICK){
          try(InputStream in=getContentResolver().openInputStream(uri)){
            send("window.onRemoteText("+JSONObject.quote(new String(limited(in),StandardCharsets.UTF_8))+",'Archivo importado')");
          }
        }else if(rq==SAVE&&exportBytes!=null){
          try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(exportBytes);out.flush();}
          send("window.onNativeMessage('Archivo guardado correctamente')");
          exportBytes=null;
        }
      }catch(Exception e){send("window.onNativeError("+JSONObject.quote(e.toString())+")");}
    }
    private final class Bridge{
      @JavascriptInterface public void browse(String url){handler.post(()->startBrowse(url));}
      @JavascriptInterface public void importRemote(String url){importUrl(url);}
      @JavascriptInterface public void importFile(){handler.post(()->{
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);
      });}
      @JavascriptInterface public String loadData(){return getPreferences(MODE_PRIVATE).getString("entries","[]");}
      @JavascriptInterface public void saveData(String text){if(text!=null&&text.length()<6000000)getPreferences(MODE_PRIVATE).edit().putString("entries",text).apply();}
      @JavascriptInterface public void copyText(String value){handler.post(()->{
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Enlace",value));
      });}
      @JavascriptInterface public void saveFile(String name,String mime,String b64){
        try{exportBytes=Base64.decode(b64,Base64.DEFAULT);handler.post(()->{
          Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType(mime);
          i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,name);
          startActivityForResult(i,SAVE);
        });}catch(Exception e){send("window.onNativeError('Exportación no disponible')");}
      }
    }
}