package ly.anoma.hex.speech

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import ly.anoma.hex.R
import ly.anoma.hex.keyboard.VoiceBarView
import ly.anoma.hex.keyboard.speechNote

/**
 * Labs › Keyboard › Voice-only mode, for keyboards and apps that ask Android to recognise speech
 * ([RecognizerIntent.ACTION_RECOGNIZE_SPEECH]) rather than switch to a voice keyboard: SwiftKey's mic key, with
 * Google Voice Typing off, does. Hex's dictation bar over the app, recording at once; ✓ hands the text back as the
 * request's result, ✕ or leaving hands back nothing; [handBack] has the keyboard type it. The activity is enabled only while the lab is on, so with it
 * off Hex never answers the request.
 */
class VoiceInputActivity : Activity() {
    private lateinit var bar: VoiceBarView
    private lateinit var capture: MicCapture
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The keyboard stays with the app's field while the bar listens (see [handBack]).
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        setFinishOnTouchOutside(true)
        bar = VoiceBarView(this, ::tap, ::cancel)
        setContentView(FrameLayout(this).apply {
            setOnClickListener { cancel() }
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM))
        })
        capture = MicCapture(LocalSpeech(applicationContext), DictationPlace.Keyboard,
            update = { phase, note -> window.keepAwake(phase); bar.status(phase, speechNote(note) ?: hint(phase)) },
            meter = bar::setLevel, insert = { text, _, _, _ -> answer(text) })
        begin()
    }

    private fun hint(phase: SpeechPhase) = getString(when (phase) {
        SpeechPhase.Opening, SpeechPhase.Listening -> R.string.voice_only_listening
        SpeechPhase.Transcribing -> R.string.keyboard_transcribing
        SpeechPhase.Idle -> R.string.voice_only_hint
    })

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bar.status(SpeechPhase.Idle, getString(R.string.keyboard_hint_microphone)); return
        }
        if (!capture.canStart) { bar.status(SpeechPhase.Idle, getString(R.string.keyboard_note_microphone)); return }
        capture.start(SpeechPreferences(this).options(null))
    }

    /** ✓: finish the dictation, or start again after a blocked or failed start. */
    private fun tap() = when (capture.phase) {
        SpeechPhase.Idle -> begin()
        SpeechPhase.Opening, SpeechPhase.Listening -> capture.finish()
        SpeechPhase.Transcribing -> Unit
    }

    private fun answer(text: String) {
        if (answered) return
        answered = true
        setResult(RESULT_OK, Intent()
            .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
            .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f)))
        handBack()
    }

    private fun cancel() {
        if (answered) return
        answered = true
        capture.cancel()
        setResult(RESULT_CANCELED)
        finish()
    }

    /**
     * SwiftKey types the text the next time its keyboard starts on a field, and only tries once: a field that isn't
     * ready loses it. Apps restart their field just after it reconnects, so a keyboard taken from the field while the
     * bar listened reconnects to a connection about to close (Android 17, Compose apps such as Claude and Contacts).
     * The bar leaves the keyboard on the field, then takes it for a moment before closing, so the field reconnects
     * once on return with nothing to restart. The bar hides first, since the keyboard shows over it meanwhile, and
     * closes once the keyboard is on this window ([HAND_BACK_MS] at most).
     */
    private fun handBack() {
        bar.visibility = View.INVISIBLE
        var closed = false
        fun close() { if (!closed) { closed = true; finish() } }
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            if (insets.isVisible(WindowInsets.Type.ime())) view.postDelayed(::close, SETTLE_MS)
            view.onApplyWindowInsets(insets)
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.decorView.postDelayed(::close, HAND_BACK_MS)
    }

    // Another app in front ends it unanswered, as the keyboard's dictation ends when the keyboard goes.
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) cancel() }
    override fun onDestroy() { capture.close(); super.onDestroy() }

    companion object {
        /** How long the bar waits for the keyboard before closing anyway. */
        private const val HAND_BACK_MS = 400L
        /** After the keyboard reaches this window, so its start is through before the app's field gets it back. */
        private const val SETTLE_MS = 50L

        /** Answers speech requests while Labs › Keyboard › Voice-only mode is on, and never while it is off. */
        fun setEnabled(context: Context, enabled: Boolean) {
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            context.packageManager.setComponentEnabledSetting(ComponentName(context, VoiceInputActivity::class.java), state,
                PackageManager.DONT_KILL_APP)
        }
    }
}
