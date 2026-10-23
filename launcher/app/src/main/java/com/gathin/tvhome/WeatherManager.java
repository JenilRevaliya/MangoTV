package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unified live weather management engine for MangoTV (Home Screen & Screensaver).
 * Fetches accurate temperature and meteorological condition from Open-Meteo using IP geolocation.
 * Caches results for 30 minutes to minimize network activity and battery/CPU overhead.
 */
public final class WeatherManager {
    private static final String TAG = "TvHome.Weather";
    private static final long CACHE_DURATION_MS = 30 * 60 * 1000L; // 30 mins
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static double cachedLat = 23.02;
    private static double cachedLon = 72.58;
    private static boolean locationResolved = false;

    private static int cachedTemp = 30;
    private static int cachedWeatherCode = 0;
    private static String cachedCondition = "Clear Sky";
    private static long lastFetchTime = 0;
    private static boolean isFetching = false;

    private static final List<WeatherListener> listeners = new ArrayList<>();

    public interface WeatherListener {
        void onWeatherUpdated(int temp, int weatherCode, String condition);
    }

    private WeatherManager() {}

    public static int getTemperature() {
        return cachedTemp;
    }

    public static int getWeatherCode() {
        return cachedWeatherCode;
    }

    public static String getCondition() {
        return cachedCondition;
    }

    public static int getWeatherIconRes(int weatherCode, int hour) {
        boolean isNight = hour < 6 || hour >= 19;
        if (weatherCode == 0) {
            return isNight ? R.drawable.ic_weather_moon : R.drawable.ic_weather_sun;
        } else if (weatherCode == 1 || weatherCode == 2) {
            return R.drawable.ic_weather_partly_cloudy;
        } else if (weatherCode == 3 || weatherCode == 45 || weatherCode == 48) {
            return R.drawable.ic_weather_cloudy;
        } else if (weatherCode >= 50 && weatherCode <= 99) {
            return R.drawable.ic_weather_rain;
        }
        return isNight ? R.drawable.ic_weather_moon : R.drawable.ic_weather_sun;
    }

    public static int getCurrentWeatherIconRes() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return getWeatherIconRes(cachedWeatherCode, hour);
    }

    public static void registerListener(WeatherListener listener) {
        if (listener == null) return;
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        // Immediately notify with current cached data
        listener.onWeatherUpdated(cachedTemp, cachedWeatherCode, cachedCondition);
        fetchWeatherIfNeeded();
    }

    public static void unregisterListener(WeatherListener listener) {
        listeners.remove(listener);
    }

    public static void fetchWeatherIfNeeded() {
        long now = System.currentTimeMillis();
        if (isFetching || (now - lastFetchTime < CACHE_DURATION_MS && lastFetchTime > 0)) {
            return;
        }

        isFetching = true;
        EXECUTOR.execute(() -> {
            try {
                // 1. IP Geolocation for latitude and longitude (resolve once, then cache)
                if (!locationResolved) {
                    try {
                        URL ipUrl = new URL("https://ipapi.co/json/");
                        HttpURLConnection ipConn = (HttpURLConnection) ipUrl.openConnection();
                        ipConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV; Build/TV)");
                        ipConn.setConnectTimeout(4000);
                        ipConn.setReadTimeout(4000);
                        if (ipConn.getResponseCode() == 200) {
                            try (InputStream is = ipConn.getInputStream();
                                 Scanner s = new Scanner(is).useDelimiter("\\A")) {
                                String res = s.hasNext() ? s.next() : "";
                                JSONObject obj = new JSONObject(res);
                                if (obj.has("latitude") && obj.has("longitude")) {
                                    cachedLat = obj.getDouble("latitude");
                                    cachedLon = obj.getDouble("longitude");
                                    locationResolved = true;
                                }
                            }
                        }
                        ipConn.disconnect();
                    } catch (Exception e) {
                        Log.w(TAG, "IP location fetch fallback: " + e.getMessage());
                    }
                }

                // 2. Open-Meteo API
                URL meteoUrl = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + cachedLat + "&longitude=" + cachedLon + "&current_weather=true");
                HttpURLConnection conn = (HttpURLConnection) meteoUrl.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV; Build/TV)");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream();
                         Scanner s = new Scanner(is).useDelimiter("\\A")) {
                        String res = s.hasNext() ? s.next() : "";
                        JSONObject obj = new JSONObject(res);
                        if (obj.has("current_weather")) {
                            JSONObject cw = obj.getJSONObject("current_weather");
                            cachedTemp = (int) Math.round(cw.getDouble("temperature"));
                            cachedWeatherCode = cw.getInt("weathercode");
                            cachedCondition = getWeatherDescription(cachedWeatherCode);
                            lastFetchTime = System.currentTimeMillis();

                            MAIN_HANDLER.post(() -> {
                                for (WeatherListener l : listeners) {
                                    l.onWeatherUpdated(cachedTemp, cachedWeatherCode, cachedCondition);
                                }
                            });
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Weather fetch failed: " + e.getMessage());
            } finally {
                isFetching = false;
            }
        });
    }

    public static String getWeatherDescription(int code) {
        if (code == 0) return "Clear Sky";
        if (code == 1 || code == 2) return "Partly Cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorm";
        return "Clear";
    }
}
