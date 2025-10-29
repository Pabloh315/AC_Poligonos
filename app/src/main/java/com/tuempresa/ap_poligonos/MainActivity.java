package com.tuempresa.ap_poligonos;

import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap myMap;
    private Spinner spProvincias;
    private Button btnAgregar, btnGenerar, btnLimpiar;

    private Geocoder geocoder;

    // Provincias -> capital
    private final Map<String, String> provinciaCapital = new HashMap<>();

    // Capital -> fallback LatLng (por si Geocoder falla)
    private final Map<String, LatLng> capitalFallback = new HashMap<>();

    // Provincias ya agregadas (evita duplicados lógicos)
    private final Set<String> agregadas = new HashSet<>();

    // Puntos seleccionados (para polígono)
    private final List<LatLng> puntosSeleccionados = new ArrayList<>();

    // Polígono actual (para poder removerlo)
    @Nullable
    private Polygon poligonoActual = null;

    // Provincia -> Marker (para limpiar)
    private final Map<String, Marker> marcadorPorProvincia = new HashMap<>();

    // Todas las provincias (15)
    private static final String[] PROVINCIAS_SCZ = new String[]{
            "Andrés Ibáñez",
            "Warnes",
            "Ichilo",
            "Sara",
            "Obispo Santistevan",
            "Ñuflo de Chávez",
            "Velasco",
            "Ángel Sandoval",
            "Germán Busch",
            "Chiquitos",
            "Guarayos",
            "Cordillera",
            "Florida",
            "Vallegrande",
            "Manuel María Caballero"
    };

    // Lista mutable para el Spinner (se irán removiendo aquí)
    private final List<String> provinciasDisponibles = new ArrayList<>();

    private ArrayAdapter<String> spinnerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geocoder = new Geocoder(this, Locale.getDefault());
        cargarMapeosProvinciaCapital();
        cargarFallbackCoords();

        spProvincias = findViewById(R.id.spProvincias);
        btnAgregar = findViewById(R.id.btnAgregar);
        btnGenerar = findViewById(R.id.btnGenerarPoligono);
        btnLimpiar = findViewById(R.id.btnLimpiar);

        // Inicializar lista y adapter del Spinner
        repoblarSpinner();

        // Cargar mapa
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        // Agregar marcador de la provincia seleccionada y remover opción del Spinner
        btnAgregar.setOnClickListener(v -> agregarProvinciaSeleccionada());

        // Generar polígono con los puntos seleccionados
        btnGenerar.setOnClickListener(v -> generarPoligono());

        // Limpiar todo y restaurar opciones
        btnLimpiar.setOnClickListener(v -> limpiarTodo());
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        myMap = googleMap;

        // Posición inicial (Santa Cruz de la Sierra)
        LatLng santaCruz = new LatLng(-17.7833, -63.1821);
        myMap.moveCamera(CameraUpdateFactory.newLatLngZoom(santaCruz, 8f));
    }

    private void agregarProvinciaSeleccionada() {
        if (provinciasDisponibles.isEmpty()) {
            Toast.makeText(this, "No hay más provincias disponibles.", Toast.LENGTH_SHORT).show();
            btnAgregar.setEnabled(false);
            return;
        }

        String provincia = (String) spProvincias.getSelectedItem();
        if (provincia == null) {
            Toast.makeText(this, "Selecciona una provincia.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (agregadas.contains(provincia)) {
            Toast.makeText(this, "Ya agregaste " + provincia, Toast.LENGTH_SHORT).show();
            // Aun así, remover del spinner si quedó (consistencia visual)
            removerProvinciaDeSpinner(provincia);
            return;
        }

        String capital = provinciaCapital.get(provincia);
        if (capital == null) {
            Toast.makeText(this, "No se encontró capital para " + provincia, Toast.LENGTH_SHORT).show();
            // Remover del spinner para evitar loops
            removerProvinciaDeSpinner(provincia);
            return;
        }

        LatLng punto = geocodificarCapital(capital + ", Santa Cruz, Bolivia");
        if (punto == null) {
            // Fallback si Geocoder falla
            punto = capitalFallback.get(capital);
        }

        if (punto == null) {
            Toast.makeText(this, "No se pudo localizar " + capital, Toast.LENGTH_SHORT).show();
            // Remover del spinner para evitar volver a intentar una y otra vez
            removerProvinciaDeSpinner(provincia);
            return;
        }

        // Agregar marcador
        Marker marker = myMap.addMarker(new MarkerOptions()
                .position(punto)
                .title(capital + " (" + provincia + ")")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        marcadorPorProvincia.put(provincia, marker);

        // Guardar punto para el futuro polígono
        puntosSeleccionados.add(punto);
        agregadas.add(provincia);

        // Enfocar levemente
        myMap.animateCamera(CameraUpdateFactory.newLatLngZoom(punto, 8.5f));

        // Remover la provincia del Spinner y actualizar selección
        removerProvinciaDeSpinner(provincia);
    }

    private void generarPoligono() {
        if (puntosSeleccionados.size() < 3) {
            Toast.makeText(this, "Agrega al menos 3 provincias para formar un polígono.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Eliminar polígono anterior si existía
        if (poligonoActual != null) {
            poligonoActual.remove();
            poligonoActual = null;
        }

        // Ordenar por ángulo alrededor del centroide para evitar auto-intersecciones
        List<LatLng> ordenados = ordenarPorAnguloCentroide(puntosSeleccionados);

        PolygonOptions polygonOptions = new PolygonOptions()
                .addAll(ordenados)
                .strokeColor(0xFFFF0000)   // rojo, ARGB
                .fillColor(0x55FF0000)     // rojo semitransparente
                .strokeWidth(5f);

        poligonoActual = myMap.addPolygon(polygonOptions);

        // Zoom sugerido general
        myMap.animateCamera(CameraUpdateFactory.zoomTo(6.8f));
    }

    private void limpiarTodo() {
        // Eliminar polígono
        if (poligonoActual != null) {
            poligonoActual.remove();
            poligonoActual = null;
        }

        // Eliminar marcadores
        for (Marker m : marcadorPorProvincia.values()) {
            if (m != null) m.remove();
        }
        marcadorPorProvincia.clear();

        // Limpiar datos
        puntosSeleccionados.clear();
        agregadas.clear();

        // Restaurar opciones del Spinner
        repoblarSpinner();

        Toast.makeText(this, "Limpieza completada. Provincias restauradas.", Toast.LENGTH_SHORT).show();
    }

    // -------- Utilidades --------

    private void repoblarSpinner() {
        provinciasDisponibles.clear();
        provinciasDisponibles.addAll(Arrays.asList(PROVINCIAS_SCZ));

        if (spinnerAdapter == null) {
            spinnerAdapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    provinciasDisponibles
            );
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spProvincias.setAdapter(spinnerAdapter);
        } else {
            spinnerAdapter.notifyDataSetChanged();
        }

        spProvincias.setSelection(0);
        btnAgregar.setEnabled(true);
    }

    private void removerProvinciaDeSpinner(String provincia) {
        int idx = provinciasDisponibles.indexOf(provincia);
        if (idx >= 0) {
            provinciasDisponibles.remove(idx);
            spinnerAdapter.notifyDataSetChanged();

            if (provinciasDisponibles.isEmpty()) {
                btnAgregar.setEnabled(false);
                Toast.makeText(this, "Se agregaron todas las provincias disponibles.", Toast.LENGTH_SHORT).show();
            } else {
                // Ajustar selección a la primera opción disponible
                spProvincias.setSelection(0);
            }
        }
    }

    private LatLng geocodificarCapital(String consulta) {
        try {
            List<Address> resultados = geocoder.getFromLocationName(consulta, 1);
            if (resultados != null && !resultados.isEmpty()) {
                Address a = resultados.get(0);
                return new LatLng(a.getLatitude(), a.getLongitude());
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private List<LatLng> ordenarPorAnguloCentroide(List<LatLng> puntos) {
        // Centroide simple
        double sx = 0, sy = 0;
        for (LatLng p : puntos) {
            sx += p.latitude;
            sy += p.longitude;
        }
        final double cx = sx / puntos.size();
        final double cy = sy / puntos.size();

        List<LatLng> copia = new ArrayList<>(puntos);
        Collections.sort(copia, new Comparator<LatLng>() {
            @Override
            public int compare(LatLng a, LatLng b) {
                double angA = Math.atan2(a.longitude - cy, a.latitude - cx);
                double angB = Math.atan2(b.longitude - cy, b.latitude - cx);
                return Double.compare(angA, angB);
            }
        });
        return copia;
    }

    private void cargarMapeosProvinciaCapital() {
        // 15 provincias -> su capital
        provinciaCapital.put("Andrés Ibáñez", "Santa Cruz de la Sierra");
        provinciaCapital.put("Warnes", "Warnes");
        provinciaCapital.put("Ichilo", "Buena Vista");
        provinciaCapital.put("Sara", "Portachuelo");
        provinciaCapital.put("Obispo Santistevan", "Montero");
        provinciaCapital.put("Ñuflo de Chávez", "Concepción");
        provinciaCapital.put("Velasco", "San Ignacio de Velasco");
        provinciaCapital.put("Ángel Sandoval", "San Matías");
        provinciaCapital.put("Germán Busch", "Puerto Suárez");
        provinciaCapital.put("Chiquitos", "San José de Chiquitos");
        provinciaCapital.put("Guarayos", "Ascensión de Guarayos");
        provinciaCapital.put("Cordillera", "Lagunillas");
        provinciaCapital.put("Florida", "Samaipata");
        provinciaCapital.put("Vallegrande", "Vallegrande");
        provinciaCapital.put("Manuel María Caballero", "Comarapa");
    }

    private void cargarFallbackCoords() {
        // Coordenadas aproximadas de capitales (usadas solo si Geocoder falla)
        capitalFallback.put("Santa Cruz de la Sierra", new LatLng(-17.7833, -63.1821));
        capitalFallback.put("Warnes", new LatLng(-17.5089, -63.1659));
        capitalFallback.put("Buena Vista", new LatLng(-17.4613, -63.6621));
        capitalFallback.put("Portachuelo", new LatLng(-17.3548, -63.3923));
        capitalFallback.put("Montero", new LatLng(-17.3390, -63.2556));
        capitalFallback.put("Concepción", new LatLng(-16.1400, -62.0300));
        capitalFallback.put("San Ignacio de Velasco", new LatLng(-16.3700, -60.9600));
        capitalFallback.put("San Matías", new LatLng(-16.3667, -58.4000));
        capitalFallback.put("Puerto Suárez", new LatLng(-18.9500, -57.8000));
        capitalFallback.put("San José de Chiquitos", new LatLng(-17.8500, -60.7500));
        capitalFallback.put("Ascensión de Guarayos", new LatLng(-15.9333, -63.2333));
        capitalFallback.put("Lagunillas", new LatLng(-19.4333, -63.6667));
        capitalFallback.put("Samaipata", new LatLng(-18.1767, -63.8789));
        capitalFallback.put("Vallegrande", new LatLng(-18.4896, -64.1061));
        capitalFallback.put("Comarapa", new LatLng(-17.9000, -64.5333));
    }
}
