package com.emsi.localisation;

import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;

import java.util.ArrayList;
import java.util.List;

import android.Manifest;

import androidx.annotation.NonNull;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.provider.Settings;

import com.android.volley.toolbox.JsonObjectRequest;

import android.os.Handler;
import android.os.Looper;

import com.android.volley.DefaultRetryPolicy;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, LocationListener {

    private GoogleMap mMap;
    private RequestQueue requestQueue;
    private static final String INSERT_URL = "http://10.0.2.2:8080/api/positions";
    private static final String SHOW_URL = "http://10.0.2.2:8080/api/positions";
    private LocationManager locationManager;
    private Polyline routePolyline;
    private List<LatLng> trackedPoints = new ArrayList<>();
    private Marker currentMarker;
    private static final long UPDATE_INTERVAL = 5000; //  5 secondes
    private static final float MIN_DISTANCE = 10;
    private String uniqueId;
    private List<Marker> historyMarkers = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int MAX_MARKERS = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        requestQueue = Volley.newRequestQueue(this);
        uniqueId = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString("unique_id", null);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        mMap.getUiSettings().setZoomControlsEnabled(false);
        mMap.getUiSettings().setAllGesturesEnabled(true);

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            enableLocationTracking();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    101);
        }

        loadPositions();
    }

    private void enableLocationTracking() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            try {
                Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (lastKnownLocation != null) {
                    onLocationChanged(lastKnownLocation);
                }

                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL,
                        MIN_DISTANCE,
                        this);
            } catch (SecurityException e) {
                Log.e("Location", "Security Exception", e);
            }
        } else {
            showGPSDisabledAlert();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        new Thread(() -> {
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            LatLng currentLatLng = new LatLng(latitude, longitude);

            // Envoi des données en arrière-plan
            sendPositionToServer(latitude, longitude);
            savePositionLocally(latitude, longitude);

            // Mise à jour UI sur le thread principal
            mainHandler.post(() -> updateMapUI(currentLatLng));
        }).start();
    }

    private void updateMapUI(LatLng currentLatLng) {
        if (currentMarker != null) {
            currentMarker.remove();
        }

        currentMarker = mMap.addMarker(new MarkerOptions()
                .position(currentLatLng)
                .title("Position actuelle")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));

        trackedPoints.add(currentLatLng);

        if (routePolyline != null) {
            routePolyline.remove();
        }

        if (trackedPoints.size() > 1) {
            routePolyline = mMap.addPolyline(new PolylineOptions()
                    .addAll(trackedPoints)
                    .width(8)
                    .color(Color.BLUE)
                    .geodesic(true));
        }

        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 12), 500, null);
    }

    private void loadPositions() {
        String url = SHOW_URL + "/" + uniqueId;

        JsonArrayRequest request = new JsonArrayRequest(
                Request.Method.GET,
                url,
                null,
                this::handlePositionsResponse,
                error -> {
                    Log.e("API", "Error loading positions: " + error.getMessage());
                    loadLocalPositions();
                });

        request.setRetryPolicy(new DefaultRetryPolicy(
                3000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        requestQueue.add(request);
    }

    private void handlePositionsResponse(JSONArray response) {
        new Thread(() -> {
            try {
                List<LatLng> points = new ArrayList<>();
                JSONArray simplifiedResponse = new JSONArray();

                for (int i = 0; i < response.length(); i++) {
                    JSONObject pos = response.getJSONObject(i);
                    JSONObject simplePos = new JSONObject();
                    simplePos.put("lat", pos.getDouble("latitude"));
                    simplePos.put("lng", pos.getDouble("longitude"));
                    simplifiedResponse.put(simplePos);
                }

                mainHandler.post(() -> {
                    try {
                        for (int i = 0; i < simplifiedResponse.length(); i++) {
                            if (historyMarkers.size() >= MAX_MARKERS) break;

                            JSONObject pos = simplifiedResponse.getJSONObject(i);
                            LatLng point = new LatLng(pos.getDouble("lat"), pos.getDouble("lng"));

                            Marker marker = mMap.addMarker(new MarkerOptions()
                                    .position(point)
                                    .title("Position " + (i + 1))
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                            historyMarkers.add(marker);
                            trackedPoints.add(point);
                        }

                        if (trackedPoints.size() > 1) {
                            routePolyline = mMap.addPolyline(new PolylineOptions()
                                    .addAll(trackedPoints)
                                    .width(8)
                                    .color(Color.BLUE)
                                    .geodesic(true));
                        }
                    } catch (JSONException e) {
                        Log.e("JSON", "Error displaying positions", e);
                    }
                });
            } catch (Exception e) {
                Log.e("LOAD", "Error processing data", e);
            }
        }).start();
    }

    private void loadLocalPositions() {
        new Thread(() -> {
            SharedPreferences prefs = getSharedPreferences("positions_prefs", MODE_PRIVATE);
            String positionsStr = prefs.getString("positions", "");

            if (!positionsStr.isEmpty()) {
                String[] positionPairs = positionsStr.split(";");

                mainHandler.post(() -> {
                    for (String pair : positionPairs) {
                        if (pair.isEmpty()) continue;

                        String[] coordinates = pair.split(",");
                        if (coordinates.length == 2) {
                            try {
                                double lat = Double.parseDouble(coordinates[0]);
                                double lng = Double.parseDouble(coordinates[1]);
                                LatLng point = new LatLng(lat, lng);

                                if (historyMarkers.size() < MAX_MARKERS) {
                                    Marker marker = mMap.addMarker(new MarkerOptions()
                                            .position(point)
                                            .title("Position sauvegardée")
                                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
                                    historyMarkers.add(marker);
                                    trackedPoints.add(point);
                                }
                            } catch (NumberFormatException e) {
                                Log.e("Local", "Error parsing position", e);
                            }
                        }
                    }

                    if (trackedPoints.size() > 1) {
                        routePolyline = mMap.addPolyline(new PolylineOptions()
                                .addAll(trackedPoints)
                                .width(8)
                                .color(Color.BLUE)
                                .geodesic(true));
                    }
                });
            }
        }).start();
    }

    private void sendPositionToServer(double latitude, double longitude) {
        try {
            JSONObject positionData = new JSONObject();
            positionData.put("latitude", latitude);
            positionData.put("longitude", longitude);
            positionData.put("uniqueId", uniqueId);

            JsonObjectRequest request = new JsonObjectRequest(
                    Request.Method.POST,
                    INSERT_URL,
                    positionData,
                    response -> Log.d("API", "Position saved"),
                    error -> Log.e("API", "Error saving position"));

            request.setRetryPolicy(new DefaultRetryPolicy(
                    3000,
                    DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                    DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

            requestQueue.add(request);
        } catch (JSONException e) {
            Log.e("JSON", "Error creating JSON", e);
        }
    }

    private void savePositionLocally(double latitude, double longitude) {
        SharedPreferences prefs = getSharedPreferences("positions_prefs", MODE_PRIVATE);
        String existingPositions = prefs.getString("positions", "");
        prefs.edit().putString("positions", existingPositions + latitude + "," + longitude + ";").apply();
    }

    private void showGPSDisabledAlert() {
        mainHandler.post(() -> new android.app.AlertDialog.Builder(this)
                .setTitle("GPS désactivé")
                .setMessage("Le GPS est nécessaire pour le suivi")
                .setPositiveButton("Paramètres", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("Annuler", (dialog, which) -> finish())
                .show());
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        showGPSDisabledAlert();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
        mainHandler.removeCallbacksAndMessages(null);
    }
}