package com.nova.watchparty

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** On-device chat translation (ML Kit). Free, no API key; language packs (~30 MB each) download on first use over Wi-Fi. */
object ChatTranslator {
    private val cache = HashMap<String, Translator>()
    private val target: String get() = TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    /** Returns the translation, or null if the message is already in the user's language / cannot be translated. */
    suspend fun translate(text: String): String? {
        if (text.length < 4) return null
        return try {
            val code = LanguageIdentification.getClient().identifyLanguage(text).await()
            val src = TranslateLanguage.fromLanguageTag(code) ?: return null
            if (code == "und" || src == target) return null
            val tr = cache.getOrPut("$src>$target") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build())
            }
            tr.downloadModelIfNeeded().await()
            tr.translate(text).await().takeIf { it.isNotBlank() && it != text }
        } catch (_: Exception) { null }
    }
}
