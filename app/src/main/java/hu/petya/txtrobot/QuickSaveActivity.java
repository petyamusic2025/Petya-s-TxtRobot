package hu.petya.txtrobot;

/**
 * A böngésző szövegkijelölő menüjéből ("Mentés TXT-be") induló képernyő.
 * Ugyanaz a felület, csak mentés után bezárul, és visszakerülsz a böngészőbe.
 */
public class QuickSaveActivity extends MainActivity {
    @Override
    protected boolean isQuickMode() {
        return true;
    }
}
