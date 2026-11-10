package com.termux.terminal;

/** Host colors are session-local defaults; the terminal protocol keeps control of explicit colors. */
public class SessionSkinColorsTest extends TerminalTestCase {
    public void testDifferentSessionsDoNotMutateGlobalOrAnsiColors() {
        TerminalColors first = new TerminalColors();
        TerminalColors second = new TerminalColors();
        int global = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        int red = first.mCurrentColors[1];
        first.setSessionDefaultColors(0xff112233, 0xff445566, 0xff778899);
        assertEquals(0xff445566, first.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertEquals(global, second.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertEquals(global, TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertEquals(red, first.mCurrentColors[1]);
    }

    public void testOscOverrideWinsAndResetReturnsToLatestSkin() throws Exception {
        withTerminalSized(10, 4);
        mTerminal.mColors.setSessionDefaultColors(0xff112233, 0xff445566, 0xff778899);
        enterString("\033]11;#123456\007");
        mTerminal.mColors.setSessionDefaultColors(0xffaabbcc, 0xffddeeff, 0xff336699);
        assertColor(TextStyle.COLOR_INDEX_BACKGROUND, 0xff123456);
        enterString("\033]111\007");
        assertColor(TextStyle.COLOR_INDEX_BACKGROUND, 0xffddeeff);
        enterString("\033]10;#abcdef\007\033]4;1;#aabbcc\007");
        mTerminal.reset();
        assertColor(TextStyle.COLOR_INDEX_FOREGROUND, 0xffaabbcc);
        assertColor(1, TerminalColors.COLOR_SCHEME.mDefaultColors[1]);
    }

    public void testEqualValuedExplicitOverrideStillWinsUntilReset() {
        TerminalColors colors = new TerminalColors();
        colors.setSessionDefaultColors(0xff112233, 0xff445566, 0xff778899);
        colors.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, "#445566");
        colors.setSessionDefaultColors(0xff112233, 0xffaabbcc, 0xff778899);
        assertEquals(0xff445566, colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        colors.reset(TextStyle.COLOR_INDEX_BACKGROUND);
        assertEquals(0xffaabbcc, colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
    }

    public void testTrueColorAndScreenContentsSurviveSkinChange() throws Exception {
        withTerminalSized(10, 4);
        enterString("\033[38;2;12;34;56mHello");
        long style = getStyleAt(0, 0);
        mTerminal.mColors.setSessionDefaultColors(0xffaabbcc, 0xff112233, 0xff336699);
        assertEquals(style, getStyleAt(0, 0));
        assertEquals(0xff0c2238, TextStyle.decodeForeColor(style));
        assertLinesAre("Hello     ", "          ", "          ", "          ");
    }
}
