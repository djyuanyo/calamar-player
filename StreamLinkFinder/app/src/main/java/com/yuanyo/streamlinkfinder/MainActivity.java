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
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
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
    private android.widget.FrameLayout fullScreenFrame;
    private WebChromeClient.CustomViewCallback videoFullscreenCallback;
    private TextView message;
    private Button adToggle,chromeButton,modeButton,autoChromeButton;
    private boolean autoChromeFallbackEnabled=true;
    private boolean customTabOpened=false,customTabQueued=false;
    private String activeBrowserUrl="";
    private boolean chromeLikeMode=true;
    private String originalUserAgent="";
    private volatile boolean adBlockEnabled=true;
    private int adsBlocked=0;
    // Solo bloquear redes publicitarias conocidas; los CDN de vídeo no se filtran por nombre.
    private static final String[] AD_DOMAINS={
      "doubleclick.net","googlesyndication.com","googleadservices.com","googletagservices.com",
      "adnxs.com","adsrvr.org","criteo.com","pubmatic.com","rubiconproject.com",
      "openx.net","smartadserver.com","adform.net","taboola.com","outbrain.com",
      "mgid.com","revcontent.com","adskeeper.com","ezoic.net","exoclick.com",
      "exosrv.com","popads.net","popcash.net","popmyads.com","propellerads.com",
      "propellerclick.com","onclickads.net","onclkds.com","onclckads.net",
      "adsterra.com","adsterratools.com","hilltopads.net","clickadu.com",
      "clickaine.com","ad-maven.com","admaven.com","adcash.com",
      "juicyads.com","trafficjunky.net","trafficfactory.biz","a-ads.com",
      "bidvertiser.com","yllix.com","monetag.com","highperformanceformat.com",
      "highperformancedisplaycontent.com","richads.com"
    };
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

    private boolean isAdDomain(String url){
      try{
        String host=Uri.parse(url).getHost();
        if(host==null)return false;
        host=host.toLowerCase(Locale.ROOT);
        for(String domain:AD_DOMAINS)if(host.equals(domain)||host.endsWith("."+domain))return true;
      }catch(Exception ignored){}
      return false;
    }
    private boolean shouldBlock(String url){
      // Las direcciones M3U/M3U8 prevalecen incluso si proceden de un servidor de anuncios.
      return adBlockEnabled&&!isStream(url)&&isAdDomain(url);
    }
    private WebResourceResponse emptyResponse(){
      return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
    }
    private void blocked(){
      handler.post(()->{
        if(!scanning)return;
        adsBlocked++;
        refreshAdToggle();
      });
    }
    private void refreshAdToggle(){
      if(adToggle==null)return;
      adToggle.setText(adBlockEnabled?"🛡 ANUNCIOS: ON ("+adsBlocked+")":"🛡 ANUNCIOS: OFF");
    }
    private static final String ANTI_POPUP_JS = """
        (function(){
          try{
            if(window.__streamLinkAdGuard) return;
            window.__streamLinkAdGuard=true;
            // Bloquear enlaces publicitarios target=_blank; no reemplazar window.open,
            // porque algunos reproductores detectan sus funciones nativas.
            document.addEventListener('click',function(ev){
              var node=ev.target && ev.target.closest ? ev.target.closest('a[target="_blank"]') : null;
              if(node && !/\\.(m3u8?|mp4)(?:$|[?#])/i.test(node.href)){
                ev.preventDefault();ev.stopImmediatePropagation();
              }
            },true);
          }catch(e){}
        })()
        """;
    private void injectAdGuard(WebView view){
      if(adBlockEnabled && scanning && view!=null){
        view.evaluateJavascript(ANTI_POPUP_JS,null);
      }
    }
    @Override public void onCreate(Bundle b){
      super.onCreate(b);
      getWindow().setStatusBarColor(Color.rgb(10,14,18));
      getWindow().setNavigationBarColor(Color.rgb(10,14,18));
      adBlockEnabled=getPreferences(MODE_PRIVATE).getBoolean("blockAds",true);
      chromeLikeMode=getPreferences(MODE_PRIVATE).getBoolean("chromeLikeMode",true);
      autoChromeFallbackEnabled=getPreferences(MODE_PRIVATE).getBoolean("autoChromeFallback",true);
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
      bar.setOrientation(LinearLayout.VERTICAL);
      LinearLayout controls=new LinearLayout(this);
      Button back=new Button(this);back.setText("← ENLACES");back.setAllCaps(false);
      back.setOnClickListener(v->closeBrowser());
      controls.addView(back,new LinearLayout.LayoutParams(0,-2,1));
      adToggle=new Button(this);adToggle.setAllCaps(false);adToggle.setTextSize(11);
      adToggle.setOnClickListener(v->{
        adBlockEnabled=!adBlockEnabled;
        getPreferences(MODE_PRIVATE).edit().putBoolean("blockAds",adBlockEnabled).apply();
        refreshAdToggle();
        if(adBlockEnabled)injectAdGuard(video);
        Toast.makeText(this,"Bloqueo "+(adBlockEnabled?"activado":"desactivado")+". Pulsa RECARGAR para aplicar el cambio.",Toast.LENGTH_LONG).show();
      });
      controls.addView(adToggle,new LinearLayout.LayoutParams(0,-2,2));
      bar.addView(controls,new LinearLayout.LayoutParams(-1,-2));
      Button reload=new Button(this);reload.setText("⟳ RECARGAR PÁGINA");reload.setTextSize(12);
      reload.setAllCaps(false);
      reload.setOnClickListener(v->{video.reload();message.setText("Recargando reproductor...");});
      bar.addView(reload,new LinearLayout.LayoutParams(-1,-2));
      LinearLayout compatibility=new LinearLayout(this);
      modeButton=new Button(this);modeButton.setAllCaps(false);modeButton.setTextSize(11);
      modeButton.setOnClickListener(v->{
        chromeLikeMode=!chromeLikeMode;
        getPreferences(MODE_PRIVATE).edit().putBoolean("chromeLikeMode",chromeLikeMode).apply();
        applyBrowserMode();
        video.reload();
      });
      compatibility.addView(modeButton,new LinearLayout.LayoutParams(0,-2,1));
      chromeButton=new Button(this);
      chromeButton.setAllCaps(false);chromeButton.setTextSize(11);
      chromeButton.setText("🌐 CHROME REAL ↗");
      chromeButton.setOnClickListener(v->openChromeCustomTab(activeBrowserUrl,false));
      compatibility.addView(chromeButton,new LinearLayout.LayoutParams(0,-2,1));
      bar.addView(compatibility,new LinearLayout.LayoutParams(-1,-2));
      autoChromeButton=new Button(this);
      autoChromeButton.setAllCaps(false);autoChromeButton.setTextSize(11);
      autoChromeButton.setOnClickListener(v->{
        autoChromeFallbackEnabled=!autoChromeFallbackEnabled;
        getPreferences(MODE_PRIVATE).edit().putBoolean("autoChromeFallback",autoChromeFallbackEnabled).apply();
        refreshChromeFallbackToggle();
        Toast.makeText(this,autoChromeFallbackEnabled?
          "Chrome real se abrirá automáticamente si falla WebView.":
          "Fallback desactivado: usa CHROME REAL manualmente.",Toast.LENGTH_SHORT).show();
      });
      bar.addView(autoChromeButton,new LinearLayout.LayoutParams(-1,-2));
      refreshChromeFallbackToggle();
      message=new TextView(this);message.setTextColor(Color.WHITE);
      message.setText("Reproduce el vídeo para analizar");
      message.setTextSize(12);message.setPadding(14,0,8,8);
      bar.addView(message,new LinearLayout.LayoutParams(-1,-2));
      browserLayout.addView(bar,new LinearLayout.LayoutParams(-1,-2));
      refreshAdToggle();
      video=new WebView(this);
      WebSettings w=video.getSettings();w.setJavaScriptEnabled(true);w.setDomStorageEnabled(true);
      w.setJavaScriptCanOpenWindowsAutomatically(false);w.setSupportMultipleWindows(false);
      // Algunos reproductores HLS empiezan el vídeo automáticamente tras cargar el iframe.
      w.setMediaPlaybackRequiresUserGesture(false);
      // Sin cookies de terceros, algunos reproductores no inicializan la sesión de vídeo.
      android.webkit.CookieManager.getInstance().setAcceptCookie(true);
      android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(video,true);
      w.setDatabaseEnabled(true);
      // Algunas páginas de vídeo sirven un reproductor distinto si detectan un UA de WebView.
      // Usa un UA de Chrome móvil conservando la versión Chromium instalada; evita un UA falso fijo.
      originalUserAgent=w.getUserAgentString();
      applyBrowserMode();
      w.setUseWideViewPort(true);
      w.setLoadWithOverviewMode(true);
      // Las listas HLS pueden cargarse desde iframes y service workers.
      if(Build.VERSION.SDK_INT>=24)try{
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient(){
          @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest req){
            String u=req.getUrl().toString();
            if(isStream(u))handler.post(()->record(u,"Reproductor incrustado"));
            if(shouldBlock(u)){blocked();return emptyResponse();}
            return null;
          }
        });
      }catch(Exception ignored){} 
      w.setAllowFileAccess(false);
      w.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
      if(Build.VERSION.SDK_INT>=26)w.setSafeBrowsingEnabled(true);
      video.setWebChromeClient(new WebChromeClient(){
        @Override public void onProgressChanged(WebView v,int p){if(p==100){injectAdGuard(v);inspect();}}
        @Override public boolean onConsoleMessage(ConsoleMessage consoleMessage){
          if(scanning && autoChromeFallbackEnabled && consoleMessage!=null){
            String log=consoleMessage.message();
            if(log!=null){
              String lower=log.toLowerCase(Locale.ROOT);
              // Console errors from unrelated ad/tracking scripts must NOT trigger fallback.
              if(lower.contains("cors") &&
                (lower.contains(".m3u8")||lower.contains("hls")||lower.contains("video")||lower.contains("manifest"))){
                scheduleChromeFallback(activeBrowserUrl,"El reproductor indica un error CORS.");
              }
            }
          }
          return super.onConsoleMessage(consoleMessage);
        }
        @Override public void onShowCustomView(android.view.View customView,CustomViewCallback callback){
          if(fullScreenFrame!=null){callback.onCustomViewHidden();return;}
          videoFullscreenCallback=callback;
          fullScreenFrame=new android.widget.FrameLayout(MainActivity.this);
          fullScreenFrame.setBackgroundColor(Color.BLACK);
          fullScreenFrame.addView(customView,new android.widget.FrameLayout.LayoutParams(-1,-1));
          setContentView(fullScreenFrame);
          getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        @Override public void onHideCustomView(){exitVideoFullscreen();}
        @Override public void onReceivedTitle(WebView view,String text){
          if(text!=null&&!text.isEmpty()&&scanning)title=text;
        }
        @Override public boolean onCreateWindow(WebView view,boolean isDialog,boolean isUserGesture,android.os.Message resultMsg){
          if(adBlockEnabled){blocked();return false;}
          return false; // Android WebView integrado: no crear nuevas pestañas externas
        }
        @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){
          if(adBlockEnabled){blocked();result.cancel();return true;}
          return super.onJsAlert(view,url,message,result);
        }
      });
      video.setWebViewClient(new WebViewClient(){
        @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
          String u=r.getUrl().toString();
          if(isStream(u))handler.post(()->record(u,"Petición de red"));
          if(shouldBlock(u)){blocked();return emptyResponse();}
          return null;
        }
        @Override public void onPageFinished(WebView v,String url){
          if(v.getTitle()!=null&&!v.getTitle().isEmpty())title=v.getTitle();
          injectAdGuard(v);
          inspect();
        }
        @Override public void onReceivedError(WebView v,WebResourceRequest req,WebResourceError error){
          if(!req.isForMainFrame() && scanning && autoChromeFallbackEnabled){
            String subUrl=req.getUrl()==null?"":req.getUrl().toString();
            if(isStream(subUrl) || subUrl.matches("(?i).*[/]api[/]player[/].*")){
              scheduleChromeFallback(activeBrowserUrl,"Error de carga en el reproductor incrustado.");
            }
          }
          if(req.isForMainFrame()&&scanning){
            String detail=error==null?"Error desconocido":String.valueOf(error.getDescription());
            handler.post(()->{
              if(!scanning)return;
              message.setText("Conexión WebView fallida ("+detail+"). "+
                (autoChromeFallbackEnabled?"Abriendo Chrome real…":"Pulsa CHROME REAL."));
              if(autoChromeFallbackEnabled){
                String failed=req.getUrl()==null?activeBrowserUrl:req.getUrl().toString();
                scheduleChromeFallback(failed,"Fallo de conexión en WebView: "+detail);
              }
            });
          }
        }
        @Override public void onReceivedHttpError(WebView v,WebResourceRequest req,WebResourceResponse resp){
          if(req.isForMainFrame()&&scanning){
            handler.post(()->{
              if(!scanning)return;
              int code=resp.getStatusCode();
              message.setText("WebView recibió HTTP "+code+".");
              if(autoChromeFallbackEnabled && (code==401||code==403||code==429||code>=500)){
                scheduleChromeFallback(activeBrowserUrl,"HTTP "+code+" en WebView.");
              }
            });
          }
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
          String s=r.getUrl().getScheme();
          if(!("http".equalsIgnoreCase(s)||"https".equalsIgnoreCase(s)))return true;
          String url=r.getUrl().toString();
          if(shouldBlock(url)){blocked();return true;}
          // Permitir redirecciones HTTPS legítimas del reproductor a dominios externos.
          // Los dominios publicitarios siguen filtrados por shouldBlock() y
          // las ventanas nuevas siguen desactivadas mediante WebChromeClient.
          return false;
        }
      });
      browserLayout.addView(video,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void applyBrowserMode(){
      if(video==null)return;
      String ua=originalUserAgent;
      if(ua==null||ua.isEmpty())ua=video.getSettings().getUserAgentString();
      if(chromeLikeMode){
        ua=ua.replace("; wv","").replace("Version/4.0 ","").replace(" Version/4.0","");
      }
      video.getSettings().setUserAgentString(ua);
      if(modeButton!=null)modeButton.setText(chromeLikeMode?"WEBVIEW: UA CHROME":"WEBVIEW: ORIGINAL");
    }

    private void playDirect(String streamUrl){
      if(streamUrl==null)return;
      Uri target=Uri.parse(streamUrl.trim());
      String scheme=target.getScheme();
      if(!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))){
        Toast.makeText(this,"La URL debe comenzar con http o https",Toast.LENGTH_LONG).show();
        return;
      }
      Intent player=new Intent(this,PlayerActivity.class);
      player.putExtra("stream_url",streamUrl.trim());
      startActivity(player);
    }

    private void refreshChromeFallbackToggle(){
      if(autoChromeButton!=null)autoChromeButton.setText(
        autoChromeFallbackEnabled?"⚡ FALLBACK AUTOMÁTICO A CHROME: ON":
        "⚡ FALLBACK AUTOMÁTICO A CHROME: OFF");
    }
    private void scheduleChromeFallback(String url,String reason){
      if(!autoChromeFallbackEnabled||!scanning||customTabOpened||customTabQueued)return;
      customTabQueued=true;
      handler.postDelayed(()->{
        customTabQueued=false;
        if(autoChromeFallbackEnabled && scanning && !customTabOpened){
          openChromeCustomTab(url,true);
        }
      },650);
    }
    private void openChromeCustomTab(String url,boolean automatic){
      if(url==null||!(url.startsWith("https://")||url.startsWith("http://"))){
        url=activeBrowserUrl;
      }
      if(url==null||!(url.startsWith("https://")||url.startsWith("http://"))){
        Toast.makeText(this,"URL de página inválida.",Toast.LENGTH_LONG).show();return;
      }
      if(customTabOpened && automatic)return;
      Uri target=Uri.parse(url);
      try{
        CustomTabColorSchemeParams colors = new CustomTabColorSchemeParams.Builder()
          .setToolbarColor(Color.rgb(12,17,22))
          .build();
        CustomTabsIntent tab=new CustomTabsIntent.Builder()
          .setShowTitle(true)
          .setDefaultColorSchemeParams(colors)
          .build();
        // Chrome real shares the browser's own cookies/session and CORS policies.
        tab.intent.setPackage("com.android.chrome");
        try{
          tab.launchUrl(this,target);
        }catch(android.content.ActivityNotFoundException ex){
          // Use another compatible installed Custom Tabs provider if Chrome is absent.
          tab.intent.setPackage(null);
          tab.launchUrl(this,target);
        }
        customTabOpened=true;scanning=false;
        handler.removeCallbacks(scanLoop);
        message.setText("Chrome real abierto. En esa pestaña no se pueden capturar "+
          "las peticiones M3U8; vuelve a la app para analizar otra página.");
        if(automatic)Toast.makeText(this,"WebView falló. Abriendo Chrome real.",Toast.LENGTH_LONG).show();
      }catch(Exception ex){
        if(!automatic)Toast.makeText(this,"No se pudo abrir Chrome: "+ex.getMessage(),Toast.LENGTH_LONG).show();
        message.setText("Error al abrir navegador externo. Puedes probar otro navegador.");
      }
    }
    private boolean isStream(String s){return (s.startsWith("https://")||s.startsWith("http://"))&&STREAM.matcher(s).find();}
    private void send(String js){handler.post(()->ui.evaluateJavascript(js,null));}
    private void startBrowse(String url){
      if(!(url.startsWith("http://")||url.startsWith("https://"))){send("window.onNativeError('URL no válida')");return;}
      found.clear();rows.clear();title="Vídeo";adsBlocked=0;
      activeBrowserUrl=url;customTabOpened=false;customTabQueued=false;
      scanning=true;refreshAdToggle();
      root.removeView(ui);
      if(browserLayout.getParent() instanceof ViewGroup)((ViewGroup)browserLayout.getParent()).removeView(browserLayout);
      root.addView(browserLayout,new LinearLayout.LayoutParams(-1,-1));
      message.setText("Analizador WebView · buscando M3U/M3U8. Si falla, se abre Chrome real automáticamente.");
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
    private void exitVideoFullscreen(){
      if(fullScreenFrame==null)return;
      android.widget.FrameLayout old=fullScreenFrame;
      fullScreenFrame=null;
      old.removeAllViews();
      setContentView(root);
      getWindow().getDecorView().setSystemUiVisibility(0);
      if(videoFullscreenCallback!=null){
        WebChromeClient.CustomViewCallback callback=videoFullscreenCallback;
        videoFullscreenCallback=null;
        callback.onCustomViewHidden();
      }
    }
    private void closeBrowser(){
      if(fullScreenFrame!=null)exitVideoFullscreen();
      scanning=false;customTabQueued=false;handler.removeCallbacks(scanLoop);
      video.stopLoading();video.loadUrl("about:blank");
      root.removeView(browserLayout);
      if(ui.getParent() instanceof ViewGroup)((ViewGroup)ui.getParent()).removeView(ui);
      root.addView(ui,new LinearLayout.LayoutParams(-1,-1));
      JSONArray a=new JSONArray();for(JSONObject j:rows)a.put(j);
      send("window.onNativeCandidates("+JSONObject.quote(a.toString())+")");
      send("window.onNativeMessage("+JSONObject.quote("Análisis completado: "+found.size()+" enlace(s).")+")");
    }
    @Override public void onBackPressed(){
      if(fullScreenFrame!=null){exitVideoFullscreen();return;}
      if(scanning){if(video.canGoBack())video.goBack();else closeBrowser();}
      else super.onBackPressed();
    }
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
      @JavascriptInterface public void playDirect(String url){handler.post(()->playDirect(url));}
      @JavascriptInterface public void openChromeTab(String url){handler.post(()->openChromeCustomTab(url,false));}
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