package com.gathin.tvhome.remote;

/**
 * Protocol constants and action identifiers for Mango Remote.
 */
public final class MangoRemoteProtocol {
    private MangoRemoteProtocol() {}

    public static final int DEFAULT_PORT = 8090;
    public static final String PROTOCOL_VERSION = "1.0";

    // Message Types
    public static final String TYPE_COMMAND = "command";
    public static final String TYPE_PAIR = "pair";
    public static final String TYPE_EVENT = "event";
    public static final String TYPE_RESPONSE = "response";

    // Actions
    public static final String ACTION_NAVIGATE = "navigate";
    public static final String ACTION_MEDIA = "media";
    public static final String ACTION_VOLUME = "volume";
    public static final String ACTION_LAUNCH_APP = "launch_app";
    public static final String ACTION_TEXT_INPUT = "text_input";
    public static final String ACTION_VOICE_SEARCH = "voice_search";
    public static final String ACTION_GET_STATUS = "get_status";
    public static final String ACTION_GET_APPS = "get_apps";
    public static final String ACTION_PING = "ping";

    // Navigation directions
    public static final String DIR_UP = "up";
    public static final String DIR_DOWN = "down";
    public static final String DIR_LEFT = "left";
    public static final String DIR_RIGHT = "right";
    public static final String DIR_SELECT = "select";
    public static final String DIR_BACK = "back";
    public static final String DIR_HOME = "home";
    public static final String DIR_MENU = "menu";

    // Media actions
    public static final String MEDIA_PLAY_PAUSE = "play_pause";
    public static final String MEDIA_PLAY = "play";
    public static final String MEDIA_PAUSE = "pause";
    public static final String MEDIA_FAST_FORWARD = "fast_forward";
    public static final String MEDIA_REWIND = "rewind";
    public static final String MEDIA_NEXT = "next";
    public static final String MEDIA_PREV = "previous";

    // Volume actions
    public static final String VOLUME_UP = "up";
    public static final String VOLUME_DOWN = "down";
    public static final String VOLUME_MUTE = "mute";

    // Events
    public static final String EVENT_TV_STATE = "tv_state";
    public static final String EVENT_PLAYBACK_STATE = "playback_state";
}
