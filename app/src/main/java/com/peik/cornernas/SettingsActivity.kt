package com.peik.cornernas

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_settings)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }

        val group = findViewById<RadioGroup>(R.id.radio_language_group)
        val locales = AppCompatDelegate.getApplicationLocales()
        when (locales.toLanguageTags()) {
            "en" -> group.check(R.id.radio_language_en)
            "zh" -> group.check(R.id.radio_language_zh)
            else -> group.check(R.id.radio_language_system)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val newLocales = when (checkedId) {
                R.id.radio_language_en -> LocaleListCompat.forLanguageTags("en")
                R.id.radio_language_zh -> LocaleListCompat.forLanguageTags("zh")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(newLocales)
        }
    }
}
