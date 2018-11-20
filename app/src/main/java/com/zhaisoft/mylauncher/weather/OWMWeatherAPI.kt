package com.zhaisoft.mylauncher.weather

import android.content.Context
import com.zhaisoft.mylauncher.Utilities
import com.kwabenaberko.openweathermaplib.implementation.OpenWeatherMapHelper
import com.kwabenaberko.openweathermaplib.models.currentweather.CurrentWeather
import java.net.URLEncoder

class OWMWeatherAPI(context: Context) : WeatherAPI(), OpenWeatherMapHelper.CurrentWeatherCallback {
    private val apiKey = Utilities.getPrefs(context).weatherApiKey
    private val helper = OpenWeatherMapHelper().apply { setApiKey(apiKey) }

    override var city: String = ""
    override var units: Units = Units.METRIC
        get() = field
        set(value) {
            field = value
            helper.setUnits(value.longName)
        }

    override fun getCurrentWeather() {
        helper.getCurrentWeatherByCityName(city, this)
    }

    override fun getForecastURL(): String {
        val cityEncoded = URLEncoder.encode(city, "UTF-8")
        return "https://openweathermap.org/?q=$cityEncoded"
    }

    override fun onSuccess(currentWeather: CurrentWeather) {
        onWeatherData(WeatherData(
                success = true,
                temp = currentWeather.main.temp.toInt(),
                icon = currentWeather.weatherArray[0].icon,
                units = units
        ))
    }

    override fun onFailure(p0: Throwable?) {
        onWeatherData(WeatherData(
                success = false,
                icon = "-1",
                units = units
        ))
    }
}