package com.yuanyo.streamlinkfinder;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.InputType;
import android.text.TextUtils;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView videoView;
    private EditText urlBox, refererBox;
    private TextView statusBox;
    private String lastDiagnostic="";
    private boolean ready=false;
    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(10,14,18));
        getWindow().setNavigationBarColor(Color.rgb(10,14,18));
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(12),dp(12),dp(12),dp(16));
        outer.setBackgroundColor(Color.rgb(12,17,22));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(outer,new ScrollView.LayoutParams(-1,-1));
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        Button back = button("← VOLVER");
        back.setOnClickListener(v -> finish());
        header.addView(back,new LinearLayout.LayoutParams(0,dp(55),1));
        Button retry=button("↻ REINTENTAR");
        retry.setOnClickListener(v -> play());
        header.addView(retry,new LinearLayout.LayoutParams(0,dp(55),1));
        outer.addView(header,new LinearLayout.LayoutParams(-1,dp(60)));

        TextView title=new TextView(this);
        title.setText("Reproductor HLS nativo");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setPadding(dp(3),dp(4),0,dp(10));
        outer.addView(title);

        videoView=new PlayerView(this);
        videoView.setUseController(true);
        videoView.setKeepScreenOn(true);
        videoView.setBackgroundColor(Color.BLACK);
        outer.addView(videoView,new LinearLayout.LayoutParams(-1,dp(225)));

        statusBox=new TextView(this);
        statusBox.setTextColor(Color.rgb(198,234,180));
        statusBox.setTextSize(14);
        statusBox.setText("Preparando reproductor…");
        statusBox.setPadding(dp(5),dp(14),dp(5),dp(12));
        statusBox.setTextIsSelectable(true);
        outer.addView(statusBox);

        TextView label=new TextView(this);
        label.setText("Enlace M3U8");
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        outer.addView(label);
        urlBox=new EditText(this);
        urlBox.setTextColor(Color.WHITE);
        urlBox.setTextSize(12);
        urlBox.setSingleLine(false);
        urlBox.setMinLines(2);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        urlBox.setText(getIntent().getStringExtra("stream_url"));
        outer.addView(urlBox,new LinearLayout.LayoutParams(-1,-2));

        TextView refLabel=new TextView(this);
        refLabel.setText("Referer (opcional, solo si conoces la página de origen autorizada)");
        refLabel.setTextColor(Color.LTGRAY);
        refLabel.setTextSize(12);
        outer.addView(refLabel);
        refererBox=new EditText(this);
        refererBox.setTextColor(Color.WHITE);
        refererBox.setTextSize(12);
        refererBox.setSingleLine(true);
        refererBox.setHint("https://ejemplo.com/pagina");
        outer.addView(refererBox);

        LinearLayout actions=new LinearLayout(this);
        Button playButton=button("▶ REPRODUCIR");
        playButton.setOnClickListener(v->play());
        actions.addView(playButton,new LinearLayout.LayoutParams(0,dp(54),1));
        Button copyError=button("COPIAR ERROR");
        copyError.setOnClickListener(v->{
            ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico de reproducción",lastDiagnostic));
            android.widget.Toast.makeText(this,"Diagnóstico copiado",android.widget.Toast.LENGTH_SHORT).show();
        });
        actions.addView(copyError,new LinearLayout.LayoutParams(0,dp(54),1));
        outer.addView(actions);
        TextView note=new TextView(this);
        note.setText("Solo reproduce URL con acceso válido. Un token caducado, un bloqueo del servidor o una protección DRM no se solucionan cambiando de reproductor.");
        note.setTextSize(11);
        note.setTextColor(Color.LTGRAY);
        note.setPadding(0,dp(10),0,0);
        outer.addView(note);
        play();
    }
    private Button button(String s){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);
        return b;
    }
    private int dp(int p){return (int)(getResources().getDisplayMetrics().density*p+0.5f);}
    private void report(String status){lastDiagnostic=status;statusBox.setText(status);}
    private void play(){
        String url=urlBox.getText().toString().trim();
        if(!(url.startsWith("https://")||url.startsWith("http://"))){
           report("URL inválida: debe empezar por http:// o https://");return;
        }
        Uri uri=Uri.parse(url);
        if(uri.getHost()==null){report("La URL no contiene un servidor válido.");return;}
        releasePlayer();
        Map<String,String> headers=new HashMap<>();
        String ref=refererBox.getText().toString().trim();
        if(ref.startsWith("https://")||ref.startsWith("http://"))headers.put("Referer",ref);
        String ua=WebSettings.getDefaultUserAgent(this).replace("; wv","").replace("Version/4.0 ","");
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
          .setUserAgent(ua)
          .setConnectTimeoutMs(14000)
          .setReadTimeoutMs(14000)
          .setAllowCrossProtocolRedirects(true)
          .setDefaultRequestProperties(headers);
        HlsMediaSource mediaSource=new HlsMediaSource.Factory(http)
          .createMediaSource(MediaItem.fromUri(uri));
        player=new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
               if(state==Player.STATE_BUFFERING)report("Conectando y cargando lista HLS…");
               else if(state==Player.STATE_READY)report("Vídeo cargado. Si la pantalla permanece negra, revisa el audio y los códecs.");
               else if(state==Player.STATE_ENDED)report("Reproducción finalizada.");
            }
            @Override public void onPlayerError(PlaybackException error){
                String detail=interpret(error);
                report(detail);
            }
        });
        videoView.setPlayer(player);
        report("Conectando con el servidor…");
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
    }
    private String interpret(PlaybackException e){
        Throwable t=e;Throwable httpIssue=null;
        for(int i=0;t!=null&&i<12;i++,t=t.getCause()){
           if(t instanceof HttpDataSource.InvalidResponseCodeException){
              int code=((HttpDataSource.InvalidResponseCodeException)t).responseCode;
              if(code==401||code==403)return "Servidor HTTP "+code+": acceso denegado. Es posible que el token haya caducado o que la sesión no tenga permiso.";
              if(code==404||code==410)return "Servidor HTTP "+code+": la lista ya no está disponible. Comprueba si el enlace ha caducado.";
              return "Servidor HTTP "+code+": rechazó la solicitud. No es un error del códec.";
           }
           if(t instanceof UnknownHostException)return "No se encuentra el servidor (error DNS). Puede haber cambiado o dejado de existir.";
           if(t instanceof SocketTimeoutException)return "Tiempo de espera agotado. El servidor no ha contestado.";
           if(t instanceof SSLException)return "No se pudo establecer una conexión HTTPS válida.";
           if(t instanceof java.net.ConnectException)return "Conexión rechazada o interrumpida por el servidor.";
        }
        if(e.errorCode==PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
           e.errorCode==PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
           return "La respuesta no es una lista HLS válida. El servidor podría estar enviando HTML u otra página en lugar del M3U8.";
        return "Error de reproducción ("+e.getErrorCodeName()+"). El servidor puede requerir una sesión válida, o el formato no es compatible.";
    }
    private void releasePlayer(){if(player!=null){player.release();player=null;}}
    @Override protected void onStop(){releasePlayer();super.onStop();}
    @Override protected void onStart(){super.onStart(); if(videoView!=null && player==null && ready)play();ready=true;}
    @Override protected void onDestroy(){releasePlayer();super.onDestroy();}
}