package com.freevideo.generator;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;

public class MainActivity extends Activity
{
    private static final int PEDIR_IMAGENES = 1;
    private static final int PEDIR_MUSICA = 2;

    private ArrayList<Uri> imagenes = new ArrayList<Uri>();
    private Uri musica = null;

    private EditText etTexto;
    private TextView tvEstado;
    private ProgressBar progreso;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        etTexto = (EditText) findViewById(R.id.etTexto);
        tvEstado = (TextView) findViewById(R.id.tvEstado);
        progreso = (ProgressBar) findViewById(R.id.progreso);

        Button btnImagenes = (Button) findViewById(R.id.btnImagenes);
        Button btnMusica = (Button) findViewById(R.id.btnMusica);
        Button btnGenerar = (Button) findViewById(R.id.btnGenerar);

        btnImagenes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvEstado.setText("Abriendo galería...");
                elegirImagenes();
            }
        });

        btnMusica.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvEstado.setText("Abriendo música...");
                elegirMusica();
            }
        });

        btnGenerar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generarVideo();
            }
        });

        actualizarEstado();
    }

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
                tvEstado.setText("No se pudo abrir la galería: " + e2.getMessage());
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
                tvEstado.setText("No se pudo abrir música: " + e2.getMessage());
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
            tvEstado.setText("Error al elegir: " + e.getMessage());
        }
    }

    private void actualizarEstado()
    {
        String m = (musica != null) ? "lista" : "ninguna";
        tvEstado.setText("Imágenes: " + imagenes.size() + " | Música: " + m);
    }

    private void generarVideo()
    {
        if (imagenes.size() == 0) {
            tvEstado.setText("Primero elige al menos una imagen");
            return;
        }

        String texto = etTexto.getText().toString().trim();
        tvEstado.setText("Listo para generar con " + imagenes.size()
            + " imagen(es)" + (texto.length() > 0 ? " y tu texto" : ""));
    }
}