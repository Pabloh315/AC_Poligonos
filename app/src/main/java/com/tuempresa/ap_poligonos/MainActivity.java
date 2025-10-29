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
    private Map<String, String> provinciaCapital = new HashMap<>();

    // Capital -> fallback LatLng (por si Geocoder falla)
    private Map<String, LatLng> capitalFallback = new HashMap<>();

    // Provincias ya agregadas (evita duplicados)
    private Set<String> agregadas = new HashSet<>();

    // Puntos seleccionados (ordenaremos al generar el polígono)
    private final List<LatLng> puntosSeleccionados = new ArrayList<>();

    // Para poder eliminar el polígono anterior
    @Nullable
    private Polygon poligonoActual = null;

    // Para poder eliminar marcadores si hace falta
    private final Map<String, Marker> marcadorPorProvincia = new HashMap<>();

    // Lista de provincias (15)
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

        // Adapter del Spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                PROVINCIAS_SCZ
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProvincias.setAdapter(adapter);

        // Cargar mapa
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        // Agregar marcador de la provincia seleccionada
        btnAgregar.setOnClickListener(v -> agregarProvinciaSeleccionada());

        // Generar polígono con los puntos seleccionados
        btnGenerar.setOnClickListener(v -> generarPoligono());

        // Limpiar todo
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
        String provincia = (String) spProvincias.getSelectedItem();
        if (provincia == null) return;

        if (agregadas.contains(provincia)) {
            Toast.makeText(this, "Ya agregaste " + provincia, Toast.LENGTH_SHORT).show();
            return;
        }

        String capital = provinciaCapital.get(provincia);
        if (capital == null) {
            Toast.makeText(this, "No se encontró capital para " + provincia, Toast.LENGTH_SHORT).show();
            return;
        }

        LatLng punto = geocodificarCapital(capital + ", Santa Cruz, Bolivia");
        if (punto == null) {
            // Fallback si Geocoder falla
            punto = capitalFallback.get(capital);
        }

        if (punto == null) {
            Toast.makeText(this, "No se pudo localizar " + capital, Toast.LENGTH_SHORT).show();
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

        // Encajar cámara a los puntos
        // (Zoom manual sencillo para no extender demasiado el ejemplo)
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

        Toast.makeText(this, "Limpieza completada.", Toast.LENGTH_SHORT).show();
    }

    // -------- Utilidades --------

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
