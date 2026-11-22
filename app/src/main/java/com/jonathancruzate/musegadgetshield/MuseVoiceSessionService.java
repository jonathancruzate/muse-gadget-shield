package com.jonathancruzate.musegadgetshield;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * Session factory for MuseVoiceInteractionService (v0.7.0): the
 * system creates one MuseVoiceSession per invocation of the
 * default assistant (e.g. a press of the remote's mic button).
 * Named by the sessionService attribute of
 * res/xml/voice_interaction.xml.
 */
public class MuseVoiceSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new MuseVoiceSession(this);
    }
}
