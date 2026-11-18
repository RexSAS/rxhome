package com.andrea.rxhome;

import java.util.Arrays;

public class TypingTest {
    public static void main(String[] a) {
        // What changed since the last send: how many to delete on the TV, then what to type.
        assert Arrays.equals(Typing.change("", "ciao"), new String[] {"0", "ciao"});
        assert Arrays.equals(Typing.change("ciao", "ciao a"), new String[] {"0", " a"});
        assert Arrays.equals(Typing.change("ciao", "ci"), new String[] {"2", ""});
        assert Arrays.equals(Typing.change("ciao", "cielo"), new String[] {"2", "elo"});   // edit in the middle: back to there
        assert Arrays.equals(Typing.change("same", "same"), new String[] {"0", ""});

        // The TV's "input text": spaces as %s, quoted for its shell, accents dropped, only what it can type.
        assert FireTv.textCommand("ciao a tutti").equals("input text 'ciao%sa%stutti'");
        assert FireTv.textCommand("perché è").equals("input text 'perche%se'");
        assert FireTv.textCommand("l'uomo").equals("input text 'l'\\''uomo'");
        assert FireTv.textCommand("a;b&c|d$e").equals("input text 'a;b&c|d$e'");   // harmless inside quotes
        assert FireTv.textCommand("🙂") == null && FireTv.textCommand("") == null;     // nothing it can type
    }
}
