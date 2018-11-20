package com.zhaisoft.mylauncher.weather;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.zhaisoft.mylauncher.Launcher;
import com.zhaisoft.mylauncher.LauncherAppState;
import com.zhaisoft.mylauncher.Utilities;
import com.zhaisoft.mylauncher.preferences.IPreferenceProvider;
import com.zhaisoft.mylauncher.preferences.PreferenceFlags;
import com.zhaisoft.mylauncher.util.PackageManagerHelper;

public class WeatherHelper implements SharedPreferences.OnSharedPreferenceChangeListener, Runnable, WeatherAPI.WeatherCallback {
    private static final int DELAY = 500 * 3600;
    private final WeatherAPI mApi;
    private WeatherAPI.WeatherData mWeatherData;
    private TextView mTemperatureView;
    private Handler mHandler;
    private ImageView mIconView;
    private WeatherIconProvider iconProvider;
    private boolean stopped = false;
    private OnWeatherLoadListener mListener;

    public WeatherHelper(TextView temperatureView, ImageView iconView, Context context) {
        mTemperatureView = temperatureView;
        mIconView = iconView;
        iconProvider = new WeatherIconProvider(context);
        setupOnClickListener(context);
        mHandler = new Handler();
        IPreferenceProvider prefs = Utilities.getPrefs(context);
        prefs.registerOnSharedPreferenceChangeListener(this);
        mApi = WeatherAPI.Companion.create(context,
                Integer.parseInt(prefs.getWeatherProvider()));
        mApi.setWeatherCallback(this);
        setCity(prefs.getWeatherCity());
        setUnits(prefs.getWeatherUnit());
        refresh();
    }

    private void refresh() {
        if (!stopped) {
            mApi.getCurrentWeather();
            mHandler.postDelayed(this, DELAY);
        }
    }

    @Override
    public void onWeatherData(@NonNull WeatherAPI.WeatherData data) {
        mWeatherData = data;
        updateTextView();
        updateIconView();
        if (mListener != null) {
            mListener.onLoad(data.getSuccess());
        }
    }

    private void updateTextView() {
        mTemperatureView.setText(mWeatherData.getTemperatureString());
    }

    private void updateIconView() {
        mIconView.setImageDrawable(iconProvider.getIcon(mWeatherData.getIcon()));
    }

    private void setCity(String city) {
        mApi.setCity(city);
    }

    private void setUnits(String units) {
        mApi.setUnits(units.equals(WeatherAPI.Units.IMPERIAL.getLongName()) ? WeatherAPI.Units.IMPERIAL : WeatherAPI.Units.METRIC);
    }

    private void setupOnClickListener(final Context context) {
        final Launcher launcher = LauncherAppState.getInstance().getLauncher();
        final Rect sourceBounds = launcher.getViewBounds(mTemperatureView);
        final Bundle options = launcher.getActivityLaunchOptions(mTemperatureView);
        mTemperatureView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (PackageManagerHelper.isAppEnabled(context.getPackageManager(), "com.google.android.googlequicksearchbox", 0)) {
                    openGoogleWeather(context, sourceBounds, options);
                } else {
                    Utilities.openURLinBrowser(context, mApi.getForecastURL(), sourceBounds, options);
                }
            }
        });
    }

    private void openGoogleWeather(Context context, Rect sourceBounds, Bundle options) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("dynact://velour/weather/ProxyActivity"));
        intent.setComponent(new ComponentName("com.google.android.googlequicksearchbox",
                "com.google.android.apps.gsa.velour.DynamicActivityTrampoline"));
        intent.setSourceBounds(sourceBounds);
        context.startActivity(intent, options);
    }

    @Override
    public void onSharedPreferenceChanged(@NonNull SharedPreferences sharedPrefs, @NonNull String key) {
        switch (key) {
            case PreferenceFlags.KEY_WEATHER_UNITS:
                setUnits(sharedPrefs.getString(PreferenceFlags.KEY_WEATHER_UNITS, PreferenceFlags.PREF_WEATHER_UNIT_METRIC));
                updateTextView();
                break;
            case PreferenceFlags.KEY_WEATHER_CITY:
                setCity(sharedPrefs.getString(PreferenceFlags.KEY_WEATHER_CITY, mApi.getCity()));
                break;
        }
    }

    @Override
    public void run() {
        refresh();
    }

    public void stop() {
        stopped = true;
        mHandler.removeCallbacks(this);
    }

    public void setListener(OnWeatherLoadListener listener) {
        mListener = listener;
    }

    public interface OnWeatherLoadListener {
        void onLoad(boolean success);
    }
}
