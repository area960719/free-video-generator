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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class MainActivity extends Activity
{
    private static final int PEDIR_IMAGENES = 1;
    private static final int PEDIR_MUSICA = 2;

    private static final int W = 720;
    private static final int H = 1280;
    private static final int FPS = 30;
    private static final int SEGUNDOS_POR_IMAGEN = 3;
    private static final int FRAMES_FUNDIDO = 12;

    private ArrayList<Uri> imagenes = new ArrayList<Uri>();
    private Uri musica = null;
    private boolean generando = false;

    private EditText etTexto;
    private TextView tvEstado;
    private TextView tvMensaje;
    private ProgressBar progreso;
    private Button btnGenerar;

    // Estado del codificador
    private MediaCodec enc;
    private MediaMuxer mux;
    private boolean muxIniciado = false;
    private int pistaVideo = -1;
    private int pistaAudio = -1;
    private MediaFormat formatoAudio = null;
    private int[] pix = new int[W * H];

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        if (getActionBar() != null) {
            getActionBar().hide();
        }
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#0B0B1A"));
            getWindow().setNavigationBarColor(Color.parseColor("#2A0F4D"));
        }

        etTexto = (EditText) findViewById(R.id.etTexto);
        tvEstado = (TextView) findViewById(R.id.tvEstado);
        tvMensaje = (TextView) findViewById(R.id.tvMensaje);
        progreso = (ProgressBar) findViewById(R.id.progreso);
        btnGenerar = (Button) findViewById(R.id.btnGenerar);

        Button btnImagenes = (Button) findViewById(R.id.btnImagenes);
        Button btnMusica = (Button) findViewById(R.id.btnMusica);

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

    private void elegirImagenes()
    {
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

    private void elegirMusica()
    {
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
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
                        imagenes.add(clip.getItemAt(i).getUri());
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

    private void actualizarEstado()
    {
        String m = (musica != null) ? "lista" : "ninguna";
        int seg = imagenes.size() * SEGUNDOS_POR_IMAGEN;
        tvEstado.setText("Imágenes: " + imagenes.size()
            + "  |  Música: " + m
            + "  |  Duración: " + seg + " s");
    }

    // ---------- Generación ----------

    private void iniciarGeneracion()
    {
        if (generando) {
            return;
        }
        if (imagenes.size() == 0) {
            tvMensaje.setText("Primero elige al menos una imagen");
            return;
        }

        generando = true;
        btnGenerar.setEnabled(false);
        progreso.setProgress(0);
        progreso.setVisibility(View.VISIBLE);
        tvMensaje.setText("Creando tu video... no cierres la app");

        final String texto = etTexto.getText().toString().trim();
        final ArrayList<Uri> lista = new ArrayList<Uri>(imagenes);
        final Uri musicaElegida = musica;

        new Thread(new Runnable() {
            @Override
            public void run() {
                crearVideo(lista, texto, musicaElegida);
            }
        }).start();
    }

    private void mostrar(final String mensaje, final int porcentaje)
    {
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

    private void terminar(final String mensaje)
    {
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

    private void crearVideo(ArrayList<Uri> lista, String texto, Uri musicaUri)
    {
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

            // --- Audio (solo AAC/M4A) ---
            if (musicaUri != null) {
                try {
                    extractor = new MediaExtractor();
                    extractor.setDataSource(this, musicaUri, null);
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat f = extractor.getTrackFormat(i);
                        String mime = f.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) {
                            if (mime.equals("audio/mp4a-latm")) {
                                extractor.selectTrack(i);
                                formatoAudio = f;
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    formatoAudio = null;
                }
                if (formatoAudio == null) {
                    aviso = "\n(Sin música: usa un archivo M4A o AAC)";
                    if (extractor != null) {
                        try { extractor.release(); } catch (Exception e) { }
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

                    float t = f / (float) (framesPorImagen - 1);
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
                        mostrar("Creando tu video... " + (n * 100 / totalFrames) + "%",
                            n * 95 / totalFrames);
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
            while ((in = enc.dequeueInputBuffer(10000)) < 0) {
                vaciar(false);
            }
            enc.queueInputBuffer(in, 0, 0, n * 1000000L / FPS,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            vaciar(true);

            // --- Audio ---
            long duracionUs = n * 1000000L / FPS;
            if (extractor != null && pistaAudio >= 0) {
                escribirAudio(extractor, formatoAudio, duracionUs);
            }

            mux.stop();
            muxIniciado = false;

            mostrar("Guardando en la galería...", 97);
            String donde = guardarEnGaleria(temp);
            terminar("✅ ¡Video listo!\nGuardado en: " + donde + aviso);

        } catch (Throwable e) {
            terminar("❌ Error: " + e.getMessage());
        } finally {
            try { if (enc != null) { enc.stop(); } } catch (Exception e) { }
            try { if (enc != null) { enc.release(); } } catch (Exception e) { }
            try { if (mux != null) { mux.release(); } } catch (Exception e) { }
            try { if (extractor != null) { extractor.release(); } } catch (Exception e) { }
            enc = null;
            mux = null;
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    private void enviarFrame(Bitmap frame, long ptsUs) throws Exception
    {
        int in;
        while ((in = enc.dequeueInputBuffer(10000)) < 0) {
            vaciar(false);
        }
        Image img = enc.getInputImage(in);
        llenarYuv(frame, img);
        enc.queueInputBuffer(in, 0, W * H * 3 / 2, ptsUs, 0);
        vaciar(false);
    }

    private void llenarYuv(Bitmap bmp, Image img)
    {
        bmp.getPixels(pix, 0, W, 0, 0, W, H);

        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        int yrs = p[0].getRowStride();
        int yps = p[0].getPixelStride();
        ByteBuffer ub = p[1].getBuffer();
        int urs = p[1].getRowStride();
        int ups = p[1].getPixelStride();
        ByteBuffer vb = p[2].getBuffer();
        int vrs = p[2].getRowStride();
        int vps = p[2].getPixelStride();

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

    private void vaciar(boolean esperarFin) throws Exception
    {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, esperarFin ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!esperarFin) {
                    return;
                }
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                pistaVideo = mux.addTrack(enc.getOutputFormat());
                if (formatoAudio != null) {
                    pistaAudio = mux.addTrack(formatoAudio);
                }
                mux.start();
                muxIniciado = true;
            } else if (idx >= 0) {
                ByteBuffer datos = enc.getOutputBuffer(idx);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size != 0 && muxIniciado) {
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

    private void escribirAudio(MediaExtractor ex, MediaFormat fmt, long duracionUs)
        throws Exception
    {
        int max = 262144;
        if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            max = Math.max(max, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
        }
        ByteBuffer buf = ByteBuffer.allocate(max);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

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
            mux.writeSampleData(pistaAudio, buf, info);
            ex.advance();
        }
    }

    // ---------- Dibujo ----------

    private void dibujarCover(Canvas c, Bitmap b, float zoom, int alfa, Paint p)
    {
        float escala = Math.max(W / (float) b.getWidth(), H / (float) b.getHeight()) * zoom;
        float dx = (W - b.getWidth() * escala) / 2f;
        float dy = (H - b.getHeight() * escala) / 2f;
        Matrix m = new Matrix();
        m.setScale(escala, escala);
        m.postTranslate(dx, dy);
        p.setAlpha(alfa);
        c.drawBitmap(b, m, p);
    }

    private void dibujarTexto(Canvas c, StaticLayout capa)
    {
        float arriba = H - 140 - capa.getHeight();
        Paint barra = new Paint();
        barra.setColor(0x99000000);
        c.drawRect(0, arriba - 30, W, arriba + capa.getHeight() + 30, barra);
        c.save();
        c.translate(60, arriba);
        capa.draw(c);
        c.restore();
    }

    private Bitmap cargarImagen(Uri uri)
    {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();

            int lado = Math.max(o.outWidth, o.outHeight);
            int s = 1;
            while (lado / s > 1600) {
                s *= 2;
            }

            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            in = getContentResolver().openInputStream(uri);
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (b == null) {
                return null;
            }

            int grados = 0;
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    in = getContentResolver().openInputStream(uri);
                    ExifInterface ex = new ExifInterface(in);
                    int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                    in.close();
                    if (ori == ExifInterface.ORIENTATION_ROTATE_90) {
                        grados = 90;
                    } else if (ori == ExifInterface.ORIENTATION_ROTATE_180) {
                        grados = 180;
                    } else if (ori == ExifInterface.ORIENTATION_ROTATE_270) {
                        grados = 270;
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
        }
    }

    // ---------- Guardado ----------

    private String guardarEnGaleria(File origen) throws Exception
    {
        String nombre = "FreeVideo_" + System.currentTimeMillis() + ".mp4";
        OutputStream out;
        String donde;

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, nombre);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/FreeVideoGenerator");
            Uri destino = getContentResolver().insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            out = getContentResolver().openOutputStream(destino);
            donde = "Galería > Movies/FreeVideoGenerator";
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            File destinoArchivo = new File(dir, nombre);
            out = new java.io.FileOutputStream(destinoArchivo);
            donde = destinoArchivo.getAbsolutePath();
        }

        FileInputStream in = new FileInputStream(origen);
        byte[] buffer = new byte[65536];
        int leidos;
        while ((leidos = in.read(buffer)) > 0) {
            out.write(buffer, 0, leidos);
        }
        in.close();
        out.close();
        return donde;
    }
}