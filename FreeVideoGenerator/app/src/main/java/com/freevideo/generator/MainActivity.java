package com.freevideo.generator;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int PEDIR_IMAGENES = 1;
    private static final int PEDIR_MUSICA = 2;

    private static final int W = 720;
    private static final int H = 1280;
    private static final int FPS = 30;
    private static final int SEGUNDOS_POR_IMAGEN = 3;
    private static final int FRAMES_FUNDIDO = 12;

    private ArrayList<Uri> imagenes = new ArrayList<>();
    private Uri musica = null;
    private boolean generando = false;

    private EditText etTexto;
    private TextView tvEstado;
    private TextView tvMensaje;
    private ProgressBar progreso;
    private Button btnGenerar;

    private MediaCodec enc;
    private MediaMuxer mux;
    private boolean muxIniciado = false;
    private int pistaVideo = -1;
    private int pistaAudio = -1;
    private MediaFormat formatoAudio = null;
    private int[] pix = new int[W * H];

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        if (getActionBar() != null) {
            getActionBar().hide();
        }
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#0B0B1A"));
            getWindow().setNavigationBarColor(Color.parseColor("#2A0F4D"));
        }

        etTexto = findViewById(R.id.etTexto);
        tvEstado = findViewById(R.id.tvEstado);
        tvMensaje = findViewById(R.id.tvMensaje);
        progreso = findViewById(R.id.progreso);
        btnGenerar = findViewById(R.id.btnGenerar);

        Button btnImagenes = findViewById(R.id.btnImagenes);
        Button btnMusica = findViewById(R.id.btnMusica);

        btnImagenes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                elegirImagenes();
            }
        });

        btnMusica.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                elegirMusica();
            }
        });

        btnGenerar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                iniciarGeneracion();
            }
        });

        actualizarEstado();
    }

    // ---------- Selección de archivos ----------

    private void elegirImagenes() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, PEDIR_IMAGENES);
        } catch (Exception e1) {
            try {
                Intent intent2 = new Intent(Intent.ACTION_PICK,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                startActivityForResult(intent2, PEDIR_IMAGENES);
            } catch (Exception e2) {
                tvMensaje.setText("No se pudo abrir la galería: " + e2.getMessage());
            }
        }
    }

    private void elegirMusica() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            startActivityForResult(intent, PEDIR_MUSICA);
        } catch (Exception e1) {
            try {
                Intent intent2 = new Intent(Intent.ACTION_PICK,
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
                startActivityForResult(intent2, PEDIR_MUSICA);
            } catch (Exception e2) {
                tvMensaje.setText("No se pudo abrir música: " + e2.getMessage());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        try {
            if (resultCode != RESULT_OK || data == null) {
                actualizarEstado();
                return;
            }

            if (requestCode == PEDIR_IMAGENES) {
                imagenes.clear();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        Uri uri = clip.getItemAt(i).getUri();
                        if (uri != null) {
                            imagenes.add(uri);
                        }
                    }
                } else if (data.getData() != null) {
                    imagenes.add(data.getData());
                }
            } else if (requestCode == PEDIR_MUSICA) {
                musica = data.getData();
            }

            actualizarEstado();
        } catch (Exception e) {
            tvMensaje.setText("Error al elegir: " + e.getMessage());
        }
    }

    private void actualizarEstado() {
        String m = (musica != null) ? "lista" : "ninguna";
        int seg = imagenes.size() * SEGUNDOS_POR_IMAGEN;
        tvEstado.setText("Imágenes: " + imagenes.size()
                + "  |  Música: " + m
                + "  |  Duración: " + seg + " s");
    }

    // ---------- Generación ----------

    private void iniciarGeneracion() {
        if (generando) {
            return;
        }
        if (imagenes.isEmpty()) {
            tvMensaje.setText("Primero elige al menos una imagen");
            return;
        }

        generando = true;
        btnGenerar.setEnabled(false);
        progreso.setProgress(0);
        progreso.setVisibility(View.VISIBLE);
        tvMensaje.setText("Creando tu video... no cierres la app");

        final String texto = etTexto.getText().toString().trim();
        final ArrayList<Uri> lista = new ArrayList<>(imagenes);
        final Uri musicaElegida = musica;

        new Thread(new Runnable() {
            @Override
            public void run() {
                crearVideo(lista, texto, musicaElegida);
            }
        }).start();
    }

    private void mostrar(final String mensaje, final int porcentaje) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvMensaje.setText(mensaje);
                if (porcentaje >= 0) {
                    progreso.setProgress(porcentaje);
                }
            }
        });
    }

    private void terminar(final String mensaje) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                generando = false;
                btnGenerar.setEnabled(true);
                progreso.setProgress(100);
                tvMensaje.setText(mensaje);
            }
        });
    }

    private void crearVideo(ArrayList<Uri> lista, String texto, Uri musicaUri) {
        File temp = new File(getCacheDir(), "temp_video.mp4");
        MediaExtractor extractor = null;
        String aviso = "";

        enc = null;
        mux = null;
        muxIniciado = false;
        pistaVideo = -1;
        pistaAudio = -1;
        formatoAudio = null;

        try {
            if (temp.exists()) {
                temp.delete();
            }

            // --- Audio (AAC/M4A) ---
            if (musicaUri != null) {
                try {
                    extractor = new MediaExtractor();
                    extractor.setDataSource(this, musicaUri, null);

                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat f = extractor.getTrackFormat(i);
                        String mime = f.getString(MediaFormat.KEY_MIME);

                        if (mime != null && mime.startsWith("audio/")) {
                            if ("audio/mp4a-latm".equalsIgnoreCase(mime)
                                    || "audio/aac".equalsIgnoreCase(mime)
                                    || "audio/mpeg".equalsIgnoreCase(mime)
                                    || "audio/mp3".equalsIgnoreCase(mime)) {
                                extractor.selectTrack(i);
                                formatoAudio = f;
                                break;
                            }
                        }
                    }

                } catch (Exception e) {
                    formatoAudio = null;
                }

                if (formatoAudio == null) {
                    aviso = "\n(Sin música: usa un archivo M4A o AAC)";
                    if (extractor != null) {
                        try {
                            extractor.release();
                        } catch (Exception ignored) {
                        }
                    }
                    extractor = null;
                }
            }

            // --- Codificador de video ---
            MediaFormat vf = MediaFormat.createVideoFormat("video/avc", W, H);
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            vf.setInteger(MediaFormat.KEY_BIT_RATE, 4000000);
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            enc = MediaCodec.createEncoderByType("video/avc");
            enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            mux = new MediaMuxer(temp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // --- Dibujo de frames ---
            Paint pincel = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(frame);

            if (texto.length() > 140) {
                texto = texto.substring(0, 140);
            }

            StaticLayout capaTexto = null;
            if (texto.length() > 0) {
                TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(Color.WHITE);
                tp.setTextSize(52);
                tp.setFakeBoldText(true);
                tp.setShadowLayer(6, 0, 3, Color.BLACK);
                capaTexto = new StaticLayout(texto, tp, W - 120,
                        Layout.Alignment.ALIGN_CENTER, 1.1f, 0f, false);
            }

            int framesPorImagen = SEGUNDOS_POR_IMAGEN * FPS;
            int totalFrames = lista.size() * framesPorImagen;
            int n = 0;
            Bitmap previa = null;

            for (int i = 0; i < lista.size(); i++) {
                Bitmap actual = cargarImagen(lista.get(i));
                if (actual == null) {
                    continue;
                }

                for (int f = 0; f < framesPorImagen; f++) {
                    canvas.drawColor(Color.BLACK);

                    float t = framesPorImagen > 1 ? (f / (float) (framesPorImagen - 1)) : 0f;
                    dibujarCover(canvas, actual, 1f + 0.12f * t, 255, pincel);

                    if (previa != null && f < FRAMES_FUNDIDO) {
                        int alfaPrevia = 255 - (f * 255 / FRAMES_FUNDIDO);
                        dibujarCover(canvas, previa, 1.12f, alfaPrevia, pincel);
                    }

                    if (capaTexto != null) {
                        dibujarTexto(canvas, capaTexto);
                    }

                    enviarFrame(frame, n * 1000000L / FPS);
                    n++;

                    if (n % 10 == 0) {
                        int porcentaje = totalFrames > 0 ? (n * 95 / totalFrames) : 0;
                        mostrar("Creando tu video... " + (totalFrames > 0 ? (n * 100 / totalFrames) : 0) + "%",
                                porcentaje);
                    }
                }

                if (previa != null) {
                    previa.recycle();
                }
                previa = actual;
            }

            if (n == 0) {
                throw new Exception("No se pudieron leer las imágenes");
            }

            // --- Fin del video ---
            int in;
            boolean emitidoFin = false;
            while (!emitidoFin) {
                in = enc.dequeueInputBuffer(10000);
                if (in >= 0) {
                    enc.queueInputBuffer(in, 0, 0, n * 1000000L / FPS,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    emitidoFin = true;
                } else {
                    vaciar(false);
                }
            }
            vaciar(true);

            // --- Audio ---
            long duracionUs = n * 1000000L / FPS;
            if (extractor != null && pistaAudio >= 0) {
                escribirAudio(extractor, formatoAudio, duracionUs);
            }

            if (mux != null && muxIniciado) {
                mux.stop();
                muxIniciado = false;
            }

            mostrar("Guardando en la galería...", 97);
            String donde = guardarEnGaleria(temp);
            terminar("✅ ¡Video listo!\nGuardado en: " + donde + aviso);

        } catch (Throwable e) {
            String msg = e.getMessage();
            if (msg == null || msg.trim().isEmpty()) {
                msg = e.toString();
            }
            terminar("❌ Error: " + msg);
        } finally {
            try {
                if (enc != null) enc.stop();
            } catch (Exception ignored) {
            }
            try {
                if (enc != null) enc.release();
            } catch (Exception ignored) {
            }
            try {
                if (mux != null) mux.release();
            } catch (Exception ignored) {
            }
            try {
                if (extractor != null) extractor.release();
            } catch (Exception ignored) {
            }
            enc = null;
            mux = null;
            extractor = null;

            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    private void enviarFrame(Bitmap frame, long ptsUs) throws Exception {
        int in;
        while ((in = enc.dequeueInputBuffer(10000)) < 0) {
            vaciar(false);
        }

        Image img = enc.getInputImage(in);
        if (img == null) {
            return;
        }

        try {
            llenarYuv(frame, img);
            enc.queueInputBuffer(in, 0, W * H * 3 / 2, ptsUs, 0);
        } finally {
            try {
                img.close();
            } catch (Exception ignored) {
            }
        }

        vaciar(false);
    }

    private void llenarYuv(Bitmap bmp, Image img) {
        if (bmp == null || img == null) {
            return;
        }

        bmp.getPixels(pix, 0, W, 0, 0, W, H);

        Image.Plane[] planes = img.getPlanes();
        if (planes == null || planes.length < 3) {
            return;
        }

        ByteBuffer yb = planes[0].getBuffer();
        int yrs = planes[0].getRowStride();
        int yps = planes[0].getPixelStride();

        ByteBuffer ub = planes[1].getBuffer();
        int urs = planes[1].getRowStride();
        int ups = planes[1].getPixelStride();

        ByteBuffer vb = planes[2].getBuffer();
        int vrs = planes[2].getRowStride();
        int vps = planes[2].getPixelStride();

        if (yb == null || ub == null || vb == null) {
            return;
        }

        for (int y = 0; y < H; y++) {
            int fila = y * W;
            for (int x = 0; x < W; x++) {
                int c = pix[fila + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yb.put(y * yrs + x * yps, (byte) yy);

                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    ub.put((y >> 1) * urs + (x >> 1) * ups, (byte) u);
                    vb.put((y >> 1) * vrs + (x >> 1) * vps, (byte) v);
                }
            }
        }
    }

    private void vaciar(boolean esperarFin) throws Exception {
        if (enc == null || mux == null) {
            return;
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int idx = enc.dequeueOutputBuffer(info, esperarFin ? 10000 : 0);

            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!esperarFin) {
                    return;
                }
                continue;
            }

            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (pistaVideo < 0) {
                    pistaVideo = mux.addTrack(enc.getOutputFormat());
                    if (formatoAudio != null) {
                        pistaAudio = mux.addTrack(formatoAudio);
                    }
                    mux.start();
                    muxIniciado = true;
                }
                continue;
            }

            if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            }

            if (idx >= 0) {
                ByteBuffer datos = enc.getOutputBuffer(idx);
                if (datos == null) {
                    enc.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return;
                    }
                    continue;
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }

                if (info.size != 0 && muxIniciado && pistaVideo >= 0) {
                    datos.position(info.offset);
                    datos.limit(info.offset + info.size);
                    mux.writeSampleData(pistaVideo, datos, info);
                }

                enc.releaseOutputBuffer(idx, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            }
        }
    }

    private void escribirAudio(MediaExtractor ex, MediaFormat fmt, long duracionUs) throws Exception {
        if (ex == null || fmt == null || mux == null || pistaAudio < 0) {
            return;
        }

        int max = 262144;
        if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            max = Math.max(max, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
        }
        if (max <= 0) {
            max = 262144;
        }

        ByteBuffer buf = ByteBuffer.allocate(max);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        int trackIndex = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat formatTrack = ex.getTrackFormat(i);
            if (formatTrack != null && formatTrack.equals(fmt)) {
                trackIndex = i;
                ex.selectTrack(i);
                break;
            }
        }

        if (trackIndex < 0) {
            return;
        }

        while (true) {
            buf.clear();
            int tam = ex.readSampleData(buf, 0);
            if (tam < 0) {
                break;
            }

            long t = ex.getSampleTime();
            if (t > duracionUs) {
                break;
            }

            info.offset = 0;
            info.size = tam;
            info.presentationTimeUs = t;
            info.flags = ex.getSampleFlags();

            buf.position(0);
            buf.limit(tam);
            mux.writeSampleData(pistaAudio, buf, info);

            ex.advance();
        }
    }

    // ---------- Dibujo ----------

    private void dibujarCover(Canvas c, Bitmap b, float zoom, int alfa, Paint p) {
        if (b == null || b.isRecycled()) {
            return;
        }

        float escala = Math.max(W / (float) b.getWidth(), H / (float) b.getHeight()) * zoom;
        float dx = (W - b.getWidth() * escala) / 2f;
        float dy = (H - b.getHeight() * escala) / 2f;

        Matrix m = new Matrix();
        m.setScale(escala, escala);
        m.postTranslate(dx, dy);

        p.setAlpha(alfa);
        c.drawBitmap(b, m, p);
    }

    private void dibujarTexto(Canvas c, StaticLayout capa) {
        float arriba = H - 140 - capa.getHeight();
        Paint barra = new Paint();
        barra.setColor(0x99000000);

        c.drawRect(0, arriba - 30, W, arriba + capa.getHeight() + 30, barra);
        c.save();
        c.translate(60, arriba);
        capa.draw(c);
        c.restore();
    }

    private Bitmap cargarImagen(Uri uri) {
        if (uri == null) {
            return null;
        }

        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();

            int lado = Math.max(o.outWidth, o.outHeight);
            int s = 1;
            while (lado / s > 1600) {
                s *= 2;
            }

            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeStream(in, null, opts);
            in.close();

            if (b == null) {
                return null;
            }

            int grados = 0;

            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in != null) {
                        ExifInterface ex = new ExifInterface(in);
                        int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL);
                        if (ori == ExifInterface.ORIENTATION_ROTATE_90) {
                            grados = 90;
                        } else if (ori == ExifInterface.ORIENTATION_ROTATE_180) {
                            grados = 180;
                        } else if (ori == ExifInterface.ORIENTATION_ROTATE_270) {
                            grados = 270;
                        }
                        in.close();
                    }
                } catch (Exception e) {
                    grados = 0;
                }
            }

            if (grados != 0) {
                Matrix m = new Matrix();
                m.postRotate(grados);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                b.recycle();
                b = r;
            }

            return b;

        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------- Guardado ----------

    private String guardarEnGaleria(File origen) throws Exception {
        if (origen == null || !origen.exists()) {
            throw new Exception("No existe el archivo temporal del video");
        }

        String nombre = "FreeVideo_" + System.currentTimeMillis() + ".mp4";
        OutputStream out = null;
        String donde;

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, nombre);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/FreeVideoGenerator");

            Uri destino = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);

            if (destino == null) {
                throw new Exception("No se pudo crear el archivo en la galería");
            }

            out = getContentResolver().openOutputStream(destino);
            donde = "Galería > Movies/FreeVideoGenerator";
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) {
                dir = getFilesDir();
            }
            File carpeta = new File(dir, "FreeVideoGenerator");
            if (!carpeta.exists()) {
                carpeta.mkdirs();
            }

            File destinoArchivo = new File(carpeta, nombre);
            out = new FileOutputStream(destinoArchivo);
            donde = destinoArchivo.getAbsolutePath();
        }

        if (out == null) {
            throw new Exception("No se pudo abrir el flujo de salida del video");
        }

        FileInputStream in = new FileInputStream(origen);
        try {
            byte[] buffer = new byte[65536];
            int leidos;
            while ((leidos = in.read(buffer)) > 0) {
                out.write(buffer, 0, leidos);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }

        return donde;
    }
}