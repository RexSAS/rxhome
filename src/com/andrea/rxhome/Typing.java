package com.andrea.rxhome;

/** Mirroring the remote's text bar on the TV: what changed since the last send. */
final class Typing {

    private Typing() {}

    /** {how many characters to delete on the TV, what to type after}: back to where they differ, then the rest. */
    static String[] change(String before, String after) {
        int same = 0;
        while (same < before.length() && same < after.length() && before.charAt(same) == after.charAt(same)) same++;
        return new String[] {Integer.toString(before.length() - same), after.substring(same)};
    }
}
