package com.andrea.rxhome;

import java.io.IOException;

/** A TV's own network API (Philips, Samsung, LG): its power and volume. Keys: "PowerOn", "Standby", "VolumeUp", "VolumeDown", "Mute". */
interface TvApi {
    void key(String key) throws IOException;

    /** An exact level on the TV's own scale. */
    void setVolume(int level) throws IOException;

    void mute() throws IOException;

    /** The current level, or -1 if this TV can't say. */
    int currentVolume() throws IOException;
}
